package protocol_test

import (
	"errors"
	"reflect"
	"strings"
	"testing"

	"github.com/remote-agent/agentmirror/internal/protocol"
)

// TestBinaryRoundTrip drives each binary kind through encode→decode and
// checks the fields survive byte-for-byte (the terminal bytes must never be
// touched).
func TestBinaryRoundTrip(t *testing.T) {
	payloads := []protocol.BinaryPayload{
		{Kind: protocol.KindSnapshot, Ref: "s1", Data: []byte("\x1b[31mred screen\x1b[0m\n")},
		{Kind: protocol.KindDelta, Ref: "s1", Data: []byte("append")},
		{Kind: protocol.KindScrollback, Ref: "s1", ReqID: 5, Data: []byte("history page one")},
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
			if got.Kind != p.Kind || got.Ref != p.Ref || got.ReqID != p.ReqID || !reflect.DeepEqual(got.Data, p.Data) {
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
		b, err := protocol.EncodeBinary(protocol.BinaryPayload{Kind: protocol.KindScrollback, Ref: "s1", ReqID: 7, Data: []byte("h")})
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
	// scrollMissingReq strips the 4-byte req_id, keeping the payload byte.
	scrollMissingReq := append(scroll()[:7], 'h')

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
		{"scrollback missing reqid", scrollMissingReq, protocol.ErrTruncated},
		{"scrollback reqid zero", func() []byte {
			b := scroll()
			b[10] = 0 // req_id occupies offsets 7-10; byte 10 is its least significant byte
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
		{"scrollback zero reqid", protocol.BinaryPayload{Kind: protocol.KindScrollback, Ref: "s1", ReqID: 0, Data: []byte("x")}},
	}
	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			if _, err := protocol.EncodeBinary(tt.p); err == nil {
				t.Fatalf("EncodeBinary(%+v) succeeded, want error", tt.p)
			}
		})
	}
}
