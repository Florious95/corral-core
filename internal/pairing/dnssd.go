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
	conn *net.UDPConn
	adv  DNSAdvertisement
	stop chan struct{}
	once sync.Once
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
	conn, err := listenMDNS(adv.Addresses)
	if err != nil {
		return nil, fmt.Errorf("pairing: dns-sd listen: %w", err)
	}
	a := &Advertiser{conn: conn, adv: adv, stop: make(chan struct{})}
	go a.serve()
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
		_ = a.send(0) // TTL 0 goodbye; failure must not delay shutdown.
		err = a.conn.Close()
	})
	return err
}

func (a *Advertiser) serve() {
	buf := make([]byte, 1500)
	for {
		a.conn.SetReadDeadline(timeNow().Add(250 * time.Millisecond))
		n, _, err := a.conn.ReadFromUDP(buf)
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

func defaultListenMDNS(addrs []net.IP) (*net.UDPConn, error) {
	ifi := multicastInterface(addrs)
	conn, err := net.ListenMulticastUDP("udp4", ifi, &net.UDPAddr{IP: net.ParseIP(dnsMDNSHost), Port: dnsMDNSPort})
	if err != nil {
		return nil, err
	}
	if err := setMDNSSendOptions(conn); err != nil {
		_ = conn.Close()
		return nil, err
	}
	return conn, nil
}

func multicastInterface(addrs []net.IP) *net.Interface {
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
					return ifi
				}
			}
		}
	}
	return nil
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
	_, err := a.conn.WriteToUDP(packet, &net.UDPAddr{IP: net.ParseIP(dnsMDNSHost), Port: dnsMDNSPort})
	return err
}

func dnsQueryMatches(p []byte) bool {
	return strings.Contains(string(p), DNSServiceType) || strings.Contains(string(p), "_services._dns-sd._udp")
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
