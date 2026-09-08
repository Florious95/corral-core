package api

// host_pane_geometry_red_test.go — 主机 tmux pane 几何记账红测（修复后转绿验证门）。
//
// 契约（leader 2026-08-13 裁定，用户裁定「改 pane 是必须的，不是缺陷」）：
//   真正缺陷 = 「重复进入会话时算出的几何 ≠ 第一次进入时算出的几何」。
//   修法三手段（均契约级）：
//     1. 原始几何是 pane 级单例——首个订阅者记录、最后一个退订者恢复，中间订阅者不记不改基线；
//     2. teardown / closeSubscriptions / relay 退出 与显式 unsubscribe 走同一条恢复路径；
//     3. 客户端 dispose 触发恢复，不能只 unsubscribe。
//
// 红测断言的是**几何数字相等关系**（tmux pane 实际宽高），不是函数调用。
// 守卫 TestFirstEntryResizesPane 必须恒绿（首次进会话仍正常 resize pane，防「永不 resize」糊弄）。
// 注：曾有一条 TestReentryGeometryReproduced（进→断连→再进，断言第二次==第一次），因单客户端
//   两次请求同尺寸、停在变形尺寸恰好满足断言，是天然假绿，无法从 pane 数字区分「真恢复」与
//   「停在变形」——已按 leader 指示删除，只保留能真判别的两条 + 守卫。

import (
	"bytes"
	"context"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// paneSize 读 tmux pane 当前实际尺寸（fresh read），返回 "WxH"。
func paneSize(te *tmuxEnv) string {
	te.t.Helper()
	out, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{pane_width}x#{pane_height}")
	if err != nil {
		te.t.Fatalf("read pane size: %v", err)
	}
	return strings.TrimSpace(out)
}

// waitPaneSize polls until the pane reaches want (async teardown/release are
// not synchronous with the client frame that triggered them), failing after a
// short deadline. Returns the last observed size on timeout.
func waitPaneSize(te *tmuxEnv, want string) string {
	te.t.Helper()
	for i := 0; i < 40; i++ {
		got := paneSize(te)
		if got == want {
			return got
		}
		time.Sleep(50 * time.Millisecond)
	}
	return paneSize(te)
}

// subscribeAndDrain 订阅一个会话并消费首帧 snapshot（用主连接的 tmuxEnv）。
func subscribeAndDrain(te *tmuxEnv, rows, cols uint16) {
	te.t.Helper()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: rows, Cols: cols})
	readBinary(te.wsEnv, te.ref())
}

// readBinary reads a bounded subscribe snapshot and pins its kind/ref so a
// stray delta cannot make a lifecycle precondition pass.
func readBinary(e *wsEnv, ref string) protocol.BinaryPayload {
	e.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	typ, data, err := e.conn.Read(ctx)
	if err != nil {
		e.t.Fatalf("read binary: %v", err)
	}
	if typ != websocket.MessageBinary {
		e.t.Fatalf("expected binary message, got %v", typ)
	}
	payload, err := protocol.DecodeBinary(data)
	if err != nil {
		e.t.Fatalf("decode binary %q: %v", data, err)
	}
	if payload.Kind != protocol.KindSnapshot {
		e.t.Fatalf("expected snapshot kind, got %d", payload.Kind)
	}
	if payload.Ref != ref {
		e.t.Fatalf("snapshot ref = %q, want %q", payload.Ref, ref)
	}
	return payload
}

func waitMirrorAndInputAck(e *wsEnv, want string, reqID uint32) protocol.InputAck {
	e.t.Helper()
	// Reuse the existing bounded reader/ack oracle while adapting the
	// second-client wsEnv to the tmuxEnv receiver it was written for.
	return (&tmuxEnv{t: e.t, wsEnv: e}).waitForMirrorAndInputAck(want, reqID)
}

func waitInputFailureAck(t *testing.T, e *wsEnv, reqID uint32) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("read input failure ack: %v", err)
		}
		if typ == websocket.MessageBinary {
			continue
		}
		frame, err := protocol.UnmarshalFrame(data)
		if err != nil {
			t.Fatalf("decode input failure ack: %v", err)
		}
		if ack, ok := frame.(protocol.InputAck); ok && ack.ReqID == reqID {
			if ack.OK {
				t.Fatalf("input req_id=%d unexpectedly succeeded after unsubscribe", reqID)
			}
			return
		}
	}
	t.Fatalf("input failure ack timeout req_id=%d", reqID)
}

// waitResizeSnapshot waits for the snapshot produced by one real geometry
// change and checks its ref, live content, and cursor re-anchor. Binary deltas
// and control frames may interleave on either subscriber's connection.
func waitResizeSnapshot(t *testing.T, te *tmuxEnv, e *wsEnv, marker string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("read resize snapshot: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode resize binary: %v", err)
		}
		if payload.Kind != protocol.KindSnapshot {
			continue
		}
		if payload.Ref != te.ref() {
			t.Fatalf("resize snapshot ref = %q, want %q", payload.Ref, te.ref())
		}
		if marker != "" && !bytes.Contains(payload.Data, []byte(marker)) {
			t.Fatalf("resize snapshot misses live marker %q: %q", marker, payload.Data)
		}
		assertSnapshotCursorSuffix(t, te, payload.Data)
		return
	}
	t.Fatalf("resize snapshot timeout for ref %q", te.ref())
}

// secondClient 复用同一个 Server 建第二个客户端（同一 discovery model 指向同一 pane）。
// 不 Cleanup srv（主 wsEnv 负责）；只关本连接。
func secondClient(t *testing.T, srv *Server) *wsEnv {
	t.Helper()
	hsrv := httptest.NewServer(srv.Handler())
	t.Cleanup(hsrv.Close)
	url := "ws" + strings.TrimPrefix(hsrv.URL, "http") + "/ws"
	conn, _, err := websocket.Dial(context.Background(), url, nil)
	if err != nil {
		t.Fatalf("dial second ws: %v", err)
	}
	t.Cleanup(func() { _ = conn.CloseNow() })
	e := &wsEnv{t: t, srv: srv, hsrv: hsrv, conn: conn}
	e.auth()
	return e
}

// --- 守卫：首次进会话必须正常 resize 主机 pane（用户明确要求改 pane，测死） ---

// TestFirstEntryResizesPane 守卫：手机订阅 108x96，pane 必须从 80x24 变成 108x96。
// 当前代码：subscribe → Resize(108,96) → pane=108x96。**应当绿**。
func TestFirstEntryResizesPane(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	if got, want := paneSize(te), "80x24"; got != want {
		t.Fatalf("precondition: pane should start 80x24, got %s", got)
	}
	subscribeAndDrain(te, 96, 108)
	if got, want := paneSize(te), "108x96"; got != want {
		t.Fatalf("首次进会话必须 resize pane：期望 108x96，实际 %s", got)
	}
	markPerf17Stage(t, "done")
	finishPerf17Error(t, te, 1711)
}

// --- 核心红测 1：异常断连必须恢复 pane 几何 ---

// TestAbnormalTeardownRestoresPaneGeometry 异常断连（conn 关闭→teardown）后 pane 必须恢复到订阅前 80x24。
// 当前代码：teardown（ws_conn.go:187）只 cancel+detach，不调 restoreSize → pane 停 108x96。**应当红**。
func TestAbnormalTeardownRestoresPaneGeometry(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	subscribeAndDrain(te, 96, 108)
	if got, want := paneSize(te), "108x96"; got != want {
		t.Fatalf("precondition: pane should be 108x96 after subscribe, got %s", got)
	}

	// 异常断连：直接关连接（服务端 readLoop 退出 → teardown()）。
	_ = te.wsEnv.conn.CloseNow()

	// teardown 完成后 pane 必须恢复到订阅前 80x24（teardown 异步，轮询等待）。
	got := waitPaneSize(te, "80x24")
	if got != "80x24" {
		t.Fatalf("异常断连后 pane 必须恢复到订阅前 80x24，实际 %s（当前代码红：teardown 不调 restoreSize）", got)
	}
}

// --- 核心红测 3：第二个订阅者不得改写 pane 级原始基线 ---

// TestSecondSubscriberDoesNotRebase 两个客户端先后订阅同一 pane。
// 客户端 A（108x96）→ 客户端 B（60x40）→ A 断连 → B 正常退出。
// 契约：pane 级原始几何是单例（首个订阅者记 80x24），B 不得改写它；最后一个退订者恢复 80x24。
// 当前代码：B 的 orig = 当前 pane 尺寸（A 改后的 108x96）；A 断连不恢复；B 退出恢复成 108x96 ≠ 80x24。**应当红**。
func TestSecondSubscriberDoesNotRebase(t *testing.T) {
	te := startTmuxEnv(t, "cat")

	// 客户端 A：订阅 108x96。
	a := te.wsEnv
	a.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 96, Cols: 108})
	readBinary(a, te.ref())

	// 客户端 B：订阅 60x40（同 pane，不同尺寸）。
	b := secondClient(t, a.srv)
	b.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 40, Cols: 60})
	readBinary(b, te.ref())

	// A 异常断连（teardown 不恢复，当前代码）。
	_ = a.conn.CloseNow()

	// B 正常退出（unsubscribe → subscribeCancel → restoreSize）。
	b.sendFrame(&protocol.Unsubscribe{Ref: te.ref()})

	// 契约：所有订阅者都退出后，pane 必须恢复到 pane 级原始基线 80x24（不是 A 的 108x96）。
	// A 的 teardown 与 B 的 unsubscribe 都异步，轮询等待最终几何。
	got := waitPaneSize(te, "80x24")
	if got != "80x24" {
		t.Fatalf("全部退订后 pane 必须恢复到 pane 级原始基线 80x24，实际 %s（当前代码红：B 把 A 改后的 108x96 当基线）", got)
	}
}

// TestMultipleSubscribersAlternateResizeRestoresOriginalPaneSize exercises
// both last-subscriber exit orders after alternating real resizes. Each fresh
// snapshot must retain the pane ref, live marker, and cursor anchor; only the
// final release may restore the independently recorded 80x24 baseline.
func TestMultipleSubscribersAlternateResizeRestoresOriginalPaneSize(t *testing.T) {
	for _, tc := range []struct {
		name   string
		firstA bool
	}{
		{name: "A-exits-first", firstA: true},
		{name: "B-exits-first", firstA: false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			te := startTmuxEnv(t, "cat")
			original := paneSize(te)
			if original != "80x24" {
				t.Fatalf("independent original pane size = %s, want 80x24", original)
			}
			a := te.wsEnv
			a.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 96, Cols: 108})
			first := readBinary(a, te.ref())
			if first.Ref != te.ref() {
				t.Fatalf("first snapshot ref = %q, want %q", first.Ref, te.ref())
			}
			assertSnapshotCursorSuffix(t, te, first.Data)
			if got := paneSize(te); got != "108x96" {
				t.Fatalf("A subscribe pane size = %s, want 108x96", got)
			}

			const marker = "PERF17_LIFECYCLE_MARK"
			a.sendFrame(&protocol.Input{ReqID: 1800, Ref: te.ref(), Text: marker})
			te.waitForMirror(marker)

			a.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 40, Cols: 100})
			waitResizeSnapshot(t, te, a, marker)
			if got := paneSize(te); got != "100x40" {
				t.Fatalf("A resize pane size = %s, want 100x40", got)
			}

			b := secondClient(t, a.srv)
			b.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 30, Cols: 70})
			second := readBinary(b, te.ref())
			if second.Ref != te.ref() || !bytes.Contains(second.Data, []byte(marker)) {
				t.Fatalf("B snapshot ref/content mismatch: ref=%q data=%q", second.Ref, second.Data)
			}
			assertSnapshotCursorSuffix(t, te, second.Data)
			if got := paneSize(te); got != "70x30" {
				t.Fatalf("B subscribe pane size = %s, want 70x30", got)
			}

			b.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 20, Cols: 60})
			waitResizeSnapshot(t, te, b, marker)
			if got := paneSize(te); got != "60x20" {
				t.Fatalf("B resize pane size = %s, want 60x20", got)
			}

			a.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 35, Cols: 90})
			waitResizeSnapshot(t, te, a, marker)
			if got := paneSize(te); got != "90x35" {
				t.Fatalf("A second resize pane size = %s, want 90x35", got)
			}

			if tc.firstA {
				_ = a.conn.CloseNow()
				b.sendFrame(&protocol.Input{ReqID: 1801, Ref: te.ref(), Text: "PERF17_A_FIRST_B_LIVE"})
				if ack := waitMirrorAndInputAck(b, "PERF17_A_FIRST_B_LIVE", 1801); !ack.OK {
					t.Fatalf("B input after A exit failed: %s", ack.Reason)
				}
				b.sendFrame(&protocol.Unsubscribe{Ref: te.ref()})
				b.sendFrame(&protocol.Input{ReqID: 1802, Ref: te.ref(), Text: "PERF17_B_EXIT_BARRIER"})
				waitInputFailureAck(t, b, 1802)
			} else {
				b.sendFrame(&protocol.Unsubscribe{Ref: te.ref()})
				b.sendFrame(&protocol.Input{ReqID: 1802, Ref: te.ref(), Text: "PERF17_B_EXIT_BARRIER"})
				waitInputFailureAck(t, b, 1802)
				_ = a.conn.CloseNow()
			}
			if got := waitPaneSize(te, original); got != original {
				t.Fatalf("last subscriber exit did not restore original %s: %s", original, got)
			}
		})
	}
}
