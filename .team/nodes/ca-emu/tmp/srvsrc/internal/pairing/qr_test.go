package pairing

// qr_test.go covers the QR payload contract: the exact JSON keys the Android
// app parses (v/url/token/ts_authkey), the JSON round trip, the terminal
// half-block rendering, and the red line that the QR payload is one of the
// token's two legal exits.

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

// TestPayloadJSONContract verifies the QR payload marshals to exactly the
// schema the app expects (requirement 011 route (a)): keys v, url, token,
// ts_authkey — nothing more, nothing less — and round-trips cleanly. The
// ts_authkey field is reserved empty for the app-tsnet task.
func TestPayloadJSONContract(t *testing.T) {
	p := NewPayload("ws://192.168.1.5:9900/ws", "tok-abc-123")

	body, err := p.Marshal()
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}

	var m map[string]any
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("payload must be valid JSON: %v", err)
	}
	want := []string{"v", "url", "token", "ts_authkey"}
	if len(m) != len(want) {
		t.Fatalf("payload has %d keys %v, want exactly %v", len(m), m, want)
	}
	for _, k := range want {
		if _, ok := m[k]; !ok {
			t.Errorf("payload missing required key %q", k)
		}
	}

	var got Payload
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatalf("unmarshal payload: %v", err)
	}
	// Payload now contains a []string Candidates field (fix-pairing-candidates),
	// so equality must be reflect.DeepEqual rather than struct `==`.
	if !reflect.DeepEqual(got, p) {
		t.Errorf("round trip = %+v, want %+v", got, p)
	}
	if got.Version != PayloadVersion {
		t.Errorf("version = %d, want %d", got.Version, PayloadVersion)
	}
	if got.TSAuthKey != "" {
		t.Errorf("ts_authkey = %q, want reserved empty", got.TSAuthKey)
	}
}

// TestRenderQRStructure verifies the terminal QR is a deterministic square
// grid of half-block cells with both dark modules and light (quiet-zone)
// cells, one terminal line per two module rows.
func TestRenderQRStructure(t *testing.T) {
	content := `{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"tok123","ts_authkey":""}`
	qr, err := RenderQR(content)
	if err != nil {
		t.Fatalf("RenderQR: %v", err)
	}

	lines := strings.Split(strings.TrimRight(qr, "\n"), "\n")
	if len(lines) == 0 {
		t.Fatal("RenderQR must produce at least one line")
	}
	width := len([]rune(lines[0]))
	if width == 0 {
		t.Fatal("QR line must be non-empty")
	}
	hasDark, hasLight := false, false
	for _, ln := range lines {
		if len([]rune(ln)) != width {
			t.Fatalf("QR line width %d != %d (must be a square grid)", len([]rune(ln)), width)
		}
		for _, r := range ln {
			if r == '█' || r == '▀' || r == '▄' {
				hasDark = true
			}
			if r == ' ' {
				hasLight = true
			}
		}
	}
	if !hasDark {
		t.Error("QR must contain dark modules (█▀▄)")
	}
	if !hasLight {
		t.Error("QR must contain light cells (quiet zone)")
	}

	qr2, err := RenderQR(content)
	if err != nil {
		t.Fatalf("RenderQR(repeat): %v", err)
	}
	if qr2 != qr {
		t.Error("RenderQR must be deterministic for the same content")
	}
}

// TestLegalExitsCarryToken locks in the §9 exit contract: the QR payload and
// the rendered QR are the token's legal exits and must CONTAIN the token, so
// a future refactor that silently drops it from onboarding fails loudly here.
func TestLegalExitsCarryToken(t *testing.T) {
	token := "abc-legal-exit-token"
	p := NewPayload("ws://10.0.0.7:9900/ws", token)

	body, err := p.Marshal()
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}
	if !strings.Contains(string(body), token) {
		t.Error("QR payload must carry the token (legal exit)")
	}

	qr, err := RenderQR(string(body))
	if err != nil {
		t.Fatalf("RenderQR: %v", err)
	}
	if !strings.Contains(qr, "█") && !strings.Contains(qr, "▀") && !strings.Contains(qr, "▄") {
		t.Error("QR must render dark modules so the payload is scannable")
	}
}
