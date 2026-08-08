// Package discovery enumerates all tmux server sockets on the host and
// aggregates sessions and panes into the two-level workspace model.
//
// Landing zone for the tmux-discovery task: it must scan every socket under
// the default tmux socket directories (including team-agent private sockets,
// requirement 001) and tolerate dead sockets by skipping them without
// failing the whole scan. Only the package contract is declared here; no
// implementation has landed yet.
package discovery
