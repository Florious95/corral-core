package api

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
)

func TestWhoamiDoesNotExposeToken(t *testing.T) {
	const token = "secret-pairing-token"
	s := NewServer(Options{
		HostID:              "AAAAAAAAAAAAAAAAAAAAAAAAAA",
		HostName:            "test-host",
		ListenPort:          9900,
		Token:               token,
		DiscoverySocketDirs: []string{},
		AddressProvider: func() []net.IP {
			return []net.IP{net.ParseIP("192.0.2.7"), net.ParseIP("100.64.0.8"), net.ParseIP("192.0.2.7")}
		},
	})
	defer s.Close()
	r := httptest.NewRequest(http.MethodGet, "http://192.0.2.10:9900/pair/whoami", nil)
	r.RemoteAddr = "192.0.2.10:40000"
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("whoami status = %d", w.Code)
	}
	if strings.Contains(w.Body.String(), token) {
		t.Fatal("whoami leaked pairing token")
	}
	var got whoamiResponse
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.HostID != "AAAAAAAAAAAAAAAAAAAAAAAAAA" || got.Port != 9900 {
		t.Fatalf("whoami = %+v", got)
	}
	if want := []string{"192.0.2.7", "100.64.0.8"}; !reflect.DeepEqual(got.Addresses, want) {
		t.Fatalf("whoami addresses = %v want %v", got.Addresses, want)
	}
}

func TestIdentifyVectorAndBoundLocalAddr(t *testing.T) {
	const (
		token  = "pairing-token"
		hostID = "AAAAAAAAAAAAAAAAAAAAAAAAAA"
		nonce  = "00112233445566778899aabbccddeeff"
	)
	s := NewServer(Options{HostID: hostID, HostName: "test-host", ListenPort: 9900, Token: token})
	defer s.Close()
	body := `{"v":1,"host_id":"` + hostID + `","nonce":"` + nonce + `","dest_ip":"192.0.2.7"}`
	r := httptest.NewRequest(http.MethodPost, "http://192.0.2.7:9900/pair/identify", strings.NewReader(body))
	r.RemoteAddr = "192.0.2.9:40000"
	r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, net.Addr(&net.TCPAddr{IP: net.ParseIP("192.0.2.7"), Port: 9900})))
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("identify status = %d body=%s", w.Code, w.Body.String())
	}
	var got identifyResponse
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.Bound != "192.0.2.7:9900" || len(got.MAC) != sha256.Size*2 {
		t.Fatalf("identify = %+v", got)
	}
	want := hmac.New(sha256.New, []byte(token))
	_, _ = want.Write([]byte("agentmirror-identify-v1\x1f" + hostID + "\x1f" + nonce + "\x1f192.0.2.7\x1f9900"))
	if got.MAC != hex.EncodeToString(want.Sum(nil)) {
		t.Fatalf("mac = %s", got.MAC)
	}
	if strings.Contains(w.Body.String(), token) {
		t.Fatal("identify leaked pairing token")
	}
}

func TestIdentifyFallbackUsesConfiguredAddressSet(t *testing.T) {
	const hostID = "AAAAAAAAAAAAAAAAAAAAAAAAAA"
	s := NewServer(Options{HostID: hostID, Token: "token", ListenPort: 9911, AddressProvider: func() []net.IP { return []net.IP{net.ParseIP("100.64.0.8")} }})
	defer s.Close()
	body := `{"v":1,"host_id":"` + hostID + `","nonce":"00112233445566778899aabbccddeeff","dest_ip":"100.64.0.8"}`
	r := httptest.NewRequest(http.MethodPost, "/pair/identify", strings.NewReader(body))
	r.RemoteAddr = "100.64.0.9:40000"
	r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, net.Addr(&net.TCPAddr{IP: net.ParseIP("0.0.0.0"), Port: 0})))
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("fallback status = %d body=%s", w.Code, w.Body.String())
	}
	var got identifyResponse
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.Bound != "100.64.0.8:9911" {
		t.Fatalf("fallback bound = %q", got.Bound)
	}
}

func TestIdentifyWildcardListenerAcceptsRequestHostAlias(t *testing.T) {
	const (
		token  = "pairing-token"
		hostID = "AAAAAAAAAAAAAAAAAAAAAAAAAA"
		nonce  = "00112233445566778899aabbccddeeff"
	)
	s := NewServer(Options{HostID: hostID, HostName: "test-host", ListenPort: 9902, Token: token})
	defer s.Close()
	body := `{"v":1,"host_id":"` + hostID + `","nonce":"` + nonce + `","dest_ip":"10.0.2.2"}`
	r := httptest.NewRequest(http.MethodPost, "http://10.0.2.2:9902/pair/identify", strings.NewReader(body))
	r.RemoteAddr = "10.0.2.15:40000"
	r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, net.Addr(&net.TCPAddr{IP: net.IPv4zero, Port: 9902})))
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("identify status = %d body=%s", w.Code, w.Body.String())
	}
	var got identifyResponse
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.Bound != "10.0.2.2:9902" || len(got.MAC) != sha256.Size*2 {
		t.Fatalf("identify = %+v", got)
	}
	want := IdentifyMAC(token, hostID, nonce, "10.0.2.2", 9902)
	if got.MAC != want {
		t.Fatalf("mac = %s want %s", got.MAC, want)
	}
}

func TestIdentifyWildcardListenerRejectsMismatchedRequestHostAlias(t *testing.T) {
	const hostID = "AAAAAAAAAAAAAAAAAAAAAAAAAA"
	s := NewServer(Options{HostID: hostID, Token: "token", ListenPort: 9902})
	defer s.Close()
	body := `{"v":1,"host_id":"` + hostID + `","nonce":"00112233445566778899aabbccddeeff","dest_ip":"10.0.2.2"}`
	r := httptest.NewRequest(http.MethodPost, "http://10.0.2.3:9902/pair/identify", strings.NewReader(body))
	r.RemoteAddr = "10.0.2.15:40000"
	r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, net.Addr(&net.TCPAddr{IP: net.IPv4zero, Port: 9902})))
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("identify status = %d body=%s", w.Code, w.Body.String())
	}
	var got identityError
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.Code != "bad_dest" {
		t.Fatalf("code = %q want bad_dest", got.Code)
	}
}

func TestIdentifyRejectsWrongBoundAndMalformedNonce(t *testing.T) {
	const hostID = "AAAAAAAAAAAAAAAAAAAAAAAAAA"
	s := NewServer(Options{HostID: hostID, Token: "token", ListenPort: 9900})
	defer s.Close()
	for _, tc := range []struct{ name, body, code string }{
		{"nonce", `{"v":1,"host_id":"AAAAAAAAAAAAAAAAAAAAAAAAAA","nonce":"bad","dest_ip":"192.0.2.7"}`, "bad_nonce"},
		{"bound", `{"v":1,"host_id":"AAAAAAAAAAAAAAAAAAAAAAAAAA","nonce":"00112233445566778899aabbccddeeff","dest_ip":"192.0.2.7"}`, "bad_dest"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodPost, "/pair/identify", strings.NewReader(tc.body))
			r.RemoteAddr = "192.0.2.9:40000"
			if tc.name == "bound" {
				r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, net.Addr(&net.TCPAddr{IP: net.ParseIP("192.0.2.8"), Port: 9900})))
			}
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, r)
			if w.Code/100 != 4 {
				t.Fatalf("status = %d", w.Code)
			}
			var got identityError
			if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
				t.Fatal(err)
			}
			if got.Code != tc.code {
				t.Fatalf("code = %q want %q", got.Code, tc.code)
			}
		})
	}
}
