package guirpc

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
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
// Native tmux lifecycle coverage lives in native_bridge_test.go. The former
// child-command/UDS supervisor fixtures are deliberately no longer a product
// path; projection, replay, history bounds and input guards above remain.

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
	tool := `{"type":"message","message":{"role":"assistant","stopReason":"toolUse","content":[{"type":"toolCall"}]}}`
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
		{"", true},
	} {
		if got := sessionBusy(tc.path); got != tc.busy {
			t.Fatalf("%s busy=%v want %v", tc.path, got, tc.busy)
		}
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

func TestSessionStatsDropTheHostPathOnly(t *testing.T) {
	raw := `{"id":"s","type":"response","command":"get_session_stats","success":true,"data":{"sessionFile":"/Users/x/.pi/s.jsonl","sessionId":"sid","tokens":{"input":1016,"output":228,"cacheRead":340,"cacheWrite":452,"total":2036},"cost":0.02036,"contextUsage":{"tokens":1916,"contextWindow":128000,"percent":1.496875}}}`
	got := string(projectStats([]byte(raw)))
	if strings.Contains(got, "sessionFile") || strings.Contains(got, "/Users/x") {
		t.Fatalf("host path leaked: %s", got)
	}
	for _, want := range []string{`"cost":0.02036`, `"sessionId":"sid"`, `"cacheWrite":452`, `"contextWindow":128000`, `"command":"get_session_stats"`} {
		if !strings.Contains(got, want) {
			t.Fatalf("missing %s in %s", want, got)
		}
	}
	failed := `{"id":"f","type":"response","command":"get_session_stats","success":false,"error":"boom"}`
	if got := string(projectStats([]byte(failed))); got != failed {
		t.Fatalf("failure reshaped: %s", got)
	}
}
