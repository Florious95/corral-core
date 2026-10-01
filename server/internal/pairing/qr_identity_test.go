package pairing

import (
	"encoding/json"
	"net"
	"testing"
)

func TestOnboardingQRIncludesIdentityWithoutDroppingV1Fields(t *testing.T) {
	o := Onboarding{Token: "token", Port: "9912", HostID: "AAAAAAAAAAAAAAAAAAAAAAAAAA", TSNodeID: "node-1", Name: "display-only"}
	p := onboardingPayload(o, []Address{{IP: net.ParseIP("192.0.2.1"), Kind: KindLAN}}, "192.0.2.1")
	body, err := p.Marshal()
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"v", "url", "token", "host_id", "port", "ts_node_id", "name"} {
		if _, ok := got[key]; !ok {
			t.Fatalf("missing QR field %q: %s", key, body)
		}
	}
	if got["port"] != float64(9912) {
		t.Fatalf("port = %v", got["port"])
	}
}
