package api

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The download route must never fall through to a host path or accept query auth.
func TestSessionExportAccessGuards(t *testing.T) {
	s := NewServer(Options{Token: uploadTestToken, Discoverer: scriptedDiscoverer{model: testModel()}})
	defer s.Close()
	for _, tc := range []struct {
		name, method, path, bearer, rangeHeader string
		status                                  int
	}{
		{"query token not authority", "GET", "/artifacts/session/export?token=" + uploadTestToken, "", "", http.StatusUnauthorized},
		{"wrong token", "GET", "/artifacts/session/export", "incorrect", "", http.StatusUnauthorized},
		{"write forbidden", "POST", "/artifacts/session/export", uploadTestToken, "", http.StatusMethodNotAllowed},
		{"option injection", "GET", "/artifacts/-option/export", uploadTestToken, "", http.StatusBadRequest},
		{"extra host path", "GET", "/artifacts/session/export/file", uploadTestToken, "", http.StatusBadRequest},
		{"regenerated range forbidden", "GET", "/artifacts/session/export", uploadTestToken, "bytes=0-10", http.StatusRequestedRangeNotSatisfiable},
		{"unknown ref", "GET", "/artifacts/session/export?ref=unknown", uploadTestToken, "", http.StatusNotFound},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest(tc.method, tc.path, nil)
			if tc.bearer != "" {
				r.Header.Set("Authorization", "Bearer "+tc.bearer)
			}
			if tc.rangeHeader != "" {
				r.Header.Set("Range", tc.rangeHeader)
			}
			w := httptest.NewRecorder()
			s.Handler().ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
			}
			if strings.Contains(w.Body.String(), uploadTestToken) {
				t.Fatal("credential in response")
			}
		})
	}
}
