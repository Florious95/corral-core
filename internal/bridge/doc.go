// Package bridge exposes a single tmux pane as a terminal bridge: first-frame
// snapshot, incremental output stream, input injection, and resize.
//
// Landing zone for the term-bridge task (capture-pane -e / pipe-pane /
// send-keys / window-size). Input injection must carry a decidable ack.
// Only the package contract is declared here; no implementation has landed yet.
package bridge
