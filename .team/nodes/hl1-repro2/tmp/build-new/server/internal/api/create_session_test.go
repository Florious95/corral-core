package api

import (
	"fmt"
	"os"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestCreateSessionAddsPane(t *testing.T) {
	te := startCreateEnv(t)
	before := paneCount(t, te)
	te.wsEnv.sendFrame(&protocol.CreateSession{
		ReqID: 31,
		Cwd:   te.cwd,
		Argv:  []string{"sleep", "30"},
	})
	got := te.wsEnv.readControlDraining()
	ack, ok := got.(protocol.CreateSessionAck)
	if !ok {
		t.Fatalf("got %T, want CreateSessionAck", got)
	}
	if !ack.OK {
		t.Fatalf("create_session_ack ok=false reason=%s", ack.Reason)
	}
	if ack.Ref == "" {
		t.Fatal("ok ack missing ref")
	}
	after := paneCount(t, te)
	if after != before+1 {
		t.Fatalf("pane count %d → %d, want +1", before, after)
	}
}

func TestCreateSessionMissingCwd(t *testing.T) {
	te := startCreateEnv(t)
	te.wsEnv.sendFrame(&protocol.CreateSession{
		ReqID: 32,
		Cwd:   te.cwd + "/no-such-dir",
		Argv:  []string{"sleep", "1"},
	})
	got := te.wsEnv.readControlDraining()
	ack, ok := got.(protocol.CreateSessionAck)
	if !ok {
		t.Fatalf("got %T", got)
	}
	if ack.OK || ack.Reason != protocol.CreateFailCwdNotFound {
		t.Fatalf("want cwd_not_found, got ok=%v reason=%s", ack.OK, ack.Reason)
	}
}

func TestCreateSessionNoAnchor(t *testing.T) {
	te := startCreateEnv(t)
	other := t.TempDir()
	te.wsEnv.sendFrame(&protocol.CreateSession{
		ReqID: 33,
		Cwd:   other,
		Argv:  []string{"sleep", "1"},
	})
	got := te.wsEnv.readControlDraining()
	ack := got.(protocol.CreateSessionAck)
	if ack.OK || ack.Reason != protocol.CreateFailNoTmuxAnchor {
		t.Fatalf("want no_tmux_anchor, got ok=%v reason=%s", ack.OK, ack.Reason)
	}
}

type createEnv struct {
	*tmuxEnv
	cwd string
}

func startCreateEnv(t *testing.T) *createEnv {
	t.Helper()
	cwd := t.TempDir()
	dir, err := os.MkdirTemp("", "wsapi")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	sock := dir + "/sock"
	env := scrubbedEnv()
	name := fmt.Sprintf("wsapi%d", atomic.AddUint64(&tmuxSeq, 1))
	if out, err := runTmuxCmd(env, sock, "new-session", "-d", "-x", "80", "-y", "24", "-s", name, "-c", cwd, "sleep 3600"); err != nil {
		t.Fatalf("new-session: %v\n%s", err, out)
	}
	t.Cleanup(func() {
		_, _ = runTmuxCmd(env, sock, "kill-server")
		_ = os.RemoveAll(dir)
	})
	out, err := runTmuxCmd(env, sock, "list-panes", "-t", name, "-F", "#{pane_id}")
	if err != nil {
		t.Fatalf("resolve pane: %v\n%s", err, out)
	}
	paneID := strings.TrimSpace(out)
	model := &discovery.Model{
		Workspaces: []discovery.Workspace{
			{
				CWD: cwd,
				Panes: []discovery.Pane{
					{Socket: sock, Session: name, PaneID: paneID, CWD: cwd, Command: "sleep", Width: 80, Height: 24},
				},
			},
		},
	}
	md := &mutableDiscoverer{model: model}
	e := startWS(t, Options{Token: "test-token", Discoverer: md, ProviderFinder: staticProvider("claude_code")})
	e.auth()
	return &createEnv{
		tmuxEnv: &tmuxEnv{t: t, sock: sock, paneID: paneID, env: env, wsEnv: e},
		cwd:     cwd,
	}
}

func paneCount(t *testing.T, te *createEnv) int {
	t.Helper()
	out, err := runTmuxCmd(te.env, te.sock, "list-panes", "-a", "-F", "#{pane_id}")
	if err != nil {
		t.Fatalf("list-panes: %v\n%s", err, out)
	}
	n := 0
	for _, line := range strings.Split(out, "\n") {
		if strings.TrimSpace(line) != "" {
			n++
		}
	}
	return n
}
