package protocol

import (
	"encoding/binary"
	"fmt"
)

// Binary wire layout (docs/protocol.md §6). One frame is:
//
//	+0  magic      "R" "A"
//	+2  version    1 byte  = BinaryVersion
//	+3  kind       1 byte  (see BinaryKind)
//	+4  reflen     1 byte  session-ref byte length (0..255)
//	+5  ref        reflen bytes, UTF-8
//	+5+reflen      payload  (kind-specific)
//
// The magic and version live on the outside so a decoder can reject a
// mis-framed message before trusting any byte.

const (
	// BinaryHeaderLen is the header byte count excluding the variable-length
	// session ref (magic 2 + version 1 + kind 1 + reflen 1).
	BinaryHeaderLen = 5

	// BinaryMaxRefLen bounds reflen at 255 (a single byte).
	BinaryMaxRefLen = 255

	// BinaryMaxPayloadLen caps a single binary frame at 1 MiB so a buggy or
	// hostile peer cannot grow memory without bound. The bridge layer may use
	// smaller, negotiated chunks.
	BinaryMaxPayloadLen = 1 << 20
)

// BinaryKind is the one-byte discriminator of a binary stream frame. It is a
// closed set; adding a kind is additive but requires both ends to share the
// constant (docs/protocol.md §6).
type BinaryKind byte

const (
	// KindSnapshot is the initial full screen (capture-pane -e, color
	// escapes preserved) that the server sends first when a Subscribe takes
	// effect. It is sent after the subscribe, before the Delta stream. On
	// reconnect the same sequence replays the current screen (requirement
	// 003/004 stateless replay).
	KindSnapshot BinaryKind = 1
	// KindDelta is one incremental terminal byte run (pipe-pane) appended to
	// the current screen.
	KindDelta BinaryKind = 2
	// KindScrollback is one page of history (capture-pane -S) as ANSI bytes;
	// it answers a Scrollback request.
	KindScrollback BinaryKind = 3
)

// validKind reports whether k is one of the three closed BinaryKind values.
func (k BinaryKind) validKind() bool {
	switch k {
	case KindSnapshot, KindDelta, KindScrollback:
		return true
	}
	return false
}

// BinaryPayload wraps the decoded body of one binary stream frame.
type BinaryPayload struct {
	// Kind discriminates the payload semantics.
	Kind BinaryKind

	// Ref is the session reference this frame belongs to.
	Ref string

	// Data is the terminal byte content: the full screen for KindSnapshot,
	// one output run for KindDelta, or one history page for KindScrollback.
	// It is raw ANSI/VT bytes, never JSON-escaped.
	Data []byte

	// ReqID correlates a KindScrollback payload to the Scrollback request
	// that produced it; 0 for Snapshot/Delta. It is never negative by
	// construction (an invalid ReqID is rejected).
	ReqID uint32
}

// EncodeBinary serializes a BinaryPayload into one complete binary WebSocket
// message. It validates the ref bounds and payload size first so a bad frame
// never crosses the wire. Kind is checked by the caller's constructor choices
// (Snapshot/Delta set ReqID 0); ReqID is meaningful only for Scrollback but a
// nonzero value is not rejected.
func EncodeBinary(p BinaryPayload) ([]byte, error) {
	if err := validateBinaryPayload(p); err != nil {
		return nil, err
	}
	buf := make([]byte, 0, BinaryHeaderLen+len(p.Ref)+len(p.Data)+4)
	buf = append(buf, BinaryMagic[0], BinaryMagic[1])
	buf = append(buf, BinaryVersion)
	buf = append(buf, byte(p.Kind))
	buf = append(buf, byte(len(p.Ref)))
	buf = append(buf, p.Ref...)
	if p.Kind == KindScrollback {
		var rb [4]byte
		binary.BigEndian.PutUint32(rb[:], p.ReqID)
		buf = append(buf, rb[:]...)
	}
	buf = append(buf, p.Data...)
	return buf, nil
}

// DecodeBinary parses one binary WebSocket message back into a BinaryPayload.
// It is strict by default: a bad magic or version, an unknown kind, a
// truncated header, an oversized frame, or an empty ref is an error (a
// malformed mirror stream must surface, not corrupt the client's grid).
func DecodeBinary(data []byte) (BinaryPayload, error) {
	if len(data) < BinaryHeaderLen {
		return BinaryPayload{}, fmt.Errorf("%w: got %d bytes", ErrTruncated, len(data))
	}
	if data[0] != BinaryMagic[0] || data[1] != BinaryMagic[1] {
		return BinaryPayload{}, fmt.Errorf("%w: %q", ErrBadMagic, string(data[:2]))
	}
	if data[2] != BinaryVersion {
		return BinaryPayload{}, fmt.Errorf("%w: got %d want %d", ErrUnsupportedVersion, data[2], BinaryVersion)
	}
	k := BinaryKind(data[3])
	if !k.validKind() {
		return BinaryPayload{}, fmt.Errorf("%w: %d", ErrUnknownKind, data[3])
	}
	reflen := int(data[4])
	if len(data) < BinaryHeaderLen+reflen {
		return BinaryPayload{}, fmt.Errorf("%w: ref %d bytes but frame has %d", ErrTruncated, reflen, len(data))
	}
	if reflen == 0 {
		return BinaryPayload{}, fmt.Errorf("%w", ErrInvalidRef)
	}
	ref := string(data[BinaryHeaderLen : BinaryHeaderLen+reflen])
	body := data[BinaryHeaderLen+reflen:]
	if k == KindScrollback {
		if len(body) < 4 {
			return BinaryPayload{}, fmt.Errorf("%w: scrollback req_id header", ErrTruncated)
		}
		reqID := binary.BigEndian.Uint32(body[:4])
		body = body[4:]
		if reqID == 0 {
			return BinaryPayload{}, fmt.Errorf("%w: scrollback req_id must be >= 1", ErrInvalidField)
		}
		return BinaryPayload{Kind: k, Ref: ref, ReqID: reqID, Data: body}, nil
	}
	return BinaryPayload{Kind: k, Ref: ref, Data: body}, nil
}

// validateBinaryPayload enforces the encode-side bounds.
func validateBinaryPayload(p BinaryPayload) error {
	if !p.Kind.validKind() {
		return fmt.Errorf("%w: %d", ErrUnknownKind, p.Kind)
	}
	if p.Ref == "" {
		return fmt.Errorf("%w", ErrInvalidRef)
	}
	if len(p.Ref) > BinaryMaxRefLen {
		return fmt.Errorf("%w: got %d", ErrRefTooLong, len(p.Ref))
	}
	if len(p.Data) > BinaryMaxPayloadLen {
		return fmt.Errorf("%w: got %d bytes", ErrInvalidField, len(p.Data))
	}
	if p.Kind == KindScrollback && p.ReqID == 0 {
		return fmt.Errorf("%w: scrollback req_id must be >= 1", ErrInvalidField)
	}
	return nil
}
