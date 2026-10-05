package bridge

import "context"

// RememberManagedCWD retains the actual workspace only across a deliberate
// native process replacement; a live process's cwd always wins in discovery.
// @contract
// @pre exact scoped pane and its discovered cwd
// @post dead-pane discovery can preserve the same public workspace/ref
// @err bounded tmux error
// @inv no process change
func RememberManagedCWD(ctx context.Context, socket, pane, cwd string) error {
	_, err := runTmux(ctx, socket, defaultTimeout, "set-option", "-p", "-t", pane, "@corral_cwd", cwd)
	return err
}
