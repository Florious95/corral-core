package main

// main_test.go covers the pairing wiring in cmd/agentmirrord: token resolution
// (explicit wins; auto-generate persists and reuses; never in logs) and the
// onboarding print (QR + guide carry the token — the legal exits). The daemon
// itself is not forked; these exercise the seams run() composes.

import (
	"bytes"
	"errors"
	"log/slog"
	"net"
	"path/filepath"
	"strings"
	"testing"

	"github.com/agentmirror/agentmirror/internal/config"
	"github.com/agentmirror/agentmirror/internal/pairing"
	"github.com/agentmirror/agentmirror/internal/tsnetd"
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

// TestRunPassesResolvedStateDirToTSNet pins the cmd-to-consumer seam. A fake
// factory stops startup after capturing the exact options, proving the
// resolved daemon state root reaches tsnetd instead of remaining dead config.
func TestRunPassesResolvedStateDirToTSNet(t *testing.T) {
	original := newTSNetGroup
	t.Cleanup(func() { newTSNetGroup = original })

	var got tsnetd.Options
	newTSNetGroup = func(opts tsnetd.Options, _ *slog.Logger) (*tsnetd.Group, error) {
		got = opts
		return nil, errors.New("stop after options capture")
	}

	t.Setenv("TS_AUTHKEY", "configured-for-test")
	stateDir := t.TempDir()
	if code := run([]string{
		"-listen", "127.0.0.1:0",
		"-state-dir", stateDir,
		"-token", "pairing-test",
	}); code != 1 {
		t.Fatalf("run exit code = %d, want 1 from capture sentinel", code)
	}

	want := filepath.Join(stateDir, "tsnet")
	if got.Dir != want {
		t.Fatalf("tsnetd.Options.Dir = %q, want resolved state subdir %q", got.Dir, want)
	}
}

// TestPrintPairingGuideCarriesLegalExits locks the §9 exit contract at the
// wiring seam: the printed guide is the token's legal exit and must contain
// the token and the ws URL, plus the manual-fill instructions.
func TestPrintPairingGuideCarriesLegalExits(t *testing.T) {
	var buf bytes.Buffer
	if err := printPairingGuide(&buf, "tok-abc-123", "9900", false, "", nil, ""); err != nil {
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

// TestPrintPairingGuideHostOverride pins the -host override seam: when a host
// is explicitly configured the QR/guide must carry it, regardless of what the
// automatic probe would pick (task fix-qr-host-detect).
func TestPrintPairingGuideHostOverride(t *testing.T) {
	var buf bytes.Buffer
	if err := printPairingGuide(&buf, "tok-abc-123", "9900", false, "10.0.0.9", nil, ""); err != nil {
		t.Fatalf("printPairingGuide(override): %v", err)
	}
	out := buf.String()
	if !strings.Contains(out, "ws://10.0.0.9:9900/ws") {
		t.Errorf("guide must carry the explicit host, got:\n%s", out)
	}
}

// TestPrintPairingGuideListsCandidates verifies the full-candidate guide lists
// every detected address with its own ws URL, so a user whose phone cannot
// reach the QR's primary can re-enter another host by hand. It injects a
// two-LAN-address probe set (the live-machine shape from the defect) and checks
// both appear with full ws URLs.
func TestPrintPairingGuideListsCandidates(t *testing.T) {
	addrs := []pairing.Address{
		{IP: net.ParseIP("192.168.31.116"), Kind: pairing.KindLAN},
		{IP: net.ParseIP("10.20.55.20"), Kind: pairing.KindLAN},
		{IP: net.ParseIP("127.0.0.1"), Kind: pairing.KindLoopback},
	}
	var buf bytes.Buffer
	if err := printOnboardingSeamAll(&buf, pairing.Onboarding{Token: "tok-x", Port: "9900"}, addrs, "192.168.31.116"); err != nil {
		t.Fatalf("printOnboardingSeamAll: %v", err)
	}
	out := buf.String()
	for _, want := range []string{
		"ws://192.168.31.116:9900/ws",
		"ws://10.20.55.20:9900/ws",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("full-candidate guide must contain %s, got:\n%s", want, out)
		}
	}
}

// TestPrintPairingGuideTailnetWired (feat-ts-wire) pins the cmd seam: with a
// tailnet IP and authkey configured, the guide lists the tailnet ws URL (the
// embedded node's address is injected — no NIC exposes it) while the authkey
// never appears in the plain text (§2.1: the QR is its only legal exit).
func TestPrintPairingGuideTailnetWired(t *testing.T) {
	const key = "tskey-auth-SECRET-guide"
	var buf bytes.Buffer
	if err := printPairingGuide(&buf, "tok-abc-123", "9900", true, "10.0.0.9", net.ParseIP("100.101.2.3"), key); err != nil {
		t.Fatalf("printPairingGuide(tailnet): %v", err)
	}
	out := buf.String()
	if !strings.Contains(out, "ws://100.101.2.3:9900/ws") {
		t.Errorf("guide must list the injected tailnet address, got:\n%s", out)
	}
	if strings.Contains(out, key) {
		t.Error("guide must never print the TS authkey in plaintext")
	}
}

func TestAutomaticPairingHostUsesTailnetInsteadOfLoopback(t *testing.T) {
	if got := automaticPairingHost("127.0.0.1", net.ParseIP("100.101.2.3")); got != "100.101.2.3" {
		t.Fatalf("automaticPairingHost = %q, want injected tailnet address", got)
	}
	if got := automaticPairingHost("192.168.1.5", net.ParseIP("100.101.2.3")); got != "192.168.1.5" {
		t.Fatalf("LAN primary must still win, got %q", got)
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
