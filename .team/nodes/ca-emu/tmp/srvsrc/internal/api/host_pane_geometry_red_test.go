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
	readBinary(te.wsEnv)
}

// readBinary 读一条 binary 帧（snapshot/delta）。
func readBinary(e *wsEnv) {
	e.t.Helper()
	typ, data, err := e.conn.Read(context.Background())
	if err != nil {
		e.t.Fatalf("read binary: %v", err)
	}
	if typ != websocket.MessageBinary {
		e.t.Fatalf("expected binary message, got %v", typ)
	}
	if _, err := protocol.DecodeBinary(data); err != nil {
		e.t.Fatalf("decode binary %q: %v", data, err)
	}
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
	readBinary(a)

	// 客户端 B：订阅 60x40（同 pane，不同尺寸）。
	b := secondClient(t, a.srv)
	b.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 40, Cols: 60})
	readBinary(b)

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
