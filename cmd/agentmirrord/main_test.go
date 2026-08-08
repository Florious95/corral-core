package main

// main_test.go covers the pairing wiring in cmd/agentmirrord: token resolution
// (explicit wins; auto-generate persists and reuses; never in logs) and the
// onboarding print (QR + guide carry the token — the legal exits). The daemon
// itself is not forked; these exercise the seams run() composes.

import (
	"bytes"
	"log/slog"
	"net"
	"strings"
	"testing"

	"github.com/agentmirror/agentmirror/internal/config"
	"github.com/agentmirror/agentmirror/internal/pairing"
)

// bufferLogger returns a logger writing to the returned buffer, so tests can
// assert the token never lands in a log line.
func bufferLogger(b *bytes.Buffer) *slog.Logger {
	return slog.New(slog.NewTextHandler(b, nil))
}

// TestResolveTokenExplicit wins without touching any store.
func TestResolveTokenExplicit(t *testing.T) {
	var buf bytes.Buffer
	tok, err := resolveToken(config.Config{Token: "explicit-tok"}, bufferLogger(&buf))
	if err != nil {
		t.Fatalf("resolveToken(explicit): %v", err)
	}
	if tok != "explicit-tok" {
		t.Errorf("resolveToken = %q, want explicit-tok", tok)
	}
}

// TestResolveTokenAutoGeneratesPersistsReuses covers the empty-config path at
// the wiring level: a token is generated into a temp store and reused on a
// second call (a daemon restart), and the log buffer never contains it.
func TestResolveTokenAutoGeneratesPersistsReuses(t *testing.T) {
	dir := t.TempDir()
	var buf bytes.Buffer
	logger := bufferLogger(&buf)

	first, err := resolveTokenDir(config.Config{}, logger, dir)
	if err != nil {
		t.Fatalf("resolveTokenDir(generate): %v", err)
	}
	if first == "" {
		t.Fatal("auto-generated token must be non-empty")
	}
	if strings.Contains(buf.String(), first) {
		t.Fatalf("log line leaks pairing token: %q", buf.String())
	}

	second, err := resolveTokenDir(config.Config{}, bufferLogger(&bytes.Buffer{}), dir)
	if err != nil {
		t.Fatalf("resolveTokenDir(reuse): %v", err)
	}
	if second != first {
		t.Errorf("restart token = %q, want persisted %q", second, first)
	}
}

// TestPrintPairingGuideCarriesLegalExits locks the §9 exit contract at the
// wiring seam: the printed guide is the token's legal exit and must contain
// the token and the ws URL, plus the manual-fill instructions.
func TestPrintPairingGuideCarriesLegalExits(t *testing.T) {
	var buf bytes.Buffer
	if err := printPairingGuide(&buf, "tok-abc-123", "9900", false); err != nil {
		t.Fatalf("printPairingGuide: %v", err)
	}
	out := buf.String()
	if !strings.Contains(out, "tok-abc-123") {
		t.Error("guide must contain the pairing token (legal exit)")
	}
	if !strings.Contains(out, "ws://") {
		t.Error("guide must contain a ws:// URL")
	}
	if !strings.Contains(out, "配对") {
		t.Error("guide must carry the Chinese onboarding instructions")
	}
}

// TestPrintPairingGuideDegradedWarns verifies a host with no LAN/tailnet
// address still gets an explicit warning instead of a silently unreachable QR.
// It forces the degraded case by injecting a loopback-only probe, since the
// sandbox itself always has a real LAN address.
func TestPrintPairingGuideDegradedWarns(t *testing.T) {
	var buf bytes.Buffer
	loopOnly := []pairing.Address{{IP: net.ParseIP("127.0.0.1"), Kind: pairing.KindLoopback}}
	if err := printOnboardingSeam(&buf, pairing.Onboarding{Token: "tok-degraded", Port: "9900"}, loopOnly, "127.0.0.1"); err != nil {
		t.Fatalf("printOnboardingSeam(degraded): %v", err)
	}
	out := buf.String()
	if !strings.Contains(out, "⚠") {
		t.Errorf("degraded guide must warn, got:\n%s", out)
	}
	if !strings.Contains(out, "127.0.0.1") {
		t.Error("degraded guide must still carry the loopback fallback URL")
	}
}

// TestListenPort extracts the port from a host:port and defaults to 9900.
func TestListenPort(t *testing.T) {
	if got := listenPort("0.0.0.0:9901"); got != "9901" {
		t.Errorf("listenPort(0.0.0.0:9901) = %q, want 9901", got)
	}
	if got := listenPort("garbage"); got != "9900" {
		t.Errorf("listenPort(garbage) = %q, want 9900 default", got)
	}
}

// TestTokenSource labels the origin without leaking the value.
func TestTokenSource(t *testing.T) {
	if tokenSource("") != "auto" {
		t.Error("tokenSource(\"\") must be auto")
	}
	if tokenSource("x") != "explicit" {
		t.Error("tokenSource(x) must be explicit")
	}
}

// TestResolveTokenFailureIsFatal guarantees the empty-token-anonymous-bypass
// cannot happen: when the token store is unusable, resolution returns an error
// rather than a blank token that would authenticate an empty auth frame.
func TestResolveTokenFailureIsFatal(t *testing.T) {
	// /dev/null/x/not-a-dir is an unwritable store on every platform; the
	// empty explicit token forces the auto path, which must fail loudly.
	tok, err := resolveTokenDir(config.Config{}, bufferLogger(&bytes.Buffer{}), "/dev/null/x/not-a-dir")
	if err == nil {
		t.Fatalf("resolveTokenDir with unusable store must fail, got token %q", tok)
	}
	if tok != "" {
		t.Errorf("failed resolution must not return a usable token, got %q", tok)
	}
}
