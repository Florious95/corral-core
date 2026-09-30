// Command agentmirrord is the service-side daemon of AgentMirror
// (product github.com/agentmirror/agentmirror): a sidecar that mirrors the
// user's existing tmux sessions to the Android app over WebSocket.
//
// Per the sidecar philosophy (requirement 001) the daemon never restarts or
// reconfigures the host's tmux; it attaches to the tmux servers already
// running and streams pane content. Configuration is flags plus environment
// variables — no config file (single-binary deployment).
//
// The daemon wires four pieces:
//
//   - config: flags/env → resolved settings (including the pairing token);
//   - pairing: resolves/generates the token and prints the QR + plain-text
//     onboarding guide to stdout (the token's two legal exits, §9);
//   - tsnetd: the listener group — LAN always, tailnet when a TS authkey is
//     configured (requirement 007);
//   - api: the WS API server (internal/api) whose handler serves /ws and
//     /upload on the group's listener;
//   - graceful shutdown on SIGINT/SIGTERM.
//
// The four internal modules it imports are declared as the dependency surface
// below so the architecture wiki can derive the graph from code.
// @consumes internal/config
// @consumes internal/pairing
// @consumes internal/tsnetd
// @consumes internal/api
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/agentmirror/agentmirror/internal/api"
	"github.com/agentmirror/agentmirror/internal/config"
	"github.com/agentmirror/agentmirror/internal/pairing"
	"github.com/agentmirror/agentmirror/internal/tsnetd"
)

// newTSNetGroup is a narrow wiring seam so cmd tests can prove the fully
// resolved state directory reaches the tsnetd consumer without starting a
// real control-plane node.
var newTSNetGroup = tsnetd.New

func main() {
	// Run returns an exit code; calling os.Exit here keeps the rest of the
	// package testable without forking a process.
	os.Exit(run(os.Args[1:]))
}

// run wires configuration, logging, listeners, and graceful shutdown. It
// returns the process exit code: 0 on clean shutdown, 1 on startup failure.
// @contract
// @pre none — args 可为空（全部走默认值）；调用方通常传 os.Args[1:]
// @post 干净关闭（ctx 取消 / SIGINT / SIGTERM）与 -h/--help 请求返回 0；任何启动或 serve 失败返回 1
// @err 配置加载失败、状态目录解析失败、单实例锁被占、token 解析失败、监听器打开失败、tailnet Up/ListenTailnet 失败、引导打印失败、serve 非 ErrServerClosed 失败——均记日志并返回 1
// @inv 单实例守卫在整个 run 生命周期持有；token 值永不落日志
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
	// Install signal cancellation before any control-plane handshake so SIGTERM
	// can abort tsnet Up and reach deferred cleanup instead of waiting 60 seconds.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Enforce the single-instance guard before opening any listener (taskbook
	// #fix-daemon-idle-cpu: orphan instances each burned ~17.5% CPU). A second
	// launch finds the flock held and fails loudly; the pidfile sits next to
	// the pairing token under the shared per-user config root.
	stateDir, err := resolveStateDir(cfg.StateDir)
	if err != nil {
		logger.Error("failed to resolve state dir", "err", err)
		return 1
	}
	pidfilePath, releaseLock, err := acquirePidfile(stateDir, "agentmirrord")
	if err != nil {
		logger.Error("single-instance guard refused startup", "err", err)
		return 1
	}
	defer releaseLock()
	logger.Info("single-instance guard acquired", "pidfile", pidfilePath)

	// Resolve the pairing token before anything else: an explicit flag/env
	// token wins, otherwise one is generated and persisted for reuse. Failure
	// is fatal — booting with an empty token would accept an empty-token auth,
	// which is the anonymous-bypass red line (§9). The value is never logged;
	// only its source and store path are.
	token, err := resolveToken(cfg, logger)
	if err != nil {
		logger.Error("failed to resolve pairing token", "err", err)
		return 1
	}

	logger.Info("agentmirrord starting",
		"listen", cfg.ListenAddr,
		"qr_listen", cfg.QRListenAddr,
		"token_source", tokenSource(cfg.Token),
		"upload_dir", cfg.UploadDir,
		"max_upload_mib", cfg.MaxUploadBytes/(1<<20),
		"list_interval", cfg.ListInterval,
	)

	// Wire the agent-state pipeline behind the StateProvider seam (task
	// fix-state-wiring, defect D-1). Before this the provider defaulted to
	// always-unknown, so every session rendered grey and blocked/done
	// notifications never fired. The provider runs its own background sampling
	// (bounded IO, TTL cache) and is closed with the api server.
	stateProvider := api.NewStateProvider(logger)
	defer stateProvider.Close()

	// The API server consumes the resolved settings. The token is write-only:
	// it is passed into the validator seam and never logged or echoed here
	// (docs/protocol.md §9).
	apiServer := api.NewServer(api.Options{
		Token:          token,
		StateProvider:  stateProvider,
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
	// The key comes from env-only TS_AUTHKEY (argv is forbidden because process
	// lists/shell history expose it); it is a token-grade secret — never logged or echoed.
	group, err := newTSNetGroup(tsnetd.Options{
		ListenAddr: cfg.ListenAddr,
		Hostname:   hostname(),
		AuthKey:    cfg.TSAuthKey,
		// Keep node keys beside the daemon state but in their own subtree. The
		// degraded path does not create it because tsnetd returns before Dir use.
		Dir: filepath.Join(stateDir, "tsnet"),
		// 自建控制面接缝（headscale，011 部署自由）：env-only，缺省官方控制面。
		ControlURL: os.Getenv("TS_CONTROL_URL"),
	}, logger)
	if err != nil {
		logger.Error("failed to open listeners", "err", err)
		return 1
	}
	defer group.Close()

	// feat-ts-wire: with an authkey the node must be UP before the guide is
	// printed — the QR needs the tailnet 100.x address (a userspace tsnet node
	// has no host NIC; Up is the only source) and carries the authkey itself
	// (011 pre-authorized distribution). Up failure is fatal: the user asked
	// for a tailnet, silently degrading to LAN-only hides the failure (config
	// fail-fast precedent + 工程红线5 失败可见). Bounded by a timeout so a bad
	// key cannot hang startup forever in the control-plane handshake.
	var (
		tailLn    net.Listener
		tailnetIP net.IP
	)
	if group.TailnetEnabled() {
		logger.Info("tailnet 入网中（等待 Tailscale 控制面握手）…")
		upCtx, cancel := context.WithTimeout(ctx, tailnetUpTimeout)
		tailnetIP, err = group.Up(upCtx)
		cancel()
		if err != nil {
			if ctx.Err() != nil {
				logger.Info("shutting down during tailnet startup")
				return 0
			}
			logger.Error("tailnet up failed (authkey 无效/过期或控制面不可达)", "err", err)
			return 1
		}
		if tailLn, err = group.ListenTailnet(); err != nil {
			logger.Error("failed to listen on tailnet", "err", err)
			return 1
		}
		defer tailLn.Close()
		logger.Info("tailnet 已入网", "ip", ipString(tailnetIP))
	}

	// Print the QR + plain-text guide to stdout now that we know the listener
	// set. This is the user-facing onboarding and the token's legal exit; a
	// failure to print it must stop the daemon, because a token the user never
	// sees leaves them unable to pair. All detected candidate addresses are
	// listed so the user can re-enter another host by hand (task
	// fix-qr-host-detect: the QR carries the best host, the guide the rest).
	// The tailnet address and authkey ride in via the tswire params (§2.1).
	if err := printPairingGuide(os.Stdout, token, listenPort(group.LAN.Addr().String()), group.TailnetEnabled(), cfg.Host, tailnetIP, cfg.TSAuthKey); err != nil {
		logger.Error("failed to print pairing guide", "err", err)
		return 1
	}

	// Serve the API handler on every listener the group provides. The LAN
	// listener is always present; the tailnet listener was opened above when
	// enabled (before the guide, so the QR could carry the tailnet address).
	srv := &http.Server{Handler: apiServer.Handler()}
	serveErr := make(chan error, 1)
	go func() {
		serveErr <- srv.Serve(group.LAN)
	}()
	if tailLn != nil {
		go func() {
			serveErr <- srv.Serve(tailLn)
		}()
	}

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

// resolveToken returns the effective pairing token for this daemon run: an
// explicitly configured token (flag/env, config.Token) wins; otherwise a token
// is auto-generated and persisted under the user config dir so restarts reuse
// it (already-paired devices stay paired). See resolveTokenDir for the
// directory override used by tests.
func resolveToken(cfg config.Config, logger *slog.Logger) (string, error) {
	return resolveTokenDir(cfg, logger, "")
}

// resolveTokenDir is resolveToken with an injectable store directory. With an
// empty dirOverride it resolves the platform user config dir; tests pass a
// temp dir to avoid touching the real store.
// @contract
// @pre cfg 为已解析配置；dirOverride 可空（空则用平台用户配置目录）
// @post 显式 token 直接返回且不持久化；自动路径生成并持久化到 dir；返回的 token 永不为空且无错误
// @err 用户配置目录解析失败或 EnsureToken 失败返回非 nil error；绝不返回空 token + nil error
// @inv token 值永不落日志（只记 source 与 store path）
func resolveTokenDir(cfg config.Config, logger *slog.Logger, dirOverride string) (string, error) {
	if cfg.Token != "" {
		logger.Info("pairing token source=explicit")
		return cfg.Token, nil
	}
	dir := dirOverride
	if dir == "" {
		var err error
		if dir, err = pairing.TokenDir(); err != nil {
			return "", fmt.Errorf("resolve token dir: %w", err)
		}
	}
	tok, err := pairing.EnsureToken("", dir)
	if err != nil {
		return "", err
	}
	// The store path is logged, never the token value (§9).
	logger.Info("pairing token source=auto", "path", dir)
	return tok, nil
}

// tokenSource labels the token's origin for the startup log line. The boolean
// alone was ambiguous once auto-generation arrived; the label stays token-free.
func tokenSource(explicit string) string {
	if explicit != "" {
		return "explicit"
	}
	return "auto"
}

// printPairingGuide writes the onboarding QR + guide to w, listing every
// detected candidate address after the QR's primary host (task
// fix-qr-host-detect). It is the thin wiring seam around pairing.PrintOnboardingAll
// (not PrintOnboarding: the daemon wants the full-candidate guide) so tests can
// capture the output without forking the daemon. hostOverride, when non-empty,
// pins the QR's primary address and beats every automatic probe. tailnetIP (nil
// when disabled) is the embedded node's address merged into the candidate set;
// tsAuthKey rides the QR payload only — the guide never prints it
// (feat-ts-wire, §2.1 red line).
// @contract
// @pre token 与 port 非空；w 非 nil
// @post 写出含 token 的 QR 与明文指引；非回环候选地址全部列出；tailnet 启用时列出 tailnet 地址；tsAuthKey 永不出现在明文
// @err 仅 QR 渲染或 payload 序列化失败返回非 nil error（透传 pairing.PrintOnboardingAll）
// @inv token 只出现于 QR 与明文指引（两个合法出口）
func printPairingGuide(w io.Writer, token, port string, tailnet bool, hostOverride string, tailnetIP net.IP, tsAuthKey string) error {
	// resolve the primary host once so the guide's primary and the QR agree.
	// The override may come from the -host flag/env (already folded into
	// cfg.Host); pass it in so PrimaryHost can pick it up deterministically.
	host := hostOverride
	if host == "" {
		host = automaticPairingHost(pairing.PrimaryHost(), tailnetIP)
	}
	return pairing.PrintOnboardingAll(pairing.Onboarding{
		Token:          token,
		Port:           port,
		TailnetEnabled: tailnet,
		TSAuthKey:      tsAuthKey,
	}, pairing.WithTailnet(pairing.DetectAddresses(), tailnetIP), host, w)
}

// automaticPairingHost avoids emitting a loopback primary when the userspace
// tsnet node is the host's only remotely reachable address. A real LAN primary
// still wins, and an explicit -host/env override is handled before this call.
func automaticPairingHost(detected string, tailnetIP net.IP) string {
	if ip := net.ParseIP(detected); ip != nil && ip.IsLoopback() && tailnetIP != nil {
		return tailnetIP.String()
	}
	return detected
}

// ipString renders an IP for logging, tolerating the nil (v6-only tailnet)
// case Up may legally return.
func ipString(ip net.IP) string {
	if ip == nil {
		return "<none>"
	}
	return ip.String()
}

// tailnetUpTimeout bounds the control-plane handshake at startup: past it the
// daemon fails loudly instead of hanging on a bad/expired authkey (红线5).
const tailnetUpTimeout = 60 * time.Second

// printOnboardingSeam renders the guide for an injected address set, exposing
// pairing's internal render path to tests that must force the degraded case.
func printOnboardingSeam(w io.Writer, o pairing.Onboarding, addrs []pairing.Address, primary string) error {
	return pairing.PrintOnboardingWith(o, addrs, primary, w)
}

// printOnboardingSeamAll is printOnboardingSeam for the full-candidate view:
// it renders every candidate address, so tests can assert the whole-candidate
// guide contract without probing the real network.
func printOnboardingSeamAll(w io.Writer, o pairing.Onboarding, addrs []pairing.Address, primary string) error {
	return pairing.PrintOnboardingAll(o, addrs, primary, w)
}

// listenPort extracts the port number from a host:port listen address,
// defaulting to the documented 9900 when the address has no parseable port.
func listenPort(addr string) string {
	if _, port, err := net.SplitHostPort(addr); err == nil {
		return port
	}
	return "9900"
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
