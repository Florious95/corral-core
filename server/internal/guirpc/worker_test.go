package guirpc

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type record struct {
	Seq   uint64          `json:"seq"`
	TS    int64           `json:"ts"`
	Event json.RawMessage `json:"event"`
}

type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func fixture(t *testing.T) [][]byte {
	t.Helper()
	data, err := os.ReadFile("testdata/pi-1.0.0-rpc-tool-turn.jsonl")
	if err != nil {
		t.Fatal(err)
	}
	var lines [][]byte
	for _, line := range bytes.Split(bytes.TrimSpace(data), []byte("\n")) {
		lines = append(lines, line)
	}
	return lines
}

func decode(t *testing.T, lines [][]byte) []record {
	t.Helper()
	out := make([]record, 0, len(lines))
	for _, line := range lines {
		var r record
		if err := json.Unmarshal(line, &r); err != nil {
			t.Fatalf("record %q: %v", line, err)
		}
		out = append(out, r)
	}
	return out
}

func eventType(r record) (kind, sub, role string) {
	var h struct {
		Type    string `json:"type"`
		Message *struct {
			Role string `json:"role"`
		} `json:"message"`
		Update *struct {
			Type string `json:"type"`
		} `json:"assistantMessageEvent"`
	}
	_ = json.Unmarshal(r.Event, &h)
	if h.Message != nil {
		role = h.Message.Role
	}
	if h.Update != nil {
		sub = h.Update.Type
	}
	return h.Type, sub, role
}

func TestReplayIsCompactedProjectionOfRealPiTurn(t *testing.T) {
	stdin := &syncBuffer{}
	w := newWorker(stdin)
	for _, line := range fixture(t) {
		w.ingest(line)
	}
	ready, replay, _ := w.attach(Hello{Type: "hello"}, false)
	if !ready.Reset || ready.Running {
		t.Fatalf("fresh attach must reset and see a settled agent: %+v", ready)
	}
	_, replay, _ = w.attach(Hello{Type: "hello"}, true)
	records := decode(t, replay)
	var last uint64
	textDeltas, starts := 0, 0
	for _, r := range records {
		if r.Seq <= last {
			t.Fatalf("seq not increasing: %d after %d", r.Seq, last)
		}
		last = r.Seq
		kind, sub, role := eventType(r)
		switch {
		case role == "system" || role == "toolResult":
			t.Fatalf("%s %s message must never reach GUI clients", kind, role)
		case kind == "response" || kind == "turn_end" || kind == "agent_end":
			t.Fatalf("%s is live-only and must not be replayed", kind)
		case kind == "extension_ui_request":
			t.Fatalf("status chrome must be dropped: %s", r.Event)
		case kind == "message_update" && strings.HasSuffix(sub, "_start"):
			starts++
		case kind == "message_update":
			textDeltas++
		case kind == "tool_execution_update":
			t.Fatalf("completed tool must replay only its end record")
		}
		if bytes.Contains(r.Event, []byte(`"usage":{"input":0`)) && kind == "message_update" {
			t.Fatalf("usage must be stripped from deltas: %s", r.Event)
		}
	}
	if textDeltas != 0 {
		t.Fatalf("completed messages replayed %d superseded deltas", textDeltas)
	}
	if starts != 2 {
		t.Fatalf("block starts carry timing and must survive compaction, got %d", starts)
	}
	if len(records) > 16 {
		t.Fatalf("compacted replay too large: %d records", len(records))
	}
	if !strings.Contains(stdin.String(), "") {
		t.Fatal("unreachable")
	}
}

func TestLiveStreamCarriesDeltasWithoutUsage(t *testing.T) {
	w := newWorker(&syncBuffer{})
	_, _, c := w.attach(Hello{Type: "hello"}, true)
	lines := fixture(t)
	for _, line := range lines {
		w.ingest(line)
	}
	w.shutdown()
	var live [][]byte
	for line := range c.ch {
		live = append(live, line)
	}
	sawDelta, sawSettled := false, false
	for _, r := range decode(t, live) {
		kind, sub, _ := eventType(r)
		if kind == "message_update" && sub == "text_delta" {
			sawDelta = true
			if bytes.Contains(r.Event, []byte(`"usage"`)) {
				t.Fatalf("live delta still carries usage: %s", r.Event)
			}
		}
		if kind == "agent_settled" {
			sawSettled = true
		}
		if kind == "agent_end" && bytes.Contains(r.Event, []byte("messages")) {
			t.Fatalf("agent_end must drop its message dump: %s", r.Event)
		}
		if kind == "response" && bytes.Contains(r.Event, []byte("sourceInfo")) {
			t.Fatalf("get_commands projection leaked sourceInfo: %s", r.Event)
		}
	}
	if !sawDelta || !sawSettled {
		t.Fatalf("live stream incomplete: delta=%v settled=%v", sawDelta, sawSettled)
	}
}

func TestResumeAfterSeqAndResetRules(t *testing.T) {
	w := newWorker(&syncBuffer{})
	lines := fixture(t)
	for _, line := range lines[:20] {
		w.ingest(line)
	}
	ready, _, _ := w.attach(Hello{Type: "hello"}, false)
	head := ready.HeadSeq
	for _, line := range lines[20:] {
		w.ingest(line)
	}
	resumed, replay, _ := w.attach(Hello{Type: "hello", Stream: ready.Stream, AfterSeq: head}, true)
	if resumed.Reset {
		t.Fatal("matching stream and retained after_seq must resume, not reset")
	}
	for _, r := range decode(t, replay) {
		if r.Seq <= head {
			t.Fatalf("resume replayed seq %d <= after_seq %d", r.Seq, head)
		}
	}
	if other, _, _ := w.attach(Hello{Type: "hello", Stream: "other", AfterSeq: head}, false); !other.Reset {
		t.Fatal("foreign stream must reset")
	}
	if ahead, _, _ := w.attach(Hello{Type: "hello", Stream: ready.Stream, AfterSeq: resumed.HeadSeq + 5}, false); !ahead.Reset {
		t.Fatal("after_seq beyond head must reset")
	}
}

func TestToolUpdatesAreRateLimitedAndSupersededByEnd(t *testing.T) {
	w := newWorker(&syncBuffer{})
	_, _, c := w.attach(Hello{Type: "hello"}, true)
	update := func(text string) []byte {
		return []byte(fmt.Sprintf(`{"type":"tool_execution_update","toolCallId":"t1","toolName":"bash","partialResult":{"content":[{"type":"text","text":%q}]}}`, text))
	}
	w.ingest(update("a"))
	w.ingest(update("ab"))
	w.ingest(update("abc"))
	time.Sleep(toolUpdateInterval + 100*time.Millisecond)
	w.ingest(update("abcd"))
	w.ingest([]byte(`{"type":"tool_execution_end","toolCallId":"t1","toolName":"bash","result":{"content":[{"type":"text","text":"abcde"}]},"isError":false}`))
	time.Sleep(toolUpdateInterval + 50*time.Millisecond)
	w.shutdown()
	var texts []string
	for line := range c.ch {
		var r record
		_ = json.Unmarshal(line, &r)
		var e struct {
			Type    string `json:"type"`
			Partial struct {
				Content []struct {
					Text string `json:"text"`
				} `json:"content"`
			} `json:"partialResult"`
		}
		_ = json.Unmarshal(r.Event, &e)
		if e.Type == "tool_execution_update" {
			texts = append(texts, e.Partial.Content[0].Text)
		} else {
			texts = append(texts, e.Type)
		}
	}
	got := strings.Join(texts, ",")
	if got != "a,abc,abcd,tool_execution_end" && got != "a,abc,tool_execution_end" {
		t.Fatalf("unexpected tool stream %q", got)
	}
	_, replay, _ := w.attach(Hello{Type: "hello"}, false)
	_, replay, _ = w.attach(Hello{Type: "hello"}, true)
	if len(replay) != 1 || !bytes.Contains(replay[0], []byte("tool_execution_end")) {
		t.Fatalf("history must keep only the end record, got %q", replay)
	}
}

func TestNewSessionResetsHistory(t *testing.T) {
	w := newWorker(&syncBuffer{})
	for _, line := range fixture(t) {
		w.ingest(line)
	}
	before, _, _ := w.attach(Hello{Type: "hello"}, false)
	w.ingest([]byte(`{"id":"n1","type":"response","command":"new_session","success":true,"data":{"cancelled":false}}`))
	after, replay, _ := w.attach(Hello{Type: "hello", Stream: before.Stream, AfterSeq: before.HeadSeq - 3}, true)
	if !after.Reset {
		t.Fatal("a client behind a session reset must rebuild")
	}
	if len(replay) != 1 || !bytes.Contains(replay[0], []byte("session_reset")) {
		t.Fatalf("history after reset = %q", replay)
	}
}

func TestDialogRequestsAreCancelledNeverLeftWaiting(t *testing.T) {
	stdin := &syncBuffer{}
	w := newWorker(stdin)
	w.ingest([]byte(`{"type":"extension_ui_request","id":"d1","method":"confirm","title":"Allow?"}`))
	if !strings.Contains(stdin.String(), `"cancelled":true`) || !strings.Contains(stdin.String(), `"id":"d1"`) {
		t.Fatalf("dialog not cancelled: %q", stdin.String())
	}
}

func TestHistoryIsBounded(t *testing.T) {
	w := newWorker(&syncBuffer{})
	for i := 0; i < maxHistoryRecords+50; i++ {
		w.ingest([]byte(fmt.Sprintf(`{"type":"compaction_start","reason":"manual","n":%d}`, i)))
	}
	ready, replay, _ := w.attach(Hello{Type: "hello"}, true)
	if len(replay) != maxHistoryRecords || !ready.Truncated {
		t.Fatalf("history len=%d truncated=%v", len(replay), ready.Truncated)
	}
}

// TestFakePi is the child process used by the integration test.
func TestFakePi(t *testing.T) {
	if os.Getenv("GUIRPC_FAKE_PI") != "1" {
		t.Skip("helper process")
	}
	scan := bufio.NewScanner(os.Stdin)
	for scan.Scan() {
		var cmd struct {
			ID      string `json:"id"`
			Type    string `json:"type"`
			Message string `json:"message"`
		}
		_ = json.Unmarshal(scan.Bytes(), &cmd)
		fmt.Printf(`{"id":%q,"type":"response","command":%q,"success":true,"data":{"disposition":"started"}}`+"\n", cmd.ID, cmd.Type)
		if cmd.Type == "prompt" {
			fmt.Println(`{"type":"agent_start"}`)
			fmt.Printf(`{"type":"message_start","message":{"role":"user","content":%q,"timestamp":1}}`+"\n", cmd.Message)
			fmt.Println(`{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending","timestamp":2}}`)
			fmt.Println(`{"type":"message_update","usage":{"input":1},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"pong"}}`)
			fmt.Println(`{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"pong"}],"stopReason":"stop","timestamp":2}}`)
			fmt.Println(`{"type":"agent_settled"}`)
		}
	}
	os.Exit(0)
}

func TestWorkerServesSocketAndCleansUp(t *testing.T) {
	dir, err := os.MkdirTemp(".", "s")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	exe, _ := os.Executable()
	prev := childCommand
	childCommand = func(ctx context.Context, _ string) *exec.Cmd {
		cmd := exec.CommandContext(ctx, exe, "-test.run=^TestFakePi$")
		cmd.Env = append(os.Environ(), "GUIRPC_FAKE_PI=1")
		return cmd
	}
	defer func() { childCommand = prev }()

	ctx, cancel := context.WithCancel(context.Background())
	ref := "/tmp/tmux-test\x1f%7"
	pane := &syncBuffer{}
	done := make(chan error, 1)
	// The pane tty never reaches EOF while the agent lives; EOF closes the worker.
	paneInput, paneInputWriter := io.Pipe()
	defer paneInputWriter.Close()
	go func() { done <- run(ctx, dir, ref, "probe", paneInput, pane) }()

	waitCtx, waitCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer waitCancel()
	if err := WaitReady(waitCtx, dir, ref); err != nil {
		entries, _ := os.ReadDir(dir)
		cancel()
		var werr error
		select {
		case werr = <-done:
		case <-time.After(5 * time.Second):
		}
		t.Fatalf("WaitReady: %v (dir=%v worker=%v pane=%q)", err, entries, werr, pane.String())
	}
	if !Available(dir, ref) || Activity(dir, ref) != "idle" {
		t.Fatalf("available=%v activity=%q", Available(dir, ref), Activity(dir, ref))
	}
	conn, err := net.Dial("unix", SocketPath(dir, ref))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	fmt.Fprintln(conn, `{"type":"hello"}`)
	reader := bufio.NewReader(conn)
	first, _ := reader.ReadBytes('\n')
	var ready Ready
	if json.Unmarshal(first, &ready) != nil || ready.Type != "ready" || ready.Stream == "" {
		t.Fatalf("ready = %q", first)
	}
	fmt.Fprintln(conn, `{"id":"p1","type":"prompt","message":"ping"}`)
	var kinds []string
	for len(kinds) < 7 {
		line, err := reader.ReadBytes('\n')
		if err != nil {
			t.Fatalf("read after %v: %v", kinds, err)
		}
		var r record
		if err := json.Unmarshal(line, &r); err != nil {
			t.Fatalf("record %q: %v", line, err)
		}
		kind, sub, role := eventType(r)
		kinds = append(kinds, strings.Trim(kind+":"+sub+":"+role, ":"))
	}
	want := "response,agent_start,message_start::user,message_start::assistant,message_update:text_delta,message_end::assistant,agent_settled"
	if got := strings.Join(kinds, ","); got != want {
		t.Fatalf("stream = %s\nwant     %s", got, want)
	}
	if !strings.Contains(pane.String(), "pong") {
		t.Fatalf("pane transcript missing reply: %q", pane.String())
	}
	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("worker did not exit after cancel")
	}
	if _, err := os.Stat(SocketPath(dir, ref)); !os.IsNotExist(err) {
		t.Fatalf("socket not removed: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, baseName(ref)+".state")); !os.IsNotExist(err) {
		t.Fatalf("state file not removed: %v", err)
	}
}

// TestWriteClientGoldens regenerates the projected live and replay streams the
// Android reducer tests consume (GUIRPC_WRITE_GOLDENS=<dir>). Both must reduce
// to the same transcript: compaction may drop only superseded records.
func TestWriteClientGoldens(t *testing.T) {
	out := os.Getenv("GUIRPC_WRITE_GOLDENS")
	if out == "" {
		t.Skip("set GUIRPC_WRITE_GOLDENS to regenerate client fixtures")
	}
	w := newWorker(&syncBuffer{})
	clock := time.UnixMilli(1791169449000)
	w.now = func() time.Time { clock = clock.Add(7 * time.Millisecond); return clock }
	_, _, c := w.attach(Hello{Type: "hello"}, true)
	for _, line := range fixture(t) {
		w.ingest(line)
	}
	_, replay, _ := w.attach(Hello{Type: "hello"}, false)
	_, replay, _ = w.attach(Hello{Type: "hello"}, true)
	w.shutdown()
	var live []byte
	for line := range c.ch {
		live = append(live, line...)
	}
	if err := os.WriteFile(filepath.Join(out, "pi-tool-turn.live.jsonl"), live, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(out, "pi-tool-turn.replay.jsonl"), bytes.Join(replay, nil), 0o644); err != nil {
		t.Fatal(err)
	}
}
