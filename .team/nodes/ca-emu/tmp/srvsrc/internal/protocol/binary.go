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
// For KindScrollback the payload itself starts with a 12-byte metadata header
// describing the ACTUAL range returned (the server clamps the request):
// req_id (4, big-endian), from_line (4, big-endian signed), line_count (4,
// big-endian unsigned), then the ANSI bytes. Snapshot and Delta carry no such
// header.
//
// The magic and version live on the outside so a decoder can reject a
// mis-framed message before trusting any byte.
//
// Dead code note: ErrInvalidGeometry and ErrInvalidCount are sentinels this
// package never constructs — they are retained because internal/api/ws_conn.go
// matches them via errors.Is in classifyCodecError.

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

	// scrollbackHeaderLen is the scrollback reply's fixed metadata header:
	// req_id (4, big-endian) + from_line (4, big-endian signed) + line_count
	// (4, big-endian unsigned), followed by the ANSI bytes.
	scrollbackHeaderLen = 12
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
//
// @contract
// @pre 由 DecodeBinary 或手工构造，Kind 属闭集且 Ref 非空
// @post 对 KindScrollback，ReqID >= 1 且 LineCount >= 1；对 Snapshot/Delta，两者为 0
// @err 作为 EncodeBinary 入参不合法时返回 ErrUnknownKind / ErrInvalidRef / ErrRefTooLong / ErrInvalidField
// @inv Data 始终是原始终端字节，绝不被 JSON 转义
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

	// FromLine is the actual first line of the returned scrollback range, in
	// the same capture-pane semantics as the Scrollback request (0 = current
	// screen top, negative = history above it). The server clamps the
	// requested range to what tmux has and reports the result here so the
	// client can anchor its scroll viewport without guessing. Meaningful only
	// for KindScrollback; 0 for Snapshot/Delta.
	FromLine int32

	// LineCount is the actual number of lines in the returned scrollback
	// range (>= 1). Present only for KindScrollback; 0 for Snapshot/Delta.
	LineCount uint32
}

// EncodeBinary serializes a BinaryPayload into one complete binary WebSocket
// message. It validates the frame first so a bad frame never crosses the
// wire: the kind must be in the closed set, the ref non-empty and within
// BinaryMaxRefLen, the data within BinaryMaxPayloadLen, and a scrollback
// reply's ReqID/LineCount at least 1. For Snapshot/Delta a nonzero ReqID is
// not rejected — it is ignored, matching the decode side.
//
// @contract
// @pre p.Kind 属闭集、p.Ref 非空且 <= BinaryMaxRefLen、len(p.Data) <= BinaryMaxPayloadLen；若 p.Kind 为 KindScrollback 则 ReqID 与 LineCount >= 1
// @post 返回以 BinaryMagic 开头、长度为 BinaryHeaderLen + len(Ref) + len(Data)（Scrollback 另加 scrollbackHeaderLen）的完整二进制消息
// @err ErrUnknownKind / ErrInvalidRef / ErrRefTooLong / ErrInvalidField
// @inv 纯函数，无外部副作用；Data 字节原样进入返回消息
func EncodeBinary(p BinaryPayload) ([]byte, error) {
	if err := validateBinaryPayload(p); err != nil {
		return nil, err
	}
	buf := make([]byte, 0, BinaryHeaderLen+len(p.Ref)+len(p.Data)+12)
	buf = append(buf, BinaryMagic[0], BinaryMagic[1])
	buf = append(buf, BinaryVersion)
	buf = append(buf, byte(p.Kind))
	buf = append(buf, byte(len(p.Ref)))
	buf = append(buf, p.Ref...)
	if p.Kind == KindScrollback {
		// Scrollback replies carry a 12-byte metadata header describing the
		// ACTUAL range returned (the server clamps the request): req_id, the
		// first line, and the line count. Without it the client could not
		// anchor the page after convergence and would mis-assemble history.
		var hdr [12]byte
		binary.BigEndian.PutUint32(hdr[0:4], p.ReqID)
		binary.BigEndian.PutUint32(hdr[4:8], uint32(p.FromLine))
		binary.BigEndian.PutUint32(hdr[8:12], p.LineCount)
		buf = append(buf, hdr[:]...)
	}
	buf = append(buf, p.Data...)
	return buf, nil
}

// DecodeBinary parses one binary WebSocket message back into a BinaryPayload.
// It is strict: a bad magic or version, an unknown kind, a truncated header,
// or an empty ref is an error (a malformed mirror stream must surface, not
// corrupt the client's grid). Decode imposes no payload-size cap — the
// BinaryMaxPayloadLen limit is enforced on the encode side by EncodeBinary,
// so a consumer that only decodes never rejects large data on size grounds.
//
// @contract
// @pre data 是至少 BinaryHeaderLen 字节的完整二进制消息
// @post 返回的 BinaryPayload.Kind 合法且 Ref 非空；出错时返回零值 BinaryPayload 与 error
// @err ErrTruncated / ErrBadMagic / ErrUnsupportedVersion / ErrUnknownKind / ErrInvalidRef / ErrInvalidField
// @inv 纯函数，无外部副作用；decode 侧不设 payload 大小上限
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
		if len(body) < scrollbackHeaderLen {
			return BinaryPayload{}, fmt.Errorf("%w: scrollback metadata header", ErrTruncated)
		}
		reqID := binary.BigEndian.Uint32(body[0:4])
		fromLine := int32(binary.BigEndian.Uint32(body[4:8]))
		lineCount := binary.BigEndian.Uint32(body[8:12])
		body = body[scrollbackHeaderLen:]
		if reqID == 0 {
			return BinaryPayload{}, fmt.Errorf("%w: scrollback req_id must be >= 1", ErrInvalidField)
		}
		if lineCount == 0 {
			return BinaryPayload{}, fmt.Errorf("%w: scrollback line_count must be >= 1", ErrInvalidField)
		}
		return BinaryPayload{Kind: k, Ref: ref, ReqID: reqID, FromLine: fromLine, LineCount: lineCount, Data: body}, nil
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
	if p.Kind == KindScrollback && p.LineCount == 0 {
		return fmt.Errorf("%w: scrollback line_count must be >= 1", ErrInvalidField)
	}
	return nil
}
