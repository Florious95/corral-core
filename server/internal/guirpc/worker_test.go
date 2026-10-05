package guirpc

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
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
		switch cmd.Type {
		case "get_state":
			fmt.Printf(`{"id":%q,"type":"response","command":"get_state","success":true,"data":{"sessionId":"sess-1","isStreaming":false}}`+"\n", cmd.ID)
			continue
		case "get_messages":
			// A resumed process knows the session's history; a fresh one has none.
			if os.Getenv("GUIRPC_FAKE_RESUME") == "sess-1" {
				fmt.Printf(`{"id":%q,"type":"response","command":"get_messages","success":true,"data":{"messages":[`+
					`{"role":"system","content":""},{"role":"user","content":"ping","timestamp":1},`+
					`{"role":"assistant","content":[{"type":"text","text":"pong"}],"stopReason":"stop","timestamp":2}]}}`+"\n", cmd.ID)
			} else {
				fmt.Printf(`{"id":%q,"type":"response","command":"get_messages","success":true,"data":{"messages":[]}}`+"\n", cmd.ID)
			}
			continue
		}
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
	useFakeAgents(t)

	ctx, cancel := context.WithCancel(context.Background())
	ref := "/tmp/tmux-test\x1f%7"
	pane := &syncBuffer{}
	done := make(chan error, 1)
	// The pane tty never reaches EOF while the agent lives; EOF closes the worker.
	paneInput, paneInputWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
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

// useFakeAgents swaps Pi for this test binary: TestFakePi (RPC) and TestFakeTUI.
func useFakeAgents(t *testing.T) {
	t.Helper()
	exe, _ := os.Executable()
	prevRPC, prevTUI := childCommand, tuiCommand
	childCommand = func(ctx context.Context, _ string, sessionID string) *exec.Cmd {
		cmd := exec.CommandContext(ctx, exe, "-test.run=^TestFakePi$")
		cmd.Env = append(os.Environ(), "GUIRPC_FAKE_PI=1", "GUIRPC_FAKE_RESUME="+sessionID)
		return cmd
	}
	tuiCommand = func(ctx context.Context, sessionID string) *exec.Cmd {
		cmd := exec.CommandContext(ctx, exe, "-test.run=^TestFakeTUI$")
		cmd.Env = append(os.Environ(), "GUIRPC_FAKE_TUI="+sessionID)
		return cmd
	}
	t.Cleanup(func() { childCommand, tuiCommand = prevRPC, prevTUI })
}

// TestFakeTUI stands in for Pi's interactive UI: it owns the tty until SIGTERM.
func TestFakeTUI(t *testing.T) {
	session := os.Getenv("GUIRPC_FAKE_TUI")
	if session == "" {
		t.Skip("helper process")
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM)
	fmt.Printf("TUI session=%s\n", session)
	<-stop
	fmt.Println("TUI restored terminal")
	os.Exit(0)
}

// readRecords reads socket records until want returns true for one of them.
func readRecords(t *testing.T, reader *bufio.Reader, want func(kind string, event json.RawMessage) bool) {
	t.Helper()
	for {
		line, err := reader.ReadBytes('\n')
		if err != nil {
			t.Fatalf("socket read: %v", err)
		}
		var r record
		if err := json.Unmarshal(line, &r); err != nil {
			t.Fatalf("record %q: %v", line, err)
		}
		kind, _, _ := eventType(r)
		if want(kind, r.Event) {
			return
		}
	}
}

func TestSwitchHandsThePaneToPiTUIAndBackOnTheSameSession(t *testing.T) {
	dir, err := os.MkdirTemp(".", "s")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	useFakeAgents(t)
	ctx, cancel := context.WithCancel(context.Background())
	ref := "/tmp/tmux-test\x1f%9"
	pane := &syncBuffer{}
	paneInput, paneInputWriter, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer paneInputWriter.Close()
	done := make(chan error, 1)
	go func() { done <- run(ctx, dir, ref, "swap", paneInput, pane) }()
	waitCtx, waitCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer waitCancel()
	if err := WaitReady(waitCtx, dir, ref); err != nil {
		t.Fatalf("WaitReady: %v", err)
	}
	conn, err := net.Dial("unix", SocketPath(dir, ref))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
	fmt.Fprintln(conn, `{"type":"hello"}`)
	reader := bufio.NewReader(conn)
	first, _ := reader.ReadBytes('\n')
	var ready Ready
	if json.Unmarshal(first, &ready) != nil || ready.Mode != ModeRPC {
		t.Fatalf("ready = %s", first)
	}
	response := func(command string) map[string]any {
		var got map[string]any
		readRecords(t, reader, func(kind string, event json.RawMessage) bool {
			_ = json.Unmarshal(event, &got)
			return kind == "response" && got["command"] == command
		})
		return got
	}
	// The switch asks Pi for its session itself; nothing to wait for first.
	fmt.Fprintln(conn, `{"id":"s1","type":"switch_mode","mode":"tui"}`)
	if got := response("switch_mode"); got["success"] != true || got["data"].(map[string]any)["mode"] != ModeTUI {
		t.Fatalf("switch to tui = %v", got)
	}
	deadline := time.Now().Add(5 * time.Second)
	for !strings.Contains(pane.String(), "TUI session=sess-1") {
		if time.Now().After(deadline) {
			t.Fatalf("TUI did not start on the session: %q", pane.String())
		}
		time.Sleep(20 * time.Millisecond)
	}
	// While the TUI owns the pane, structured commands fail visibly instead of vanishing.
	fmt.Fprintln(conn, `{"id":"p1","type":"prompt","message":"hi"}`)
	if got := response("prompt"); got["success"] != false || got["id"] != "p1" {
		t.Fatalf("prompt in tui mode = %v", got)
	}
	var probe Ready
	if c, err := net.Dial("unix", SocketPath(dir, ref)); err == nil {
		fmt.Fprintln(c, `{"type":"hello","probe":true}`)
		line, _ := bufio.NewReader(c).ReadBytes('\n')
		_ = json.Unmarshal(line, &probe)
		c.Close()
	}
	if probe.Mode != ModeTUI {
		t.Fatalf("ready while in tui = %+v", probe)
	}

	fmt.Fprintln(conn, `{"id":"s2","type":"switch_mode","mode":"rpc"}`)
	// The resumed agent's record comes back as a fresh transcript of the same session;
	// the switch answer may interleave anywhere after the reset.
	var replayed []string
	var answer map[string]any
	readRecords(t, reader, func(kind string, event json.RawMessage) bool {
		if kind == "response" {
			_ = json.Unmarshal(event, &answer)
		} else {
			replayed = append(replayed, kind)
		}
		return kind == "worker_mode"
	})
	if got := strings.Join(replayed, ","); got != "session_reset,message_start,message_end,message_start,message_end,worker_mode" {
		t.Fatalf("resume stream = %s", got)
	}
	if answer == nil {
		answer = response("switch_mode")
	}
	if answer["success"] != true || answer["data"].(map[string]any)["mode"] != ModeRPC {
		t.Fatalf("switch back to rpc = %v", answer)
	}
	if !strings.Contains(pane.String(), "TUI restored terminal") {
		t.Fatalf("TUI was not stopped gracefully: %q", pane.String())
	}
	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("worker did not exit after cancel")
	}
}

func TestSessionBusyReadsTheLastMessage(t *testing.T) {
	dir := t.TempDir()
	n := 0
	write := func(lines ...string) string {
		n++
		path := filepath.Join(dir, fmt.Sprintf("s%d.jsonl", n))
		_ = os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0o600)
		return path
	}
	head := `{"type":"session","id":"x","cwd":"/w"}`
	user := `{"type":"message","message":{"role":"user","content":"go"}}`
	tool := `{"type":"message","message":{"role":"assistant","stopReason":"toolUse"}}`
	result := `{"type":"message","message":{"role":"toolResult"}}`
	done := `{"type":"message","message":{"role":"assistant","stopReason":"stop"}}`
	for _, tc := range []struct {
		path string
		busy bool
	}{
		{write(head), false},
		{write(head, user), true},
		{write(head, user, tool), true},
		{write(head, user, tool, result), true},
		{write(head, user, tool, result, done), false},
		{write(head, user, done, `{"type":"model_change"}`), false},
		{"", false},
	} {
		if got := sessionBusy(tc.path); got != tc.busy {
			t.Fatalf("%s busy=%v want %v", tc.path, got, tc.busy)
		}
	}
	if id, cwd := sessionHeader(write(head, user, done, done, done, done, done)); id != "x" || cwd != "/w" {
		t.Fatalf("header = %q %q", id, cwd)
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

func TestBracketedPasteIsOnePrompt(t *testing.T) {
	var j pasteJoiner
	var got []string
	for _, line := range []string{
		"plain",
		"\x1b[200~first", "", "  indented", "last\x1b[201~ and typed",
		"\x1b[200~single\x1b[201~",
	} {
		if text, ok := j.line(line); ok {
			got = append(got, text)
		}
	}
	want := []string{"plain", "first\n\n  indented\nlast and typed", "single"}
	if fmt.Sprintf("%q", got) != fmt.Sprintf("%q", want) {
		t.Fatalf("prompts = %q\nwant      %q", got, want)
	}
}

func TestModelResponsesAreSlimmed(t *testing.T) {
	raw, err := os.ReadFile("testdata/pi-1.0.0-available-models.json")
	if err != nil {
		t.Fatal(err)
	}
	got := string(projectModels(bytes.TrimSpace(raw)))
	want := `{"id":"a","type":"response","command":"get_available_models","success":true,"data":{"models":[` +
		`{"id":"probe-reasoner","name":"Probe Reasoner","provider":"probe","reasoning":true},{"id":"probe-plain","name":"Probe Plain","provider":"probe"}]}}`
	if got != want {
		t.Fatalf("projected\n%s\nwant\n%s", got, want)
	}
	set := string(projectModels([]byte(`{"id":"b","type":"response","command":"set_model","success":true,"data":{"id":"m","name":"M","provider":"p","reasoning":true,"baseUrl":"https://x","cost":{"input":1}}}`)))
	if set != `{"id":"b","type":"response","command":"set_model","success":true,"data":{"id":"m","name":"M","provider":"p","reasoning":true}}` {
		t.Fatalf("set_model projected %s", set)
	}
	failed := string(projectModels([]byte(`{"id":"c","type":"response","command":"set_model","success":false,"error":"No API key for provider"}`)))
	if failed != `{"id":"c","type":"response","command":"set_model","success":false,"error":"No API key for provider"}` {
		t.Fatalf("failure projected %s", failed)
	}
}
