package protocol_test

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// testdata is part of the wire contract: the Kotlin conn-layer consumes the
// same fixtures for its own decode assertions, so both ends share one set of
// golden samples and drift is caught. Every golden sample MUST round-trip
// byte-stably (decode → re-encode → identical bytes). A codec change that
// alters the wire bytes breaks the fixture and is a contract violation.
//
// JSON golden samples live in testdata/*.json (one per control frame type).
// Binary samples live in testdata/*.bin (snapshot / delta / scrollback), with
// *.bin.txt annotations. Do not rename or re-format these files without
// bumping the protocol version.
var (
	jsonGoldenFiles = []struct {
		name string
		want protocol.Typed
	}{
		{"auth.json", protocol.Auth{Token: "tok-abc-123"}},
		{"auth_ack_ok.json", protocol.AuthAck{OK: true}},
		{"auth_ack_reject.json", protocol.AuthAck{OK: false, Reason: "bad token"}},
		{"list.json", protocol.List{ReqID: 7}},
		{"listing.json", protocol.Listing{
			ReqID: 7, Seq: 42,
			Workspaces: []protocol.Workspace{
				{
					Cwd: "/proj/a", SessionCount: 2,
					Sessions: []protocol.Session{
						{Ref: "s1", Name: "claude", Cwd: "/proj/a", Rows: 40, Cols: 100},
						{Ref: "s2", Name: "codex", Cwd: "/proj/a", Rows: 24, Cols: 80},
					},
				},
				{Cwd: "/proj/b", SessionCount: 1,
					Sessions: []protocol.Session{
						{Ref: "s3", Name: "claude", Cwd: "/proj/b", Rows: 30, Cols: 90},
					}},
			},
		}},
		{"list_delta.json", protocol.ListDelta{
			Seq: 45,
			AddedSessions: []protocol.Session{
				{Ref: "s4", Name: "claude", Cwd: "/proj/c", Rows: 25, Cols: 100},
			},
			RemovedRefs: []string{"s1"},
			ChangedSessions: []protocol.Session{
				{Ref: "s2", Name: "codex", Cwd: "/proj/a", Rows: 24, Cols: 80},
			},
			ChangedWorkspaces: []protocol.Workspace{
				{Cwd: "/proj/a", SessionCount: 2},
			},
		}},
		{"subscribe.json", protocol.Subscribe{Ref: "s1", Rows: 40, Cols: 100}},
		{"unsubscribe.json", protocol.Unsubscribe{Ref: "s1"}},
		{"input.json", protocol.Input{ReqID: 9, Ref: "s1", Text: "/model opus"}},
		{"input_keys.json", protocol.Input{ReqID: 10, Ref: "s1", Keys: []protocol.Key{protocol.KeyEsc, protocol.KeyCtrlC, protocol.KeyTab}}},
		{"input_ack_ok.json", protocol.InputAck{ReqID: 9, OK: true}},
		{"input_ack_fail.json", protocol.InputAck{ReqID: 9, OK: false, Reason: protocol.InputFailInjectFailed}},
		{"scrollback.json", protocol.Scrollback{ReqID: 5, Ref: "s1", FromLine: -300, Count: 100}},
		{"resize.json", protocol.Resize{Ref: "s1", Rows: 48, Cols: 120}},
		{"error.json", protocol.ErrorFrame{Code: protocol.ErrCodeSessionNotFound, Reason: "session s1 vanished"}},
		{"close_session.json", protocol.CloseSession{ReqID: 11, Ref: "s1"}},
		{"close_session_ack_ok.json", protocol.CloseSessionAck{ReqID: 11, OK: true}},
		{"close_session_ack_fail.json", protocol.CloseSessionAck{ReqID: 11, OK: false, Reason: protocol.CloseFailCloseFailed}},
		{"create_session.json", protocol.CreateSession{ReqID: 12, Cwd: "/ws", Argv: []string{"sleep", "30"}}},
		{"create_session_ack_ok.json", protocol.CreateSessionAck{ReqID: 12, OK: true, Ref: "s1"}},
		{"create_session_ack_fail.json", protocol.CreateSessionAck{ReqID: 12, OK: false, Reason: protocol.CreateFailNoTmuxAnchor}},
	}
	binaryGoldenFiles = []struct {
		file string
		want protocol.BinaryPayload
	}{
		{"snapshot.bin", protocol.BinaryPayload{Kind: protocol.KindSnapshot, Ref: "s1", Data: []byte("\x1b[31mred screen\x1b[0m\n")}},
		{"delta.bin", protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: "s1", Data: []byte("append")}},
		{"scrollback.bin", protocol.BinaryPayload{Kind: protocol.KindScrollback, Ref: "s1", ReqID: 5, FromLine: -100, LineCount: 50, Data: []byte("history page one")}},
	}
)

// TestJSONGoldenFixtures decodes every JSON golden sample and re-encodes it,
// asserting the bytes are identical (the wire contract must be byte-stable).
func TestJSONGoldenFixtures(t *testing.T) {
	for _, g := range jsonGoldenFiles {
		t.Run(g.name, func(t *testing.T) {
			data := readTestData(t, g.name)
			frame, err := protocol.UnmarshalFrame(data)
			if err != nil {
				t.Fatalf("UnmarshalFrame(%s) failed: %v", g.name, err)
			}
			if !reflect.DeepEqual(frame, g.want) {
				t.Errorf("decoded %s mismatch:\n got %#v\nwant %#v", g.name, frame, g.want)
			}
			re, err := protocol.MarshalFrame(frame)
			if err != nil {
				t.Fatalf("MarshalFrame(%s) failed: %v", g.name, err)
			}
			if !jsonEqual(t, data, re) {
				t.Errorf("golden %s re-encode is not byte-stable:\n got  %s\n want %s", g.name, re, data)
			}
		})
	}
}

// TestBinaryGoldenFixtures decodes every binary golden sample into the exact
// expected payload and re-encodes to byte-identical wire form.
func TestBinaryGoldenFixtures(t *testing.T) {
	for _, g := range binaryGoldenFiles {
		t.Run(g.file, func(t *testing.T) {
			data := readTestData(t, g.file)
			got, err := protocol.DecodeBinary(data)
			if err != nil {
				t.Fatalf("DecodeBinary(%s) failed: %v", g.file, err)
			}
			if got.Kind != g.want.Kind || got.Ref != g.want.Ref || got.ReqID != g.want.ReqID || !reflect.DeepEqual(got.Data, g.want.Data) {
				t.Errorf("decoded %s mismatch:\n got %+v\nwant %+v", g.file, got, g.want)
			}
			re, err := protocol.EncodeBinary(got)
			if err != nil {
				t.Fatalf("EncodeBinary(%s) failed: %v", g.file, err)
			}
			if !bytes.Equal(re, data) {
				t.Errorf("golden %s re-encode is not byte-stable:\n got  %x\n want %x", g.file, re, data)
			}
		})
	}
}

// TestBackspaceKeyIsInClosedSet verifies the backspace wire key (requirement
// 059 passthrough) is a valid protocol Key and round-trips on the wire.
func TestBackspaceKeyIsInClosedSet(t *testing.T) {
	if !protocol.KeyBackspace.IsValid() {
		t.Fatal("KeyBackspace must be a valid closed-set key (requirement 059)")
	}
	frame := protocol.Input{ReqID: 1, Ref: "s1", Keys: []protocol.Key{protocol.KeyBackspace}}
	wire, err := protocol.MarshalFrame(frame)
	if err != nil {
		t.Fatalf("MarshalFrame(backspace input): %v", err)
	}
	if !bytes.Contains(wire, []byte(`"keys":["backspace"]`)) {
		t.Errorf("marshal backspace input = %s, want keys [backspace]", wire)
	}
}

// readTestData loads one golden fixture from the testdata directory next to
// this test file.
func readTestData(t *testing.T, name string) []byte {
	t.Helper()
	path := filepath.Join("testdata", name)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return data
}

// jsonEqual compares two JSON payloads semantically after compacting, so the
// fixture's pretty formatting does not count as a byte difference.
func jsonEqual(t *testing.T, a, b []byte) bool {
	t.Helper()
	var va, vb any
	if err := json.Unmarshal(a, &va); err != nil {
		t.Fatalf("jsonEqual: %v", err)
	}
	if err := json.Unmarshal(b, &vb); err != nil {
		t.Fatalf("jsonEqual: %v", err)
	}
	return reflect.DeepEqual(va, vb)
}
