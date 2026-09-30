package api

import (
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

type staticProvider string

func (s staticProvider) Identify(int) string { return string(s) }

type mapProvider map[int]string

func (m mapProvider) Identify(pid int) string { return m[pid] }

func TestProviderWhitelistIdentityBeforeStatus(t *testing.T) {
	// 身份优先：别家会认领的标题，落到本家时**不得被判成别家的状态**。
	// 068 §8 修正后，本家不认领 ⇒ 落回 062 三态（此标题字母开头 ⇒ idle），
	// ⛔ 关键是它**不能是 working**——那才叫「被别家偷走」。
	title := "x - Thinking - stolen-by-wrong-family"
	stGrok, claimed := grokDetector{}.Match(title)
	if !claimed || stGrok != protocol.SessionStatusWorking {
		t.Fatalf("precondition: grok detector should claim %q", title)
	}
	st, _, known := classifyForProvider("claude_code", title)
	if st == protocol.SessionStatusWorking || st != protocol.SessionStatusIdle || !known {
		t.Fatalf("identity-first: provider=claude_code title=%q → status=%q known=%v; want idle (068 §8：不认领则落回 062 三态；未知只由认不出的前导符号产生)",
			title, st, known)
	}
}

func TestProviderWhitelistUnknownLogsProvider(t *testing.T) {
	title := "?wl-unknown-for-known-family"
	var cap syncBuf
	lg := slog.New(slog.NewTextHandler(&cap, &slog.HandlerOptions{Level: slog.LevelDebug}))
	logUnknownForProvider(lg, "codex", title, '?')
	got := cap.String()
	if !strings.Contains(got, "codex") || !strings.Contains(got, "U+003F") || !strings.Contains(got, title) {
		t.Fatalf("unknown log missing provider/codepoint/title: %q", got)
	}
}

func TestProviderWhitelistExcludesNoiseFromLevel2(t *testing.T) {
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				l2Pane("◐ keep", "alpha", "w-keep", "/ws/a", "/tmp/sock1", "%0", 80, 24),
				l2Pane("host", "alpha", "w-bare", "/ws/a", "/tmp/sock1", "%1", 80, 24),
			}},
		},
	}}
	md.model.Workspaces[0].Panes[0].PanePID = 11
	md.model.Workspaces[0].Panes[1].PanePID = 22
	e := startWS(t, Options{
		Token:          "test-token",
		Discoverer:     md,
		ListInterval:   time.Hour,
		Level2Interval: 30 * time.Millisecond,
		ProviderFinder: mapProvider{11: "claude_code"},
	})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
	got := waitLevel2Frame(t, e, 5*time.Second)
	if len(got.Sessions) != 1 {
		t.Fatalf("sessions=%d, want 1 (bare pane must not appear)", len(got.Sessions))
	}
	if got.Sessions[0].Name != "w-keep" {
		t.Fatalf("name=%q, want w-keep", got.Sessions[0].Name)
	}
	if got.Sessions[0].Provider != "claude_code" {
		t.Fatalf("provider=%q, want claude_code", got.Sessions[0].Provider)
	}
}

func TestProviderWhitelistLevel1(t *testing.T) {
	// A-wl-l1: a socket with only a bare shell must not appear in the
	// level-1 listing; a socket with one whitelist pane must. Identity
	// is identifyProvider (same function as level-2).
	md := &mutableDiscoverer{model: &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/bare", Panes: []discovery.Pane{
				{Socket: "/tmp/sock-bare", Session: "sh", PaneID: "%0", CWD: "/ws/bare",
					Command: "bash", WindowName: "w-bare", PanePID: 101, Width: 80, Height: 24},
			}},
			{CWD: "/ws/cli", Panes: []discovery.Pane{
				{Socket: "/tmp/sock-cli", Session: "team", PaneID: "%1", CWD: "/ws/cli",
					Command: "node", WindowName: "w-codex", PanePID: 202, Width: 80, Height: 24},
				{Socket: "/tmp/sock-cli", Session: "team", PaneID: "%2", CWD: "/ws/cli",
					Command: "bash", WindowName: "w-noise", PanePID: 203, Width: 80, Height: 24},
			}},
		},
	}}
	e := startWS(t, Options{
		Token:          "test-token",
		Discoverer:     md,
		ListInterval:   time.Hour,
		ProviderFinder: mapProvider{202: "codex"},
	})
	e.auth()
	e.sendFrame(&protocol.List{ReqID: 9})
	got := e.readControl()
	l, ok := got.(protocol.Listing)
	if !ok {
		t.Fatalf("expected listing, got %v", got.FrameType())
	}
	if len(l.Workspaces) != 1 {
		t.Fatalf("workspaces=%d, want 1 (bare socket must vanish); %+v", len(l.Workspaces), l.Workspaces)
	}
	ws := l.Workspaces[0]
	if ws.Cwd != "/ws/cli" {
		t.Fatalf("cwd=%q, want /ws/cli (bare socket leaked)", ws.Cwd)
	}
	if ws.SessionCount != 1 || len(ws.Sessions) != 1 {
		t.Fatalf("sessions=%d count=%d, want 1 (noise pane on the same socket must not inflate L1)",
			len(ws.Sessions), ws.SessionCount)
	}
	if ws.Sessions[0].Name != "w-codex" {
		t.Fatalf("session name=%q, want w-codex", ws.Sessions[0].Name)
	}

	// Same identity function: L2 of /ws/cli is the same single row.
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/cli"})
	l2 := waitLevel2Frame(t, e, 5*time.Second)
	if len(l2.Sessions) != 1 || l2.Sessions[0].Name != "w-codex" || l2.Sessions[0].Provider != "codex" {
		t.Fatalf("L2 must match L1 (same identifyProvider): %+v", l2.Sessions)
	}
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/bare"})
	l2bare := waitLevel2Frame(t, e, 5*time.Second)
	if len(l2bare.Sessions) != 0 {
		t.Fatalf("L2 of excluded socket must be empty, got %+v", l2bare.Sessions)
	}
}

func TestProviderWhitelistNoArgvInImpl(t *testing.T) {
	root := filepath.Join("..", "..")
	if _, err := os.Stat(filepath.Join(root, "internal")); err != nil {
		// running from server/: internal/api is cwd
		root = ".."
	}
	cmd := exec.Command("grep", "-RInE", `pgrep[^\n]*-f|ps[^\n]*-f|args=|command=`,
		filepath.Join(root, "internal"),
		filepath.Join(root, "..", "tools", "nodeprobe"),
	)
	out, _ := cmd.CombinedOutput()
	var hits []string
	for _, ln := range strings.Split(string(out), "\n") {
		if ln == "" {
			continue
		}
		if strings.Contains(ln, "pane_current_command") {
			continue
		}
		if strings.Contains(ln, "probe-wl") {
			continue
		}
		if strings.Contains(ln, "_test.go") {
			continue
		}
		// Rust 侧写了同一条 argv 禁令，它的断言里必然引用这些禁词
		// （assert!(!prod.contains("args="))）。断言引用禁词，按定义不是
		// 「调用 ps 取 argv」——不跳过的话两份禁令会互相举报。
		if strings.Contains(ln, "assert!") {
			continue
		}
		if strings.Contains(ln, "de-facto") {
			continue
		}
		hits = append(hits, ln)
	}
	if len(hits) > 0 {
		t.Fatalf("argv read in impl:\n%s", strings.Join(hits, "\n"))
	}
}

func TestProviderWhitelistSharedLayerHasNoCLILiterals(t *testing.T) {
	for _, name := range []string{"detect.go", "proctree.go", "level2.go", "whitelist.go"} {
		data, err := os.ReadFile(name)
		if err != nil {
			t.Fatal(err)
		}
		lower := strings.ToLower(string(data))
		for _, lit := range []string{"claude", "codex", "copilot", "grok", "cursor"} {
			if strings.Contains(lower, lit) {
				t.Fatalf("shared layer %s contains CLI literal %q", name, lit)
			}
		}
	}
}

func TestProviderWhitelistLiveFiveAndBare(t *testing.T) {
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not in PATH")
	}
	dir := "/tmp/wl-dev-server"
	sock := dir + "/sock"
	bin := dir + "/bin"
	_ = os.RemoveAll(dir)
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cmd := exec.Command("tmux", "-S", sock, "kill-server")
		cmd.Env = isolatedTmuxEnv()
		_ = cmd.Run()
		_ = os.RemoveAll(dir)
	})
	for _, name := range []string{"claude", "codex", "copilot", "grok", "cursor-agent"} {
		p := filepath.Join(bin, name)
		if err := os.WriteFile(p, []byte("#!/bin/sh\nexec sleep 600\n"), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	newSess := exec.Command("tmux", "-S", sock, "new-session", "-d", "-s", "wlfix", "-n", "w-claude",
		"exec -a "+filepath.Join(bin, "claude")+" sleep 600")
	newSess.Env = isolatedTmuxEnv()
	if out, err := newSess.CombinedOutput(); err != nil {
		t.Fatalf("new-session: %v %s", err, out)
	}
	list := exec.Command("tmux", "-S", sock, "list-sessions", "-F", "#{session_name}")
	list.Env = isolatedTmuxEnv()
	gotSess, err := list.CombinedOutput()
	if err != nil || !strings.Contains(string(gotSess), "wlfix") {
		t.Fatalf("自检失败：会话不在隔离 socket（got=%q err=%v）", gotSess, err)
	}
	wins := []struct{ name, comm string }{
		{"w-codex", "codex"},
		{"w-copilot", "copilot"},
		{"w-grok", "grok"},
		{"w-cursor", "cursor-agent"},
	}
	for _, w := range wins {
		cmd := exec.Command("tmux", "-S", sock, "new-window", "-t", "wlfix", "-n", w.name,
			"exec -a "+filepath.Join(bin, w.comm)+" sleep 600")
		cmd.Env = isolatedTmuxEnv()
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("%s: %v %s", w.name, err, out)
		}
	}
	bare := exec.Command("tmux", "-S", sock, "new-window", "-t", "wlfix", "-n", "w-bare",
		`exec bash --norc --noprofile -c "exec -a bash sleep 600"`)
	bare.Env = isolatedTmuxEnv()
	if out, err := bare.CombinedOutput(); err != nil {
		t.Fatalf("w-bare: %v %s", err, out)
	}
	time.Sleep(200 * time.Millisecond)

	e := startWS(t, Options{
		Token:               "test-token",
		DiscoverySocketDirs: []string{dir},
		ListInterval:        time.Hour,
		Level2Interval:      40 * time.Millisecond,
		Level2Heartbeat:     time.Hour,
	})
	e.auth()

	cwd := paneCWD(t, sock)
	e.sendFrame(&protocol.Level2Subscribe{Workspace: cwd})
	frame := waitLevel2Frame(t, e, 8*time.Second)
	names := map[string]string{}
	for _, s := range frame.Sessions {
		names[s.Name] = s.Provider
	}
	want := map[string]string{
		"w-claude":  "claude_code",
		"w-codex":   "codex",
		"w-copilot": "copilot",
		"w-grok":    "grok",
		"w-cursor":  "cursor",
	}
	for n, p := range want {
		if names[n] != p {
			t.Errorf("%s provider=%q want=%q (sessions=%v)", n, names[n], p, names)
		}
	}
	if _, ok := names["w-bare"]; ok {
		t.Fatal("w-bare must not appear")
	}
	if len(frame.Sessions) != 5 {
		t.Fatalf("sessions=%d want 5 (got %v)", len(frame.Sessions), names)
	}
}

func paneCWD(t *testing.T, sock string) string {
	t.Helper()
	cmd := exec.Command("tmux", "-S", sock, "list-panes", "-a", "-F", "#{pane_current_path}")
	cmd.Env = isolatedTmuxEnv()
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("list-panes: %v %s", err, out)
	}
	for _, ln := range strings.Split(string(out), "\n") {
		ln = strings.TrimSpace(ln)
		if ln != "" {
			return ln
		}
	}
	t.Fatal("no pane cwd")
	return ""
}
