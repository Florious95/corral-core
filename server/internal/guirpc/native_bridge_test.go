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

// Explicit live test: official Pi, real tmux, no cloud prompt/provider call.
// The operator supplies a project-local short socket directory; no host tmux
// or production daemon is ever addressed.
func TestNativeBridgeRealPiLifecycle(t *testing.T) {
	root := os.Getenv("ISSUE56_NATIVE_ROOT")
	if root == "" {
		t.Skip("set ISSUE56_NATIVE_ROOT to a project-local isolated directory")
	}
	if !filepath.IsAbs(root) {
		t.Fatal("native root must be absolute")
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	sock := filepath.Join(root, "native-test.sock")
	if len(sock) >= 100 {
		t.Fatal("native socket path is too long")
	}
	run := func(args ...string) string {
		t.Helper()
		cmd := exec.Command("tmux", append([]string{"-S", sock}, args...)...)
		cmd.Env = append(os.Environ(), "TMUX=")
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("tmux operation %s: %v", args[0], err)
		}
		return strings.TrimSpace(string(out))
	}
	id := "11111111-2222-4333-8444-555555555555"
	file := filepath.Join(root, "session.jsonl")
	head, _ := json.Marshal(map[string]any{"type": "session", "version": 3, "id": id, "timestamp": "2026-10-05T00:00:00.000Z", "cwd": root})
	data := append(head, '\n')
	data = append(data, []byte(`{"type":"message","id":"entry001","parentId":null,"timestamp":"2026-10-05T00:00:01.000Z","message":{"role":"user","content":[{"type":"text","text":"native lifecycle context"}],"timestamp":1}}`+"\n")...)
	data = append(data, []byte(`{"type":"message","id":"entry002","parentId":"entry001","timestamp":"2026-10-05T00:00:02.000Z","message":{"role":"assistant","content":[{"type":"text","text":"retained native reply"}],"api":"openai-responses","provider":"openai-codex","model":"gpt-6-luna","stopReason":"stop","timestamp":2,"usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}}}}`+"\n")...)
	if err := os.WriteFile(file, data, 0600); err != nil {
		t.Fatal(err)
	}
	pi, err := exec.LookPath("pi")
	if err != nil {
		t.Fatal(err)
	}
	// A direct native executable, not a private command or injected helper.
	run("new-session", "-d", "-s", "issue56-native", "-c", root, pi, "--mode", "rpc", "--session", file, "--no-extensions", "--no-skills")
	t.Cleanup(func() { exec.Command("tmux", "-S", sock, "kill-server").Run() })
	if got := run("list-sessions", "-F", "#{session_name}"); got != "issue56-native" {
		t.Fatalf("wrong socket: %s", got)
	}
	pane := discovery.Pane{Socket: sock, PaneID: run("list-panes", "-F", "#{pane_id}"), CWD: root, Command: "pi"}
	p := bridge.NewPane(sock, pane.PaneID)
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	var process bridge.PiProcess
	for until := time.Now().Add(10 * time.Second); time.Now().Before(until); {
		process, err = p.NativePi(ctx)
		if err == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("native detection: %v", err)
	}
	if process.Mode != ModeRPC || process.Session != file {
		t.Fatalf("mode/session detection incorrect: mode=%q session_matches=%v argc=%d", process.Mode, process.Session == file, len(process.Args))
	}
	manager := NewManager()
	defer func() { manager.Close() }()
	if !manager.Detect(ctx, pane) {
		t.Fatal("native RPC not advertised")
	}
	open := func() (net.Conn, *bufio.Reader, Ready) {
		t.Helper()
		conn, err := manager.Open(ctx, pane)
		if err != nil {
			t.Fatal(err)
		}
		conn.SetDeadline(time.Now().Add(40 * time.Second))
		fmt.Fprintln(conn, `{"type":"hello"}`)
		reader := bufio.NewReader(conn)
		line, err := reader.ReadBytes('\n')
		if err != nil {
			t.Fatal(err)
		}
		var ready Ready
		if json.Unmarshal(line, &ready) != nil || ready.Type != "ready" {
			t.Fatal("missing ready")
		}
		return conn, reader, ready
	}
	conn, reader, ready := open()
	defer conn.Close()
	if ready.Mode != ModeRPC || !ready.Reset || ready.HeadSeq < 4 {
		t.Fatalf("native history not replayed: %+v", ready)
	}
	for i := uint64(0); i < ready.HeadSeq; i++ {
		line, err := reader.ReadBytes('\n')
		if err != nil {
			t.Fatal(err)
		}
		var r record
		if json.Unmarshal(line, &r) != nil {
			t.Fatal("bad replay")
		}
	}
	response := func(id string) map[string]any {
		t.Helper()
		returningRPC := id == "to-rpc" || id == "empty-rpc"
		sawReset := false
		for {
			line, err := reader.ReadBytes('\n')
			if err != nil {
				t.Fatal(err)
			}
			var r record
			if json.Unmarshal(line, &r) != nil {
				t.Fatal("bad record")
			}
			var event map[string]any
			json.Unmarshal(r.Event, &event)
			if event["type"] == "session_reset" {
				sawReset = true
			}
			if returningRPC && event["type"] == "message_start" && !sawReset {
				t.Fatal("native history replay preceded client transcript reset")
			}
			if event["type"] == "response" && event["id"] == id {
				if returningRPC && event["success"] == true && !sawReset {
					t.Fatal("RPC return never reset client transcript")
				}
				return event
			}
		}
	}
	large, _ := json.Marshal(map[string]any{"id": "large", "type": "get_commands", "padding": strings.Repeat("x", 16384)})
	fmt.Fprintln(conn, string(large))
	if got := response("large"); got["success"] != true {
		t.Fatal("long JSON failed")
	}
	fmt.Fprintln(conn, `{"id":"to-tui","type":"switch_mode","mode":"tui"}`)
	if got := response("to-tui"); got["success"] != true {
		t.Fatalf("to TUI: %v", got)
	}
	tui, err := p.NativePi(ctx)
	if err != nil || tui.Mode != ModeTUI || tui.Session != file {
		t.Fatal("not native TUI on same session")
	}
	fmt.Fprintln(conn, `{"id":"blocked","type":"prompt","message":"must not reach TUI"}`)
	if got := response("blocked"); got["success"] != false {
		t.Fatal("structured prompt reached TUI")
	}
	conn.SetDeadline(time.Now().Add(40 * time.Second))
	fmt.Fprintln(conn, `{"id":"to-rpc","type":"switch_mode","mode":"rpc"}`)
	if got := response("to-rpc"); got["success"] != true {
		t.Fatalf("to RPC: %v", got)
	}
	rpc, err := p.NativePi(ctx)
	if err != nil || rpc.Mode != ModeRPC || rpc.Session != file {
		t.Fatal("not native RPC on same session")
	}
	before := rpc.PID
	conn.Close()
	manager.Close()
	still, err := p.NativePi(ctx)
	if err != nil || still.PID != before {
		t.Fatal("bridge shutdown killed native Pi")
	}
	if got := run("display-message", "-p", "-t", pane.PaneID, "#{pane_pipe}"); got != "0" {
		t.Fatal("bridge left pipe-pane attached")
	}
	manager = NewManager()
	conn, reader, ready = open()
	defer conn.Close()
	if !ready.Reset || ready.HeadSeq < 4 {
		t.Fatal("daemon reattach did not hydrate native history")
	}
	conn.Close()
	manager.Close()
	// A newly created empty session has no durable file yet. Native explicit-ID
	// startup must still preserve its UUID across both modes, without a wrapper.
	emptyID := "66666666-7777-4888-8999-000000000000"
	emptyDir := filepath.Join(root, "empty-sessions")
	pane.PaneID = run("new-window", "-P", "-F", "#{pane_id}", "-t", "issue56-native", "-c", root, pi, "--mode", "rpc", "--session-id", emptyID, "--session-dir", emptyDir, "--no-extensions", "--no-skills")
	manager = NewManager()
	conn, reader, ready = open()
	defer conn.Close()
	if ready.Mode != ModeRPC || ready.HeadSeq != 0 {
		t.Fatal("new native session is not empty RPC")
	}
	fmt.Fprintln(conn, `{"id":"empty-tui","type":"switch_mode","mode":"tui"}`)
	if got := response("empty-tui"); got["success"] != true {
		t.Fatalf("empty TUI: %v", got)
	}
	fmt.Fprintln(conn, `{"id":"empty-rpc","type":"switch_mode","mode":"rpc"}`)
	if got := response("empty-rpc"); got["success"] != true {
		t.Fatalf("empty RPC: %v", got)
	}
	manager.mu.Lock()
	currentSession := manager.sessions[refOf(pane)]
	manager.mu.Unlock()
	currentSession.w.mu.Lock()
	sameID := currentSession.w.sessionID == emptyID
	currentSession.w.mu.Unlock()
	if !sameID {
		t.Fatal("empty session UUID changed")
	}
}
