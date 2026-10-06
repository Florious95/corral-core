package api

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Real net/http connections report the accepted socket IP, never the listener's
// wildcard IP. Android's 10.0.2.2 relay reaches this host loopback connection.
func TestIdentifyRealLoopbackEmulatorRelay(t *testing.T) {
	const hostID = "AAAAAAAAAAAAAAAAAAAAAAAAAA"
	const token = "owned-nat-fixture-only"
	const nonce = "00112233445566778899aabbccddeeff"
	s := NewServer(Options{HostID: hostID, Token: token, DiscoverySocketDirs: []string{}})
	defer s.Close()
	httpServer := httptest.NewServer(s.Handler())
	defer httpServer.Close()
	_, port, _ := net.SplitHostPort(httpServer.Listener.Addr().String())
	bound := "10.0.2.2:" + port
	body := `{"v":1,"host_id":"` + hostID + `","nonce":"` + nonce + `","dest_ip":"10.0.2.2"}`
	request, err := http.NewRequest(http.MethodPost, httpServer.URL+"/pair/identify", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	request.Host = bound
	response, err := httpServer.Client().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		var rejected identityError
		_ = json.Unmarshal(raw, &rejected)
		t.Fatalf("actual socket identify status=%d reason=%s", response.StatusCode, rejected.Code)
	}
	var proof identifyResponse
	if err := json.Unmarshal(raw, &proof); err != nil {
		t.Fatal(err)
	}
	_, actualPort, _ := net.SplitHostPort(proof.Bound)
	if proof.HostID != hostID || proof.Bound != bound || proof.MAC != IdentifyMAC(token, hostID, nonce, "10.0.2.2", httpServer.Listener.Addr().(*net.TCPAddr).Port) || actualPort != port {
		t.Fatal("actual relay proof did not preserve host/nonce/destination/port/token bindings")
	}
}

func TestIdentifyLoopbackRelayRejectsForeignBindings(t *testing.T) {
	for _, tc := range []struct{ name, local, remote, host, dest string }{
		{"foreign-peer", "127.0.0.1", "192.0.2.9:40000", "10.0.2.2:19994", "10.0.2.2"},
		{"foreign-local", "192.0.2.7", "127.0.0.1:40000", "10.0.2.2:19994", "10.0.2.2"},
		{"wrong-port", "127.0.0.1", "127.0.0.1:40000", "10.0.2.2:19995", "10.0.2.2"},
		{"wrong-host", "127.0.0.1", "127.0.0.1:40000", "10.0.2.3:19994", "10.0.2.2"},
		{"arbitrary-ip", "127.0.0.1", "127.0.0.1:40000", "198.51.100.3:19994", "198.51.100.3"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := NewServer(Options{HostID: "AAAAAAAAAAAAAAAAAAAAAAAAAA", Token: "owned-nat-fixture-only", DiscoverySocketDirs: []string{}, AddressProvider: func() []net.IP { return nil }})
			defer s.Close()
			r := httptest.NewRequest(http.MethodPost, "http://"+tc.host+"/pair/identify", strings.NewReader(`{"v":1,"nonce":"00112233445566778899aabbccddeeff","dest_ip":"`+tc.dest+`"}`))
			r.RemoteAddr = tc.remote
			r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.TCPAddr{IP: net.ParseIP(tc.local), Port: 19994}))
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, r)
			var result identityError
			_ = json.Unmarshal(w.Body.Bytes(), &result)
			if w.Code != http.StatusBadRequest || result.Code != "bad_dest" {
				t.Fatalf("foreign binding not rejected: status=%d reason=%s", w.Code, result.Code)
			}
		})
	}
}
