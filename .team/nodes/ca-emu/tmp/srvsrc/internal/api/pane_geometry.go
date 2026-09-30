package api

// pane_geometry.go — 主机 pane 原始几何的共享记账（fix-host-pane-geometry-accounting）。
//
// 契约（leader 2026-08-13 裁定，用户裁定「改 pane 是必须的，不是缺陷」）：
//   真正缺陷 = 「重复进入会话时算出的几何 ≠ 第一次进入时算出的几何」。
//   修法三手段（本文件实现契约 1 与契约 2 的记账半边）：
//     1. 原始几何是 pane 级单例——首个订阅者记录、最后一个退订者恢复，中间订阅者不记不改基线；
//     2. teardown / closeSubscriptions / relay 退出 与显式 unsubscribe 走同一条恢复路径
//        （本记账的 release 归零即恢复，谁调用都一样）。
//
// 设计：几何记账从 per-connection subscription.restoreSize 闭包提升为 Server 级
// pane 共享态。每个 pane ref 一个 [paneGeometry]，首个订阅者 acquire 时记原始几何，
// 之后的订阅者只增加计数不覆盖基线；最后一个 release 归零时恢复原始几何。这样
// 无论哪条退出路径（显式退订 / 断连 teardown / 服务端关闭 / relay 退出）只要 release
// 到零就触发恢复，天然满足「四路径统一恢复」，且多客户端不会把变形后的尺寸当新基线。

import (
	"context"
	"log/slog"
	"sync"

	"github.com/agentmirror/agentmirror/internal/bridge"
)

// paneGeometry is the shared original-geometry singleton for one pane ref.
// It lives on the Server (connection-independent), so two clients subscribed to
// the same pane share one baseline and only the last releaser restores it.
type paneGeometry struct {
	mu sync.Mutex

	origCols  int
	origRows  int
	origKnown bool // 首个订阅者已记录原始几何；之后不覆盖（契约 1）
	active    int  // 当前订阅者数（>=0；归零时恢复原始几何）
}

// geometryFor returns the pane-level geometry tracker for ref, creating it on
// first use. The tracker is owned by the Server and survives across connections.
func (s *Server) geometryFor(ref string) *paneGeometry {
	s.paneGeomsMu.Lock()
	defer s.paneGeomsMu.Unlock()
	g := s.paneGeoms[ref]
	if g == nil {
		g = &paneGeometry{}
		s.paneGeoms[ref] = g
	}
	return g
}

// acquire claims one subscription on the pane. On the very first subscriber it
// snapshots the pane's current (pre-phone) geometry as the baseline; subsequent
// subscribers never overwrite it (契约 1). Returns the baseline, or ok=false if
// the pane size could not be read (no baseline established; release will be a
// no-op rather than restoring a guessed size).
func (g *paneGeometry) acquire(ctx context.Context, br *bridge.Pane) (cols, rows int, ok bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.active == 0 && !g.origKnown {
		// 首个订阅者：记 pane 当前实际几何为基线（订阅前 = pre-phone）。
		c, r, err := br.Size(ctx)
		if err != nil {
			// Size 失败：非致命，镜像继续；但基线未知时 release 不恢复（避免恢复错尺寸）。
			g.active++
			return 0, 0, false
		}
		g.origCols, g.origRows = c, r
		g.origKnown = true
	}
	g.active++
	return g.origCols, g.origRows, g.origKnown
}

// release claims one subscription left. When the count drops to zero it restores
// the pane to the original baseline (the last subscriber to leave does the
// restore, regardless of which exit path brought the count to zero — 契约 2).
// log is the server logger for restore-failure diagnostics.
func (g *paneGeometry) release(ctx context.Context, br *bridge.Pane, log *slog.Logger, ref string) {
	g.mu.Lock()
	if g.active <= 0 {
		// 防御：重复 release / 无订阅时释放，幂等无副作用。
		g.mu.Unlock()
		return
	}
	g.active--
	if g.active == 0 {
		// 最后一个订阅者离开：恢复 pane 到原始几何。
		cols, rows, known := g.origCols, g.origRows, g.origKnown
		// 重置记账，便于下一次 acquire 重新建立基线。
		g.origKnown = false
		g.origCols, g.origRows = 0, 0
		g.mu.Unlock()
		if known {
			// 用 Background ctx 而非调用方的 ctx：恢复是清理收尾，可能发生在
			// 连接已取消之后（teardown/断连路径），此时发起连接的 ctx 已死，
			// 用它调 Resize 会让 tmux 命令被 exec 提前 kill（fix-host-pane-
			// geometry-accounting：TestAbnormalTeardown 实测 108x96 不恢复根因）。
			if _, _, err := br.Resize(context.Background(), cols, rows); err != nil {
				// 恢复失败：日志（pane 可能已不存在），不影响连接关闭。
				log.Debug("pane restore size", "ref", ref, "err", err)
			}
		}
		return
	}
	g.mu.Unlock()
}
