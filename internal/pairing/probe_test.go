package pairing

// probe_test.go covers LAN/tailnet address discovery: the classification rules
// (loopback / tailnet CGNAT / LAN / skip), the primary-host fallback ladder,
// and the ws URL builder.

import (
	"net"
	"strings"
	"testing"
)

// TestClassifyIP pins the classification rules for the address probe. The
// 100.64.0.0/10 range is Tailscale's CGNAT space — any interface address in it
// is the host's tailnet address; link-local and (for now) global IPv6 are
// skipped so the guide never offers an unreachable target.
func TestClassifyIP(t *testing.T) {
	cases := []struct {
		ip   string
		want string
	}{
		{"127.0.0.1", KindLoopback},
		{"::1", KindLoopback},
		{"192.168.1.5", KindLAN},
		{"10.0.0.7", KindLAN},
		{"172.16.3.9", KindLAN},
		{"8.8.8.8", KindLAN}, // non-private unicast IPv4 is still a host address
		{"100.101.20.3", KindTailnet},
		{"100.64.0.1", KindTailnet},      // /10 lower bound
		{"100.127.255.254", KindTailnet}, // /10 upper bound
		{"100.63.255.255", KindLAN},      // just below the /10
		{"100.128.0.1", KindLAN},         // just above the /10
		{"169.254.1.2", ""},              // link-local: skip
		{"fe80::1", ""},                  // IPv6 link-local: skip
		{"2001:db8::1", ""},              // global IPv6: not yet paired over
	}
	for _, c := range cases {
		if got := classifyIP(net.ParseIP(c.ip)); got != c.want {
			t.Errorf("classifyIP(%s) = %q, want %q", c.ip, got, c.want)
		}
	}
}

// TestPickPrimary verifies the deterministic fallback ladder for the QR's
// single host: first LAN address, else first tailnet address, else loopback.
func TestPickPrimary(t *testing.T) {
	// DetectAddresses returns addresses in deterministic order (LAN sorted
	// first); pickPrimary honors that order, so feed it the sorted sequence.
	addrs := []Address{
		{IP: net.ParseIP("10.0.0.7"), Kind: KindLAN},
		{IP: net.ParseIP("192.168.1.5"), Kind: KindLAN},
		{IP: net.ParseIP("100.101.2.3"), Kind: KindTailnet},
		{IP: net.ParseIP("127.0.0.1"), Kind: KindLoopback},
	}
	if got := pickPrimary(addrs); got != "10.0.0.7" {
		t.Errorf("pickPrimary = %q, want 10.0.0.7 (first LAN)", got)
	}

	tailnetOnly := []Address{{IP: net.ParseIP("100.101.2.3"), Kind: KindTailnet}}
	if got := pickPrimary(tailnetOnly); got != "100.101.2.3" {
		t.Errorf("pickPrimary(tailnet-only) = %q, want the tailnet address", got)
	}

	if got := pickPrimary(nil); got != "127.0.0.1" {
		t.Errorf("pickPrimary(empty) = %q, want loopback fallback", got)
	}
}

// TestWSURL builds the ws endpoint the QR carries.
func TestWSURL(t *testing.T) {
	cases := []struct{ host, port, want string }{
		{"192.168.1.5", "9900", "ws://192.168.1.5:9900/ws"},
		{"100.101.2.3", "9900", "ws://100.101.2.3:9900/ws"},
	}
	for _, c := range cases {
		if got := WSURL(c.host, c.port); got != c.want {
			t.Errorf("WSURL(%s, %s) = %q, want %q", c.host, c.port, got, c.want)
		}
	}
}

// TestDetectAddressesAlwaysHasLoopback guarantees DetectAddresses never comes
// back empty: even a host with no usable network (or a probe failure) yields a
// loopback fallback so callers always have a last-resort URL.
func TestDetectAddressesAlwaysHasLoopback(t *testing.T) {
	addrs := DetectAddresses()
	hasLoop := false
	for _, a := range addrs {
		if a.Kind == KindLoopback {
			hasLoop = true
		}
	}
	if !hasLoop {
		t.Errorf("DetectAddresses = %+v, must include a loopback fallback", addrs)
	}
}

// TestHasReachableLAN is the degraded-path red test for the guide's warning:
// a loopback-only address set must report unreachable (so the guide emits the
// explicit degraded warning instead of a silently useless QR), while any
// LAN/tailnet address means reachable.
func TestHasReachableLAN(t *testing.T) {
	loopOnly := []Address{{IP: net.ParseIP("127.0.0.1"), Kind: KindLoopback}}
	if hasReachableLAN(loopOnly) {
		t.Error("loopback-only must be unreachable (degraded)")
	}
	mixed := []Address{
		{IP: net.ParseIP("127.0.0.1"), Kind: KindLoopback},
		{IP: net.ParseIP("192.168.1.5"), Kind: KindLAN},
	}
	if !hasReachableLAN(mixed) {
		t.Error("LAN present must be reachable (no warning)")
	}
	tailOnly := []Address{{IP: net.ParseIP("100.101.2.3"), Kind: KindTailnet}}
	if !hasReachableLAN(tailOnly) {
		t.Error("tailnet present must be reachable (no warning)")
	}
}

// TestPrintOnboardingDegradedWarns verifies the silent-failure red line at the
// render seam: with a loopback-only address set the guide MUST carry an
// explicit degraded warning (not a silently unreachable QR); with a LAN
// address present it MUST NOT.
func TestPrintOnboardingDegradedWarns(t *testing.T) {
	var buf strings.Builder
	loopOnly := []Address{{IP: net.ParseIP("127.0.0.1"), Kind: KindLoopback}}
	if err := printOnboarding(&buf, Onboarding{Token: "tok-x", Port: "9900"}, loopOnly, "127.0.0.1"); err != nil {
		t.Fatalf("printOnboarding(degraded): %v", err)
	}
	if !strings.Contains(buf.String(), "⚠") {
		t.Errorf("degraded guide must warn, got:\n%s", buf.String())
	}

	buf.Reset()
	mixed := []Address{
		{IP: net.ParseIP("127.0.0.1"), Kind: KindLoopback},
		{IP: net.ParseIP("192.168.1.5"), Kind: KindLAN},
	}
	if err := printOnboarding(&buf, Onboarding{Token: "tok-x", Port: "9900"}, mixed, "192.168.1.5"); err != nil {
		t.Fatalf("printOnboarding(healthy): %v", err)
	}
	if strings.Contains(buf.String(), "⚠") {
		t.Errorf("healthy guide must not warn, got:\n%s", buf.String())
	}
}
