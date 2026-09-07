// Package discovery enumerates every tmux server socket on the host and
// aggregates their sessions and panes into the two-level workspace model
// (requirements 001 and 002).
//
// It considers entries only after a local, fork-free classification: the
// current uid's `default` socket, user-named sockets, and the first socket
// path from TMUX are allowed; this includes current-user Team-agent `ta-*`
// sockets. Other uid directories, explicit test/e2e trees, and repo-local node
// tmp trees are skipped fail-closed with path, classification, and action
// operands in debug logs. Explicitly isolated fixtures remain outside
// the product discovery object. Dead or stale allowed sockets are skipped
// with a debug log: one unreachable socket never aborts the overall scan (red
// line).
//
// The package returns a pure data structure for the API layer to consume. The
// workspace model is never cached: every call re-lists the socket directories
// and re-queries every socket that still looks alive. Unreachable sockets are
// remembered briefly (path + inode/mtime, with a TTL) so a directory full of
// stale files does not fork tmux on every tick — that was the 2026-08-23 idle
// CPU burn (140 dead sockets × list-interval 2s).
package discovery
