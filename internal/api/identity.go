package api

// identity.go implements the public discovery/identify HTTP endpoints. These
// endpoints deliberately sit beside /ws and /upload but never authenticate a
// WebSocket connection: identify proves only that this HTTP response knew the
// pairing token for this host and destination.

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agentmirror/agentmirror/internal/pairing"
)

const (
	identifyVersion = 1
	identityBodyMax = 1 << 10
	identityRate    = 5
	identityWindow  = time.Second
	identityMaxKeys = 4096
)

type whoamiResponse struct {
	Version int    `json:"v"`
	HostID  string `json:"host_id"`
	Name    string `json:"name"`
	Port    int    `json:"port"`
}

type identifyRequest struct {
	Version int    `json:"v"`
	HostID  string `json:"host_id,omitempty"`
	Nonce   string `json:"nonce"`
	DestIP  string `json:"dest_ip"`
}

type identifyResponse struct {
	Version int    `json:"v"`
	HostID  string `json:"host_id"`
	Name    string `json:"name"`
	Bound   string `json:"bound"`
	MAC     string `json:"mac"`
}

type identityError struct {
	Code string `json:"code"`
}

type identityRateLimiter struct {
	mu   sync.Mutex
	seen map[string][]time.Time
}

func newIdentityRateLimiter() *identityRateLimiter {
	return &identityRateLimiter{seen: make(map[string][]time.Time)}
}

func (l *identityRateLimiter) allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	old := l.seen[key]
	cut := now.Add(-identityWindow)
	keep := old[:0]
	for _, at := range old {
		if at.After(cut) {
			keep = append(keep, at)
		}
	}
	if len(keep) >= identityRate {
		l.seen[key] = keep
		return false
	}
	l.seen[key] = append(keep, now)
	if len(l.seen) > identityMaxKeys {
		for k, times := range l.seen {
			if len(times) == 0 {
				delete(l.seen, k)
			}
		}
	}
	return true
}

func (s *Server) serveWhoami(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeIdentityError(w, http.StatusMethodNotAllowed, "bad_request")
		return
	}
	if !s.identityLimiter.allow(remoteIP(r), time.Now()) {
		writeIdentityError(w, http.StatusTooManyRequests, "rate_limited")
		return
	}
	body := whoamiResponse{Version: identifyVersion, HostID: s.hostID, Name: s.hostName, Port: s.listenPort}
	writeIdentityJSON(w, http.StatusOK, body)
}

func (s *Server) serveIdentify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeIdentityError(w, http.StatusMethodNotAllowed, "bad_request")
		return
	}
	if !s.identityLimiter.allow(remoteIP(r), time.Now()) {
		writeIdentityError(w, http.StatusTooManyRequests, "rate_limited")
		return
	}
	body := http.MaxBytesReader(w, r.Body, identityBodyMax)
	dec := json.NewDecoder(body)
	dec.DisallowUnknownFields()
	var req identifyRequest
	if err := dec.Decode(&req); err != nil {
		writeIdentityError(w, http.StatusBadRequest, "bad_request")
		return
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		writeIdentityError(w, http.StatusBadRequest, "bad_request")
		return
	}
	if req.Version != identifyVersion {
		writeIdentityError(w, http.StatusBadRequest, "bad_request")
		return
	}
	if req.HostID != "" && req.HostID != s.hostID {
		writeIdentityError(w, http.StatusBadRequest, "unknown_host")
		return
	}
	if !validNonce(req.Nonce) {
		writeIdentityError(w, http.StatusBadRequest, "bad_nonce")
		return
	}
	dest := net.ParseIP(req.DestIP)
	if dest == nil || dest.To4() == nil || dest.IsLoopback() || dest.IsUnspecified() {
		writeIdentityError(w, http.StatusBadRequest, "bad_dest")
		return
	}
	dest = dest.To4()
	boundIP, boundPort, ok := s.boundAddress(r, dest)
	if !ok {
		writeIdentityError(w, http.StatusBadRequest, "bad_dest")
		return
	}
	msg := identifyMessage(s.hostID, req.Nonce, boundIP.String(), boundPort)
	mac := hmac.New(sha256.New, []byte(s.pairingToken))
	_, _ = mac.Write(msg)
	resp := identifyResponse{
		Version: identifyVersion,
		HostID:  s.hostID,
		Name:    s.hostName,
		Bound:   net.JoinHostPort(boundIP.String(), strconv.Itoa(boundPort)),
		MAC:     hex.EncodeToString(mac.Sum(nil)),
	}
	writeIdentityJSON(w, http.StatusOK, resp)
}

func validNonce(nonce string) bool {
	if len(nonce) != 32 {
		return false
	}
	decoded, err := hex.DecodeString(nonce)
	return err == nil && len(decoded) == 16 && strings.ToLower(nonce) == nonce
}

func identifyMessage(hostID, nonceHex, boundIP string, boundPort int) []byte {
	return []byte("agentmirror-identify-v1\x1f" + hostID + "\x1f" + nonceHex + "\x1f" + boundIP + "\x1f" + strconv.Itoa(boundPort))
}

func remoteIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	if r.RemoteAddr != "" {
		return r.RemoteAddr
	}
	return "unknown"
}

// boundAddress uses only http.LocalAddrContextKey for the direct path. A
// malformed/loopback local address may use the explicit address provider,
// which is the userspace-tsnet fallback; listener.Addr is intentionally never
// consulted.
func (s *Server) boundAddress(r *http.Request, dest net.IP) (net.IP, int, bool) {
	if local, ok := r.Context().Value(http.LocalAddrContextKey).(net.Addr); ok && local != nil {
		if ip, port, parsed := localIPv4(local); parsed {
			if !ip.IsLoopback() && !ip.IsUnspecified() {
				if !ip.Equal(dest) {
					return nil, 0, false
				}
				return ip, port, true
			}
			// A wildcard listener accepts connections addressed through a NAT
			// alias that is not one of the host's enumerated interfaces (for
			// example Android emulator 10.0.2.2). Bind the proof to the
			// request Host, but only when its literal IPv4 and port match the
			// listener and the requested destination exactly.
			if ip.IsUnspecified() {
				if hostIP, hostPort, hostOK := requestHostIPv4(r, port); hostOK && hostPort == port && hostIP.Equal(dest) {
					return hostIP, port, true
				}
			}
		}
	}
	for _, candidate := range s.identityAddresses() {
		ip := candidate.To4()
		if ip != nil && ip.Equal(dest) && !ip.IsLoopback() && !ip.IsUnspecified() {
			port := s.listenPort
			if port < 1 {
				port = 9900
			}
			return ip, port, true
		}
	}
	return nil, 0, false
}

func requestHostIPv4(r *http.Request, fallbackPort int) (net.IP, int, bool) {
	host, portText, err := net.SplitHostPort(r.Host)
	if err != nil {
		host = r.Host
		portText = strconv.Itoa(fallbackPort)
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return nil, 0, false
	}
	ip := net.ParseIP(host).To4()
	return ip, port, ip != nil && !ip.IsLoopback() && !ip.IsUnspecified()
}

func localIPv4(addr net.Addr) (net.IP, int, bool) {
	var ip net.IP
	var port int
	switch typed := addr.(type) {
	case *net.TCPAddr:
		ip, port = typed.IP, typed.Port
	case *net.UDPAddr:
		ip, port = typed.IP, typed.Port
	default:
		host, portText, err := net.SplitHostPort(addr.String())
		if err != nil {
			return nil, 0, false
		}
		ip = net.ParseIP(host)
		port, err = strconv.Atoi(portText)
		if err != nil {
			return nil, 0, false
		}
	}
	ip = ip.To4()
	return ip, port, ip != nil && port > 0
}

func (s *Server) identityAddresses() []net.IP {
	s.identityMu.RLock()
	provider := s.addresses
	tailnet := append([]net.IP(nil), s.tailnetIPs...)
	s.identityMu.RUnlock()
	if provider != nil {
		return provider()
	}
	addrs := pairing.DetectAddresses()
	out := make([]net.IP, 0, len(addrs)+len(tailnet))
	for _, a := range addrs {
		if ip := a.IP.To4(); ip != nil && !ip.IsLoopback() {
			out = append(out, ip)
		}
	}
	return append(out, tailnet...)
}

func writeIdentityJSON(w http.ResponseWriter, status int, value any) {
	body, err := json.Marshal(value)
	if err != nil || len(body) > identityBodyMax {
		writeIdentityError(w, http.StatusInternalServerError, "internal_error")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func writeIdentityError(w http.ResponseWriter, status int, code string) {
	body, _ := json.Marshal(identityError{Code: code})
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

// IdentifyMAC computes the frozen vector for independent client/test use.
func IdentifyMAC(token, hostID, nonceHex, boundIP string, boundPort int) string {
	m := hmac.New(sha256.New, []byte(token))
	_, _ = m.Write(identifyMessage(hostID, nonceHex, boundIP, boundPort))
	return hex.EncodeToString(m.Sum(nil))
}
