// Package overlay captures tmux choose-tree for the in-session floating
// window (requirement 064). Capture is a dedicated scratch-session client
// PTY plus refresh-client — not capture-pane, not a self-drawn tree.
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
