package discovery

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"syscall"
)

// DiscoverSockets freshly enumerates an already-discovered set of sockets. It
// never walks a socket directory and never falls back to Discover. In contrast
// to best-effort global discovery, a routed server failure is an error, NOT an
// authoritative empty workspace. A socket proven removed is an empty success.
// scanServer applies the existing per-socket timeout; ctx bounds the whole query.
func DiscoverSockets(ctx context.Context, logger *slog.Logger, sockets []string) (*Model, error) {
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	var panes []Pane
	seen := make(map[string]struct{}, len(sockets))
	for _, socket := range sockets {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if _, duplicate := seen[socket]; duplicate {
			continue
		}
		seen[socket] = struct{}{}
		if !filepath.IsAbs(socket) || !classifySocketDirectory(filepath.Dir(socket)).allowed || !classifySocket(socket).allowed {
			return nil, fmt.Errorf("discovery: scoped socket outside discovery boundary")
		}
		st, err := os.Lstat(socket)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("discovery: scoped socket stat: %w", err)
		}
		if st.Mode()&os.ModeSocket == 0 {
			return nil, fmt.Errorf("discovery: scoped path is no longer a socket")
		}
		// Do not use the global stale cache here. A skipped known socket is
		// not evidence of zero panes, and an explicit refresh must retry it.
		ps, err := scanServer(ctx, socket, logger)
		if err != nil {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			// A server may exit between Lstat and list-panes. Only a proven
			// removed socket permits an authoritative empty result.
			if _, statErr := os.Lstat(socket); os.IsNotExist(statErr) {
				continue
			}
			// tmux can leave its filesystem socket after its last session exits.
			// Refused/absent listeners prove no server; a timeout or other failure
			// does not. Preserve the socket file and never turn a slow server empty.
			conn, dialErr := net.DialTimeout("unix", socket, probeTO)
			if conn != nil {
				_ = conn.Close()
			}
			if errors.Is(dialErr, syscall.ECONNREFUSED) || errors.Is(dialErr, syscall.ENOENT) {
				continue
			}
			return nil, fmt.Errorf("discovery: scoped server query failed: %w", err)
		}
		panes = append(panes, ps...)
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return buildModel(panes), nil
}
