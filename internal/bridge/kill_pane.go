package bridge

import "context"

// KillPane terminates exactly one tmux pane. The target must be the pane id
// (for example, "%12"); this operation intentionally never addresses a
// window or session, so sibling panes remain untouched.
func KillPane(socket, paneID string) error {
	return NewPane(socket, paneID).Kill(context.Background())
}

// Kill terminates this pane using only tmux's kill-pane command. It is kept as
// a Pane method for callers that already carry a request context; KillPane is
// the simple package-level entry point used by the close-session handler.
func (p *Pane) Kill(ctx context.Context) error {
	_, err := runTmux(ctx, p.socket, p.timeout, "kill-pane", "-t", p.target)
	return err
}
