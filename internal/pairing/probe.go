package pairing

// probe.go discovers the host addresses the Android app can reach the daemon
// on, to build the ws URL that rides in the QR. Silent failure is a red line:
// if no usable LAN address is found the caller gets an explicit degraded
// signal (loopback + warning) instead of an unreachable QR.

import (
	"fmt"
	"io"
	"net"
	"os"
	"sort"
)

// tailnetNet is the Tailscale CGNAT range (100.64.0.0/10): an interface address
// inside it is the host's tailnet address.
var tailnetNet = func() *net.IPNet {
	_, n, _ := net.ParseCIDR("100.64.0.0/10")
	return n
}()

// rfc2544Net is the RFC 2544 benchmark range (198.18.0.0/15). Proxy tools
// (Clash/Surge/… fake-IP modes) hand out addresses from this block to virtual
// TUN interfaces; a phone on the real LAN can never reach one, so it must be
// excluded from the candidate list (task fix-qr-host-detect, real 198.18.0.1).
var rfc2544Net = func() *net.IPNet {
	_, n, _ := net.ParseCIDR("198.18.0.0/15")
	return n
}()

// Address kind values.
const (
	// KindLAN marks a unicast IPv4 address the host exposes on its LAN: any IPv4
	// that is not loopback, link-local, RFC 2544, or tailnet (a public/global
	// unicast address is still KindLAN).
	KindLAN = "lan"
	// KindTailnet marks an address in the Tailscale CGNAT range (100.64.0.0/10).
	KindTailnet = "tailnet"
	// KindLoopback marks 127.0.0.1/::1.
	KindLoopback = "loopback"
)

// Address is one reachable address of the daemon host and how it is labelled
// to the user.
type Address struct {
	// IP is the host address (IPv4 or loopback).
	IP net.IP
	// Kind is one of KindLAN, KindTailnet, KindLoopback.
	Kind string
}

// classifyIP returns the address kind for one IP, or "" when the address must
// be skipped. Skipped blocks: link-local (169.254/16 + IPv6 fe80::/10), the
// RFC 2544 benchmark range (198.18.0.0/15 — proxy fake-IP TUNs live here, a
// phone can never reach them), and IPv6 (not yet paired over).
func classifyIP(ip net.IP) string {
	if ip.IsLoopback() {
		return KindLoopback
	}
	if ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
		return ""
	}
	if ip.To4() == nil {
		// Global IPv6 is a valid host address but the Android LAN pairing
		// targets IPv4/tailnet for now; skip to keep the guide focused.
		return ""
	}
	if rfc2544Net.Contains(ip) {
		// 198.18.0.0/15 is not link-local, so IsLinkLocal* cannot catch it;
		// exclude explicitly (task fix-qr-host-detect).
		return ""
	}
	if tailnetNet.Contains(ip) {
		return KindTailnet
	}
	return KindLAN
}

// DetectAddresses enumerates the host's usable unicast addresses in a
// deterministic order: LAN addresses (sorted), then tailnet addresses, then
// loopback. A loopback fallback is guaranteed even when the enumeration fails,
// so callers always have a last-resort URL.
// @contract
// @pre none
// @post 返回顺序 LAN（排序）→ tailnet（排序）→ loopback，且总是含 loopback
// @err none（接口枚举失败时降级为 loopback 而非报错）
// @inv none
func DetectAddresses() []Address {
	ifs, err := net.Interfaces()
	if err != nil {
		return loopbackOnly()
	}
	// Collect the live probe table (interface name + addresses), then delegate
	// the whole classification/ordering decision to detectAddresses. That seam
	// is what lets the sort/exclusion contract be tested against a fake
	// interface table instead of the real (variable) network.
	probes := make([]ifaceAddr, 0, len(ifs))
	for _, i := range ifs {
		if i.Flags&net.FlagUp == 0 {
			continue // a down interface is not reachable
		}
		addrs, err := i.Addrs()
		if err != nil {
			continue
		}
		probes = append(probes, ifaceAddr{name: i.Name, addrs: addrs})
	}
	return detectAddresses(probes)
}

// ifaceAddr is one probe unit: an interface name plus its addresses. The name
// matters because virtual-tunnel and NIC-naming rules (utun*/awdl*/bridge*…,
// en*) are part of the address-selection contract.
type ifaceAddr struct {
	name  string
	addrs []net.Addr
}

// detectAddresses classifies and orders a probe table the same way the live
// path does: LAN (sorted) then tailnet (sorted) then a guaranteed loopback
// fallback. It is a pure function over its input, so tests can drive it with a
// fake interface table reproducing a real machine snapshot.
func detectAddresses(probes []ifaceAddr) []Address {
	var lan, tail, loop []Address
	for _, p := range probes {
		for _, a := range p.addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			switch classifyIP(ipnet.IP) {
			case KindLAN:
				lan = append(lan, Address{IP: ipnet.IP, Kind: KindLAN})
			case KindTailnet:
				tail = append(tail, Address{IP: ipnet.IP, Kind: KindTailnet})
			case KindLoopback:
				loop = append(loop, Address{IP: ipnet.IP, Kind: KindLoopback})
			}
		}
	}
	sort.Slice(lan, func(i, j int) bool { return lan[i].IP.String() < lan[j].IP.String() })
	sort.Slice(tail, func(i, j int) bool { return tail[i].IP.String() < tail[j].IP.String() })

	if len(loop) == 0 {
		loop = append(loop, Address{IP: net.ParseIP("127.0.0.1"), Kind: KindLoopback})
	}
	return append(append(lan, tail...), loop...)
}

// loopbackOnly is the guaranteed fallback when enumeration itself fails: a
// caller always gets a last-resort (loopback) URL rather than nothing.
func loopbackOnly() []Address {
	return []Address{{IP: net.ParseIP("127.0.0.1"), Kind: KindLoopback}}
}

// defaultRouteSource returns the source IP the OS selects for outbound
// default-route traffic — i.e. the LAN interface most devices on the network
// can reach. It dials a UDP address only (a UDP connect sends no packets) to
// ask the routing table which local IP to use.
func defaultRouteSource() (net.IP, error) {
	conn, err := net.Dial("udp", "8.8.8.8:53")
	if err != nil {
		return nil, fmt.Errorf("pairing: default-route probe: %w", err)
	}
	defer conn.Close()
	if ua, ok := conn.LocalAddr().(*net.UDPAddr); ok {
		// The routing table can legitimately select a proxy fake-IP TUN as the
		// outbound source (the real-machine defect: 198.18.0.1 won). Reject an
		// address that classifyIP would skip, so the fallback ladder (which is
		// already exclusion-aware) picks a phone-reachable host.
		if classifyIP(ua.IP) == "" {
			return nil, fmt.Errorf("pairing: default-route probe: source %s is not a usable LAN address", ua.IP)
		}
		return ua.IP, nil
	}
	return nil, fmt.Errorf("pairing: default-route probe: unexpected local addr")
}

// pickPrimary chooses the single host the QR carries from a set of detected
// addresses: the first LAN address, else the first tailnet address, else
// loopback. It is the pure fallback ladder used when the default-route probe
// fails.
func pickPrimary(addrs []Address) string {
	for _, a := range addrs {
		if a.Kind == KindLAN {
			return a.IP.String()
		}
	}
	for _, a := range addrs {
		if a.Kind == KindTailnet {
			return a.IP.String()
		}
	}
	return "127.0.0.1"
}

// PrimaryHost returns the best host for the QR: an explicit override
// (AGENTMIRROR_HOST env), else the default-route source IP when discoverable,
// else the first LAN/tailnet address from the (exclusion-aware)
// DetectAddresses ladder, else loopback.
//
// Note: the -host flag itself is folded into config.Host by cmd/agentmirrord
// and delivered to the guide via printPairingGuide's hostOverride (main.go),
// which bypasses PrimaryHost — so PrimaryHost only honors the env override.
// @contract
// @pre none
// @post 返回非空 host：env 覆盖 > 默认路由源 IP > LAN/tailnet 首选 > "127.0.0.1"
// @err none（各自动探测失败静默降级到下一梯级）
// @inv none
func PrimaryHost() string {
	// An explicit override wins over every automatic probe: the phone is the
	// ground truth of what is reachable, and the user knows it best.
	if h := os.Getenv("AGENTMIRROR_HOST"); h != "" {
		return h
	}
	if ip, err := defaultRouteSource(); err == nil {
		return ip.String()
	}
	// Tail-recursive: recompute DetectAddresses rather than recursing on
	// PrimaryHost (which would re-run the env check and loop if a bug ever
	// left defaultRouteSource always failing).
	return pickPrimary(DetectAddresses())
}

// WSURL builds the WebSocket endpoint for one host:port pair.
func WSURL(host, port string) string {
	return "ws://" + net.JoinHostPort(host, port) + "/ws"
}

// buildCandidates returns the full candidate ws URL set for the QR payload
// (docs/protocol.md §2.1, task fix-pairing-candidates): the primary host first
// when it is a real (non-loopback) host — it may come from a -host/env override
// absent from the probe table, and must still lead — then every non-loopback
// detected address in detect order, deduplicated. Loopback is never a
// candidate: a phone can never reach 127.0.0.1. A loopback-only primary
// (degraded mode) yields an empty set, so that QR stays byte-identical to the
// pre-feature contract.
func buildCandidates(primary, port string, addrs []Address) []string {
	seen := make(map[string]bool, len(addrs)+1)
	out := make([]string, 0, len(addrs)+1)
	add := func(host string) {
		u := WSURL(host, port)
		if !seen[u] {
			seen[u] = true
			out = append(out, u)
		}
	}
	// The primary is a host string (IP or name); only a literal loopback IP is
	// the degraded case. A hostname is never loopback, so it is always added.
	if ip := net.ParseIP(primary); ip == nil || !ip.IsLoopback() {
		add(primary)
	}
	for _, a := range addrs {
		if a.Kind == KindLoopback {
			continue
		}
		add(a.IP.String())
	}
	return out
}

// onboardingPayload builds the QR payload the guide renders: the primary ws
// URL plus the host's full candidate set. Extracted as a seam so tests can
// assert the QR carries candidates without scanning the half-block art.
func onboardingPayload(o Onboarding, addrs []Address, primary string) Payload {
	p := NewPayload(WSURL(primary, o.Port), o.Token)
	p.Candidates = buildCandidates(primary, o.Port, addrs)
	// feat-ts-wire（011 预授权分发）：配置的 TS authkey 原样上 QR——扫码即同时
	// 完成配对+入网。QR 是 authkey 唯一合法出口（§2.1）；guide 明文区绝不打印。
	p.TSAuthKey = o.TSAuthKey
	return p
}

// WithTailnet merges the embedded tsnet node's tailnet IP into a probe result
// (task feat-ts-wire): a userspace node has no host NIC, so DetectAddresses
// cannot see its 100.x address — the daemon injects it here after Up, and the
// merged set feeds both the QR candidates and the plain-text guide. The
// address is deduplicated (the host may also run the Tailscale app whose TUN
// already exposes the same IP) and inserted just before the loopback fallback
// (LAN -> tailnet -> loopback order). A nil IP (degraded / v6-only) is a no-op.
// @contract
// @pre none
// @post ip 非 nil 且不在 addrs 时，按其 Kind 序插入一个 KindTailnet 的副本；ip 已在集内或 nil 则原样返回
// @err none（纯函数，不失败）
// @inv 返回值保持 LAN → tailnet → loopback 的地址序；ip 至多出现一次
func WithTailnet(addrs []Address, ip net.IP) []Address {
	if ip == nil {
		return addrs
	}
	for _, a := range addrs {
		if a.IP.Equal(ip) {
			return addrs
		}
	}
	// Keep the package-wide address contract LAN -> tailnet -> loopback. Live
	// DetectAddresses always appends loopback, so inserting at the end would put
	// an injected userspace-node address after the fallback.
	out := make([]Address, 0, len(addrs)+1)
	inserted := false
	for _, a := range addrs {
		if !inserted && a.Kind == KindLoopback {
			out = append(out, Address{IP: ip, Kind: KindTailnet})
			inserted = true
		}
		out = append(out, a)
	}
	if !inserted {
		out = append(out, Address{IP: ip, Kind: KindTailnet})
	}
	return out
}

// Onboarding carries what the daemon knows when it prints the pairing screen.
type Onboarding struct {
	// Token is the resolved pairing token to encode.
	Token string
	// Port is the listen port number (e.g. "9900"), shared by every URL.
	Port string
	// TailnetEnabled reports whether a tailnet listener is up, so the guide
	// can surface the tailnet address.
	TailnetEnabled bool
	// TSAuthKey is the configured Tailscale auth key destined for the QR's
	// ts_authkey field ONLY (011 pre-authorized distribution, feat-ts-wire).
	// Red line: it must never be rendered in the plain-text guide — the QR is
	// its only legal exit (docs/protocol.md §2.1, token-grade secret).
	TSAuthKey string
}

// PrintOnboarding writes the QR plus the plain-text connection guide to w
// (typically os.Stdout). It probes the host's addresses and delegates to
// printOnboarding; the QR and this guide are the token's two legal exits
// (docs/protocol.md §9) — the only places it may appear.
// @contract
// @pre w 非 nil（nil writer 将 panic）
// @post 向 w 写入 QR + 明文指引；无 LAN/tailnet 时带降级警告
// @err 载荷编组或 QR 编码失败返回包装错误
// @inv none
func PrintOnboarding(w io.Writer, o Onboarding) error {
	return printOnboarding(w, o, DetectAddresses(), PrimaryHost(), false)
}

// PrintOnboardingWith renders the guide for an injected address set and
// primary host instead of probing the machine. It is the seam that lets callers
// (and tests) exercise the degraded-warning path deterministically.
// @contract
// @pre w 非 nil（nil writer 将 panic）
// @post 按注入的 addrs/primary 渲染指引，不再探测本机
// @err 载荷编组或 QR 编码失败返回包装错误
// @inv none
func PrintOnboardingWith(o Onboarding, addrs []Address, primary string, w io.Writer) error {
	return printOnboarding(w, o, addrs, primary, false)
}

// PrintOnboardingAll renders the guide with every detected candidate address
// listed alongside the primary (task fix-qr-host-detect: when multiple usable
// hosts coexist, the QR carries the best one and the guide offers the rest for
// manual re-entry). It is the seam the cmd wiring calls when it wants the full
// candidate list shown.
// @contract
// @pre w 非 nil（nil writer 将 panic）
// @post 除主 URL 外，把每个非 loopback 候选地址列入明文指引（listAll 视图）
// @err 载荷编组或 QR 编码失败返回包装错误
// @inv none
func PrintOnboardingAll(o Onboarding, addrs []Address, primary string, w io.Writer) error {
	return printOnboarding(w, o, addrs, primary, true)
}

// printOnboarding renders the guide for a given address set and primary host.
// If no LAN/tailnet address was found it prints an explicit degraded warning
// instead of silently handing out an unreachable QR (knowledge-base red line).
// Splitting probe from render keeps that warning testable without the real
// network. When listAll is set, every candidate address is listed under the
// primary (the full-candidate guide); otherwise only tailnet addresses are
// listed after it (the legacy view).
func printOnboarding(w io.Writer, o Onboarding, addrs []Address, primary string, listAll bool) error {
	url := WSURL(primary, o.Port)

	// The QR payload now embeds the host's full candidate ws URL set so the App
	// auto-tries each on primary failure (task fix-pairing-candidates, P0).
	// A no-candidate (loopback-only degraded) payload stays byte-identical.
	body, err := onboardingPayload(o, addrs, primary).Marshal()
	if err != nil {
		return fmt.Errorf("pairing: marshal onboarding payload: %w", err)
	}
	qr, err := RenderQR(string(body))
	if err != nil {
		return fmt.Errorf("pairing: render onboarding qr: %w", err)
	}

	fmt.Fprintln(w, qr)
	fmt.Fprintln(w, "──────────────────────────────────────────────")
	fmt.Fprintln(w, "配对信息 · 用 App 扫码，或手填以下内容：")
	fmt.Fprintf(w, "  服务端 ws 地址 : %s\n", url)
	fmt.Fprintf(w, "  配对 token      : %s\n", o.Token)
	if listAll {
		// The QR carries only the primary host; the full candidate list lets a
		// user whose phone cannot reach it re-enter another address by hand.
		// Loopback is never offered as an alternative (it is the last-resort QR).
		fmt.Fprintln(w, "  其他可达地址（若上面的连不上，改用下列之一）：")
		for _, a := range addrs {
			if a.Kind == KindLoopback {
				continue
			}
			fmt.Fprintf(w, "    ws://%s:%s/ws   [%s]\n", a.IP.String(), o.Port, a.Kind)
		}
	}
	if o.TailnetEnabled {
		for _, a := range addrs {
			if a.Kind == KindTailnet {
				fmt.Fprintf(w, "  tailnet 地址   : ws://%s:%s/ws\n", a.IP.String(), o.Port)
			}
		}
	}
	if !hasReachableLAN(addrs) {
		fmt.Fprintln(w, "  ⚠ 未探测到 LAN/tailnet 地址：当前二维码只在手机可达主机回环时有效。")
		fmt.Fprintln(w, "    请检查网卡/网络；或显式配置 -token 后手填正确的服务端地址。")
	}
	return nil
}

// hasReachableLAN reports whether any detected address is usable from another
// device (LAN or tailnet), as opposed to loopback-only.
func hasReachableLAN(addrs []Address) bool {
	for _, a := range addrs {
		if a.Kind != KindLoopback {
			return true
		}
	}
	return false
}
