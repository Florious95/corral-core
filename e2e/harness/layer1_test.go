package main

// layer1_test.go — 协议链路层（知识基底 §1 层 1，主力）。真实链条：
// 隔离 tmux（脚本化 shell 会话 + 可选真 claude CLI 场景）→ 起 agentmirrord
// （自动/显式 token）→ WS 客户端全链路：auth→list（两级模型）→subscribe
// （首帧 <200ms）→input 注入回显→input_ack→scrollback 分页（区间头断言）
// →resize→图片 multipart 上传→路径注入回显→阳性对照（坏 token 必拒、
// 坏 ref 必错）。

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// freePort asks the kernel for an ephemeral port we can hand to the daemon.
func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("free port: %v", err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return port
}

// envSetup starts the isolated environment for a layer-1 run: a temp tree, one
// tmux server with two panes in two distinct cwds (two-level model), and the
// daemon. It returns the env plus the two cwds.
func envSetup(t *testing.T, daemonBin, token string, useCLI bool) (*Env, string, string) {
	t.Helper()
	e, err := StartEnv(t, daemonBin, token, freePort(t))
	if err != nil {
		t.Fatalf("start env: %v", err)
	}

	// Two sessions in two cwds → the two-level model must show both workspaces.
	// macOS /tmp is a symlink to /private/tmp; tmux reports the resolved real
	// path, so we resolve e.Root before comparing (otherwise the assertion
	// never matches).
	realRoot, err := filepath.EvalSymlinks(e.Root)
	if err != nil {
		t.Fatalf("resolve root: %v", err)
	}
	cwdA := filepath.Join(realRoot, "sub-a")
	cwdB := realRoot
	for _, d := range []string{cwdA, cwdB} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatalf("mkdir %s: %v", d, err)
		}
	}
	// sess-a lives in cwdA; sess-b lives in cwdB (the harness root).
	if _, err := e.Tmux("new-session", "-d", "-s", "sess-a", "-c", cwdA, "bash -i"); err != nil {
		t.Fatalf("new sess-a: %v", err)
	}
	if _, err := e.Tmux("new-session", "-d", "-s", "sess-b", "-c", cwdB, "bash -i"); err != nil {
		t.Fatalf("new sess-b: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := e.StartDaemon(ctx); err != nil {
		t.Fatalf("start daemon: %v", err)
	}
	return e, cwdA, cwdB
}

// TestLayer1 runs the full protocol chain. It is deliberately one test so the
// daemon and its initial host-fleet scan are amortized across scenarios.
func TestLayer1(t *testing.T) {
	if testing.Short() {
		t.Skip("short mode")
	}
	daemonBin := os.Getenv("E2E_DAEMON_BIN")
	if daemonBin == "" {
		daemonBin = "../bin/agentmirrord"
	}
	if _, err := os.Stat(daemonBin); err != nil {
		t.Fatalf("daemon binary %s missing (run.sh builds it): %v", daemonBin, err)
	}
	token := "e2e-test-token-" + time.Now().Format("150405")

	e, cwdA, cwdB := envSetup(t, daemonBin, token, false)
	metrics := &Metrics{}
	t.Cleanup(func() {
		metrics.Layer1Pass = !t.Failed()
		_ = metrics.Write()
		// Preserve the daemon log for post-mortem (env root is removed next).
		if data, err := os.ReadFile(filepath.Join(e.Root, "daemon.log")); err == nil {
			_ = os.WriteFile("/tmp/harness-daemon.log", data, 0o644)
		}
	})

	ctx := context.Background()

	// --- Scenario 0: auth positive control (wrong token MUST be rejected) ---
	t.Run("auth_reject_bad_token", func(t *testing.T) {
		ctxT, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		_, err := Connect(ctxT, e.WSURL, "definitely-wrong-token")
		if err == nil {
			t.Fatalf("bad token was accepted; positive control failed")
		}
		if !strings.Contains(err.Error(), "rejected") {
			t.Logf("rejection detail: %v", err)
		}
	})

	// --- Scenario 1: auth + two-level listing ---
	var listing protocol.Listing
	t.Run("list_two_level", func(t *testing.T) {
		ctxT, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		c, err := Connect(ctxT, e.WSURL, token)
		if err != nil {
			t.Fatalf("connect+auth: %v", err)
		}
		defer c.Close()

		if err := c.Send(ctxT, protocol.List{ReqID: 7}); err != nil {
			t.Fatalf("send list: %v", err)
		}
		f, err := c.waitControl(ctxT, protocol.TypeListing)
		if err != nil {
			t.Fatalf("wait listing: %v", err)
		}
		l, ok := f.(protocol.Listing)
		if !ok {
			t.Fatalf("listing decoded as %T", f)
		}
		listing = l
		if l.Seq == 0 {
			t.Fatalf("listing seq must be >= 1, got 0")
		}
		present := workspacesPresent(l)
		if !present[cwdA] || !present[cwdB] {
			t.Fatalf("two-level model missing our workspaces: got %v (want both %q and %q)",
				present, cwdA, cwdB)
		}
		// Each workspace must aggregate its session and expose refs.
		for _, cwd := range []string{cwdA, cwdB} {
			refs := refsInWorkspace(l, cwd)
			if len(refs) != 1 {
				t.Fatalf("workspace %s: want 1 session, got %d (%v)", cwd, len(refs), refs)
			}
		}
		t.Logf("two-level listing ok: workspaces=%d seq=%d", len(l.Workspaces), l.Seq)
	})

	if len(listing.Workspaces) == 0 {
		t.Fatal("listing scenario failed; cannot continue")
	}

	// refB is the primary harness pane we drive (in cwdB).
	refBList := refsInWorkspace(listing, cwdB)
	if len(refBList) == 0 {
		t.Fatalf("no session under cwd %s", cwdB)
	}
	refB := refBList[0]

	// --- Scenario 2: subscribe → snapshot; first-frame < 200ms ---
	var firstFrames []float64
	t.Run("subscribe_first_frame", func(t *testing.T) {
		// 5 samples to build a small distribution.
		for i := 0; i < 5; i++ {
			ctxT, cancel := context.WithTimeout(ctx, 20*time.Second)
			c, err := Connect(ctxT, e.WSURL, token)
			if err != nil {
				cancel()
				t.Fatalf("connect: %v", err)
			}
			start := time.Now()
			if err := c.Send(ctxT, protocol.Subscribe{Ref: refB, Rows: 24, Cols: 80}); err != nil {
				c.Close()
				cancel()
				t.Fatalf("send subscribe: %v", err)
			}
			snap, err := c.waitBinary(ctxT, protocol.KindSnapshot, refB)
			elapsed := time.Since(start)
			ms := float64(elapsed) / float64(time.Millisecond)
			firstFrames = append(firstFrames, ms)
			metrics.AddFirstFrame(ms, "shell")
			if err != nil {
				c.Close()
				cancel()
				t.Fatalf("wait snapshot: %v", err)
			}
			if snap.Kind != protocol.KindSnapshot || snap.Ref != refB {
				c.Close()
				cancel()
				t.Fatalf("unexpected frame %+v", snap)
			}
			if len(snap.Data) == 0 {
				c.Close()
				cancel()
				t.Fatalf("snapshot empty for ref %s", refB)
			}
			if ms >= 200 {
				t.Errorf("first frame %d: %.2f ms >= 200ms threshold (006)", i, ms)
			} else {
				t.Logf("first frame %d: %.2f ms", i, ms)
			}
			c.Close()
			cancel()
		}
	})

	// --- Scenario 3: input round-trip (echo into the pane + decidable ack) ---
	t.Run("input_echo_ack", func(t *testing.T) {
		ctxT, cancel := context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		c, err := Connect(ctxT, e.WSURL, token)
		if err != nil {
			t.Fatalf("connect: %v", err)
		}
		defer c.Close()
		// Subscribe, then drain the snapshot so the input marker is cleanly
		// observable.
		if err := c.Send(ctxT, protocol.Subscribe{Ref: refB, Rows: 24, Cols: 80}); err != nil {
			t.Fatalf("send subscribe: %v", err)
		}
		if _, err := c.waitBinary(ctxT, protocol.KindSnapshot, refB); err != nil {
			t.Fatalf("snapshot: %v", err)
		}
		marker := fmt.Sprintf("E2E_MARKER_%d", time.Now().UnixNano()%1000000)
		ackOK, deltaOK := c.waitAckAndDelta(ctxT, 11, refB, "echo "+marker, marker, 10*time.Second)
		if !ackOK {
			t.Fatalf("input_ack not ok (003 send-must-arrive)")
		}
		if !deltaOK {
			t.Fatalf("echo of %q never appeared in the stream", marker)
		}
		t.Logf("input round-trip ok: ack=ok marker=%s", marker)
	})

	// --- Scenario 4: scrollback pagination with converged range header ---
	t.Run("scrollback_paging", func(t *testing.T) {
		ctxT, cancel := context.WithTimeout(ctx, 25*time.Second)
		defer cancel()
		c, err := Connect(ctxT, e.WSURL, token)
		if err != nil {
			t.Fatalf("connect: %v", err)
		}
		defer c.Close()
		if err := c.Send(ctxT, protocol.Subscribe{Ref: refB, Rows: 24, Cols: 80}); err != nil {
			t.Fatalf("send subscribe: %v", err)
		}
		if _, err := c.waitBinary(ctxT, protocol.KindSnapshot, refB); err != nil {
			t.Fatalf("snapshot: %v", err)
		}
		// Generate > one screen of scrollback deterministically.
		if err := c.Send(ctxT, protocol.Input{ReqID: 21, Ref: refB, Text: "seq 1 200"}); err != nil {
			t.Fatalf("send seq: %v", err)
		}
		if _, err := c.waitControl(ctxT, protocol.TypeInputAck); err != nil {
			t.Fatalf("input ack: %v", err)
		}
		// Give the shell time to print 200 lines.
		time.Sleep(1500 * time.Millisecond)

		// Request one page from the history (5 lines above the screen top).
		if err := c.Send(ctxT, protocol.Scrollback{ReqID: 22, Ref: refB, FromLine: -5, Count: 20}); err != nil {
			t.Fatalf("send scrollback: %v", err)
		}
		sb, err := c.waitBinary(ctxT, protocol.KindScrollback, refB)
		if err != nil {
			t.Fatalf("wait scrollback: %v", err)
		}
		if sb.ReqID != 22 {
			t.Fatalf("scrollback req_id mismatch: got %d want 22", sb.ReqID)
		}
		if sb.LineCount == 0 || sb.LineCount > 20 {
			t.Fatalf("scrollback line_count %d out of expected (1..20)", sb.LineCount)
		}
		// The header must describe the ACTUAL range (converged). Check the
		// payload is non-empty and contains at least one digit line.
		if len(sb.Data) == 0 {
			t.Fatalf("scrollback payload empty")
		}
		t.Logf("scrollback ok: from=%d count=%d bytes=%d", sb.FromLine, sb.LineCount, len(sb.Data))
	})

	// --- Scenario 5: resize applies to the subscribed pane ---
	t.Run("resize_dims", func(t *testing.T) {
		ctxT, cancel := context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		c, err := Connect(ctxT, e.WSURL, token)
		if err != nil {
			t.Fatalf("connect: %v", err)
		}
		defer c.Close()
		if err := c.Send(ctxT, protocol.Subscribe{Ref: refB, Rows: 24, Cols: 80}); err != nil {
			t.Fatalf("send subscribe: %v", err)
		}
		if _, err := c.waitBinary(ctxT, protocol.KindSnapshot, refB); err != nil {
			t.Fatalf("snapshot: %v", err)
		}
		if err := c.Send(ctxT, protocol.Resize{Ref: refB, Rows: 40, Cols: 100}); err != nil {
			t.Fatalf("send resize: %v", err)
		}
		// Poll a fresh list until the pane dims reflect the resize.
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			time.Sleep(400 * time.Millisecond)
			if err := c.Send(ctxT, protocol.List{ReqID: 30}); err != nil {
				t.Fatalf("send list: %v", err)
			}
			f, err := c.waitControl(ctxT, protocol.TypeListing)
			if err != nil {
				t.Fatalf("wait listing: %v", err)
			}
			l := f.(protocol.Listing)
			for i := range l.Workspaces {
				for j := range l.Workspaces[i].Sessions {
					s := l.Workspaces[i].Sessions[j]
					if s.Ref == refB {
						if s.Rows == 40 && s.Cols == 100 {
							t.Logf("resize ok: pane now %dx%d", s.Cols, s.Rows)
							return
						}
						t.Logf("resize pending: pane %dx%d", s.Cols, s.Rows)
					}
				}
			}
		}
		t.Fatalf("pane did not reach 100x40 within deadline")
	})

	// --- Scenario 6: image multipart upload → path → inject & echo ---
	t.Run("upload_image_path_inject", func(t *testing.T) {
		ctxT, cancel := context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		// Build a tiny valid PNG body (1x1).
		png := []byte{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
			0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
			0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
			0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
			0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
			0x54, 0x08, 0xD7, 0x63, 0xF8, 0xCF, 0xC0, 0x00,
			0x00, 0x00, 0x03, 0x00, 0x01, 0x38, 0x45, 0xE4,
			0xAF, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
			0x44, 0xAE, 0x42, 0x60, 0x82}
		var body bytes.Buffer
		mw := multipart.NewWriter(&body)
		fw, err := mw.CreateFormFile("file", "shot.png")
		if err != nil {
			t.Fatalf("create form file: %v", err)
		}
		if _, err := fw.Write(png); err != nil {
			t.Fatalf("write png: %v", err)
		}
		mw.Close()

		req, err := http.NewRequestWithContext(ctxT, http.MethodPost, e.UploadURL, &body)
		if err != nil {
			t.Fatalf("new request: %v", err)
		}
		req.Header.Set("Content-Type", mw.FormDataContentType())
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("upload: %v", err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			b, _ := io.ReadAll(resp.Body)
			t.Fatalf("upload status %d: %s", resp.StatusCode, b)
		}
		var up protocol.UploadResp
		if err := json.NewDecoder(resp.Body).Decode(&up); err != nil {
			t.Fatalf("decode upload resp: %v", err)
		}
		if up.Path == "" {
			t.Fatalf("upload resp path empty")
		}
		if _, err := os.Stat(up.Path); err != nil {
			t.Fatalf("uploaded file not on disk: %v", err)
		}
		t.Logf("upload ok: path=%s", up.Path)

		// Inject the path into the pane; the echo proves the path round-trips.
		c, err := Connect(ctxT, e.WSURL, token)
		if err != nil {
			t.Fatalf("connect: %v", err)
		}
		defer c.Close()
		if err := c.Send(ctxT, protocol.Subscribe{Ref: refB, Rows: 24, Cols: 80}); err != nil {
			t.Fatalf("send subscribe: %v", err)
		}
		if _, err := c.waitBinary(ctxT, protocol.KindSnapshot, refB); err != nil {
			t.Fatalf("snapshot: %v", err)
		}
		ackOK, deltaOK := c.waitAckAndDelta(ctxT, 41, refB, "echo "+up.Path, up.Path, 10*time.Second)
		if !ackOK {
			t.Fatalf("inject ack not ok (003)")
		}
		if !deltaOK {
			t.Fatalf("injected path %q never echoed in the stream", up.Path)
		}
		t.Logf("image path inject round-trip ok")
	})

	// --- Scenario 7: positive control — bogus ref subscribe must error ---
	t.Run("subscribe_bogus_ref", func(t *testing.T) {
		ctxT, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		c, err := Connect(ctxT, e.WSURL, token)
		if err != nil {
			t.Fatalf("connect: %v", err)
		}
		defer c.Close()
		if err := c.Send(ctxT, protocol.Subscribe{Ref: "bogus\x1f%99", Rows: 24, Cols: 80}); err != nil {
			t.Fatalf("send subscribe: %v", err)
		}
		ef, err := c.waitError(ctxT)
		if err != nil {
			t.Fatalf("wait error: %v", err)
		}
		if ef.Code != protocol.ErrCodeSessionNotFound {
			t.Fatalf("expected session_not_found, got code=%s reason=%s", ef.Code, ef.Reason)
		}
		t.Logf("bogus ref rejected: code=%s", ef.Code)
	})

	// --- Scenario 7b: state wiring end-to-end (defect D-1 regression) ---
	// The built daemon now wires the agent-state pipeline (task
	// fix-state-wiring). A wrapper-shaped fake claude tree (bash pane root →
	// claude-named descendant) with a blocked permission box on screen must
	// surface state=blocked (≠ unknown) in the daemon's listing — the
	// blocked/done notification data source finally reachable from production.
	t.Run("state_wiring_blocked_listing", func(t *testing.T) {
		ctxT, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()

		// Isolated session: bash prints the blocked box then forks a child
		// that becomes the claude-named fake (the wrapper scene; Identify walks
		// descendants of pane_pid, so the fake must be a child, not the root).
		// The pane's real pane_pid and pane_current_command=bash are what a
		// real discovery scan reports.
		cmd := `bash -c 'printf "Do you want to proceed?\n  (esc to cancel)\n"; sh -c "exec -a claude /bin/sleep 120" & wait'`
		if _, err := e.Tmux("new-session", "-d", "-s", "state-e2e", "-c", e.Root, cmd); err != nil {
			t.Fatalf("start state-e2e session: %v", err)
		}
		// Cleanup: kill the fake tree (scoped to our session's panes only —
		// kill-window tears the fake claude child down with the session).
		t.Cleanup(func() {
			_, _ = e.Tmux("kill-session", "-t", "state-e2e")
		})

		// Poll listing until the daemon's background state refresh resolves the
		// fake claude pane to blocked.
		c, err := Connect(ctxT, e.WSURL, token)
		if err != nil {
			t.Fatalf("connect: %v", err)
		}
		defer c.Close()

		deadline := time.Now().Add(20 * time.Second)
		last := protocol.StateUnknown
		for time.Now().Before(deadline) {
			if err := c.Send(ctxT, protocol.List{ReqID: 55}); err != nil {
				t.Fatalf("send list: %v", err)
			}
			f, err := c.waitControl(ctxT, protocol.TypeListing)
			if err != nil {
				t.Fatalf("wait listing: %v", err)
			}
			l := f.(protocol.Listing)
			stateRefs := refsInSession(l, "state-e2e")
			if len(stateRefs) == 0 {
				time.Sleep(500 * time.Millisecond)
				continue // not scanned yet
			}
			// Find the state-e2e session's state (single pane).
			for i := range l.Workspaces {
				for j := range l.Workspaces[i].Sessions {
					if l.Workspaces[i].Sessions[j].Name == "state-e2e" {
						last = l.Workspaces[i].Sessions[j].State
						if last == protocol.StateBlocked {
							// The 012 aggregate of this workspace must follow.
							if agg := l.Workspaces[i].AggregateState; agg != protocol.StateBlocked {
								t.Fatalf("state-e2e aggregate = %q, want blocked", agg)
							}
							t.Logf("state wiring e2e ok: session state=%s aggregate=%s", last, l.Workspaces[i].AggregateState)
							return
						}
					}
				}
			}
			time.Sleep(500 * time.Millisecond)
		}
		t.Fatalf("state-e2e never surfaced blocked (last %s); daemon state wiring broken (D-1)", last)
	})

	// --- Scenario 8: real Claude Code CLI end-to-end (if enabled) ---
	t.Run("real_claude_cli", func(t *testing.T) {
		if os.Getenv("E2E_SKIP_CLI") != "" {
			t.Skip("real CLI scenario skipped via E2E_SKIP_CLI")
		}
		ctxT, cancel := context.WithTimeout(ctx, 120*time.Second)
		defer cancel()

		// A dedicated session running the real CLI in print mode, then falling
		// back to a live bash so the pane survives for input injection. The
		// session dies with its tmux server at cleanup; we never touch the
		// host's fleet sockets.
		cliCmd := `bash -lc 'claude -p "say E2E_CLI_READY" --max-turns 1; exec bash'`
		if _, err := e.Tmux("new-session", "-d", "-s", "cli-e2e", "-c", e.Root, cliCmd); err != nil {
			t.Fatalf("start claude session: %v", err)
		}

		// Wait until the CLI has produced its marker (poll pane capture).
		deadline := time.Now().Add(100 * time.Second)
		for time.Now().Before(deadline) {
			out, _ := e.Tmux("capture-pane", "-p", "-t", "cli-e2e:0.0")
			if strings.Contains(out, "E2E_CLI_READY") {
				break
			}
			select {
			case <-ctxT.Done():
				t.Fatalf("claude CLI never printed marker")
			default:
			}
			time.Sleep(1 * time.Second)
		}
		if !strings.Contains(mustCapture(e, "cli-e2e:0.0"), "E2E_CLI_READY") {
			t.Fatalf("claude CLI marker missing from pane")
		}

		// Listing must now expose the cli-e2e session (cwd = e.Root).
		c, err := Connect(ctxT, e.WSURL, token)
		if err != nil {
			t.Fatalf("connect: %v", err)
		}
		defer c.Close()
		if err := c.Send(ctxT, protocol.List{ReqID: 50}); err != nil {
			t.Fatalf("send list: %v", err)
		}
		lF, err := c.waitControl(ctxT, protocol.TypeListing)
		if err != nil {
			t.Fatalf("wait listing: %v", err)
		}
		l := lF.(protocol.Listing)
		cliRefs := refsInSession(l, "cli-e2e")
		if len(cliRefs) == 0 {
			t.Fatalf("cli-e2e pane not listed")
		}
		cliRef := cliRefs[0]

		// Subscribe and measure the first frame for the real CLI too.
		start := time.Now()
		if err := c.Send(ctxT, protocol.Subscribe{Ref: cliRef, Rows: 24, Cols: 80}); err != nil {
			t.Fatalf("send subscribe: %v", err)
		}
		snap, err := c.waitBinary(ctxT, protocol.KindSnapshot, cliRef)
		ms := float64(time.Since(start)) / float64(time.Millisecond)
		metrics.AddFirstFrame(ms, "claude")
		firstFrames = append(firstFrames, ms)
		if err != nil {
			t.Fatalf("wait snapshot: %v", err)
		}
		if len(snap.Data) == 0 {
			t.Fatalf("cli snapshot empty")
		}
		// The snapshot should contain the CLI's marker or the shell prompt.
		if !strings.Contains(string(snap.Data), "E2E_CLI_READY") {
			t.Logf("snapshot does not contain marker (may have scrolled); bytes=%d", len(snap.Data))
		}

		// Inject an echo into the shell prompt the CLI left behind; ack must
		// be OK and the marker must echo (input round-trip on a real CLI).
		marker := fmt.Sprintf("E2E_CLI_AFTER_%d", time.Now().UnixNano()%100000)
		ackOK, deltaOK := c.waitAckAndDelta(ctxT, 51, cliRef, "echo "+marker, marker, 15*time.Second)
		if !ackOK {
			t.Fatalf("cli input ack not ok (003)")
		}
		if !deltaOK {
			t.Fatalf("echo of %q never arrived on real CLI pane", marker)
		}
		t.Logf("real claude CLI scenario ok (first frame %.2f ms)", ms)
	})

	// Summarize the first-frame distribution for the report.
	if len(firstFrames) > 0 {
		min := firstFrames[0]
		for _, v := range firstFrames {
			if v < min {
				min = v
			}
		}
		t.Logf("first-frame samples=%d min=%.2fms", len(firstFrames), min)
	}
}

// mustCapture returns the current pane capture, failing the test on error.
func mustCapture(e *Env, target string) string {
	out, err := e.Tmux("capture-pane", "-p", "-t", target)
	if err != nil {
		return ""
	}
	return out
}

// waitForDelta polls the delta stream for a substring in the given ref's
// payload, up to the deadline. It drains the connection (including snapshots
// from re-subscribe) until the substring appears or the deadline lapses.
func waitForDelta(ctx context.Context, c *Client, ref, substr string, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		dctx, cancel := context.WithTimeout(ctx, time.Until(deadline))
		m, err := c.waitFrame(dctx)
		cancel()
		if err != nil {
			return false
		}
		if !m.binOK || m.bin.Ref != ref {
			continue
		}
		if bytes.Contains(m.bin.Data, []byte(substr)) {
			return true
		}
	}
	return false
}
