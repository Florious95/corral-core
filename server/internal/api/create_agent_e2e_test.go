package api

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestCreateAgentUsesAnchorSessionAndLocksTmuxName(t *testing.T) {
	dir, err := os.MkdirTemp("", "ca")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	socket := filepath.Join(dir, "sock")
	cwd := t.TempDir()
	run := func(args ...string) string {
		t.Helper()
		cmd := exec.Command("tmux", append([]string{"-S", socket}, args...)...)
		env := make([]string, 0, len(os.Environ()))
		for _, kv := range os.Environ() {
			if strings.HasPrefix(kv, "TMUX=") || strings.HasPrefix(kv, "TMUX_TMPDIR=") {
				continue
			}
			env = append(env, kv)
		}
		cmd.Env = env
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("tmux %v: %v (%s)", args, err, out)
		}
		return strings.TrimSpace(string(out))
	}
	t.Cleanup(func() { _ = exec.Command("tmux", "-S", socket, "kill-server").Run() })
	session := "0"
	run("new-session", "-d", "-s", session, "-c", cwd, "sh")
	paneID := run("list-panes", "-t", session, "-F", "#{pane_id}")
	model := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: cwd, Panes: []discovery.Pane{{
		Socket: socket, Session: session, PaneID: paneID, CWD: cwd, Command: "sh", Width: 80, Height: 24,
	}}}}}
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: model}, ListInterval: time.Hour})
	e.srv.agentLaunchers = []agentLauncher{{
		AgentLauncher: protocol.AgentLauncher{Provider: "fixture", DisplayName: "Fixture", Naming: "tmux"},
		command:       "/bin/sh",
	}}
	e.auth()
	e.sendFrame(&protocol.CreateAgent{ReqID: 9, Workspace: cwd, AnchorRef: socket + "\x1f" + paneID, Provider: "fixture", Name: "child window", Bypass: false})
	result := e.readControl().(protocol.CreateAgentResult)
	if !result.OK || result.Naming != "tmux" || result.Name != "child window" {
		t.Fatalf("create result=%+v, want successful tmux result", result)
	}
	meta := run("display-message", "-p", "-t", strings.TrimPrefix(result.Ref, socket+"\x1f"), "#{window_name}|#{pane_title}|#{automatic-rename}|#{allow-rename}")
	if meta != "child window|child window|0|0" {
		t.Fatalf("new pane metadata=%q, want locked name and title", meta)
	}
}
