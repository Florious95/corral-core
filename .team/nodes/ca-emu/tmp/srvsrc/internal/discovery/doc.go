// Package discovery enumerates every tmux server socket on the host and
// aggregates their sessions and panes into the two-level workspace model
// (requirements 001 and 002).
//
// It scans all sockets under the default tmux socket directories
// (/tmp/tmux-<uid>/ and /private/tmp/tmux-<uid>/ on macOS) plus the
// $TMUX_TMPDIR override, so team-agent private sockets are seen in addition to
// the default server (requirement 001). Dead or stale sockets are skipped with
// a debug log: one unreachable socket never aborts the overall scan (red line).
//
// The package returns a pure data structure for the API layer to consume. It
// performs no caching; every call returns one fresh snapshot, and the polling
// cadence is decided by the caller.
package discovery
