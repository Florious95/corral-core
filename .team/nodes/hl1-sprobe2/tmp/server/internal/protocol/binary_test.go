package protocol_test

import (
	"errors"
	"reflect"
	"strings"
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// TestBinaryRoundTrip drives each binary kind through encode→decode and
// checks the fields survive byte-for-byte (the terminal bytes must never be
// touched).
func TestBinaryRoundTrip(t *testing.T) {
	payloads := []protocol.BinaryPayload{
		{Kind: protocol.KindSnapshot, Ref: "s1", Data: []byte("\x1b[31mred screen\x1b[0m\n")},
		{Kind: protocol.KindDelta, Ref: "s1", Data: []byte("append")},
		{Kind: protocol.KindScrollback, Ref: "s1", ReqID: 5, FromLine: -100, LineCount: 50, Data: []byte("history page one")},
	}
	for _, p := range payloads {
		t.Run(string(p.Kind), func(t *testing.T) {
			wire, err := protocol.EncodeBinary(p)
			if err != nil {
				t.Fatalf("EncodeBinary failed: %v", err)
			}
			got, err := protocol.DecodeBinary(wire)
			if err != nil {
				t.Fatalf("DecodeBinary of own bytes failed: %v", err)
			}
			if got.Kind != p.Kind || got.Ref != p.Ref || got.ReqID != p.ReqID ||
				got.FromLine != p.FromLine || got.LineCount != p.LineCount || !reflect.DeepEqual(got.Data, p.Data) {
				t.Errorf("round trip mismatch:\n got %#v\nwant %#v", got, p)
			}
		})
	}
}

// TestBinaryWireLayout pins the exact byte layout so a mis-framed stream is
// caught by construction: magic, version, kind, reflen, ref, payload.
func TestBinaryWireLayout(t *testing.T) {
	wire, err := protocol.EncodeBinary(protocol.BinaryPayload{
		Kind: protocol.KindDelta, Ref: "ab", Data: []byte("XY"),
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []byte{'R', 'A', 1, byte(protocol.KindDelta), 2, 'a', 'b', 'X', 'Y'}
	if !reflect.DeepEqual(wire, want) {
		t.Errorf("wire layout = %v, want %v", wire, want)
	}
}

// TestBinaryScrollbackHeaderLayout pins the scrollback reply header: req_id,
// from_line (signed), line_count (unsigned), then ANSI bytes. The from_line
// sign must survive the round trip (a negative anchor is the common case).
func TestBinaryScrollbackHeaderLayout(t *testing.T) {
	wire, err := protocol.EncodeBinary(protocol.BinaryPayload{
		Kind: protocol.KindScrollback, Ref: "s1", ReqID: 5, FromLine: -100, LineCount: 50, Data: []byte("page"),
	})
	if err != nil {
		t.Fatal(err)
	}
	// [0-6] magic+ver+kind+reflen+ref("s1"), [7-10] req_id=5,
	// [11-14] from_line=-100 (ff ff ff 9c), [15-18] line_count=50,
	// [19-22] "page".
	want := []byte{'R', 'A', 1, byte(protocol.KindScrollback), 2, 's', '1',
		0, 0, 0, 5,
		0xff, 0xff, 0xff, 0x9c,
		0, 0, 0, 50,
		'p', 'a', 'g', 'e'}
	if !reflect.DeepEqual(wire, want) {
		t.Errorf("scrollback header layout = %v, want %v", wire, want)
	}

	// The negative from_line must come back as -100, not a huge unsigned.
	got, err := protocol.DecodeBinary(wire)
	if err != nil {
		t.Fatalf("DecodeBinary: %v", err)
	}
	if got.FromLine != -100 || got.LineCount != 50 {
		t.Errorf("decoded range = from %d x %d, want -100 x 50", got.FromLine, got.LineCount)
	}
}

// TestBinaryRedPaths drives the failure paths a mirror-stream decoder must
// reject before trusting any byte.
func TestBinaryRedPaths(t *testing.T) {
	// fresh encodes a valid delta frame we then corrupt.
	fresh := func() []byte {
		b, err := protocol.EncodeBinary(protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: "s1", Data: []byte("x")})
		if err != nil {
			t.Fatal(err)
		}
		return b
	}

	// truncated cuts into the declared ref (6 bytes = header 5 + 1 of 2 ref
	// bytes), so the frame genuinely cannot be parsed.
	truncated := fresh()[:6]

	scroll := func() []byte {
		b, err := protocol.EncodeBinary(protocol.BinaryPayload{Kind: protocol.KindScrollback, Ref: "s1", ReqID: 7, FromLine: -5, LineCount: 1, Data: []byte("h")})
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
	// scrollNoHdr drops the entire 12-byte metadata header: bytes 0-6 are
	// magic+ver+kind+reflen+ref("s1"), so [:7] leaves no header for the
	// decoder.
	scrollNoHdr := scroll()[:7]

	cases := []struct {
		name string
		wire []byte
		want error
	}{
		{"too short", []byte{'R', 'A'}, protocol.ErrTruncated},
		{"bad magic", []byte{'X', 'Y', 1, 2, 1, 's', 'x'}, protocol.ErrBadMagic},
		{"bad version byte", append([]byte{'R', 'A', 9}, fresh()[3:]...), protocol.ErrUnsupportedVersion},
		{"unknown kind", []byte{'R', 'A', 1, 9, 1, 's', 'x'}, protocol.ErrUnknownKind},
		{"truncated ref", []byte{'R', 'A', 1, 2, 5, 's', '1', 'x'}, protocol.ErrTruncated},
		{"empty ref", []byte{'R', 'A', 1, 2, 0, 'x'}, protocol.ErrInvalidRef},
		{"truncated frame", truncated, protocol.ErrTruncated},
		{"scrollback missing header", scrollNoHdr, protocol.ErrTruncated},
		{"scrollback reqid zero", func() []byte {
			b := scroll()
			b[10] = 0 // req_id occupies offsets 7-10; byte 10 is its least significant byte
			return b
		}(), protocol.ErrInvalidField},
		{"scrollback line_count zero", func() []byte {
			b := scroll()
			b[18] = 0 // line_count occupies offsets 15-18; byte 18 is its least significant byte
			return b
		}(), protocol.ErrInvalidField},
	}
	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			_, err := protocol.DecodeBinary(tt.wire)
			if err == nil {
				t.Fatalf("DecodeBinary(%v) succeeded, want error", tt.wire)
			}
			if !errors.Is(err, tt.want) {
				t.Errorf("DecodeBinary error = %v, want errors.Is(err, %v)", err, tt.want)
			}
		})
	}
}

// TestBinaryEncodeRedPaths drives the encode-side bounds: a frame that cannot
// exist on the wire must never be emitted.
func TestBinaryEncodeRedPaths(t *testing.T) {
	cases := []struct {
		name string
		p    protocol.BinaryPayload
	}{
		{"empty ref", protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: "", Data: []byte("x")}},
		{"ref too long", protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: strings.Repeat("r", protocol.BinaryMaxRefLen+1), Data: []byte("x")}},
		{"payload too large", protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: "s1", Data: make([]byte, protocol.BinaryMaxPayloadLen+1)}},
		{"unknown kind", protocol.BinaryPayload{Kind: 99, Ref: "s1", Data: []byte("x")}},
		{"scrollback zero reqid", protocol.BinaryPayload{Kind: protocol.KindScrollback, Ref: "s1", ReqID: 0, FromLine: -1, LineCount: 1, Data: []byte("x")}},
		{"scrollback zero linecount", protocol.BinaryPayload{Kind: protocol.KindScrollback, Ref: "s1", ReqID: 1, FromLine: -1, LineCount: 0, Data: []byte("x")}},
	}
	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			if _, err := protocol.EncodeBinary(tt.p); err == nil {
				t.Fatalf("EncodeBinary(%+v) succeeded, want error", tt.p)
			}
		})
	}
}

// TestScrollbackConvergedRange is the clamping scenario that motivated the
// range metadata: the client requests from_line=-300, count=100 but the server
// only has 50 lines and converges to from_line=-100. The reply must carry the
// ACTUAL range so the client can anchor its viewport without guessing — an
// out-of-bounds request must never come back silently mis-assembled.
func TestScrollbackConvergedRange(t *testing.T) {
	req := protocol.Scrollback{ReqID: 5, Ref: "s1", FromLine: -300, Count: 100}
	_ = req // the request itself is a control frame; the reply is what we test.

	// The bridge would converge the request to the available history and
	// report the actual range in the reply.
	reply := protocol.BinaryPayload{
		Kind:      protocol.KindScrollback,
		Ref:       req.Ref,
		ReqID:     req.ReqID,
		FromLine:  -100,
		LineCount: 50,
		Data:      []byte("clamped history page"),
	}
	wire, err := protocol.EncodeBinary(reply)
	if err != nil {
		t.Fatalf("EncodeBinary: %v", err)
	}
	got, err := protocol.DecodeBinary(wire)
	if err != nil {
		t.Fatalf("DecodeBinary: %v", err)
	}
	if got.ReqID != req.ReqID || got.FromLine != -100 || got.LineCount != 50 {
		t.Errorf("converged reply = req %d, from %d, count %d; want req %d, from -100, count 50",
			got.ReqID, got.FromLine, got.LineCount, req.ReqID)
	}
}
