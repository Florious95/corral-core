package pairing

// tswire_test.go covers the pairing-side half of task feat-ts-wire (P0): the
// QR payload must carry the configured TS authkey (requirement 011
// pre-authorized distribution: scan once = pair + join tailnet), and the
// embedded node's tailnet address — invisible to interface probing, because a
// userspace tsnet node has no host NIC — must be injectable into the address
// set so it reaches both the QR candidates and the plain-text guide.
//
// Red tests first: written before the implementation, they fail against the
// pre-feature Onboarding (no TSAuthKey field / no WithTailnet helper).
//
// Security red line (docs/protocol.md §2.1 / FIELD ruling): the QR is the
// authkey's ONLY legal exit — the plain-text guide must never print it.

import (
	"bytes"
	"net"
	"strings"
	"testing"
)

// TestOnboardingPayloadCarriesTSAuthKey locks the 011 pre-authorization wire
// contract: the authkey the daemon was configured with rides the QR payload
// verbatim so a scan also joins the phone onto the tailnet.
func TestOnboardingPayloadCarriesTSAuthKey(t *testing.T) {
	o := Onboarding{Token: "tok-1", Port: "9900", TailnetEnabled: true, TSAuthKey: "tskey-auth-test-123"}
	addrs := []Address{{IP: net.ParseIP("192.168.1.5"), Kind: KindLAN}}

	p := onboardingPayload(o, addrs, "192.168.1.5")
	if p.TSAuthKey != "tskey-auth-test-123" {
		t.Fatalf("payload ts_authkey = %q, want the configured authkey", p.TSAuthKey)
	}
}

// TestOnboardingPayloadEmptyAuthKeyStaysEmpty asserts the degraded/no-key QR
// stays byte-compatible with the historical contract: empty in, empty out.
func TestOnboardingPayloadEmptyAuthKeyStaysEmpty(t *testing.T) {
	o := Onboarding{Token: "tok-1", Port: "9900"}
	addrs := []Address{{IP: net.ParseIP("192.168.1.5"), Kind: KindLAN}}

	if p := onboardingPayload(o, addrs, "192.168.1.5"); p.TSAuthKey != "" {
		t.Fatalf("payload ts_authkey = %q, want empty when no key configured", p.TSAuthKey)
	}
}

// TestGuideNeverPrintsAuthKeyPlaintext is the red-line test: the plain-text
// guide (everything outside the QR bitmap) must not contain the authkey. The
// QR art renders modules as half-block glyphs, so a plain substring match on
// the whole output is a valid detector for a plaintext leak.
func TestGuideNeverPrintsAuthKeyPlaintext(t *testing.T) {
	const key = "tskey-auth-SECRET-must-not-print"
	o := Onboarding{Token: "tok-1", Port: "9900", TailnetEnabled: true, TSAuthKey: key}
	addrs := []Address{
		{IP: net.ParseIP("192.168.1.5"), Kind: KindLAN},
		{IP: net.ParseIP("100.101.2.3"), Kind: KindTailnet},
	}

	var buf bytes.Buffer
	if err := PrintOnboardingAll(o, addrs, "192.168.1.5", &buf); err != nil {
		t.Fatalf("PrintOnboardingAll: %v", err)
	}
	if strings.Contains(buf.String(), key) {
		t.Fatal("guide output contains the TS authkey in plaintext — QR is its only legal exit (§2.1)")
	}
}

// TestWithTailnetAppendsAddress covers the tsnet injection seam: a userspace
// node's 100.x address is not a host NIC, so it must be explicitly appended
// to the probe result — classified as tailnet, placed before loopback.
func TestWithTailnetAppendsAddress(t *testing.T) {
	base := []Address{
		{IP: net.ParseIP("192.168.1.5"), Kind: KindLAN},
		{IP: net.ParseIP("127.0.0.1"), Kind: KindLoopback},
	}

	got := WithTailnet(base, net.ParseIP("100.101.2.3"))
	var tail []string
	for _, a := range got {
		if a.Kind == KindTailnet {
			tail = append(tail, a.IP.String())
		}
	}
	if len(tail) != 1 || tail[0] != "100.101.2.3" {
		t.Fatalf("tailnet addresses = %v, want exactly [100.101.2.3]", tail)
	}
	if len(got) != 3 || got[1].Kind != KindTailnet || got[2].Kind != KindLoopback {
		t.Fatalf("address order = %v, want LAN then tailnet then loopback", got)
	}
	// Candidate building consumes the merged set: the tailnet ws URL must ride
	// the QR candidates (goal: candidates 追加 tailnet 地址).
	cands := buildCandidates("192.168.1.5", "9900", got)
	want := "ws://100.101.2.3:9900/ws"
	found := false
	for _, c := range cands {
		if c == want {
			found = true
		}
	}
	if !found {
		t.Fatalf("candidates %v missing tailnet URL %s", cands, want)
	}
}

// TestWithTailnetDedupsExistingAddress: a host that also runs the Tailscale
// app may already expose the same 100.x on a TUN interface — the merge must
// not duplicate it.
func TestWithTailnetDedupsExistingAddress(t *testing.T) {
	base := []Address{
		{IP: net.ParseIP("192.168.1.5"), Kind: KindLAN},
		{IP: net.ParseIP("100.101.2.3"), Kind: KindTailnet},
	}

	got := WithTailnet(base, net.ParseIP("100.101.2.3"))
	count := 0
	for _, a := range got {
		if a.IP.String() == "100.101.2.3" {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("100.101.2.3 appears %d times after merge, want 1 (dedup)", count)
	}
}

// TestWithTailnetNilIPNoop: a nil/absent tailnet IP (degraded or Up not yet
// done) must leave the probe result untouched.
func TestWithTailnetNilIPNoop(t *testing.T) {
	base := []Address{{IP: net.ParseIP("192.168.1.5"), Kind: KindLAN}}
	if got := WithTailnet(base, nil); len(got) != len(base) {
		t.Fatalf("WithTailnet(nil) changed the set: %v", got)
	}
}
