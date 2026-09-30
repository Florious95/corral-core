package agentstate

import "unicode/utf8"

// This file holds a self-contained ANSI/VT escape-sequence stripper. It is a
// tiny state machine (no third-party dependency, per the task contract) that
// removes terminal control sequences so the per-agent rule tables can match
// against plain text.
//
// Why it must exist (requirement 008 isolation law): pane output is arbitrary
// terminal bytes — SGR color codes, cursor motion, OSC title updates, stray
// ESC. The state layer must never crash on any of it, and every decision must
// be able to run on empty/garble input and degrade to unknown. The stripper
// therefore treats every unknown byte as ordinary output to skip, never as an
// error.

// stripANSI removes CSI (ESC [ ... final), OSC (ESC ] ... BEL or ST), stray
// ESC pairs, and the BEL character, preserving all printable bytes including
// multi-byte UTF-8 (the prompt marker "❯" and Chinese output must survive
// untouched).
//
// The subtlety: the 8-bit C1 forms of CSI (0x9b) and OSC (0x9d) share byte
// values with UTF-8 continuation bytes (0x80–0xbf). A "❯" is U+276F, encoded
// as E2 9D AF — its middle byte is 0x9d, which is not a control but part of
// the rune. We therefore only treat 0x9b/0x9d as controls when they appear as
// invalid standalone bytes (not after a multi-byte lead), via utf8.DecodeRune.
func stripANSI(s string) string {
	b := []byte(s)
	out := make([]byte, 0, len(b))
	i := 0
	for i < len(b) {
		c := b[i]
		switch {
		case c == 0x1b: // ESC
			i++
			if i >= len(b) {
				break // trailing ESC at end of input: drop it
			}
			switch b[i] {
			case '[':
				i = skipCSI(b, i)
			case ']':
				i = skipOSC(b, i)
			default:
				// Two-byte escapes (ESC 7 save cursor, ESC ( charset, ESC c
				// reset, ...) carry no displayable text to preserve.
				i++
			}
		case c == 0x07: // BEL outside an OSC sequence has nothing to display
			i++
		case c < 0x80: // ASCII printable and controls: copy verbatim
			out = append(out, c)
			i++
		default:
			// >= 0x80: a UTF-8 rune, a C1 control, or invalid garbage.
			_, size := decodeRune(b[i:])
			if size <= 1 {
				// Invalid standalone byte: a genuine 8-bit C1 control (rare
				// under UTF-8, handled anyway) or a stray high byte. Skip a
				// CSI/OSC if it is one, otherwise drop the lone byte.
				switch c {
				case 0x9b:
					i = skipCSI(b, i)
				case 0x9d:
					i = skipOSC(b, i)
				default:
					i++ // unprintable garbage: drop, never fail
				}
			} else {
				out = append(out, b[i:i+size]...) // valid rune: preserve
				i += size
			}
		}
	}
	return string(out)
}

// decodeRune returns the byte length of the UTF-8 rune starting at b, or
// 1 when b[0] is an invalid lead byte (DecodeRune's RuneError+size1 contract).
// It is a thin wrapper over the stdlib decoder so the stripper's invalid-byte
// handling stays explicit and testable.
func decodeRune(b []byte) (r rune, size int) {
	r, size = utf8.DecodeRune(b)
	if r == utf8.RuneError && size == 1 {
		return r, 1 // invalid encoding (including a lone C1 control byte)
	}
	return r, size
}

// skipCSI consumes from the byte after '[' (or after 0x9b) until the CSI final
// byte (0x40–0x7e). Parameter and intermediate bytes (0x20–0x3f) are skipped by
// the loop. A control byte (< 0x20) before the final byte means the sequence is
// malformed: we stop there so the control byte survives as output. Returns the
// index of the first byte after the consumed sequence.
func skipCSI(b []byte, at int) int {
	i := at + 1
	for ; i < len(b); i++ {
		c := b[i]
		if c >= 0x40 && c <= 0x7e {
			return i + 1 // final byte
		}
		if c < 0x20 || c == 0x7f {
			return i // malformed: resume here, keep the control byte
		}
	}
	return i // unterminated CSI: drop the remainder
}

// skipOSC consumes from the byte after ']' (or after 0x9d) until the OSC
// terminator: BEL (0x07) or ST (ESC \). A lone ESC inside the title is part of
// the title bytes and is consumed; only ESC followed by '\' ends the sequence.
// Returns the index of the first byte after the consumed sequence.
func skipOSC(b []byte, at int) int {
	i := at + 1
	for ; i < len(b); i++ {
		switch b[i] {
		case 0x07:
			return i + 1 // BEL terminates OSC
		case 0x1b:
			if i+1 < len(b) && b[i+1] == '\\' {
				return i + 2 // ST (ESC \) terminates OSC
			}
			// else: stray ESC inside the title, consume and keep scanning
		}
	}
	return i // unterminated OSC at end of input: drop the tail
}
