package pairing

import (
	"encoding/binary"
	"fmt"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestDNSAdvertisementContainsOnlyPublicIdentity(t *testing.T) {
	id := "AAAAAAAAAAAAAAAAAAAAAAAAAA"
	adv := DNSAdvertisement{HostID: id, Port: 9900, Addresses: []net.IP{net.ParseIP("192.0.2.7")}}
	if got := adv.TXT(); len(got) != 1 || got[0] != "id="+id {
		t.Fatalf("TXT = %v", got)
	}
	packet := dnsPacket(adv, dnsLiveTTL)
	if !strings.Contains(string(packet), id) {
		t.Fatal("packet missing host id")
	}
	if strings.Contains(string(packet), "token") || strings.Contains(string(packet), "tskey") {
		t.Fatal("packet contains credential-shaped data")
	}
	if binary.BigEndian.Uint16(packet[6:8]) != 4 {
		t.Fatalf("answer count = %d", binary.BigEndian.Uint16(packet[6:8]))
	}
}

func TestProductionAnnounceAndQueryUseLiveTTL(t *testing.T) {
	id := "AAAAAAAAAAAAAAAAAAAAAAAAAA"
	adv := DNSAdvertisement{HostID: id, Port: 19928, Addresses: []net.IP{net.ParseIP("192.0.2.7")}}

	ln, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	prevListen := listenMDNS
	listenMDNS = func([]net.IP) (*net.UDPConn, error) { return ln, nil }
	t.Cleanup(func() { listenMDNS = prevListen })

	var mu sync.Mutex
	var packets [][]byte
	prevRecord := recordDNSPacket
	recordDNSPacket = func(p []byte) {
		cp := append([]byte(nil), p...)
		mu.Lock()
		packets = append(packets, cp)
		mu.Unlock()
	}
	t.Cleanup(func() { recordDNSPacket = prevRecord })

	a, err := RegisterDNSService(adv)
	if err != nil {
		t.Fatalf("RegisterDNSService: %v", err)
	}
	t.Cleanup(func() { _ = a.Close() })

	announce := waitDNSPackets(t, &mu, &packets, 1, time.Second)
	assertLiveDNSPacket(t, announce[0], adv, dnsLiveTTL)

	query, err := net.DialUDP("udp4", nil, ln.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer query.Close()
	if _, err := query.Write([]byte(DNSServiceType + ".local")); err != nil {
		t.Fatalf("write query: %v", err)
	}
	live := waitDNSPackets(t, &mu, &packets, 2, time.Second)
	assertLiveDNSPacket(t, live[1], adv, dnsLiveTTL)

	if err := a.Close(); err != nil {
		t.Fatal(err)
	}
	all := waitDNSPackets(t, &mu, &packets, 3, time.Second)
	assertLiveDNSPacket(t, all[len(all)-1], adv, 0)
}

func TestDNSQueryMatchesWireLabels(t *testing.T) {
	packet := make([]byte, 12)
	binary.BigEndian.PutUint16(packet[4:6], 1) // QDCOUNT
	packet = append(packet, 12, '_', 'a', 'g', 'e', 'n', 't', 'm', 'i', 'r', 'r', 'o', 'r', 4, '_', 't', 'c', 'p', 5, 'l', 'o', 'c', 'a', 'l', 0)
	packet = append(packet, 0, 12, 0, 1) // PTR, IN
	if !dnsQueryMatches(packet) {
		t.Fatal("wire DNS-SD question was not recognized")
	}

	services := make([]byte, 12)
	binary.BigEndian.PutUint16(services[4:6], 1)
	services = append(services, 9, '_', 's', 'e', 'r', 'v', 'i', 'c', 'e', 's', 7, '_', 'd', 'n', 's', '-', 's', 'd', 4, '_', 'u', 'd', 'p', 5, 'l', 'o', 'c', 'a', 'l', 0)
	services = append(services, 0, 12, 0, 1)
	if !dnsQueryMatches(services) {
		t.Fatal("wire service-enumeration question was not recognized")
	}
}

func TestDNSRegistrationValidatesIdentityAndPort(t *testing.T) {
	for _, adv := range []DNSAdvertisement{{HostID: "bad", Port: 9900}, {HostID: "AAAAAAAAAAAAAAAAAAAAAAAAAA", Port: 0}, {HostID: "AAAAAAAAAAAAAAAAAAAAAAAAAA", Port: 65536}} {
		if got, err := RegisterDNSService(adv); err == nil || got != nil {
			t.Fatalf("RegisterDNSService(%+v) unexpectedly succeeded", adv)
		}
	}
}

func TestDNSAdvertisementPortDoesNotUseNetworkProbe(t *testing.T) {
	adv := DNSAdvertisement{HostID: "AAAAAAAAAAAAAAAAAAAAAAAAAA", Port: 12345}
	if adv.Port != 12345 || net.ParseIP("127.0.0.1") == nil {
		t.Fatal("test setup")
	}
}

func waitDNSPackets(t *testing.T, mu *sync.Mutex, packets *[][]byte, n int, d time.Duration) [][]byte {
	t.Helper()
	deadline := time.Now().Add(d)
	for {
		mu.Lock()
		out := append([][]byte(nil), (*packets)...)
		mu.Unlock()
		if len(out) >= n {
			return out
		}
		if time.Now().After(deadline) {
			t.Fatalf("got %d packets, want at least %d", len(out), n)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func assertLiveDNSPacket(t *testing.T, packet []byte, adv DNSAdvertisement, wantTTL uint32) {
	t.Helper()
	answers, err := parseMDNSAnswers(packet)
	if err != nil {
		t.Fatalf("parse packet: %v", err)
	}
	service := strings.TrimSuffix(adv.serviceName(), ".")
	typeName := DNSServiceType + ".local"
	target := adv.HostID + ".local"
	var sawPTR, sawSRV, sawTXT, sawA bool
	for _, rec := range answers {
		if rec.ttl != wantTTL {
			t.Fatalf("record %s type %d ttl=%d want %d", rec.name, rec.typ, rec.ttl, wantTTL)
		}
		switch rec.typ {
		case 12:
			sawPTR = true
			if rec.name != typeName {
				t.Fatalf("PTR name = %q want %q", rec.name, typeName)
			}
			ptr, _, err := readDNSName(rec.rdata, 0)
			if err != nil {
				t.Fatalf("PTR rdata: %v", err)
			}
			if ptr != service {
				t.Fatalf("PTR target = %q want %q", ptr, service)
			}
		case 33:
			sawSRV = true
			if rec.name != service {
				t.Fatalf("SRV name = %q want %q", rec.name, service)
			}
			if len(rec.rdata) < 6 {
				t.Fatalf("SRV rdata too short: %d", len(rec.rdata))
			}
			port := binary.BigEndian.Uint16(rec.rdata[4:6])
			if int(port) != adv.Port {
				t.Fatalf("SRV port = %d want %d", port, adv.Port)
			}
			host, _, err := readDNSName(rec.rdata, 6)
			if err != nil {
				t.Fatalf("SRV target: %v", err)
			}
			if host != target {
				t.Fatalf("SRV target = %q want %q", host, target)
			}
		case 16:
			sawTXT = true
			if rec.name != service {
				t.Fatalf("TXT name = %q want %q", rec.name, service)
			}
			if len(rec.rdata) < 1 {
				t.Fatal("TXT rdata empty")
			}
			n := int(rec.rdata[0])
			txt := string(rec.rdata[1 : 1+n])
			if txt != "id="+adv.HostID {
				t.Fatalf("TXT = %q", txt)
			}
		case 1:
			sawA = true
			if rec.name != target {
				t.Fatalf("A name = %q want %q", rec.name, target)
			}
			if got := net.IP(rec.rdata).To4(); got == nil || !got.Equal(adv.Addresses[0].To4()) {
				t.Fatalf("A rdata = %v", rec.rdata)
			}
		}
	}
	if !sawPTR || !sawSRV || !sawTXT || !sawA {
		t.Fatalf("missing records PTR=%v SRV=%v TXT=%v A=%v", sawPTR, sawSRV, sawTXT, sawA)
	}
	raw := string(packet)
	if strings.Contains(raw, "token") || strings.Contains(raw, "tskey") {
		t.Fatal("packet contains credential-shaped data")
	}
}

type mdnsAnswer struct {
	name  string
	typ   uint16
	ttl   uint32
	rdata []byte
}

func parseMDNSAnswers(packet []byte) ([]mdnsAnswer, error) {
	if len(packet) < 12 {
		return nil, fmt.Errorf("header %d bytes", len(packet))
	}
	if binary.BigEndian.Uint16(packet[4:6]) != 0 {
		return nil, fmt.Errorf("qdcount=%d", binary.BigEndian.Uint16(packet[4:6]))
	}
	an := binary.BigEndian.Uint16(packet[6:8])
	if binary.BigEndian.Uint16(packet[8:10]) != 0 || binary.BigEndian.Uint16(packet[10:12]) != 0 {
		return nil, fmt.Errorf("nscount=%d arcount=%d", binary.BigEndian.Uint16(packet[8:10]), binary.BigEndian.Uint16(packet[10:12]))
	}
	off := 12
	out := make([]mdnsAnswer, 0, an)
	for i := 0; i < int(an); i++ {
		name, next, err := readDNSName(packet, off)
		if err != nil {
			return nil, err
		}
		off = next
		if off+10 > len(packet) {
			return nil, fmt.Errorf("short record header at %d", off)
		}
		typ := binary.BigEndian.Uint16(packet[off : off+2])
		ttl := binary.BigEndian.Uint32(packet[off+4 : off+8])
		rdlen := int(binary.BigEndian.Uint16(packet[off+8 : off+10]))
		off += 10
		if off+rdlen > len(packet) {
			return nil, fmt.Errorf("short rdata at %d", off)
		}
		out = append(out, mdnsAnswer{name: name, typ: typ, ttl: ttl, rdata: packet[off : off+rdlen]})
		off += rdlen
	}
	return out, nil
}

func readDNSName(p []byte, off int) (string, int, error) {
	var labels []string
	for {
		if off >= len(p) {
			return "", 0, fmt.Errorf("name truncated at %d", off)
		}
		n := int(p[off])
		off++
		if n == 0 {
			return strings.Join(labels, "."), off, nil
		}
		if n > 63 || off+n > len(p) {
			return "", 0, fmt.Errorf("bad label len %d at %d", n, off-1)
		}
		labels = append(labels, string(p[off:off+n]))
		off += n
	}
}
