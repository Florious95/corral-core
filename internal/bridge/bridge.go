package bridge

// bridge.go implements the single-pane terminal bridge primitive: first-frame
// snapshot (capture-pane -e), scrollback paging (capture-pane -S/-E), whole
// input injection with a decidable ack (send-keys / paste-buffer, requirement
// 003), and resize (window-size latest + resize-window, requirement 005).
//
// A Pane is mirror-and-inject only: it never kills, detaches, or otherwise
// mutates the target pane's runtime state beyond what the caller explicitly
// requests. That is the hard red line of this task.

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Pane bridges a single tmux pane. The pane is addressed by its bare pane id
// (e.g. "%0") as produced by the discovery layer; bare ids are required
// because tmux resolves session:window.pane targets to every pane in the
// window, which breaks exact existence checks. All methods share the pane's
// per-command timeout.
type Pane struct {
	socket  string
	target  string // bare pane id "%N"
	timeout time.Duration
}

// NewPane returns a Pane bound to a bare pane id on the given tmux socket.
// The empty socket means the tmux default socket. The default per-command
// timeout applies unless overridden via WithTimeout.
// @contract
// @pre none — 参数不在本函数校验；paneID 应为 discovery 层产出的裸 pane id（"%N"）
// @post 返回绑定 socket/paneID 的 Pane，timeout 为 defaultTimeout
// @err none
// @inv none — 纯构造，不触碰 tmux
func NewPane(socket, paneID string) *Pane {
	return &Pane{socket: socket, target: paneID, timeout: defaultTimeout}
}

// WithTimeout returns a copy of p with a custom per-command timeout,
// overriding the default set by NewPane. The receiver p is left unchanged.
func (p *Pane) WithTimeout(d time.Duration) *Pane {
	cp := *p
	cp.timeout = d
	return &cp
}

// Snapshot captures the pane's visible screen as raw terminal bytes, ANSI
// color escapes preserved (capture-pane -e). It is the first frame a
// subscriber draws before switching to the incremental stream (requirement
// 006's "video fast-open").
// @contract
// @pre none — pane 存在性由 tmux 在调用时惰性判定
// @post 返回原始终端字节且 ANSI 转义保留；pane 运行态不被修改
// @err 目标 pane 不存在→ErrPaneNotFound；server 不可达→ErrServerUnreachable；超时→ErrTmuxTimeout
// @inv none — 只读操作
func (p *Pane) Snapshot(ctx context.Context) ([]byte, error) {
	return runTmux(ctx, p.socket, p.timeout, "capture-pane", "-e", "-p", "-t", p.target)
}

// CursorPos reads the pane's current cursor position (0-based column x,
// row y) from the server. capture-pane output carries no cursor state, so a
// snapshot consumer that replays it must re-anchor the client cursor
// separately — otherwise the next output without absolute addressing (e.g. a
// shell's SIGWINCH prompt redraw, plain "\r ESC[K …") lands wherever the
// replay left the cursor instead of where the real cursor is
// (fix-term-residuals: phantom bottom-row prompt on device).
// @contract
// @pre none — pane 存在性由 tmux 在调用时惰性判定
// @post 返回 0-based 列 x 与行 y
// @err tmux 失败→ErrPaneNotFound/ErrServerUnreachable/ErrTmuxTimeout；cursor 输出解析失败→fmt.Errorf
// @inv none — 只读操作
func (p *Pane) CursorPos(ctx context.Context) (x, y int, err error) {
	out, err := runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{cursor_x},#{cursor_y}")
	if err != nil {
		return 0, 0, err
	}
	if _, err := fmt.Sscanf(strings.TrimSpace(string(out)), "%d,%d", &x, &y); err != nil {
		return 0, 0, fmt.Errorf("tmux: parse cursor pos %q: %w", strings.TrimSpace(string(out)), err)
	}
	return x, y, nil
}

// Scrollback fetches one page of the pane's history + screen. start/end are
// capture-pane -S/-E coordinates, TOP-RELATIVE: 0 = screen top row, -1 = the
// row just above the screen, -2 = two rows above, etc. This matches the
// protocol's from_line semantics (§6.3) directly, so the API layer passes
// protocol coordinates through WITHOUT translation. A history-only page uses a
// fully negative range (e.g. -30..-21); the current-screen page uses 0..(height-1);
// a range spanning history + screen is allowed (e.g. -5..4). Returns raw bytes.
// @contract
// @pre start < end（capture-pane -S/-E 坐标，顶部相对：0=屏顶，负=屏上历史）
// @post 返回该页原始终端字节（ANSI 保留，capture-pane -S/-E）
// @err tmux 失败→ErrPaneNotFound/ErrServerUnreachable/ErrTmuxTimeout
// @inv none — 只读操作
func (p *Pane) Scrollback(ctx context.Context, start, end int) ([]byte, error) {
	return runTmux(ctx, p.socket, p.timeout,
		"capture-pane", "-e", "-p", "-t", p.target,
		"-S", strconv.Itoa(start), "-E", strconv.Itoa(end))
}

// Inject sends the whole message in one shot and presses Enter, then returns
// a decidable ack: a non-nil error means the input did not go in (pane was
// already gone or the server unreachable), which is requirement 003's
// "发送必达". Single-line text goes through send-keys -l (literal); multi-line
// text goes through load-buffer + paste-buffer, which tmux handles more
// reliably for embedded newlines. Carriage returns are normalized away so a
// phone line-ending cannot inject stray keystrokes.
// @contract
// @pre 目标 pane 存在（requirePane 前置检查）；text 可为任意内容（"\r" 会被删除）
// @post 整条消息进入 pane 并按一次 Enter；单行走 send-keys -l，多行走 load-buffer + paste-buffer
// @err pane 不存在→ErrPaneNotFound；server 不可达/超时→ErrServerUnreachable/ErrTmuxTimeout
// @inv none — 除注入文本与一次 Enter 外不触碰 pane 运行态
func (p *Pane) Inject(ctx context.Context, text string) error {
	if err := p.requirePane(ctx); err != nil {
		return err
	}
	text = strings.ReplaceAll(text, "\r", "")

	var err error
	if strings.Contains(text, "\n") {
		err = p.pasteMultiline(ctx, text)
	} else {
		_, err = runTmux(ctx, p.socket, p.timeout, "send-keys", "-t", p.target, "-l", "--", text)
	}
	if err != nil {
		return err
	}
	// One Enter commits the whole injected message (whole-input paradigm).
	_, err = runTmux(ctx, p.socket, p.timeout, "send-keys", "-t", p.target, "Enter")
	return err
}

// namedKeys maps a wire special-key name (protocol.Key value, R-1 shortcut
// bar) to the tmux send-keys named key. The closed set is enforced at the
// protocol boundary; this table is the bridge's own defensive lookup, so an
// unknown name is a hard error (ErrInvalidKey), never a silent no-op.
var namedKeys = map[string]string{
	"esc":    "Escape",
	"ctrl_c": "C-c",
	"tab":    "Tab",
	"up":     "Up",
	"down":   "Down",
	"left":   "Left",
	"right":  "Right",
}

// SendKeys sends named special keys to the pane (R-1 shortcut bar, requirement
// 017). Each key is a wire key name ("esc", "ctrl_c", …) mapped to its tmux
// send-keys named key; all keys are sent in one send-keys invocation, in order,
// WITHOUT appending an Enter — the shortcut-bar semantics are "press that key
// once", unlike Inject's "inject then Enter". It returns the same decidable
// ack as Inject (requirement 003): a non-nil error means the keys did not go in
// (unknown key name, pane gone, or server unreachable). An unknown key name
// fails before any tmux call.
// @contract
// @pre pane 存在；每个 key 都属 namedKeys 闭集（否则在任意 tmux 调用前返回 ErrInvalidKey）
// @post 全部命名 key 在单次 send-keys 调用中按序发送，不追加 Enter
// @err 未知 key→ErrInvalidKey；pane 不存在→ErrPaneNotFound；server 不可达/超时→ErrServerUnreachable/ErrTmuxTimeout
// @inv none — 除按键外不触碰 pane 运行态
func (p *Pane) SendKeys(ctx context.Context, keys ...string) error {
	named := make([]string, 0, len(keys))
	for _, k := range keys {
		n, ok := namedKeys[k]
		if !ok {
			return fmt.Errorf("%w: %q", ErrInvalidKey, k)
		}
		named = append(named, n)
	}
	if err := p.requirePane(ctx); err != nil {
		return err
	}
	// Go cannot splice a slice into a variadic call after fixed args, so build
	// the full argv first: send-keys -t <pane> -- <named keys...>.
	args := append([]string{"send-keys", "-t", p.target, "--"}, named...)
	_, err := runTmux(ctx, p.socket, p.timeout, args...)
	return err
}

// pasteMultiline injects a multi-line message via a named tmux buffer: it is
// pasted verbatim (bracketed paste, so the target CLI treats it as one paste
// rather than a burst of keystrokes) and the buffer is deleted on the spot.
func (p *Pane) pasteMultiline(ctx context.Context, text string) error {
	buf := newBufferName()
	// load-buffer -b <name> - reads the buffer from stdin.
	if err := p.loadBuffer(ctx, buf, text); err != nil {
		return err
	}
	if _, err := runTmux(ctx, p.socket, p.timeout,
		"paste-buffer", "-b", buf, "-t", p.target, "-d", "-p"); err != nil {
		return err
	}
	return nil
}

// loadBuffer pipes text into a named tmux buffer. It is a plain stdin pipe to
// tmux, not an exec-wrapped command, because the payload is the stdin stream.
func (p *Pane) loadBuffer(ctx context.Context, name, text string) error {
	cmd, stderr, ctx, cancel := newTmuxCommand(ctx, p.socket, p.timeout, "load-buffer", "-b", name, "-")
	defer cancel()
	cmd.Stdin = strings.NewReader(text)
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return ErrTmuxTimeout
		}
		return classifyTmuxError(stderr.String())
	}
	return nil
}

// requirePane is the exact existence pre-check that makes every ack
// decidable (requirement 003). Because the target is a bare pane id, tmux
// resolves it to exactly one line; a multi-pane resolution or a "can't find"
// from tmux both fail the check.
func (p *Pane) requirePane(ctx context.Context) error {
	out, err := runTmux(ctx, p.socket, p.timeout, "list-panes", "-t", p.target, "-F", "#{pane_id}")
	if err != nil {
		return err
	}
	ids := strings.Fields(string(out))
	if len(ids) != 1 || ids[0] != p.target {
		return fmt.Errorf("%w: target %q resolves to %d panes", ErrPaneNotFound, p.target, len(ids))
	}
	return nil
}

// Resize sets the pane's window to cols x rows (window-size latest + tmux
// 3.2+ resize-window) and returns the pane's actual new size as read back
// from the server, so the caller sees the truth, not the request. Resize is
// a primitive only: the "whose last operation wins" grouping policy
// (requirement 005) belongs to the layer that owns sessions.
// @contract
// @pre pane 存在；cols/rows 为请求尺寸（tmux 侧再约束）
// @post window-size latest 已设、resize-window 已执行；返回 pane 实际新尺寸（读回值，非请求值）
// @err 解析 window id/尺寸失败→fmt.Errorf；tmux 失败→ErrPaneNotFound/ErrServerUnreachable/ErrTmuxTimeout
// @inv none — 只改尺寸，不触碰 pane 其他运行态
func (p *Pane) Resize(ctx context.Context, cols, rows int) (width, height int, err error) {
	winID, err := p.windowID(ctx)
	if err != nil {
		return 0, 0, err
	}
	// window-size latest makes the resize stick instead of being overridden
	// by an attached client's dimensions.
	if _, err := runTmux(ctx, p.socket, p.timeout, "set-option", "-w", "-t", winID, "window-size", "latest"); err != nil {
		return 0, 0, err
	}
	if _, err := runTmux(ctx, p.socket, p.timeout,
		"resize-window", "-t", winID, "-x", strconv.Itoa(cols), "-y", strconv.Itoa(rows)); err != nil {
		return 0, 0, err
	}
	return p.size(ctx)
}

// windowID resolves the tmux window id ("@N") owning this pane, which is the
// target resize-window and set-option operate on.
func (p *Pane) windowID(ctx context.Context) (string, error) {
	out, err := runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{window_id}")
	if err != nil {
		return "", err
	}
	id := strings.TrimSpace(string(out))
	if !strings.HasPrefix(id, "@") {
		return "", fmt.Errorf("tmux: could not resolve window for pane %s", p.target)
	}
	return id, nil
}

// size reads the pane's actual dimensions from the server.
func (p *Pane) size(ctx context.Context) (int, int, error) {
	out, err := runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{pane_width}x#{pane_height}")
	if err != nil {
		return 0, 0, err
	}
	var w, h int
	if _, err := fmt.Sscanf(strings.TrimSpace(string(out)), "%dx%d", &w, &h); err != nil {
		return 0, 0, fmt.Errorf("tmux: parse pane size %q: %w", strings.TrimSpace(string(out)), err)
	}
	return w, h, nil
}

// Socket exposes the socket the Pane is bound to. An empty string means the
// tmux default socket.
func (p *Pane) Socket() string { return p.socket }

// Target exposes the bare pane id (e.g. "%0") the Pane is bound to.
func (p *Pane) Target() string { return p.target }

// Timeout exposes the per-command timeout that bounds every tmux invocation
// the Pane makes.
func (p *Pane) Timeout() time.Duration { return p.timeout }
