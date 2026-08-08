// Command agentmirrord is the service-side daemon of remote-agent
// agentmirror (working title): a sidecar that mirrors the user's existing
// tmux sessions to the Android app over WebSocket.
//
// Per the sidecar philosophy (requirement 001) the daemon never restarts or
// reconfigures the host's tmux; it attaches to the tmux servers already
// running and streams pane content. Configuration is flags plus environment
// variables — no config file (single-binary deployment).
//
// The daemon wires four pieces:
//
//   - config: flags/env → resolved settings (including the pairing token);
//   - tsnetd: the listener group — LAN always, tailnet when a TS authkey is
//     configured (requirement 007);
//   - api: the WS API server (internal/api) whose handler serves /ws and
//     /upload on the group's listener;
//   - graceful shutdown on SIGINT/SIGTERM.
package main

import (
	"context"
	"errors"
	"flag"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/remote-agent/agentmirror/internal/api"
	"github.com/remote-agent/agentmirror/internal/config"
	"github.com/remote-agent/agentmirror/internal/tsnetd"
)

func main() {
	// Run returns an exit code; calling os.Exit here keeps the rest of the
	// package testable without forking a process.
	os.Exit(run(os.Args[1:]))
}

// run wires configuration, logging, listeners, and graceful shutdown. It
// returns the process exit code: 0 on clean shutdown, 1 on startup failure.
func run(args []string) int {
	cfg, err := config.Load(args)
	if err != nil {
		// A help request is a clean exit, not a failure: the flag package
		// already printed usage to stdout/stderr, so just stop here.
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		slog.Error("failed to load config", "err", err)
		return 1
	}

	logger := newLogger(cfg.LogLevel)
	logger.Info("agentmirrord starting",
		"listen", cfg.ListenAddr,
		"qr_listen", cfg.QRListenAddr,
		"token_configured", cfg.Token != "",
		"upload_dir", cfg.UploadDir,
		"max_upload_mib", cfg.MaxUploadBytes/(1<<20),
		"list_interval", cfg.ListInterval,
	)

	// The API server consumes the resolved settings. The token is write-only:
	// it is passed into the validator seam and never logged or echoed here
	// (docs/protocol.md §9).
	apiServer := api.NewServer(api.Options{
		Token:          cfg.Token,
		UploadDir:      cfg.UploadDir,
		MaxUploadBytes: cfg.MaxUploadBytes,
		MaxInputBytes:  int(cfg.MaxInputBytes),
		ListInterval:   cfg.ListInterval,
		Log:            logger,
	})
	defer apiServer.Close()

	// The listener group always opens the LAN listener; the tailnet listener is
	// created lazily only when a TS authkey is configured. No authkey means a
	// LAN-only daemon with zero control-plane contact (requirement 007 red line).
	group, err := tsnetd.New(tsnetd.Options{
		ListenAddr: cfg.ListenAddr,
		Hostname:   hostname(),
		AuthKey:    os.Getenv("TS_AUTHKEY"),
	}, logger)
	if err != nil {
		logger.Error("failed to open listeners", "err", err)
		return 1
	}
	defer group.Close()

	// Serve the API handler on every listener the group provides. The LAN
	// listener is always present; the tailnet listener is added when enabled.
	srv := &http.Server{Handler: apiServer.Handler()}
	serveErr := make(chan error, 1)
	go func() {
		serveErr <- srv.Serve(group.LAN)
	}()
	if group.TailnetEnabled() {
		tailLn, err := group.ListenTailnet()
		if err != nil {
			logger.Error("failed to listen on tailnet", "err", err)
			return 1
		}
		defer tailLn.Close()
		go func() {
			serveErr <- srv.Serve(tailLn)
		}()
	}

	// ctx is cancelled on SIGINT/SIGTERM so long-running components shut down
	// cooperatively instead of being killed mid-write.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	select {
	case <-ctx.Done():
		logger.Info("shutting down")
	case err := <-serveErr:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("serve failed", "err", err)
			return 1
		}
	}
	if err := srv.Shutdown(context.Background()); err != nil {
		logger.Warn("shutdown", "err", err)
	}
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

// hostname returns the machine hostname for the tailnet node name. An error
// leaves it empty (tsnetd falls back gracefully).
func hostname() string {
	h, err := os.Hostname()
	if err != nil {
		return ""
	}
	return h
}
