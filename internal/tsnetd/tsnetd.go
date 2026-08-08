// Package tsnetd embeds Tailscale networking (tsnet) so the daemon's
// WebSocket service is reachable over the tailnet as well as the LAN.
//
// See doc.go for the full package contract.
package tsnetd

import (
	"errors"
	"fmt"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"sync"

	"tailscale.com/tsnet"
)

// ErrTailnetDisabled is returned by Group.ListenTailnet when the group was
// built in degraded mode (no TS authkey configured): there is no embedded
// node to listen on.
var ErrTailnetDisabled = errors.New("tsnetd: tailnet disabled (no TS authkey)")

// envAuthKey is the environment variable read for the Tailscale auth key when
// Options.AuthKey is empty, per the flag/env configuration contract.
const envAuthKey = "TS_AUTHKEY"

// Options selects how the listener group is built.
type Options struct {
	// ListenAddr is the host:port the LAN listener binds, e.g.
	// "0.0.0.0:9900". The tailnet listener serves the same port number on
	// the tailnet address space.
	ListenAddr string

	// Hostname is the name this host registers as on the tailnet. Only used
	// when the tailnet is enabled; ignored in degraded mode.
	Hostname string

	// AuthKey is the Tailscale node auth key. When empty it falls back to
	// the TS_AUTHKEY environment variable; when both are empty the tailnet
	// is disabled and New returns a LAN-only group.
	AuthKey string

	// Dir is the directory where tsnet persists node state (private keys,
	// control-plane cache). Empty resolves to DefaultDir, the platform user
	// config directory. The directory is created and only touched when the
	// tailnet is enabled.
	Dir string
}

// Group is the set of listeners the daemon accepts client connections on. It
// always carries the LAN listener; the tailnet listener is produced lazily by
// ListenTailnet, and only when an authkey was configured.
type Group struct {
	// LAN is the plain TCP listener bound to ListenAddr. Always non-nil
	// after a successful New, even in degraded mode.
	LAN net.Listener

	// ts is the embedded Tailscale server, constructed but NOT started. It
	// is unexported so callers cannot accidentally trigger Up (which
	// contacts the Tailscale control plane); that only happens inside
	// ListenTailnet.
	ts *tsnet.Server

	// started reports whether ListenTailnet succeeded, i.e. whether the
	// node's initialization actually began. tsnet.Server.Close panics on a
	// server that was never started (its sys bus is nil), so Close must not
	// call it before started is true.
	started bool

	// port is the port number the tailnet listener serves on the tailnet,
	// derived from ListenAddr (same port, tailnet address).
	port string

	// log is the logger used for the degraded-mode notice and the tsnet
	// backend's debug logs. Always non-nil after New (nil-safe default).
	log *slog.Logger

	// closeOnce makes Close idempotent regardless of what the underlying
	// listeners report on a second close.
	closeOnce sync.Once
	closeErr  error
}

// New builds the listener group. It always opens the LAN listener on
// opts.ListenAddr. When a TS authkey is present (opts.AuthKey, else the
// TS_AUTHKEY env var) it also resolves and creates the state directory and
// constructs a tsnet.Server wired with Hostname/AuthKey/Dir — but starts
// nothing, so construction alone never touches the Tailscale control plane.
// Without an authkey it degrades to a LAN-only group and logs that the
// tailnet is not enabled.
func New(opts Options, logger *slog.Logger) (*Group, error) {
	if logger == nil {
		logger = slog.Default()
	}

	lan, err := net.Listen("tcp", opts.ListenAddr)
	if err != nil {
		return nil, fmt.Errorf("tsnetd: LAN listen on %q: %w", opts.ListenAddr, err)
	}

	authKey := opts.AuthKey
	if authKey == "" {
		authKey = os.Getenv(envAuthKey)
	}

	g := &Group{
		LAN:  lan,
		log:  logger,
		port: portOf(opts.ListenAddr),
	}

	// No authkey anywhere: degrade to LAN-only. No tailscale state is
	// created and the control plane is never contacted.
	if authKey == "" {
		logger.Warn("tailnet 未启用：未配置 TS authkey，仅 LAN 监听")
		return g, nil
	}

	// Tailnet enabled: resolve and create the state dir, then construct (not
	// start) the tsnet server. On any failure undo the LAN listener so New
	// never leaks a socket.
	dir := opts.Dir
	if dir == "" {
		dir, err = DefaultDir()
		if err != nil {
			lan.Close()
			return nil, fmt.Errorf("tsnetd: resolve default state dir: %w", err)
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		lan.Close()
		return nil, fmt.Errorf("tsnetd: create state dir %q: %w", dir, err)
	}

	g.ts = &tsnet.Server{
		Hostname: opts.Hostname,
		AuthKey:  authKey,
		Dir:      dir,
		Logf: func(format string, args ...any) {
			// tsnet's Logf is printf-style; slog takes a single message, so
			// format first then log at debug level.
			logger.Debug("tsnet " + fmt.Sprintf(format, args...))
		},
	}
	logger.Info("tailnet 已启用", "hostname", opts.Hostname, "state_dir", dir)
	return g, nil
}

// DefaultDir returns the platform user-config directory that tsnet node
// state is persisted to when Options.Dir is empty. It is the Go user config
// directory (e.g. $XDG_CONFIG_HOME or ~/.config on Linux) joined with
// agentmirror/tsnet, keeping per-user state without root privileges.
func DefaultDir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", fmt.Errorf("tsnetd: resolve user config dir: %w", err)
	}
	return filepath.Join(base, "agentmirror", "tsnet"), nil
}

// TailnetEnabled reports whether the group carries an embedded Tailscale
// node, i.e. whether a TS authkey was configured.
func (g *Group) TailnetEnabled() bool {
	return g.ts != nil
}

// ListenTailnet starts the embedded node — the first call is what contacts
// the Tailscale control plane — and returns the tailnet listener serving the
// group's port. In degraded mode it returns ErrTailnetDisabled without doing
// anything. Callers that only want LAN connectivity must not call this.
func (g *Group) ListenTailnet() (net.Listener, error) {
	if g.ts == nil {
		return nil, ErrTailnetDisabled
	}
	ln, err := g.ts.Listen("tcp", ":"+g.port)
	if err != nil {
		return nil, fmt.Errorf("tsnetd: tailnet listen on :%s: %w", g.port, err)
	}
	g.started = true
	return ln, nil
}

// Close releases the LAN listener and, if an embedded node was started,
// shuts it down. It is safe to call multiple times: the underlying close
// runs once. An embedded node that was constructed but never started is left
// untouched (calling tsnet.Close on it would panic), which can only happen
// after a failed ListenTailnet.
func (g *Group) Close() error {
	g.closeOnce.Do(func() {
		var errs []error
		if g.ts != nil && g.started {
			if err := g.ts.Close(); err != nil {
				errs = append(errs, err)
			}
		}
		if g.LAN != nil {
			if err := g.LAN.Close(); err != nil {
				errs = append(errs, err)
			}
		}
		g.closeErr = errors.Join(errs...)
	})
	return g.closeErr
}

// portOf extracts the port number from a host:port address, returning "0"
// when the address has no parseable port. The tailnet listener reuses the
// same port number on the tailnet address space.
func portOf(addr string) string {
	if _, port, err := net.SplitHostPort(addr); err == nil {
		return port
	}
	return "0"
}
