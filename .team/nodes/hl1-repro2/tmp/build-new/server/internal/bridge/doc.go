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
//   - SendKeys — named special keys (esc / ctrl_c / tab / arrows, R-1 shortcut
//     bar) via one send-keys named-key invocation, no Enter appended;
//   - Resize — window-size latest + resize-window (requirement 005).
//
// The mirror/inject red line is structural: a Pane only reads and writes the
// pane's input/output streams; it never kills, detaches, or alters the pane's
// runtime state beyond what a call explicitly requests.
//
// @consumes none — 本包只依赖标准库（context/fmt/os/strconv/strings/syscall/time/
// os/exec/bytes/sync/sync/atomic/path/filepath），无内部包跨层依赖
package bridge
