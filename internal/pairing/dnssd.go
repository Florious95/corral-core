package pairing

// dnssd.go is a deliberately small DNS-SD advertiser for the LAN discovery
// contract. It advertises only the public host id; pairing tokens and TS keys
// never enter a DNS packet. Failure to bind/join mDNS is returned to the
// caller so startup can log a safe diagnostic while keeping the LAN listener.

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	DNSServiceType = "_agentmirror._tcp"
	dnsMDNSHost    = "224.0.0.251"
	dnsMDNSPort    = 5353
	// dnsLiveTTL is the existing live-record TTL already used by package tests.
	// RFC 6762 treats TTL 0 as goodbye; announce and query responses must not use it.
	dnsLiveTTL uint32 = 120
)

type DNSAdvertisement struct {
	HostID    string
	Port      int
	Addresses []net.IP
}

func (a DNSAdvertisement) serviceName() string {
	return a.HostID + "." + DNSServiceType + ".local."
}

// TXT returns the complete public TXT set. The id is the only attribute.
func (a DNSAdvertisement) TXT() []string { return []string{"id=" + a.HostID} }

// Advertiser owns a best-effort mDNS responder. Close releases its socket and
// sends a goodbye packet when possible.
type Advertiser struct {
	conns []*net.UDPConn
	adv   DNSAdvertisement
	stop  chan struct{}
	once  sync.Once
}

// RegisterDNSService starts an mDNS advertiser. It validates all public data
// before touching the socket and never returns an error that contains a token
// or auth key (the inputs do not include either).
func RegisterDNSService(adv DNSAdvertisement) (*Advertiser, error) {
	if !ValidHostID(adv.HostID) {
		return nil, errors.New("pairing: invalid dns-sd host id")
	}
	if adv.Port < 1 || adv.Port > 65535 {
		return nil, errors.New("pairing: invalid dns-sd port")
	}
	conns, err := listenMDNS(adv.Addresses)
	if err != nil {
		return nil, fmt.Errorf("pairing: dns-sd listen: %w", err)
	}
	a := &Advertiser{conns: conns, adv: adv, stop: make(chan struct{})}
	for _, conn := range conns {
		go a.serve(conn)
	}
	// Announce once immediately; subsequent query responses keep discovery
	// working on networks that suppress unsolicited multicast.
	_ = a.send(dnsLiveTTL)
	return a, nil
}

func (a *Advertiser) Close() error {
	if a == nil {
		return nil
	}
	var err error
	a.once.Do(func() {
		close(a.stop)
		// TTL 0 goodbye; failure must not delay shutdown.
		_ = a.send(0)
		for _, conn := range a.conns {
			if closeErr := conn.Close(); err == nil {
				err = closeErr
			}
		}
	})
	return err
}

func (a *Advertiser) serve(conn *net.UDPConn) {
	buf := make([]byte, 1500)
	for {
		conn.SetReadDeadline(timeNow().Add(250 * time.Millisecond))
		n, _, err := conn.ReadFromUDP(buf)
		if err == nil && dnsQueryMatches(buf[:n]) {
			_ = a.send(dnsLiveTTL)
		}
		select {
		case <-a.stop:
			return
		default:
		}
	}
}

// timeNow is a seam only for deterministic deadline behavior in package tests.
var timeNow = now

func now() time.Time { return time.Now() }

// listenMDNS is a seam so package tests can drive RegisterDNSService/serve/Close
// without binding host UDP 5353. Production joins the LAN multicast interface.
var listenMDNS = defaultListenMDNS

func defaultListenMDNS(addrs []net.IP) ([]*net.UDPConn, error) {
	interfaces := multicastInterfaces(addrs)
	// With no concrete LAN address, retain the platform's default multicast
	// interface as a best-effort fallback.
	if len(interfaces) == 0 {
		interfaces = []*net.Interface{nil}
	}
	conns := make([]*net.UDPConn, 0, len(interfaces))
	for _, ifi := range interfaces {
		conn, err := net.ListenMulticastUDP("udp4", ifi, &net.UDPAddr{IP: net.ParseIP(dnsMDNSHost), Port: dnsMDNSPort})
		if err != nil {
			for _, opened := range conns {
				_ = opened.Close()
			}
			return nil, err
		}
		if err := setMDNSSendOptions(conn); err != nil {
			_ = conn.Close()
			for _, opened := range conns {
				_ = opened.Close()
			}
			return nil, err
		}
		conns = append(conns, conn)
	}
	return conns, nil
}

func multicastInterfaces(addrs []net.IP) []*net.Interface {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	want := map[string]struct{}{}
	for _, ip := range addrs {
		ip4 := ip.To4()
		if ip4 == nil || ip4.IsLoopback() {
			continue
		}
		want[ip4.String()] = struct{}{}
	}
	if len(want) == 0 {
		return nil
	}
	matched := make([]*net.Interface, 0, len(ifaces))
	seen := make(map[int]struct{})
	for i := range ifaces {
		ifi := &ifaces[i]
		if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagMulticast == 0 {
			continue
		}
		got, err := ifi.Addrs()
		if err != nil {
			continue
		}
		for _, a := range got {
			var ip net.IP
			switch v := a.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip4 := ip.To4(); ip4 != nil {
				if _, ok := want[ip4.String()]; ok {
					if _, duplicate := seen[ifi.Index]; !duplicate {
						seen[ifi.Index] = struct{}{}
						matched = append(matched, ifi)
					}
					break
				}
			}
		}
	}
	return matched
}

// multicastInterface preserves the old single-interface seam for callers that
// only need to inspect the preferred interface. Production registration uses
// multicastInterfaces so every advertised LAN address receives mDNS traffic.
func multicastInterface(addrs []net.IP) *net.Interface {
	interfaces := multicastInterfaces(addrs)
	if len(interfaces) == 0 {
		return nil
	}
	return interfaces[0]
}

func setMDNSSendOptions(conn *net.UDPConn) error {
	rc, err := conn.SyscallConn()
	if err != nil {
		return err
	}
	var opErr error
	if err := rc.Control(func(fd uintptr) {
		opErr = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IP, syscall.IP_MULTICAST_LOOP, 1)
		if opErr != nil {
			return
		}
		opErr = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IP, syscall.IP_MULTICAST_TTL, 255)
	}); err != nil {
		return err
	}
	return opErr
}

// recordDNSPacket, when set, observes the exact bytes send() is about to
// transmit. Production leaves it nil.
var recordDNSPacket func([]byte)

func (a *Advertiser) send(ttl uint32) error {
	packet := dnsPacket(a.adv, ttl)
	if recordDNSPacket != nil {
		recordDNSPacket(packet)
	}
	var firstErr error
	for _, conn := range a.conns {
		if _, err := conn.WriteToUDP(packet, &net.UDPAddr{IP: net.ParseIP(dnsMDNSHost), Port: dnsMDNSPort}); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

func dnsQueryMatches(p []byte) bool {
	// Keep accepting the tiny text seam used by package tests, but parse real
	// DNS-SD packets by labels: a wire question stores each label with a length
	// byte, so `_agentmirror._tcp` never appears as one contiguous substring.
	if len(p) < 12 {
		return strings.Contains(string(p), DNSServiceType) || strings.Contains(string(p), "_services._dns-sd._udp")
	}
	questions := int(binary.BigEndian.Uint16(p[4:6]))
	if questions > 0 {
		off := 12
		for i := 0; i < questions; i++ {
			name, next, ok := readDNSQuestionName(p, off)
			if !ok || next+4 > len(p) {
				break
			}
			if name == DNSServiceType+".local" || name == "_services._dns-sd._udp.local" {
				return true
			}
			off = next + 4 // QTYPE + QCLASS
		}
	}
	// Compatibility seam for the package's pre-wire tests and callers that
	// provide only the textual service name rather than a DNS packet.
	return strings.Contains(string(p), DNSServiceType) || strings.Contains(string(p), "_services._dns-sd._udp")
}

// readDNSQuestionName decodes one DNS name and returns the offset immediately
// after its question-name encoding. mDNS questions normally use labels, but
// handling compression pointers makes the matcher safe for valid DNS packets
// emitted by generic discovery libraries as well.
func readDNSQuestionName(p []byte, off int) (string, int, bool) {
	labels := make([]string, 0, 4)
	pos := off
	next := off
	jumped := false
	visited := make(map[int]struct{})
	for {
		if pos >= len(p) {
			return "", 0, false
		}
		n := int(p[pos])
		switch {
		case n == 0:
			if !jumped {
				next = pos + 1
			}
			return strings.Join(labels, "."), next, true
		case n&0xc0 == 0xc0:
			if pos+1 >= len(p) {
				return "", 0, false
			}
			ptr := (n&0x3f)<<8 | int(p[pos+1])
			if ptr >= len(p) {
				return "", 0, false
			}
			if _, seen := visited[ptr]; seen {
				return "", 0, false
			}
			visited[ptr] = struct{}{}
			if !jumped {
				next = pos + 2
				jumped = true
			}
			pos = ptr
		case n&0xc0 != 0 || n > 63 || pos+1+n > len(p):
			return "", 0, false
		default:
			labels = append(labels, string(p[pos+1:pos+1+n]))
			pos += 1 + n
		}
	}
}

func dnsPacket(a DNSAdvertisement, ttl uint32) []byte {
	service := a.serviceName()
	target := a.HostID + ".local."
	var b dnsBuilder
	b.u16(0)
	b.u16(0x8400)
	b.u16(0)
	answerCount := uint16(3)
	for _, address := range a.Addresses {
		if ip := address.To4(); ip != nil && !ip.IsLoopback() {
			answerCount++
		}
	}
	b.u16(answerCount)
	b.u16(0) // NSCOUNT
	b.u16(0) // ARCOUNT
	b.name(DNSServiceType + ".local.")
	b.u16(12) // PTR
	b.u16(1)
	b.u32(ttl)
	var ptr dnsBuilder
	ptr.name(service)
	b.bytesWithLen(ptr.data())
	b.name(service)
	b.u16(33) // SRV
	b.u16(1)
	b.u32(ttl)
	var srv dnsBuilder
	srv.u16(0)
	srv.u16(0)
	srv.u16(uint16(a.Port))
	srv.name(target)
	b.bytesWithLen(srv.data())
	b.name(service)
	b.u16(16) // TXT
	b.u16(1)
	b.u32(ttl)
	txt := []byte("id=" + a.HostID)
	b.u16(uint16(len(txt) + 1))
	b.u8(byte(len(txt)))
	b.appendBytes(txt)
	for _, address := range a.Addresses {
		ip := address.To4()
		if ip == nil || ip.IsLoopback() {
			continue
		}
		b.name(target)
		b.u16(1) // A
		b.u16(1)
		b.u32(ttl)
		b.u16(4)
		b.appendBytes(ip)
	}
	return b.data()
}

type dnsBuilder struct{ b []byte }

func (d *dnsBuilder) u8(v byte) { d.b = append(d.b, v) }
func (d *dnsBuilder) u16(v uint16) {
	var x [2]byte
	binary.BigEndian.PutUint16(x[:], v)
	d.b = append(d.b, x[:]...)
}
func (d *dnsBuilder) u32(v uint32) {
	var x [4]byte
	binary.BigEndian.PutUint32(x[:], v)
	d.b = append(d.b, x[:]...)
}
func (d *dnsBuilder) appendBytes(v []byte)  { d.b = append(d.b, v...) }
func (d *dnsBuilder) bytesWithLen(v []byte) { d.u16(uint16(len(v))); d.appendBytes(v) }
func (d *dnsBuilder) name(name string) {
	for _, label := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		if label == "" || len(label) > 63 {
			return
		}
		d.u8(byte(len(label)))
		d.appendBytes([]byte(label))
	}
	d.u8(0)
}
func (d *dnsBuilder) data() []byte { return d.b }
