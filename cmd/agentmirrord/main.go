// Command agentmirrord is the service-side daemon of remote-agent
// agentmirror (working title): a sidecar that mirrors the user's existing
// tmux sessions to the Android app over WebSocket.
//
// Per the sidecar philosophy (requirement 001) the daemon never restarts or
// reconfigures the host's tmux; it attaches to the tmux servers already
// running and streams pane content. Configuration is flags plus environment
// variables — no config file (single-binary deployment).
package main

import (
	"context"
	"errors"
	"flag"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/remote-agent/agentmirror/internal/config"
)

func main() {
	// Run returns an exit code; calling os.Exit here keeps the rest of the
	// package testable without forking a process.
	os.Exit(run(os.Args[1:]))
}

// run wires configuration, logging, and graceful shutdown. It returns the
// process exit code: 0 on clean shutdown, 1 on startup failure.
func run(args []string) int {
	cfg, err := config.Load(args)
	if err != nil {
		// A help request is a clean exit, not a failure: the flag package
		// already printed usage to stdout/stderr, so just stop here.
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		// No logger exists yet; slog's default handler to stderr is fine.
		slog.Error("failed to load config", "err", err)
		return 1
	}

	logger := newLogger(cfg.LogLevel)
	logger.Info("agentmirrord starting",
		"listen", cfg.ListenAddr,
		"qr_listen", cfg.QRListenAddr,
	)

	// ctx is cancelled on SIGINT/SIGTERM so long-running components can
	// shut down cooperatively instead of being killed mid-write.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// The service body (discovery, bridge, ws-api, tsnetd) lands in later
	// tasks on this skeleton; until then we just wait for the first signal
	// to prove graceful shutdown works end to end.
	<-ctx.Done()
	stop()
	logger.Info("shutting down")
	return 0
}

// newLogger builds the structured logger used by the whole daemon. level is
// one of debug|info|warn|error; anything unrecognized falls back to info so
// a typo never silences the process.
func newLogger(level string) *slog.Logger {
	var l slog.Level
	switch level {
	case "debug":
		l = slog.LevelDebug
	case "warn":
		l = slog.LevelWarn
	case "error":
		l = slog.LevelError
	default:
		l = slog.LevelInfo
	}
	return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: l}))
}
