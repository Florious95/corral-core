package api

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestIdentityRateLimitIsPerIPAndShared(t *testing.T) {
	s := NewServer(Options{HostID: "AAAAAAAAAAAAAAAAAAAAAAAAAA", Token: "token"})
	defer s.Close()
	for i := 0; i < identityRate; i++ {
		r := httptest.NewRequest(http.MethodGet, "/pair/whoami", nil)
		r.RemoteAddr = "192.0.2.20:40000"
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, r)
		if w.Code != http.StatusOK {
			t.Fatalf("request %d status = %d", i, w.Code)
		}
	}
	r := httptest.NewRequest(http.MethodPost, "/pair/identify", strings.NewReader(`{}`))
	r.RemoteAddr = "192.0.2.20:40001"
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("shared rate status = %d", w.Code)
	}
}

func TestIdentifyRejectsBodyOverOneKiB(t *testing.T) {
	s := NewServer(Options{HostID: "AAAAAAAAAAAAAAAAAAAAAAAAAA", Token: "token"})
	defer s.Close()
	r := httptest.NewRequest(http.MethodPost, "/pair/identify", strings.NewReader(`{"v":1,"nonce":"`+strings.Repeat("a", 32)+`","dest_ip":"192.0.2.1","padding":"`+strings.Repeat("x", 1100)+`"}`))
	r.RemoteAddr = "192.0.2.21:40000"
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code/100 != 4 {
		t.Fatalf("oversized body status = %d", w.Code)
	}
}
