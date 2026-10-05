package guirpc

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
	"golang.org/x/term"
)

func TestNativeGrokBridgeLifecycle(t *testing.T) {
	root := os.Getenv("GROK_NATIVE_ROOT")
	if root == "" {
		t.Skip("set project-local GROK_NATIVE_ROOT for official Grok and a real prompt")
	}
	if !filepath.IsAbs(root) {
		t.Fatal("native root must be absolute")
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	sock := filepath.Join(root, "g.sock")
	leader := filepath.Join(root, "gl.sock")
	if len([]byte(sock)) >= 100 || len([]byte(leader)) >= 100 {
		t.Fatal("native socket path too long")
	}
	grok, err := exec.LookPath("grok")
	if err != nil {
		t.Fatal(err)
	}
	marker := "CORRAL-GROK-LIFECYCLE-9221"
	if err := os.WriteFile(filepath.Join(root, "proof.txt"), []byte(marker+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	run := func(args ...string) string {
		t.Helper()
		cmd := exec.Command("tmux", append([]string{"-S", sock}, args...)...)
		cmd.Env = append(os.Environ(), "TMUX=")
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("owned tmux %s: %v", args[0], err)
		}
		return strings.TrimSpace(string(out))
	}
	run("new-session", "-d", "-s", "grok-native", "-c", root, grok, "--leader-socket", leader, "agent", "--no-leader", "stdio")
	t.Cleanup(func() {
		cmd := exec.Command("tmux", "-S", sock, "kill-server")
		cmd.Env = append(os.Environ(), "TMUX=")
		_ = cmd.Run()
		_ = os.Remove(sock)
	})
	if run("list-sessions", "-F", "#{socket_path} #{session_name}") != sock+" grok-native" {
		t.Fatal("socket identity mismatch")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	b := bridge.NewPane(sock, "%0")
	var original bridge.NativeProcess
	for until := time.Now().Add(10 * time.Second); time.Now().Before(until); {
		original, err = b.NativeAgent(ctx)
		if err == nil && original.Provider == "grok" && original.Mode == ModeRPC {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err != nil || original.Provider != "grok" {
		t.Fatal("native Grok not detected")
	}
	fd, err := os.OpenFile(original.TTY, os.O_RDWR, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer fd.Close()
	originalTTY, err := term.GetState(int(fd.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	// Read discovery's actual tmux command field, never an assumed name.
	// The normal discovery scanner deliberately excludes project-owned test
	// sockets; querying this exact socket preserves that production boundary.
	observedPane := func(id string) discovery.Pane {
		t.Helper()
		for until := time.Now().Add(10 * time.Second); time.Now().Before(until); {
			process, err := bridge.NewPane(sock, id).NativeAgent(ctx)
			if err == nil && process.Provider == "grok" {
				command := run("display-message", "-p", "-t", id, "#{pane_current_command}")
				t.Logf("actual tmux pane=%s command=%s", id, command)
				return discovery.Pane{Socket: sock, PaneID: id, CWD: root, Command: command}
			}
			time.Sleep(50 * time.Millisecond)
		}
		t.Fatal("native Grok pane did not start")
		return discovery.Pane{}
	}
	pane := observedPane("%0")
	manager := NewManager()
	defer manager.Close()
	if manager.Detect(ctx, pane) != "grok" {
		t.Fatal("passive Grok capability unavailable")
	}
	conn, err := manager.Open(ctx, pane)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(100 * time.Second))
	_, err = conn.Write([]byte("{\"type\":\"hello\"}\n"))
	if err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(conn)
	line, err := reader.ReadBytes('\n')
	if err != nil {
		t.Fatal(err)
	}
	var ready Ready
	if json.Unmarshal(line, &ready) != nil || ready.Type != "ready" || ready.Mode != ModeRPC {
		t.Fatal("native ACP not ready")
	}
	send := func(c map[string]any) {
		t.Helper()
		data, _ := json.Marshal(c)
		if _, err := conn.Write(append(data, '\n')); err != nil {
			t.Fatal(err)
		}
	}
	read := func() map[string]json.RawMessage {
		t.Helper()
		line, err := reader.ReadBytes('\n')
		if err != nil {
			t.Fatal(err)
		}
		var record struct {
			Event map[string]json.RawMessage `json:"event"`
		}
		if json.Unmarshal(line, &record) != nil {
			t.Fatal("invalid record")
		}
		return record.Event
	}
	await := func(id string) map[string]json.RawMessage {
		t.Helper()
		for {
			event := read()
			var got string
			_ = json.Unmarshal(event["id"], &got)
			if got == id {
				var ok bool
				_ = json.Unmarshal(event["success"], &ok)
				if !ok {
					t.Fatalf("command %s failed: %s", id, event["error"])
				}
				return event
			}
		}
	}
	send(map[string]any{"type": "get_state", "id": "state"})
	state := await("state")
	var header struct {
		Model    modelSummary `json:"model"`
		Thinking string       `json:"thinkingLevel"`
		Agent    string       `json:"agentProvider"`
	}
	if json.Unmarshal(state["data"], &header) != nil || header.Agent != "grok" || header.Model.Provider != "grok" || header.Model.ID == "" {
		t.Fatal("model projection missing")
	}
	send(map[string]any{"type": "get_available_models", "id": "models"})
	models := await("models")
	if !strings.Contains(string(models["data"]), "grok-4.7-build-fast") {
		t.Fatal("dynamic Grok models missing")
	}
	send(map[string]any{"type": "get_available_thinking_levels", "id": "levels"})
	levels := await("levels")
	if !strings.Contains(string(levels["data"]), "xhigh") {
		t.Fatal("native effort config missing")
	}
	send(map[string]any{"type": "set_model", "id": "model-fast", "modelId": "grok-4.7-build-fast"})
	await("model-fast")
	send(map[string]any{"type": "set_thinking_level", "id": "effort", "level": "low"})
	await("effort")
	send(map[string]any{"type": "prompt", "id": "prompt", "message": "Read proof.txt in the current directory using read_file, then answer with exactly its content. Do not modify files, use shell commands, start subagents, or browse. Ignore this padding: " + strings.Repeat("x", 16<<10)})
	gotTool, gotText, gotAdmission := false, false, false
	for {
		e := read()
		var kind string
		_ = json.Unmarshal(e["type"], &kind)
		if kind == "tool_execution_start" {
			gotTool = true
		}
		if kind == "message_end" && strings.Contains(string(e["message"]), marker) {
			gotText = true
		}
		if kind == "response" {
			var id string
			_ = json.Unmarshal(e["id"], &id)
			if id == "prompt" {
				var ok bool
				_ = json.Unmarshal(e["success"], &ok)
				if !ok {
					t.Fatal(string(e["error"]))
				}
				gotAdmission = true
			}
		}
		if kind == "agent_settled" {
			break
		}
	}
	if !gotTool || !gotText || !gotAdmission {
		t.Fatalf("prompt projection: tool=%v text=%v admission=%v", gotTool, gotText, gotAdmission)
	}
	id, err := b.NativeSession(ctx, original)
	if err != nil || id == "" {
		t.Fatal("durable ACP identity unavailable")
	}
	conn.Close()
	manager.Close()
	restoredTTY, err := term.GetState(int(fd.Fd()))
	if err != nil || !reflect.DeepEqual(originalTTY, restoredTTY) {
		t.Fatal("Grok bridge shutdown did not restore full original termios")
	}
	now, err := b.NativeAgent(ctx)
	if err != nil || now.PID != original.PID || now.Started != original.Started {
		t.Fatal("bridge shutdown killed or replaced Grok")
	}
	manager = NewManager()
	defer manager.Close()
	conn, err = manager.Open(ctx, pane)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
	_, _ = conn.Write([]byte("{\"type\":\"hello\"}\n"))
	reader = bufio.NewReader(conn)
	line, err = reader.ReadBytes('\n')
	if err != nil {
		t.Fatal(err)
	}
	if json.Unmarshal(line, &ready) != nil || !ready.Reset || ready.HeadSeq == 0 {
		t.Fatal("native session replay missing")
	}
	replayed := false
	for i := uint64(0); i < ready.HeadSeq; i++ {
		e := read()
		if strings.Contains(string(e["message"]), marker) {
			replayed = true
			break
		}
	}
	if !replayed {
		t.Fatal("reattach did not load real native history")
	}
	manager.mu.Lock()
	nativeSession := manager.sessions[refOf(pane)]
	manager.mu.Unlock()
	switchNative := nativeSession.w.onSwitch
	nativeSession.w.onSwitch = func(mode string, force bool) (map[string]any, error) {
		data, err := switchNative(mode, force)
		if err != nil {
			t.Logf("native switch mode=%s force=%v: %v", mode, force, err)
		}
		return data, err
	}
	send(map[string]any{"type": "switch_mode", "id": "tui", "mode": ModeTUI})
	await("tui")
	terminal, err := b.NativeAgent(ctx)
	if err != nil || terminal.Mode != ModeTUI || terminal.Provider != "grok" || terminal.Session != id {
		t.Fatal("official TUI resume did not preserve the known session")
	}
	if manager.Detect(ctx, pane) != "grok" {
		t.Fatal("managed Grok TUI lost its switch capability")
	}
	send(map[string]any{"type": "switch_mode", "id": "consent", "mode": ModeRPC})
	for {
		e := read()
		var got string
		_ = json.Unmarshal(e["id"], &got)
		if got != "consent" {
			continue
		}
		var ok bool
		_ = json.Unmarshal(e["success"], &ok)
		var data struct {
			Busy bool `json:"busy"`
		}
		_ = json.Unmarshal(e["data"], &data)
		if ok || !data.Busy {
			t.Fatal("unknown TUI task state was silently treated as idle")
		}
		break
	}
	send(map[string]any{"type": "switch_mode", "id": "rpc", "mode": ModeRPC, "force": true})
	await("rpc")
	resumed, err := b.NativeAgent(ctx)
	if err != nil || resumed.Mode != ModeRPC || resumed.Provider != "grok" {
		t.Fatal("official ACP replacement unavailable")
	}
	resumedID, err := b.NativeSession(ctx, resumed)
	if err != nil || resumedID != id {
		t.Fatal("last-known native session was not restored")
	}
	if resumed.PID == terminal.PID || terminal.PID == original.PID {
		t.Fatal("mode change did not replace the native process")
	}
	send(map[string]any{"type": "new_session", "id": "new-session"})
	await("new-session")
	newID, err := b.NativeSession(ctx, resumed)
	if err != nil || newID == "" || newID == id {
		t.Fatal("GUI session/new did not update durable native identity")
	}
	// A pure TUI has no known ACP identity. It stays untouched until explicit
	// consent, then official session/new creates a genuinely new GUI session.
	freshID := run("split-window", "-d", "-P", "-F", "#{pane_id}", "-t", "%0", "-c", root, grok, "--no-leader", "--leader-socket", leader)
	freshPane := observedPane(freshID)
	if manager.Detect(ctx, freshPane) != "grok" {
		t.Fatal("pure TUI absent from native listing capability")
	}
	fresh, err := manager.Open(ctx, freshPane)
	if err != nil {
		t.Fatal(err)
	}
	defer fresh.Close()
	_ = fresh.SetDeadline(time.Now().Add(35 * time.Second))
	_, _ = fresh.Write([]byte("{\"type\":\"hello\"}\n"))
	freshReader := bufio.NewReader(fresh)
	line, err = freshReader.ReadBytes('\n')
	if err != nil || json.Unmarshal(line, &ready) != nil || ready.Mode != ModeTUI {
		t.Fatal("pure TUI capability did not remain TUI")
	}
	_, err = fresh.Write([]byte("{\"type\":\"switch_mode\",\"id\":\"fresh-rpc\",\"mode\":\"rpc\",\"force\":true}\n"))
	if err != nil {
		t.Fatal(err)
	}
	for {
		line, err = freshReader.ReadBytes('\n')
		if err != nil {
			t.Fatal(err)
		}
		var r struct {
			Event struct {
				ID      string `json:"id"`
				Success bool   `json:"success"`
				Error   string `json:"error"`
			} `json:"event"`
		}
		if json.Unmarshal(line, &r) != nil {
			t.Fatal("invalid fresh TUI record")
		}
		if r.Event.ID != "fresh-rpc" {
			continue
		}
		if !r.Event.Success {
			t.Fatal(r.Event.Error)
		}
		break
	}
	freshProcess, err := bridge.NewPane(sock, freshID).NativeAgent(ctx)
	if err != nil || freshProcess.Mode != ModeRPC {
		t.Fatal("pure TUI did not become native ACP")
	}
	freshSession, err := bridge.NewPane(sock, freshID).NativeSession(ctx, freshProcess)
	if err != nil || freshSession == "" || freshSession == id {
		t.Fatal("pure TUI switch guessed an existing session")
	}
}
