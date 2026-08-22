package pairing

// candidates_test.go covers the QR payload candidates field (task
// fix-pairing-candidates, P0): a QR carries the host's full candidate ws URL
// set so the App auto-tries each one when the primary is unreachable, instead
// of betting on a single primary address the phone cannot reach. Red tests
// first: this file is written before the implementation and must fail red
// against the pre-feature Payload (no Candidates field / no buildCandidates).
//
// Wire contract (docs/protocol.md §2.1):
//   - candidates is OPTIONAL and forward compatible: an absent candidate set
//     must not even emit the JSON key (a no-candidate QR stays byte-identical
//     to the pre-feature contract, no version bump);
//   - candidates carries the same host's other addresses (LAN + tailnet),
//     never loopback — a phone can never reach 127.0.0.1;
//   - the primary host leads the set when it is a real (non-loopback) host,
//     even when it came from a -host/env override absent from the probe table.

import (
	"encoding/json"
	"net"
	"reflect"
	"strings"
	"testing"
)

// TestPayloadCandidatesJSONContract locks the wire shape: candidates round-trips
// and is preserved by Marshal/Unmarshal, primary first.
func TestPayloadCandidatesJSONContract(t *testing.T) {
	want := []string{
		"ws://10.20.55.20:9900/ws",
		"ws://192.168.31.116:9900/ws",
		"ws://100.101.2.3:9900/ws",
	}
	p := NewPayloadWithCandidates("ws://10.20.55.20:9900/ws", "tok-1", want)

	body, err := p.Marshal()
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}
	var m map[string]any
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("payload must be valid JSON: %v", err)
	}
	cands, ok := m["candidates"]
	if !ok {
		t.Fatalf("payload with candidates must carry the candidates key, got %v", m)
	}
	arr, ok := cands.([]any)
	if !ok || len(arr) != len(want) {
		t.Fatalf("candidates = %v, want array of %d", cands, len(want))
	}

	var got Payload
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatalf("unmarshal payload: %v", err)
	}
	if !reflect.DeepEqual(got.Candidates, want) {
		t.Errorf("round-trip candidates = %v, want %v", got.Candidates, want)
	}
	if got.URL != "ws://10.20.55.20:9900/ws" {
		t.Errorf("url = %q, want the primary", got.URL)
	}
}

// TestPayloadNoCandidatesOmitsKey is the forward-compat red line: a payload
// without candidates must not emit the candidates key at all, so old QRs (and
// old App bytes) behave identically (docs/protocol.md §2.1).
func TestPayloadNoCandidatesOmitsKey(t *testing.T) {
	body, err := NewPayload("ws://192.168.1.5:9900/ws", "tok-x").Marshal()
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}
	if strings.Contains(string(body), "candidates") {
		t.Errorf("no-candidate payload must omit the candidates key, got %s", body)
	}
}

// TestBuildCandidatesExcludesLoopbackAndLeadsPrimary verifies the candidate
// set builder: primary first, then detected LAN/tailnet in detect order,
// loopback never offered.
func TestBuildCandidatesExcludesLoopbackAndLeadsPrimary(t *testing.T) {
	addrs := []Address{
		{IP: net.ParseIP("10.20.55.20"), Kind: KindLAN},
		{IP: net.ParseIP("192.168.31.116"), Kind: KindLAN},
		{IP: net.ParseIP("100.101.2.3"), Kind: KindTailnet},
		{IP: net.ParseIP("127.0.0.1"), Kind: KindLoopback},
	}
	got := buildCandidates("10.20.55.20", "9900", addrs)
	want := []string{
		"ws://10.20.55.20:9900/ws",
		"ws://192.168.31.116:9900/ws",
		"ws://100.101.2.3:9900/ws",
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("buildCandidates = %v, want %v", got, want)
	}
}

// TestBuildCandidatesHostOverridePrepended: the -host override may name a host
// absent from the probe table (e.g. emulator NAT 10.0.2.2); it must still lead
// the candidate list so the QR's primary and the candidates agree.
func TestBuildCandidatesHostOverridePrepended(t *testing.T) {
	addrs := []Address{{IP: net.ParseIP("192.168.31.116"), Kind: KindLAN}}
	got := buildCandidates("10.0.2.2", "9900", addrs)
	want := []string{
		"ws://10.0.2.2:9900/ws",
		"ws://192.168.31.116:9900/ws",
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("buildCandidates(override) = %v, want %v", got, want)
	}
}

// TestBuildCandidatesDegradedLoopbackEmpty: when the primary itself is loopback
// (no LAN/tailnet detected), candidates is empty — the App only tries the
// primary and the degraded path stays byte-identical to the pre-feature QR.
func TestBuildCandidatesDegradedLoopbackEmpty(t *testing.T) {
	addrs := []Address{{IP: net.ParseIP("127.0.0.1"), Kind: KindLoopback}}
	got := buildCandidates("127.0.0.1", "9900", addrs)
	if len(got) != 0 {
		t.Errorf("degraded loopback primary must yield no candidates, got %v", got)
	}
}

// TestOnboardingPayloadCarriesCandidates locks the render seam: the payload the
// guide renders must embed the full candidate set, so scanning the QR gives the
// App the addresses to auto-try (FIELD.md walkthrough: -host 指错误地址但
// candidates 含正确项 → 自动逐试成功进列表).
func TestOnboardingPayloadCarriesCandidates(t *testing.T) {
	addrs := []Address{
		{IP: net.ParseIP("10.20.55.20"), Kind: KindLAN},
		{IP: net.ParseIP("192.168.31.116"), Kind: KindLAN},
	}
	p := onboardingPayload(Onboarding{Token: "tok-x", Port: "9900"}, addrs, "10.20.55.20")
	want := []string{
		"ws://10.20.55.20:9900/ws",
		"ws://192.168.31.116:9900/ws",
	}
	if !reflect.DeepEqual(p.Candidates, want) {
		t.Errorf("onboarding payload candidates = %v, want %v", p.Candidates, want)
	}
	if p.Token != "tok-x" {
		t.Errorf("payload token = %q, want tok-x", p.Token)
	}
}
