package guirpc

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
)

// Official offline Pi, owned socket and session files. No model calls or daemon.
func TestNativePiForkCloneRenameNewAndInteractiveVeto(t *testing.T) {
	root := os.Getenv("FULL_TUI_NATIVE_ROOT")
	if root == "" {
		t.Skip("set FULL_TUI_NATIVE_ROOT to an isolated project directory")
	}
	sock := filepath.Join(root, "fork.sock")
	if !filepath.IsAbs(root) || len([]byte(sock)) >= 100 {
		t.Fatal("isolated socket path invalid")
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(root, "sessions")
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	sourceID := "55555555-6666-4777-8888-999999999999"
	active := filepath.Join(dir, "active.jsonl")
	writePiSessionFixture(t, active, sourceID, root, historyMessage("user-one", "", "user", "first request"), historyMessage("assistant-one", "user-one", "assistant", "first answer"), historyMessage("user-two", "assistant-one", "user", "second request"), historyMessage("assistant-two", "user-two", "assistant", "second answer"), map[string]any{"type": "session_info", "id": "title", "parentId": "assistant-two", "name": "ORIGINAL TITLE", "timestamp": "2026-10-06T01:00:00Z"})
	extension := filepath.Join(root, "decision.ts")
	if err := os.WriteFile(extension, []byte(`export default function(pi) { pi.on("session_before_fork", async (event,ctx) => { const yes = await ctx.ui.confirm("Native before fork", "Owned offline fixture only"); return { cancel: !yes }; }); }`), 0600); err != nil {
		t.Fatal(err)
	}
	run := func(args ...string) string {
		t.Helper()
		cmd := exec.Command("tmux", append([]string{"-f", "/dev/null", "-S", sock}, args...)...)
		cmd.Env = append(os.Environ(), "TMUX=")
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("owned tmux %s: %v", args[0], err)
		}
		return strings.TrimSpace(string(out))
	}
	pi, err := exec.LookPath("pi")
	if err != nil {
		t.Fatal(err)
	}
	run("new-session", "-d", "-s", "full-tui-native", "-c", root, "-e", "PI_CODING_AGENT_DIR="+filepath.Join(root, "agent"), pi, "--mode", "rpc", "--session", active, "--session-dir", dir, "--offline", "--no-extensions", "--no-skills", "--no-context-files", "--provider", "openai", "--model", "gpt-4.1", "--extension", extension)
	t.Cleanup(func() { exec.Command("tmux", "-S", sock, "kill-server").Run() })
	if run("list-sessions", "-F", "#{session_name}") != "full-tui-native" {
		t.Fatal("socket identity selfcheck failed")
	}
	pane := discovery.Pane{Socket: sock, PaneID: run("list-panes", "-F", "#{pane_id}"), CWD: root, Command: run("display-message", "-p", "#{pane_current_command}")}
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	m := NewManager()
	defer m.Close()
	s, err := m.sessionFor(ctx, pane)
	if err != nil {
		t.Fatal(err)
	}
	before, err := bridge.NewPane(sock, pane.PaneID).NativePi(ctx)
	if err != nil {
		t.Fatal(err)
	}
	attach := func() { _, _, _ = s.w.attach(Hello{}, true) }
	attach()
	command := func(kind string, fields map[string]any) map[string]json.RawMessage {
		fields["type"] = kind
		raw, _ := json.Marshal(fields)
		var c map[string]json.RawMessage
		_ = json.Unmarshal(raw, &c)
		return c
	}
	delayedClone := false
	mutate := func(kind string, fields map[string]any, accept bool) (map[string]any, error) {
		t.Helper()
		type reply struct {
			data map[string]any
			err  error
		}
		ch := make(chan reply, 1)
		go func() { d, e := s.operation(command(kind, fields)); ch <- reply{d, e} }()
		deadline := time.Now().Add(10 * time.Second)
		token := ""
		for token == "" && time.Now().Before(deadline) {
			s.w.mu.Lock()
			for k := range s.w.interactions {
				token = k
			}
			s.w.mu.Unlock()
			if token == "" {
				time.Sleep(5 * time.Millisecond)
			}
		}
		if token == "" {
			t.Fatal("real before-fork dialog never arrived")
		}
		if kind == "clone_session" && accept && !delayedClone {
			delayedClone = true
			time.Sleep(30 * time.Second)
		}
		raw, _ := json.Marshal(map[string]any{"requestId": token, "confirmed": accept})
		if err := s.w.replyInteraction(raw); err != nil {
			t.Fatal(err)
		}
		select {
		case r := <-ch:
			return r.data, r.err
		case <-ctx.Done():
			t.Fatal("native mutation did not finish")
			return nil, ctx.Err()
		}
	}
	cancelled, err := mutate("clone_session", map[string]any{}, false)
	if err == nil || cancelled["cancelled"] != true || s.w.sessionID != sourceID {
		t.Fatalf("veto changed context: %v %v", cancelled, err)
	}
	clone, err := mutate("clone_session", map[string]any{}, true)
	if err != nil {
		t.Fatal(err)
	}
	cloneID, _ := clone["session_id"].(string)
	if cloneID == "" || cloneID == sourceID {
		t.Fatal("clone did not confirm new native ID")
	}
	replay := s.w.historySnapshot()
	var text strings.Builder
	for _, r := range replay.records {
		text.Write(r.event)
	}
	for _, want := range []string{"first request", "first answer", "second request", "second answer"} {
		if !strings.Contains(text.String(), want) {
			t.Fatalf("clone missing %s", want)
		}
	}
	attach()
	rename, err := s.operation(command("rename_session", map[string]any{"name": "CLONED TITLE"}))
	if err != nil || rename["sessionName"] != "CLONED TITLE" || rename["session_id"] != cloneID {
		t.Fatalf("rename=%v err=%v", rename, err)
	}
	points, err := s.operation(command("fork_points", map[string]any{}))
	if err != nil {
		t.Fatal(err)
	}
	rows := points["points"].([]map[string]any)
	token := ""
	for _, row := range rows {
		if row["text"] == "second request" {
			token = row["id"].(string)
		}
	}
	if token == "" {
		t.Fatal("native second user not selectable")
	}
	fork, err := mutate("fork_session", map[string]any{"pointId": token}, true)
	if err != nil {
		t.Fatal(err)
	}
	if fork["draft"] != "second request" || fork["session_id"] == cloneID {
		t.Fatalf("fork identity/draft=%v", fork)
	}
	replay = s.w.historySnapshot()
	text.Reset()
	for _, r := range replay.records {
		text.Write(r.event)
	}
	if !strings.Contains(text.String(), "first request") || strings.Contains(text.String(), "second request") || strings.Contains(text.String(), "second answer") {
		t.Fatalf("fork before-user semantics wrong: %s", text.String())
	}
	attach()
	points, err = s.operation(command("fork_points", map[string]any{}))
	if err != nil {
		t.Fatal(err)
	}
	token = ""
	for _, row := range points["points"].([]map[string]any) {
		if row["text"] == "first request" {
			token = row["id"].(string)
		}
	}
	if token == "" {
		t.Fatal("native first user not selectable")
	}
	emptyFork, err := mutate("fork_session", map[string]any{"pointId": token}, true)
	if err != nil || emptyFork["draft"] != "first request" {
		t.Fatalf("empty before-first fork=%v err=%v", emptyFork, err)
	}
	attach()
	_, rejected := mutate("clone_session", map[string]any{}, true)
	if rejected == nil || !strings.Contains(rejected.Error(), "尚未持久化") {
		t.Fatalf("native empty clone needs an exact visible reason: %v", rejected)
	}
	if s.ctx.Err() != nil {
		t.Fatal("explicit native rejection closed a healthy bridge")
	}
	if err := s.confirmStateContext(ctx, time.Second*5); err != nil || s.w.sessionID != emptyFork["session_id"] {
		t.Fatalf("rejected clone lost unchanged native context: %v", err)
	}
	created, err := s.operation(command("new_session", map[string]any{}))
	if err != nil {
		t.Fatal(err)
	}
	if created["sessionName"] != "" || created["session_id"] == fork["session_id"] {
		t.Fatalf("new title/identity=%v", created)
	}
	replay = s.w.historySnapshot()
	text.Reset()
	for _, r := range replay.records {
		text.Write(r.event)
	}
	if strings.Contains(text.String(), "CLONED TITLE") || strings.Contains(text.String(), "ORIGINAL TITLE") || strings.Contains(text.String(), "first request") {
		t.Fatalf("new retained old title/history: %s", text.String())
	}
	after, err := bridge.NewPane(sock, pane.PaneID).NativePi(ctx)
	if err != nil || after.PID != before.PID || after.Started != before.Started {
		t.Fatal("native process was replaced")
	}
	t.Log("official Pi clone veto/accept, real confirm callback, fork-before-user/draft, rename and new reset: PASS; same PID, zero model calls")
}
