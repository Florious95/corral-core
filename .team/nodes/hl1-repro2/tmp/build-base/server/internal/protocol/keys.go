package protocol

// Key is one named special key the client can inject via Input.Keys (R-1
// shortcut bar, requirement 017; backspace added by requirement 059
// passthrough). The values form a closed set; a new key requires a protocol
// version bump. Each maps to a tmux send-keys named key in the bridge layer —
// the protocol never carries raw terminal bytes in a control frame (that is
// the binary channel's job).
//
// The closed set is Esc / Ctrl-C / Tab / Up / Down / Left / Right / Backspace
// — the keys Claude Code depends on daily. Arrow/vertical-bar values are
// chosen to read unambiguously on the wire.
type Key string

const (
	// KeyEsc is Escape (interrupts the agent's current step).
	KeyEsc Key = "esc"
	// KeyCtrlC is Ctrl-C (SIGINT).
	KeyCtrlC Key = "ctrl_c"
	// KeyTab is Tab (completion).
	KeyTab Key = "tab"
	// KeyUp is the up arrow (history / menu selection).
	KeyUp Key = "up"
	// KeyDown is the down arrow (history / menu selection).
	KeyDown Key = "down"
	// KeyLeft is the left arrow (menu selection).
	KeyLeft Key = "left"
	// KeyRight is the right arrow (menu selection).
	KeyRight Key = "right"
	// KeyBackspace is the delete/backspace key (requirement 059 passthrough:
	// the virtual keyboard delete key goes straight to the CLI, not consumed
	// locally).
	KeyBackspace Key = "backspace"
)

// IsValid reports whether k is one of the eight closed key values. The codec
// rejects any other value on decode so a typo is caught at the boundary.
func (k Key) IsValid() bool {
	switch k {
	case KeyEsc, KeyCtrlC, KeyTab, KeyUp, KeyDown, KeyLeft, KeyRight, KeyBackspace:
		return true
	}
	return false
}
