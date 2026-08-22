// Package overlay captures tmux choose-tree for the in-session floating
// window (requirement 064). Capture is a dedicated scratch-session client
// PTY plus refresh-client — not capture-pane, not a self-drawn tree.
//
// 已归档，2026-08-19 用户令暂不介入；展示不完全问题未修。
// 主流程「查看」改为二级菜单列表后，api.NewServer 不再构造本包、不再 overlayLoop、
// 不再 attach scratch 客户端。本包代码保留，不删除，也不修展示不完全。
package overlay

import "context"

// Capturer is the idle-gated overlay source. Start may create a tmux client
// on the requested socket (never "first discovered"). Stop must tear it down.
// Zero subscribers ⇒ the API loop never calls Start and must call Stop, so
// CaptureCount/ClientCount stay 0.
type Capturer interface {
	Start(ctx context.Context, socket string) error
	Snapshot(ctx context.Context) ([]byte, error)
	Stop()
	CaptureCount() int64
	ClientCount() int64
}
