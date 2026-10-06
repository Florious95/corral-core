package guirpc

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
)

// Official Pi on an operator-provided, project-local tmux socket. No network
// prompt, production daemon, user tmux, credentials or CLI wrapper is involved.
func TestNativePiSessionResumeSameProcess(t *testing.T) {
	root := os.Getenv("ISSUE55_NATIVE_ROOT")
	if root == "" {
		t.Skip("set ISSUE55_NATIVE_ROOT to a project-local isolated directory")
	}
	sock := filepath.Join(root, "resume.sock")
	if !filepath.IsAbs(root) || len([]byte(sock)) >= 100 {
		t.Fatal("invalid isolated native root")
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(root, "sessions")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	a, b := "11111111-2222-4333-8444-555555555555", "22222222-3333-4444-8555-666666666666"
	active := filepath.Join(dir, "active.jsonl")
	target := filepath.Join(dir, "renamed-history.jsonl")
	writePiSessionFixture(t, active, a, root, historyMessage("a1", "", "user", "old context"), historyMessage("a2", "a1", "assistant", "old answer"))
	thought := historyMessage("b2", "b1", "assistant", "")
	thought["message"] = map[string]any{"role": "assistant", "content": []any{map[string]any{"type": "thinking", "thinking": "retained original thought"}, map[string]any{"type": "toolCall", "id": "native-tool", "name": "read", "arguments": map[string]any{"path": "fixture.txt"}}}, "api": "openai-responses", "provider": "openai", "model": "gpt-4.1", "stopReason": "toolUse", "timestamp": 2, "usage": fixtureUsage()}
	writePiSessionFixture(t, target, b, root,
		historyMessage("b1", "", "user", "target context before compaction"), thought,
		map[string]any{"type": "message", "id": "b3", "parentId": "b2", "message": map[string]any{"role": "toolResult", "toolCallId": "native-tool", "toolName": "read", "content": []any{map[string]any{"type": "text", "text": "retained original tool output"}}, "isError": false, "timestamp": 3}},
		map[string]any{"type": "compaction", "id": "b4", "parentId": "b3", "summary": "compacted target context", "firstKeptEntryId": "b3", "tokensBefore": 400, "timestamp": "2026-10-06T01:00:04Z"},
		historyMessage("dead", "b1", "assistant", "abandoned branch marker"),
		historyMessage("b5", "b4", "assistant", "target latest reply"),
		map[string]any{"type": "session_info", "id": "b6", "parentId": "b5", "name": "Selected history title", "timestamp": "2026-10-06T01:00:06Z"})
	writePiSessionFixture(t, filepath.Join(dir, "foreign.jsonl"), "33333333-4444-4555-8666-777777777777", root+"-foreign")
	cancelledID := "44444444-5555-4666-8777-888888888888"
	writePiSessionFixture(t, filepath.Join(dir, "cancelled.jsonl"), cancelledID, root, historyMessage("cancel-user", "", "user", "must not replace current history"))
	extension := filepath.Join(root, "cancel-switch.ts")
	if err := os.WriteFile(extension, []byte(`export default function(pi) { pi.on("session_before_switch", (event) => event.targetSessionFile?.endsWith("/cancelled.jsonl") ? {cancel: true} : undefined); }`), 0600); err != nil {
		t.Fatal(err)
	}
	run := func(args ...string) string {
		t.Helper()
		cmd := exec.Command("tmux", append([]string{"-f", "/dev/null", "-S", sock}, args...)...)
		cmd.Env = append(os.Environ(), "TMUX=")
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("tmux %s failed: %v", args[0], err)
		}
		return strings.TrimSpace(string(out))
	}
	pi, err := exec.LookPath("pi")
	if err != nil {
		t.Fatal(err)
	}
	run("new-session", "-d", "-s", "issue55-native", "-c", root, "-e", "PI_CODING_AGENT_DIR="+filepath.Join(root, "agent"), pi, "--mode", "rpc", "--session", active, "--offline", "--no-extensions", "--no-skills", "--no-context-files", "--provider", "openai", "--model", "gpt-4.1", "--extension", extension)
	t.Cleanup(func() { exec.Command("tmux", "-S", sock, "kill-server").Run() })
	if run("list-sessions", "-F", "#{session_name}") != "issue55-native" {
		t.Fatal("tmux isolation selfcheck failed")
	}
	pane := discovery.Pane{Socket: sock, PaneID: run("list-panes", "-F", "#{pane_id}"), CWD: root, Command: run("display-message", "-p", "#{pane_current_command}")}
	ctx, cancel := context.WithTimeout(context.Background(), 80*time.Second)
	defer cancel()
	manager := NewManager()
	defer manager.Close()
	open := func(stream string) (net.Conn, *bufio.Reader, Ready) {
		t.Helper()
		conn, err := manager.Open(ctx, pane)
		if err != nil {
			t.Fatal(err)
		}
		_ = conn.SetDeadline(time.Now().Add(35 * time.Second))
		hello, _ := json.Marshal(Hello{Type: "hello", Stream: stream})
		fmt.Fprintln(conn, string(hello))
		reader := bufio.NewReader(conn)
		raw, err := reader.ReadBytes('\n')
		if err != nil {
			t.Fatal(err)
		}
		var ready Ready
		if json.Unmarshal(raw, &ready) != nil || ready.Type != "ready" {
			t.Fatal("missing Ready")
		}
		return conn, reader, ready
	}
	conn, reader, initial := open("")
	defer conn.Close()
	for i := uint64(0); i < initial.HeadSeq; i++ {
		if _, err := reader.ReadBytes('\n'); err != nil {
			t.Fatal(err)
		}
	}
	bridgePane := bridge.NewPane(sock, pane.PaneID)
	before, err := bridgePane.NativePi(ctx)
	if err != nil {
		t.Fatal(err)
	}
	sessions, err := manager.ListSessions(ctx, pane)
	if err != nil || len(sessions) != 3 {
		t.Fatalf("list rows=%d err=%v", len(sessions), err)
	}
	found := false
	for _, s := range sessions {
		if s.ID == b {
			found = s.Name == "Selected history title" && s.FirstMessage == "target context before compaction"
		}
		if s.ID == a && !s.Current {
			t.Fatal("current session not marked")
		}
	}
	if !found {
		t.Fatal("target history metadata unavailable")
	}
	cancelled, cancelErr := manager.ResumeSession(ctx, pane, cancelledID, false)
	if cancelErr == nil || cancelled["cancelled"] != true {
		t.Fatalf("official extension cancellation was ignored: result=%v err=%v", cancelled, cancelErr)
	}
	manager.mu.Lock()
	currentSession := manager.sessions[refOf(pane)]
	manager.mu.Unlock()
	currentSession.w.mu.Lock()
	preserved := currentSession.w.stream == initial.Stream && currentSession.w.sessionID == a
	currentSession.w.mu.Unlock()
	if !preserved {
		t.Fatal("cancelled switch reset old stream/session")
	}
	result, err := manager.ResumeSession(ctx, pane, b, false)
	if err != nil || result["session_id"] != b {
		t.Fatalf("resume result=%v err=%v", result, err)
	}
	after, err := bridgePane.NativePi(ctx)
	if err != nil || before.PID != after.PID || before.Started != after.Started {
		t.Fatal("resume replaced native Pi process")
	}
	conn.Close()
	conn, reader, ready := open(initial.Stream)
	defer conn.Close()
	if !ready.Reset || ready.Stream == initial.Stream {
		t.Fatal("resume did not reset stream")
	}
	var replay strings.Builder
	for {
		raw, err := reader.ReadBytes('\n')
		if err != nil {
			t.Fatal(err)
		}
		replay.Write(raw)
		var record record
		if json.Unmarshal(raw, &record) != nil {
			t.Fatal("invalid replay")
		}
		if record.Seq == ready.HeadSeq {
			break
		}
	}
	for _, want := range []string{"session_reset", b, "target context before compaction", "retained original thought", "retained original tool output", "target latest reply", "Selected history title"} {
		if !strings.Contains(replay.String(), want) {
			t.Fatalf("missing replay %s", want)
		}
	}
	for _, hidden := range []string{"old context", "old answer", "abandoned branch marker"} {
		if strings.Contains(replay.String(), hidden) {
			t.Fatalf("replayed foreign branch %s", hidden)
		}
	}
	if _, err = manager.ResumeSession(ctx, pane, "33333333-4444-4555-8666-777777777777", false); err == nil {
		t.Fatal("foreign cwd history was selectable")
	}
	fmt.Fprintln(conn, `{"type":"get_state","id":"native-confirm"}`)
	for {
		raw, err := reader.ReadBytes('\n')
		if err != nil {
			t.Fatal(err)
		}
		var record record
		_ = json.Unmarshal(raw, &record)
		var response struct {
			ID      string `json:"id"`
			Success bool   `json:"success"`
			Data    struct {
				ID string `json:"sessionId"`
			} `json:"data"`
		}
		_ = json.Unmarshal(record.Event, &response)
		if response.ID == "native-confirm" {
			if !response.Success || response.Data.ID != b {
				t.Fatal("native RPC not usable on selected session")
			}
			break
		}
	}
	t.Logf("official switch_session PASS: same native PID=%d, sessions=%d, reset=true, raw branch/thought/tool restored", after.PID, len(sessions))
}
