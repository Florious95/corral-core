// Package bridge exposes a single tmux pane as a terminal bridge: first-frame
// snapshot, incremental output stream, whole-message input injection with a
// decidable ack, resize, and scrollback paging.
//
// Implemented for the term-bridge task. Every operation is a tmux primitive
// on a bare pane id over one socket, routed through the exec seam in tmux.go:
//
//   - Snapshot / Scrollback — capture-pane -e (-S/-E for history paging);
//   - Subscribe — pipe-pane -o incremental byte stream via a FIFO;
//   - Inject — send-keys -l (single line) or paste-buffer (multi-line), then
//     Enter; returns a decidable ack, so "sent but no reply" is impossible
//     (requirement 003);
//   - Resize — window-size latest + resize-window (requirement 005).
//
// The mirror/inject red line is structural: a Pane only reads and writes the
// pane's input/output streams; it never kills, detaches, or alters the pane's
// runtime state beyond what a call explicitly requests.
package bridge
