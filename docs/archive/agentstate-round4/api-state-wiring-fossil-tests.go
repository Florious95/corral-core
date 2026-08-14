// 归档（2026-08-15，058 补刀）：原 server/internal/api/state_wiring_test.go 中被否定的测试。
// 这些测试断言「静态字形/屏文本 → 某个状态」，正是 058 判定为错的那个做法；归档代码却留下
// 强制它存在的测试 = 腐败会自我复制（详见同目录 README.md 第 七 节）。逐字节拷贝自
// commit 7117f57cb 时的 server/internal/api/state_wiring_test.go，未改一字。
// 包级 helper（tmuxEnv / runTmuxCmd / scrubbedEnv / tmuxSeq / discardLogger / startWS /
// mutableDiscoverer / sessionRef）在 server/internal/api 其他测试文件与 git 历史中。
//
// 下面两个测试的「唯一仍然有效的部分」：PaneTitle 必须到达决策函数这个接线——新判据同样
// 需要它，但接线的验证不该绑定任何字形语义。这句留给 t.oracle 重写（probe 由 oracle 新写）。
package api

// ==== startWiredEnv / descendantPIDs：wrapper 树场景的 setup helper（随测试一起归档） ====
// startWiredEnv starts an isolated tmux whose pane runs a wrapper-shaped fake
// claude process tree (bash pane → claude-named descendant) and prints the
// blocked permission box on screen — the exact two signals the pipeline needs:
// the tree for identify, the screen text for the blocked rule. It returns the
// tmux env plus the real pane_pid resolved from the live pane.
func startWiredEnv(t *testing.T, paneCmd string) (*tmuxEnv, int) {
	t.Helper()
	dir, err := os.MkdirTemp("", "wsapi-state")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	sock := filepath.Join(dir, "sock")
	env := scrubbedEnv()

	name := fmt.Sprintf("wsstate%d", tmuxSeq)
	// The pane command is the wrapper shape: bash (pane_pid) forks a child
	// that execs a claude-named binary, so the process-tree descent in
	// Identify finds a claude descendant below a bash root (state-ident-wrapper
	// §5 real shape). The blocked marker is printed at startup so capture-pane
	// samples it.
	if out, err := runTmuxCmd(env, sock, "new-session", "-d", "-x", "80", "-y", "24", "-s", name, "-c", t.TempDir(), paneCmd); err != nil {
		t.Fatalf("new-session: %v\n%s", err, out)
	}
	t.Cleanup(func() {
		_, _ = runTmuxCmd(env, sock, "kill-server")
		_ = os.RemoveAll(dir)
	})

	// Resolve the bare pane id (the exact-existence-check form).
	out, err := runTmuxCmd(env, sock, "list-panes", "-t", name, "-F", "#{pane_id}|#{pane_pid}")
	if err != nil {
		t.Fatalf("resolve pane: %v\n%s", err, out)
	}
	parts := strings.SplitN(strings.TrimSpace(out), "|", 2)
	if len(parts) != 2 {
		t.Fatalf("unexpected list-panes output %q", out)
	}
	paneID, pid := parts[0], parts[1]
	panePID := 0
	if _, err := fmt.Sscanf(pid, "%d", &panePID); err != nil {
		t.Fatalf("parse pane_pid %q: %v", pid, err)
	}

	// Cleanup: the fake claude process tree must not outlive the test (process
	// hygiene). kill-server tears the session down (SIGHUP to pane processes);
	// best-effort kill of the pane tree too so a sleeping fake claude can never
	// leak. Scoped strictly to our own pane root's descendants — never a broad
	// pkill.
	t.Cleanup(func() {
		if panePID > 0 {
			for _, pid := range descendantPIDs(panePID) {
				_ = exec.Command("kill", "-9", fmt.Sprint(pid)).Run()
			}
		}
	})

	return &tmuxEnv{t: t, sock: sock, paneID: paneID, env: env}, panePID
}

// descendantPIDs returns the given pid and every process whose ancestor chain
// reaches it, from one bounded ps snapshot. Used only to reap a test's own
// fake process tree — never a broad match.
func descendantPIDs(root int) []int {
	out, err := exec.Command("ps", "-axo", "pid=,ppid=").Output()
	if err != nil {
		return []int{root}
	}
	children := map[int][]int{}
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 {
			continue
		}
		var pid, ppid int
		if _, err := fmt.Sscanf(fields[0], "%d", &pid); err != nil {
			continue
		}
		if _, err := fmt.Sscanf(fields[1], "%d", &ppid); err != nil {
			continue
		}
		children[ppid] = append(children[ppid], pid)
	}
	ids := []int{root}
	queue := []int{root}
	for len(queue) > 0 {
		p := queue[0]
		queue = queue[1:]
		for _, c := range children[p] {
			ids = append(ids, c)
			queue = append(queue, c)
		}
	}
	return ids
}

// ==== TestStateWiringWrapperProcessTreeBlocksListing：断言 wrapper 树 + 屏文本 → blocked（归档） ====
// TestStateWiringWrapperProcessTreeBlocksListing is the acceptance red test:
// with the production wiring, a wrapper fake-claude pane whose screen shows a
// blocked box must surface state=blocked (≠ unknown) in listing, and the 012
// aggregate of its workspace must be blocked — the blocked/done notification
// data source (requirement 003 standard four) finally reachable.
func TestStateWiringWrapperProcessTreeBlocksListing(t *testing.T) {
	// The pane command: bash (the pane root, reported as pane_current_command
	// "bash" — the wrapper scene) prints the blocked box, then forks a child
	// that becomes the claude-named fake. Identify only matches DESCENDANTS of
	// pane_pid (root argv is deliberately ignored), so the claude-named process
	// must be the child, never the pane itself. The box text keys the
	// claude-blocked-permission-box rule ("Do you want to proceed?" + "esc to
	// cancel"); the fake tree keys the wrapper Identify path.
	const paneCmd = `bash -c 'printf "Do you want to proceed?\n  (esc to cancel)\n"; sh -c "exec -a claude /bin/sleep 300" & wait'`

	te, panePID := startWiredEnv(t, paneCmd)
	if panePID == 0 {
		t.Fatal("isolated pane resolved no pane_pid; wrapper tree cannot be identified")
	}

	// Wire the REAL provider (real ps + real capture-pane) with a short TTL so
	// the first listing refresh lands within the test's patience.
	p := NewStateProvider(discardLogger())
	defer p.Close()
	p.ttl = 100 * time.Millisecond
	p.pruneAge = time.Minute

	// The discoverer points at this isolated pane only, with the wrapper
	// command (bash) and the real pane_pid — exactly what a real scan reports.
	model := &discovery.Model{
		Workspaces: []discovery.Workspace{
			{
				CWD: "/ws/wired",
				Panes: []discovery.Pane{
					{Socket: te.sock, Session: "wired", PaneID: te.paneID, CWD: "/ws/wired", Command: "bash", PanePID: panePID, Width: 80, Height: 24},
				},
			},
		},
	}
	md := &mutableDiscoverer{model: model}
	e := startWS(t, Options{Token: "test-token", Discoverer: md, StateProvider: p})
	e.auth()

	// Poll listing until the provider's background refresh lands. The first
	// listing after auth may carry unknown (cache seeded); the refresh then
	// resolves the fake claude tree and the blocked box. The listing loop also
	// pushes list_delta frames on the same connection, so each poll drains
	// frames until the requested Listing reply arrives.
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		e.sendFrame(&protocol.List{ReqID: 1})
		var l protocol.Listing
		var ok bool
		for {
			got := e.readControl()
			l, ok = got.(protocol.Listing)
			if ok {
				break // the List reply; list_delta frames are skipped below
			}
			// Not a listing (e.g. a list_delta the loop pushed): drain and
			// read the next frame. Bound the inner drain so a frame burst
			// cannot spin forever.
			if time.Now().After(deadline) {
				t.Fatal("drained non-listing frames until deadline")
			}
		}
		if len(l.Workspaces) != 1 {
			continue // not yet scanned
		}
		ws := l.Workspaces[0]
		if len(ws.Sessions) != 1 {
			t.Fatalf("workspace has %d sessions, want 1", len(ws.Sessions))
		}
		st := ws.Sessions[0].State
		t.Logf("state=%s aggregate=%s", st, ws.AggregateState)
		if st != protocol.StateBlocked {
			time.Sleep(200 * time.Millisecond)
			continue
		}
		// The pane resolved to blocked (≠ unknown) and the workspace aggregate
		// must follow (012: blocked is the highest-ranked member state).
		if ws.AggregateState != protocol.StateBlocked {
			t.Fatalf("aggregate = %q, want blocked (012 with a blocked member)", ws.AggregateState)
		}
		t.Logf("state wiring ok: session state=%s aggregate=%s", st, ws.AggregateState)
		return
	}
	t.Fatal("listing never surfaced the blocked state from the wired provider")
}

// ==== TestStateProviderTitleSignalDrivesState：断言静态 PaneTitle 字形 → working/idle（归档） ====
// TestStateProviderTitleSignalDrivesState pins the D-26 title wiring (task
// fix-state-detection): the pane title (OSC title) must reach the agentstate
// adapter, so a working spinner title yields working even when the screen text
// alone would read idle. This is the end-to-end gap the reverted finalizeState
// fix never covered — it tuned cache timing, not the detection input.
func TestStateProviderTitleSignalDrivesState(t *testing.T) {
	p := NewStateProvider(discardLogger())
	defer p.Close()
	p.ttl = time.Millisecond // refresh eagerly

	// sample seam returns an idle-looking screen; identify resolves the kind.
	p.sample = func(ctx context.Context, pn discovery.Pane) ([]byte, time.Duration, error) {
		// A screen that, without the title, reads idle: bare prompt + agents hint.
		return []byte("❯ \n⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents\n"), 0, nil
	}
	p.identify = func(ctx context.Context, in agentstate.IdentifyInput) agentstate.AgentKind {
		return agentstate.AgentKindClaude
	}

	// Working title (braille spinner): must resolve working.
	working := discovery.Pane{Socket: "/s", PaneID: "%0", Command: "bash", PaneTitle: "⠙ w-librarian", CWD: "/ws/x", Width: 80, Height: 24}
	_ = p.State(context.Background(), working)
	// Give the refresh time to land.
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		st := p.State(context.Background(), working)
		if st == protocol.StateWorking {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if got := p.State(context.Background(), working); got != protocol.StateWorking {
		t.Fatalf("working-title pane state = %q, want working (title signal)", got)
	}

	// Idle title (✳): must resolve idle despite the agents-hint screen.
	idle := discovery.Pane{Socket: "/s", PaneID: "%1", Command: "bash", PaneTitle: "✳ w-librarian", CWD: "/ws/x", Width: 80, Height: 24}
	_ = p.State(context.Background(), idle)
	deadline = time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		st := p.State(context.Background(), idle)
		if st == protocol.StateIdle {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if got := p.State(context.Background(), idle); got != protocol.StateIdle {
		t.Fatalf("idle-title pane state = %q, want idle (title signal)", got)
	}
}
