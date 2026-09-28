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
		{"198.18.0.1", ""},               // RFC 2544 benchmark range: proxy fake-IP TUN, skip
		{"198.19.255.255", ""},           // RFC 2544 /15 upper bound: skip
		{"198.17.255.255", KindLAN},      // just below the /15: still a real host address
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

// fakeAddr builds a *net.IPNet probe entry from a CIDR string, keeping the
// host IP from the address portion (ParseCIDR normalizes IP to the network
// address; the probe table must mirror the host's actual interface address).
func fakeAddr(cidr string) net.Addr {
	ip, n, err := net.ParseCIDR(cidr)
	if err != nil {
		panic(err)
	}
	// A /32 mask keeps the exact IP while still presenting as an *IPNet.
	return &net.IPNet{IP: ip, Mask: n.Mask}
}

// fakeProbe builds one probe unit (interface name + addresses) from CIDRs.
func fakeProbe(name string, cidrs ...string) ifaceAddr {
	addrs := make([]net.Addr, 0, len(cidrs))
	for _, c := range cidrs {
		addrs = append(addrs, fakeAddr(c))
	}
	return ifaceAddr{name: name, addrs: addrs}
}

// probeSet is the injected live address table: en* = physical NIC, utun/tun/
// tap/awdl/llw/bridge = virtual tunnel/group names, lo0 = loopback.
var probeSet = []ifaceAddr{
	fakeProbe("en0", "192.168.31.116/24", "fe80::1e0a:3a00:1/64"), // real LAN
	fakeProbe("en1", "10.20.55.20/24"),                            // real LAN (second NIC)
	fakeProbe("utun3", "198.18.0.1/15"),                           // proxy fake-IP TUN (real 198.18 defect)
	fakeProbe("bridge0", "169.254.27.197/16"),                     // link-local
	fakeProbe("awdl0", "fe80::100/64"),                            // AWDL, IPv6 only
	fakeProbe("lo0", "127.0.0.1/8"),
}

// TestDetectAddressesFakeIfacesExcludesVirtualTunnels is the red test for the
// QR host-detection defect (task fix-qr-host-detect). The live machine carries
// RFC1918 addresses on real NICs (en0/en1) plus a proxy fake-IP TUN
// (utun3 = 198.18.0.1, RFC2544 benchmark range) and a link-local bridge0. The
// defect was that 198.18.0.1 won primary and the QR pointed at an unreachable
// host. The contract now is: RFC1918 physical-NIC addresses rank first, the
// proxy TUN and link-local must never appear in the candidate list.
func TestDetectAddressesFakeIfacesExcludesVirtualTunnels(t *testing.T) {
	addrs := detectAddresses(probeSet)

	var ips []string
	for _, a := range addrs {
		ips = append(ips, a.IP.String())
	}
	wantFirstTwo := []string{"10.20.55.20", "192.168.31.116"}
	for i, want := range wantFirstTwo {
		if len(addrs) < i+1 || addrs[i].IP.String() != want {
			t.Errorf("candidate[%d] = %v, want %s (RFC1918 real NIC must lead); full list %v",
				i, ips, want, ips)
		}
	}
	for _, banned := range []string{"198.18.0.1", "169.254.27.197"} {
		for _, a := range addrs {
			if a.IP.String() == banned {
				t.Errorf("candidate list must exclude %s (proxy TUN / link-local), got %v", banned, ips)
			}
		}
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
	if err := printOnboarding(&buf, Onboarding{Token: "tok-x", Port: "9900"}, loopOnly, "127.0.0.1", false); err != nil {
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
	if err := printOnboarding(&buf, Onboarding{Token: "tok-x", Port: "9900"}, mixed, "192.168.1.5", false); err != nil {
		t.Fatalf("printOnboarding(healthy): %v", err)
	}
	if strings.Contains(buf.String(), "⚠") {
		t.Errorf("healthy guide must not warn, got:\n%s", buf.String())
	}
}
