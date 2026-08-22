// Package tsnetd embeds Tailscale networking (tsnet) so the daemon's
// WebSocket service is reachable over the tailnet as well as the LAN.
//
// See doc.go for the full package contract.
package tsnetd

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
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

	// ControlURL is the coordination-server URL. Empty means the official
	// Tailscale control plane; non-empty points at a self-hosted one
	// (headscale — deployment freedom per requirement 011, feat-ts-wire).
	// Configured via the TS_CONTROL_URL environment variable in cmd.
	ControlURL string
}

// Group is the set of listeners the daemon accepts client connections on. It
// always carries the LAN listener; the tailnet listener is produced lazily by
// ListenTailnet, and only when an authkey was configured.
type Group struct {
	// LAN is the plain TCP listener bound to ListenAddr. Always non-nil
	// after a successful New, even in degraded mode.
	LAN net.Listener

	// ts is the embedded Tailscale server, constructed but NOT started. It
	// is unexported so callers cannot bypass Group.Up/ListenTailnet (the
	// operations that contact the Tailscale control plane).
	ts *tsnet.Server

	// started reports whether Up/ListenTailnet invoked a tsnet method that calls
	// Start. It is set even when that method returns an error: a cancelled or
	// failed Up still owns backend resources that Close must release. Close must
	// only skip a server that was constructed but never asked to start.
	started bool

	// port is the port number the tailnet listener serves on the tailnet,
	// derived from ListenAddr (same port, tailnet address).
	port string

	// authKey is retained only so external tsnet errors/log lines can be redacted
	// before leaving this package. It is never logged or exported.
	authKey string

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
// @contract
// @pre opts.ListenAddr 非空合法（net.Listen 的要求）；opts.AuthKey 为空时读 TS_AUTHKEY 环境变量
// @post 返回的 Group 恒带已打开的 LAN listener；有 authkey 时 ts 节点被构造但未启动（零控制面接触）；无 authkey 时降级为 LAN-only 且不创建任何 state dir
// @err LAN 监听失败、TS_DEBUG_REGISTER 开启且配置了 authkey、默认 state dir 解析失败、state dir 创建失败
// @inv LAN listener 在成功返回后归调用者所有（Close 负责释放）；失败路径不外泄任何已打开的 socket
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
	if raw := os.Getenv("TS_DEBUG_REGISTER"); authKey != "" && raw != "" {
		enabled, parseErr := strconv.ParseBool(raw)
		if parseErr != nil || enabled {
			// This upstream debug path writes the complete registration request,
			// including AuthKey, to tsnet's private logtail before Server.Logf can
			// redact it. Reject the unsafe combination without echoing either value.
			lan.Close()
			return nil, errors.New("tsnetd: TS_DEBUG_REGISTER must be disabled when a TS authkey is configured")
		}
	}

	g := &Group{
		LAN:     lan,
		log:     logger,
		port:    portOf(lan.Addr().String()),
		authKey: authKey,
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
		Hostname:   opts.Hostname,
		AuthKey:    authKey,
		Dir:        dir,
		ControlURL: opts.ControlURL,
		Logf: func(format string, args ...any) {
			// tsnet's Logf is printf-style; slog takes a single message, so
			// format first, redact the configured credential, then log. Upstream
			// debug modes may include a full RegisterRequest containing AuthKey.
			logger.Debug("tsnet " + redactAuthKey(fmt.Sprintf(format, args...), authKey))
		},
	}
	logger.Info("tailnet 已启用", "hostname", opts.Hostname, "state_dir", dir)
	return g, nil
}

// DefaultDir returns the platform user-config directory that tsnet node
// state is persisted to when Options.Dir is empty. It is the Go user config
// directory (e.g. $XDG_CONFIG_HOME or ~/.config on Linux) joined with
// agentmirror/tsnet, keeping per-user state without root privileges.
// @contract
// @pre none
// @post 返回 <用户配置目录>/agentmirror/tsnet 的绝对路径；不创建目录
// @err 用户配置目录不可解析时返回包装错误
// @inv none
func DefaultDir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", fmt.Errorf("tsnetd: resolve user config dir: %w", err)
	}
	return filepath.Join(base, "agentmirror", "tsnet"), nil
}

// TailnetEnabled reports whether the group carries an embedded Tailscale
// node, i.e. whether a TS authkey was configured.
// @contract
// @pre none
// @post 返回 true 当且仅当 New 时配置了 authkey（ts 节点非 nil）
// @err none
// @inv 与 ListenTailnet/Up 能否工作一致：true 时二者可调用，false 时二者返回 ErrTailnetDisabled
func (g *Group) TailnetEnabled() bool {
	return g.ts != nil
}

// ListenTailnet starts the embedded node — the first call is what contacts
// the Tailscale control plane — and returns the tailnet listener serving the
// group's port. In degraded mode it returns ErrTailnetDisabled without doing
// anything. Callers that only want LAN connectivity must not call this.
// @contract
// @pre TailnetEnabled() 为 true（否则返回 ErrTailnetDisabled）；同一 Group 上不应与 Up 重复启动节点
// @post 成功时返回 tailnet listener（首次调用即触发 tsnet.Start 与控制面握手）；失败时组内节点标记为已启动，供 Close 回收
// @err degraded 模式返回 ErrTailnetDisabled；tsnet.Listen 失败返回包装错误（authkey 已从错误文本脱敏）
// @inv 调用后 started 恒为 true（即使失败）；不影响 LAN listener
func (g *Group) ListenTailnet() (net.Listener, error) {
	if g.ts == nil {
		return nil, ErrTailnetDisabled
	}
	ln, err := g.ts.Listen("tcp", ":"+g.port)
	// Listen calls tsnet.Start even when the later listener setup fails.
	g.started = true
	if err != nil {
		return nil, fmt.Errorf("tsnetd: tailnet listen on :%s: %s", g.port, redactAuthKey(err.Error(), g.authKey))
	}
	return ln, nil
}

// Up connects the embedded node to the tailnet and blocks until it is
// running, returning the node's tailnet IPv4 (the 100.64.0.0/10 address the
// pairing QR appends to its candidates, task feat-ts-wire). A userspace tsnet
// node has no host NIC, so this is the only way the daemon can learn its own
// tailnet address — interface probing cannot see it. In degraded mode it
// returns ErrTailnetDisabled without touching the network. Cancel/timeout via
// ctx: an invalid authkey otherwise blocks forever in the control-plane
// handshake, and startup must fail visibly instead (工程红线5 失败可见).
// @contract
// @pre TailnetEnabled() 为 true；ctx 非 nil；调用者需对 ctx 设超时/取消（坏 authkey 会在控制面握手处阻塞）
// @post 成功时节点已入网并返回其 tailnet IPv4（100.64.0.0/10）；v6-only tailnet 返回 nil IP（非错误）
// @err degraded 模式返回 ErrTailnetDisabled；tsnet.Up 失败返回包装错误（authkey 已脱敏）；ctx 超时/取消同样返回错误
// @inv 调用后 started 恒为 true（即使失败）；返回的 IP 是 pairing 侧注入候选集的唯一来源（WithTailnet）
func (g *Group) Up(ctx context.Context) (net.IP, error) {
	if g.ts == nil {
		return nil, ErrTailnetDisabled
	}
	st, err := g.ts.Up(ctx)
	// Up calls LocalClient -> Start before it can return any error. Mark the
	// attempted node as closeable so timeout/bad-key paths do not leak it.
	g.started = true
	if err != nil {
		return nil, fmt.Errorf("tsnetd: tailnet up: %s", redactAuthKey(err.Error(), g.authKey))
	}
	for _, a := range st.TailscaleIPs {
		if a.Is4() {
			return a.AsSlice(), nil
		}
	}
	// Running but no IPv4 (v6-only tailnet): not an error — the caller just
	// has no v4 address to advertise (pairing skips IPv6 for now).
	return nil, nil
}

// Close releases the LAN listener and, if an embedded node was started,
// shuts it down. It is safe to call multiple times: the underlying close
// runs once. An embedded node that was constructed but never started is left
// untouched (calling tsnet.Close on it would panic), which happens when the
// group was constructed but neither Up nor ListenTailnet was attempted.
// @contract
// @pre 任意次调用均安全（幂等）
// @post 首次调用关闭已启动的 ts 节点与 LAN listener；后续调用为 no-op 并返回首次错误
// @err 首次关闭中 ts 节点或 LAN listener 的关闭错误以 errors.Join 汇总返回
// @inv Close 后 LAN listener 不再 Accept；未启动的 ts 节点（started=false）不被触碰（tsnet.Close 会 panic）
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

// redactAuthKey removes the exact configured credential from external text.
// Empty keys are a no-op: strings.ReplaceAll with an empty old value would
// corrupt every log line.
func redactAuthKey(text, authKey string) string {
	if authKey == "" {
		return text
	}
	return strings.ReplaceAll(text, authKey, "[REDACTED]")
}
