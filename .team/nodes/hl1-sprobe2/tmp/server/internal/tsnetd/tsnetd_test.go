// Package tsnetd embeds Tailscale networking (tsnet) so the daemon's
// WebSocket service is reachable over the tailnet as well as the LAN.
//
// Two listeners feed the same HTTP/WS handler (wired by the ws-api task): a
// plain net.Listener on the LAN and, when a TS authkey is configured, a
// tsnet listener on the tailnet. With no authkey the package degrades to a
// LAN-only group without ever contacting the Tailscale control plane.
//
// The tests in this file follow the task's red-line: they construct tsnet
// servers but never call ListenTailnet (which is what starts the node and
// touches the network). Real tailnet connectivity is out of scope here — it
// belongs to the e2e aging manual.
package tsnetd

import (
	"bytes"
	"errors"
	"io"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// discardLogger returns a slog logger that swallows everything, keeping test
// output focused on assertions rather than the daemon's informational logs.
func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// TestNewDegradedNoAuthkey is the red-line test for the degraded path: with
// no authkey anywhere (neither Options nor TS_AUTHKEY env) construction MUST
// succeed and MUST produce a group with a LAN listener and no tailnet node.
func TestNewDegradedNoAuthkey(t *testing.T) {
	t.Setenv("TS_AUTHKEY", "")

	g, err := New(Options{ListenAddr: "127.0.0.1:0"}, discardLogger())
	if err != nil {
		t.Fatalf("New with empty authkey must succeed (degraded mode), got err: %v", err)
	}
	defer g.Close()

	if g.LAN == nil {
		t.Fatal("degraded group must still expose a LAN listener")
	}
	if g.TailnetEnabled() {
		t.Fatal("degraded group must not carry an embedded tailnet node")
	}
	if g.ts != nil {
		t.Fatal("degraded group must not construct a tsnet.Server")
	}
}

// TestDegradedListenTailnetRejected asserts that asking for the tailnet
// listener in degraded mode fails cleanly with ErrTailnetDisabled instead of
// starting a node or touching the network.
func TestDegradedListenTailnetRejected(t *testing.T) {
	t.Setenv("TS_AUTHKEY", "")

	g, err := New(Options{ListenAddr: "127.0.0.1:0"}, discardLogger())
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer g.Close()

	ln, err := g.ListenTailnet()
	if !errors.Is(err, ErrTailnetDisabled) {
		t.Fatalf("ListenTailnet in degraded mode: got err=%v (want ErrTailnetDisabled)", err)
	}
	if ln != nil {
		t.Fatalf("ListenTailnet in degraded mode must return a nil listener, got %v", ln)
	}
}

// TestNewWithAuthkeyConstructsOnly covers the tailnet-enabled construction
// path: with an authkey the group carries a configured tsnet.Server
// (Hostname/AuthKey/Dir wired through) but nothing is started — no Up, no
// Listen — so no control-plane traffic is possible from construction alone.
func TestNewWithAuthkeyConstructsOnly(t *testing.T) {
	const (
		fakeKey  = "tskey-test-fake"
		hostname = "agentmirror-host"
	)
	dir := filepath.Join(t.TempDir(), "tsnet-state")

	g, err := New(Options{
		ListenAddr: "127.0.0.1:0",
		Hostname:   hostname,
		AuthKey:    fakeKey,
		Dir:        dir,
	}, discardLogger())
	if err != nil {
		t.Fatalf("New with authkey failed: %v", err)
	}
	defer g.Close()

	if !g.TailnetEnabled() {
		t.Fatal("group with authkey must report tailnet enabled")
	}
	if g.LAN == nil {
		t.Fatal("group with authkey must still expose a LAN listener")
	}
	if g.ts == nil {
		t.Fatal("group with authkey must construct a tsnet.Server")
	}
	if g.ts.Hostname != hostname {
		t.Errorf("tsnet.Server.Hostname = %q, want %q", g.ts.Hostname, hostname)
	}
	if g.ts.AuthKey != fakeKey {
		t.Errorf("tsnet.Server.AuthKey = %q, want %q", g.ts.AuthKey, fakeKey)
	}
	if g.ts.Dir != dir {
		t.Errorf("tsnet.Server.Dir = %q, want %q", g.ts.Dir, dir)
	}
}

func TestNewUsesActualLANPortForTailnet(t *testing.T) {
	g, err := New(Options{
		ListenAddr: "127.0.0.1:0",
		AuthKey:    "tskey-test-fake",
		Dir:        t.TempDir(),
	}, discardLogger())
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer g.Close()

	want := portOf(g.LAN.Addr().String())
	if want == "0" || g.port != want {
		t.Fatalf("tailnet port = %q, want bound LAN port %q", g.port, want)
	}
}

func TestUpstreamLogRedactsAuthKey(t *testing.T) {
	const key = "tskey-auth-must-not-reach-log"
	var buf bytes.Buffer
	logger := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	g, err := New(Options{
		ListenAddr: "127.0.0.1:0",
		AuthKey:    key,
		Dir:        t.TempDir(),
	}, logger)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer g.Close()

	g.ts.Logf("register request auth=%s", key)
	if strings.Contains(buf.String(), key) {
		t.Fatalf("upstream log leaked TS authkey: %q", buf.String())
	}
}

func TestNewRejectsRegisterDebugWithAuthKey(t *testing.T) {
	// Tailscale's TS_DEBUG_REGISTER path serializes the full register request,
	// including AuthKey, into its private disk/remote logtail before Server.Logf
	// can redact it. Fail closed instead of offering a misleading safe logger.
	t.Setenv("TS_DEBUG_REGISTER", "true")
	const key = "tskey-auth-must-not-reach-upstream-logtail"
	g, err := New(Options{
		ListenAddr: "127.0.0.1:0",
		AuthKey:    key,
		Dir:        t.TempDir(),
	}, discardLogger())
	if g != nil {
		g.Close()
	}
	if err == nil {
		t.Fatal("New must reject TS_DEBUG_REGISTER when an authkey is configured")
	}
	if strings.Contains(err.Error(), key) {
		t.Fatalf("rejection leaked TS authkey: %q", err)
	}
}

// TestControlURLWired (feat-ts-wire) asserts Options.ControlURL reaches the
// tsnet server (self-hosted control planes — headscale — are a deployment
// freedom per requirement 011; empty means the official control plane).
func TestControlURLWired(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "tsnet-state")

	g, err := New(Options{
		ListenAddr: "127.0.0.1:0",
		AuthKey:    "tskey-test-fake",
		ControlURL: "http://127.0.0.1:8090",
		Dir:        dir,
	}, discardLogger())
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer g.Close()

	if g.ts == nil || g.ts.ControlURL != "http://127.0.0.1:8090" {
		t.Fatalf("tsnet.Server.ControlURL not wired, got %+v", g.ts)
	}
}

// TestStateDirCreated asserts that New creates the configured state directory
// (nested on purpose) when the tailnet is enabled, matching the contract that
// tsnet state lives under the user config directory.
func TestStateDirCreated(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "nested", "tsnet")

	if _, err := New(Options{
		ListenAddr: "127.0.0.1:0",
		AuthKey:    "tskey-test-fake",
		Dir:        dir,
	}, discardLogger()); err != nil {
		t.Fatalf("New failed: %v", err)
	}

	st, err := os.Stat(dir)
	if err != nil {
		t.Fatalf("state dir %q not created: %v", dir, err)
	}
	if !st.IsDir() {
		t.Fatalf("state path %q exists but is not a directory", dir)
	}
}

// TestStateDirNotCreatedWhenDegraded asserts the inverse: the degraded path
// has no tailscale node, so it must not create any tsnet state directory.
func TestStateDirNotCreatedWhenDegraded(t *testing.T) {
	t.Setenv("TS_AUTHKEY", "")
	dir := filepath.Join(t.TempDir(), "should-not-exist")

	if _, err := New(Options{
		ListenAddr: "127.0.0.1:0",
		Dir:        dir,
	}, discardLogger()); err != nil {
		t.Fatalf("New failed: %v", err)
	}

	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("degraded mode must not create a state dir, got stat err=%v", err)
	}
}

// TestDefaultDir asserts the state directory default resolves under the
// platform user config directory as .../agentmirror/tsnet.
func TestDefaultDir(t *testing.T) {
	dir, err := DefaultDir()
	if err != nil {
		t.Fatalf("DefaultDir failed: %v", err)
	}
	if !filepath.IsAbs(dir) {
		t.Fatalf("DefaultDir %q must be absolute", dir)
	}
	if !strings.HasSuffix(filepath.ToSlash(dir), "/agentmirror/tsnet") {
		t.Fatalf("DefaultDir %q must end with agentmirror/tsnet", dir)
	}
}

// TestAuthKeyFromEnvFallback asserts that an empty Options.AuthKey falls back
// to the TS_AUTHKEY environment variable, so the daemon can be configured
// purely by env (per the flag/env config contract).
func TestAuthKeyFromEnvFallback(t *testing.T) {
	t.Setenv("TS_AUTHKEY", "tskey-test-env")
	dir := filepath.Join(t.TempDir(), "tsnet-state")

	g, err := New(Options{ListenAddr: "127.0.0.1:0", Dir: dir}, discardLogger())
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer g.Close()

	if !g.TailnetEnabled() {
		t.Fatal("empty Options.AuthKey must fall back to TS_AUTHKEY env")
	}
	if g.ts == nil || g.ts.AuthKey != "tskey-test-env" {
		t.Fatalf("tsnet.Server.AuthKey from env = %v, want tskey-test-env", g.ts.AuthKey)
	}
}

// TestAuthKeyOptionOverridesEnv asserts explicit Options.AuthKey beats the
// environment variable.
func TestAuthKeyOptionOverridesEnv(t *testing.T) {
	t.Setenv("TS_AUTHKEY", "tskey-test-env")
	dir := filepath.Join(t.TempDir(), "tsnet-state")

	g, err := New(Options{
		ListenAddr: "127.0.0.1:0",
		AuthKey:    "tskey-test-option",
		Dir:        dir,
	}, discardLogger())
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer g.Close()

	if g.ts == nil || g.ts.AuthKey != "tskey-test-option" {
		t.Fatalf("tsnet.Server.AuthKey = %v, want tskey-test-option", g.ts.AuthKey)
	}
}

// TestDegradedUpRejected (task feat-ts-wire, red first) asserts that Up in
// degraded mode fails cleanly with ErrTailnetDisabled — same contract as
// ListenTailnet: no node, no control-plane contact, an explicit signal.
func TestDegradedUpRejected(t *testing.T) {
	t.Setenv("TS_AUTHKEY", "")

	g, err := New(Options{ListenAddr: "127.0.0.1:0"}, discardLogger())
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer g.Close()

	ip, err := g.Up(t.Context())
	if !errors.Is(err, ErrTailnetDisabled) {
		t.Fatalf("Up in degraded mode: got err=%v (want ErrTailnetDisabled)", err)
	}
	if ip != nil {
		t.Fatalf("Up in degraded mode must return a nil IP, got %v", ip)
	}
}

// TestLANListenerAccepts proves the LAN listener produced by the group is a
// real listening socket: a client can dial it and the group accepts.
func TestLANListenerAccepts(t *testing.T) {
	t.Setenv("TS_AUTHKEY", "")

	g, err := New(Options{ListenAddr: "127.0.0.1:0"}, discardLogger())
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer g.Close()

	client, err := net.Dial("tcp", g.LAN.Addr().String())
	if err != nil {
		t.Fatalf("dial LAN listener: %v", err)
	}
	defer client.Close()

	server, err := g.LAN.Accept()
	if err != nil {
		t.Fatalf("LAN Accept: %v", err)
	}
	defer server.Close()
}

// TestCloseReleasesLAN asserts Close closes the LAN listener so subsequent
// Accept fails, and that Close is idempotent.
func TestCloseReleasesLAN(t *testing.T) {
	t.Setenv("TS_AUTHKEY", "")

	g, err := New(Options{ListenAddr: "127.0.0.1:0"}, discardLogger())
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}

	if err := g.Close(); err != nil {
		t.Fatalf("first Close: %v", err)
	}
	if _, err := g.LAN.Accept(); err == nil {
		t.Fatal("Accept after Close must fail")
	}
	if err := g.Close(); err != nil {
		t.Fatalf("second Close must be a no-op, got: %v", err)
	}
}
