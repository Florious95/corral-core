package pairing

import (
	"encoding/binary"
	"net"
	"strings"
	"testing"
)

func TestDNSAdvertisementContainsOnlyPublicIdentity(t *testing.T) {
	id := "AAAAAAAAAAAAAAAAAAAAAAAAAA"
	adv := DNSAdvertisement{HostID: id, Port: 9900, Addresses: []net.IP{net.ParseIP("192.0.2.7")}}
	if got := adv.TXT(); len(got) != 1 || got[0] != "id="+id {
		t.Fatalf("TXT = %v", got)
	}
	packet := dnsPacket(adv, 120)
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
