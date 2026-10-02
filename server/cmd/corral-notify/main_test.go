package main

import (
	"bytes"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/agentmirror/agentmirror/internal/api"
)

func TestAgentNameOptionRemoved(t *testing.T) {
	for _, args := range [][]string{{"body", "--agent-name", "spoof"}, {"body", "--agent-name=spoof"}} {
		if _, err := parseArgs(args); err == nil {
			t.Fatalf("accepted removed option: %v", args)
		}
	}
	if strings.Contains(usage, "agent-name") {
		t.Fatal("usage advertises the removed option")
	}
}

func TestTMUXSocketAlias(t *testing.T) {
	if runtime.GOOS != "darwin" {
		t.Skip("/tmp and /private/tmp are macOS aliases")
	}
	t.Setenv("TMUX", "/private/tmp/tmux-notify/default,123,0")
	t.Setenv("TMUX_PANE", "%7")
	if got := tmuxSessionRef(); got != "/tmp/tmux-notify/default\x1f%7" {
		t.Fatalf("ref=%q", got)
	}
}

func TestMinimalNotificationDerivesTMUXRefWithoutAgentName(t *testing.T) {
	dir := t.TempDir()
	socket := api.NotificationSocketPath(dir)
	ln, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Remove(socket) })
	aliasSocket := filepath.Join(dir, "alias.sock")
	if err := os.Symlink(socket, aliasSocket); err != nil {
		t.Fatal(err)
	}
	canonical, err := filepath.EvalSymlinks(aliasSocket)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS == "darwin" && strings.HasPrefix(canonical, "/private/tmp/") {
		canonical = strings.TrimPrefix(canonical, "/private")
	}
	t.Setenv("TMUX", aliasSocket+",123,0")
	t.Setenv("TMUX_PANE", "%7")
	t.Setenv("CORRAL_SESSION_REF", "")
	t.Setenv("CORRAL_NOTIFY_SOCKET", socket)

	requests := make(chan map[string]any, 1)
	hs := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var payload map[string]any
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		requests <- payload
		_ = json.NewEncoder(w).Encode(api.NotificationIPCResponse{Accepted: true, ID: "notification"})
	})}
	go func() { _ = hs.Serve(ln) }()
	t.Cleanup(func() { _ = hs.Close() })
	var stdout, stderr bytes.Buffer
	if code := run([]string{"极简通知", "--title", "完成", "--level", "success"}, &stdout, &stderr); code != 0 {
		t.Fatalf("exit=%d stderr=%s", code, &stderr)
	}
	payload := <-requests
	if payload["session_ref"] != canonical+"\x1f%7" {
		t.Fatalf("session_ref=%q want=%q", payload["session_ref"], canonical+"\x1f%7")
	}
	if _, present := payload["agent_name"]; present {
		t.Fatal("CLI supplied agent_name instead of leaving it to the daemon")
	}
	if stderr.Len() != 0 {
		t.Fatalf("stderr=%s", &stderr)
	}
}
