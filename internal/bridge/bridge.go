package bridge

// bridge.go implements the single-pane terminal bridge primitive: first-frame
// snapshot (capture-pane -e), scrollback paging (capture-pane -S/-E), whole
// input injection with a decidable ack (send-keys / paste-buffer, requirement
// 003), resize (window-size latest + resize-window, requirement 005), and
// scroll-wheel forwarding (feat-remote-scroll-forward).
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

// Scrollback fetches one line range of the pane (capture-pane -S/-E).
// start/end use tmux top-relative coordinates: 0 = visible screen top,
// negative = history above it — identical to the protocol (§6.3), so callers
// pass protocol rows through unchanged (no screen-height translation; D-36
// corrected the old "bottom-relative" misreading). Returns raw ANSI bytes.
// @contract
// @pre start、end 为 tmux capture-pane 行坐标（0=屏顶，负=屏上历史）且 start < end
// @post 返回该行区间的原始终端字节（ANSI 保留，capture-pane -S/-E）
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
		err = p.pasteViaBuffer(ctx, text)
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

// InjectWithAttachment sends a message that combines an image path with
// optional caption text (feat-image-upload-inline). attachmentPath == "" is
// byte-identical to calling Inject(ctx, text) directly — this delegates to it
// unchanged, so the no-attachment path is untouched by this feature.
//
// With attachmentPath set: the path alone is pasted via pasteViaBuffer
// (load-buffer + paste-buffer -d -p, bracketed paste, no Enter) so Claude
// Code's own paste-path recognition inlines it as `[Image #N]`. That
// recognition requires the pasted buffer to be exactly the path (Claude
// Code trims and checks the whole buffer ends in a known image extension —
// feat-image-upload-inline probe), so caption text is NEVER combined into
// the same paste call. If text is non-empty it is then typed literally via
// a second, separate send-keys -l (not bracketed — this follows the
// already-inlined image rather than being part of its paste). The function
// then waits PasteSettleDelay before sending the one Enter that commits the
// whole sequence: Claude Code's paste handling does real async work (decode
// + resize + re-encode + cache-write to ~/.claude/image-cache) after the
// bracketed-paste bytes land, and an Enter that arrives before that settles
// is silently swallowed — the message is left sitting in the input box,
// fully pasted and inlined but never submitted. This was found and fixed by
// running the real product path (real daemon → real WS Input frame →
// InjectWithAttachment → real `claude` pane) against real images, not
// synthetic fake paths — see doc comment on PasteSettleDelay.
// @contract
// @pre pane 存在（requirePane 前置检查）；attachmentPath 为空时等价于 Inject(ctx, text)
// @post attachmentPath 非空：先 paste-buffer -d -p 注入路径（不回车）；text 非空时再单独一次 send-keys -l 注入文字；等 PasteSettleDelay 后发一次 send-keys Enter 提交；text 为空时跳过 send-keys -l 那一步
// @err pane 不存在→ErrPaneNotFound；server 不可达/超时→ErrServerUnreachable/ErrTmuxTimeout
// @inv attachmentPath 为空时与 Inject 逐字节一致；路径与文字永不共享同一次 paste-buffer
func (p *Pane) InjectWithAttachment(ctx context.Context, text, attachmentPath string) error {
	if attachmentPath == "" {
		return p.Inject(ctx, text)
	}
	if err := p.requirePane(ctx); err != nil {
		return err
	}
	if err := p.pasteViaBuffer(ctx, attachmentPath); err != nil {
		return err
	}
	return p.finishAfterPaste(ctx, text, PasteSettleDelay)
}

// InjectAfterPreview commits a message whose image was already pasted ahead
// of time via PastePreview (requirement 057's two-step flow: paste at
// upload-success, confirm at send). It does NOT paste anything itself — the
// `[Image #N]` is already in the pane — it only types text (if non-empty)
// and sends Enter, waiting out remaining first. remaining is the caller's
// job to compute (see api.Server's preview bookkeeping): typically 0 when
// the user spent long enough typing a caption to cover PasteSettleDelay on
// its own, matching plain Inject's "no added wait" feel; non-zero only for
// the "picked an image and sent immediately, no typing" edge case.
// @contract
// @pre pane 存在（requirePane 前置检查）；调用方已确认该 pane 有一次尚在沉降窗口内、路径匹配的 PastePreview（本函数不重新校验路径，只信调用方算好的 remaining）
// @post 等 remaining（可为 0）后，text 非空则先 send-keys -l 注入文字，再一次 send-keys Enter 提交
// @err pane 不存在→ErrPaneNotFound；server 不可达/超时→ErrServerUnreachable/ErrTmuxTimeout
// @inv 不调用 pasteViaBuffer；不追加换行；remaining<=0 时零等待直接提交
func (p *Pane) InjectAfterPreview(ctx context.Context, text string, remaining time.Duration) error {
	if err := p.requirePane(ctx); err != nil {
		return err
	}
	return p.finishAfterPaste(ctx, text, remaining)
}

// PastePreview pastes path alone into the pane (bracketed paste, no Enter,
// no wait) so Claude Code's own paste-path recognition inlines it as
// `[Image #N]` and starts its async decode/cache-write immediately —
// requirement 057's "paste at upload-success" step. Never combine caption
// text into this call (see requirement 057 clause 2 / doc comment on
// protocol.AttachPreview): that falls back to Claude Code's slow
// clipboard-lookup branch and the eventual Enter gets swallowed.
// @contract
// @pre pane 存在（requirePane 前置检查）
// @post path 已 paste-buffer -d -p 注入 pane，不追加 Enter，不等待
// @err pane 不存在→ErrPaneNotFound；server 不可达/超时→ErrServerUnreachable/ErrTmuxTimeout
// @inv 从不清理 pane 已有内容；path 不与其它内容共享同一次 paste-buffer
func (p *Pane) PastePreview(ctx context.Context, path string) error {
	if err := p.requirePane(ctx); err != nil {
		return err
	}
	return p.pasteViaBuffer(ctx, path)
}

// finishAfterPaste is the shared tail of InjectWithAttachment and
// InjectAfterPreview: optional literal caption text, then wait, then the one
// Enter that commits the message. wait is PasteSettleDelay for the
// full-paste-here-and-now path, or a caller-computed remainder for the
// preview-already-pasted path — see doc comment on PasteSettleDelay for why
// this wait exists at all (it is not a UI animation delay; Claude Code does
// real async decode/cache-write work after a paste lands).
func (p *Pane) finishAfterPaste(ctx context.Context, text string, wait time.Duration) error {
	text = strings.ReplaceAll(text, "\r", "")
	if text != "" {
		if _, err := runTmux(ctx, p.socket, p.timeout, "send-keys", "-t", p.target, "-l", "--", text); err != nil {
			return err
		}
	}
	if wait > 0 {
		time.Sleep(wait)
	}
	_, err := runTmux(ctx, p.socket, p.timeout, "send-keys", "-t", p.target, "Enter")
	return err
}

// PasteSettleDelay is how long InjectWithAttachment waits after pasting the
// image path before sending Enter, so Claude Code's own async image
// decode/cache-write has time to finish (see InjectWithAttachment doc
// comment for the failure mode this avoids).
const PasteSettleDelay = 2 * time.Second

// namedKeys maps a wire special-key name (protocol.Key value, R-1 shortcut
// bar) to the tmux send-keys named key. The closed set is enforced at the
// protocol boundary; this table is the bridge's own defensive lookup, so an
// unknown name is a hard error (ErrInvalidKey), never a silent no-op.
var namedKeys = map[string]string{
	"esc":      "Escape",
	"ctrl_c":   "C-c",
	"tab":      "Tab",
	"up":       "Up",
	"down":     "Down",
	"left":     "Left",
	"right":    "Right",
	"backspace": "BSpace",
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

// TypeKeys sends each key literal to the pane one keystroke at a time via
// `send-keys -l` (requirement 059 passthrough). It is the per-key typing
// primitive: unlike Inject it NEVER appends an Enter, and unlike SendKeys it
// injects literal characters (not named special keys) — the keystrokes land in
// the CLI's own input box as a live draft, exactly the "键入即直达" model.
// The caller is responsible for draining acked keys between sends if it needs
// strict ordering; each key is its own send-keys invocation so an ack is
// decidable per keystroke (requirement 003's 发送必达 holds per key).
// @contract
// @pre pane 存在（requirePane 前置检查）；keys 为要逐字注入的字符序列
// @post 每个字符经 send-keys -l 逐次注入 pane，不追加 Enter（草稿停留在 CLI 输入框）
// @err pane 不存在→ErrPaneNotFound；server 不可达/超时→ErrServerUnreachable/ErrTmuxTimeout
// @inv 从不追加 Enter；每次注入一个字符，不共享 send-keys 调用
func (p *Pane) TypeKeys(ctx context.Context, keys ...string) error {
	if err := p.requirePane(ctx); err != nil {
		return err
	}
	for _, k := range keys {
		if _, err := runTmux(ctx, p.socket, p.timeout, "send-keys", "-t", p.target, "-l", "--", k); err != nil {
			return err
		}
	}
	return nil
}

// pasteViaBuffer injects text via a named tmux buffer: it is pasted verbatim
// (bracketed paste, so the target CLI treats it as one paste rather than a
// burst of keystrokes) and the buffer is deleted on the spot. Despite the
// original multi-line use case, this works identically for any string —
// InjectWithAttachment reuses it to paste a single-line image path, which
// depends on the same bracketed-paste behavior to be recognized by Claude
// Code's own paste-path detection (feat-image-upload-inline).
func (p *Pane) pasteViaBuffer(ctx context.Context, text string) error {
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
	return p.Size(ctx)
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

// Size reads the pane's actual dimensions from the tmux server (fresh read,
// never cached). It is the truth source for "did a resize actually change the
// pane" — D-27 fix: a no-op resize (requested dims equal the current pane
// dims) must be detectable by comparing the read-back before/after, because
// tmux may converge a same-size request to the same pane size (fix-d27-v3).
// @contract
// @pre pane 存在（错误归类沿用 requirePane 语义）
// @post 返回 pane 当前实际字符尺寸 (width, height)，均为正数
// @err tmux 失败→ErrPaneNotFound/ErrServerUnreachable/ErrTmuxTimeout；尺寸解析失败→fmt.Errorf
// @inv none — 纯只读查询，不触碰 pane 运行态
func (p *Pane) Size(ctx context.Context) (int, int, error) {
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

// InjectScroll delivers one scroll-wheel event to the pane
// (feat-remote-scroll-forward). delta < 0 = up (toward history); delta > 0 =
// down. Routes on mouse_any_flag (feat-remote-scroll-mouse-wheel):
//
//   - mouse_any_flag=1 (app has mouse tracking on, e.g. Claude Code) →
//     forward abs(delta) raw SGR-1006 wheel events via `send-keys -H`.
//     No copy-mode entered; enteredCopyMode is always false on this path.
//   - mouse_any_flag=0 (bare shell, or an alt-screen app with no mouse
//     tracking) → copy-mode fallback: enter copy-mode -e if not already in
//     it (returns enteredCopyMode=true so the caller can push
//     PaneModeChanged), then scroll abs(delta) lines via send-keys -X.
//
// History (2026-08-14, corrected 2026-08-14 after feat-remote-scroll-mouse-wheel
// probe): an earlier design tried the mouse_any_flag=1 raw-byte path and
// declared it "proved ineffective", collapsing both branches into copy-mode
// for every pane. That verdict was never actually about Claude Code — the
// experiment behind it only drove `less` and `vim (mouse=a)`, both
// alt-screen apps, and generalized the negative result to "TUI" as a whole.
// It also misclassified Claude Code as tier① "non-alt-screen, copy-mode
// works ✓", a claim feat-remote-scroll-forward's rounds 8-10 had already
// falsified (Claude Code runs alt_on=1/history_size=0, so copy-mode has
// nothing to scroll into). Re-running the raw-byte experiment against an
// actual `claude` pane (mouse_any_flag=1) — not less/vim — produced a
// visible scroll and a "Jump to bottom" indicator; a control group on a bare
// shell (mouse_any_flag=0) confirmed the same bytes never reach it. See
// feat-remote-scroll-mouse-wheel probe notes for the capture-pane evidence.
// @contract
// @pre pane 存在（requirePane 前置）；delta != 0（Validate 已拒绝 0）
// @post mouse_any_flag=1 时已发送 abs(delta) 个 SGR 滚轮字节，pane 运行态不变（enteredCopyMode=false）；mouse_any_flag=0 时 pane 处于 copy-mode 并已滚动 abs(delta) 行；均不追加 Enter
// @err pane 不存在→ErrPaneNotFound；server 不可达/超时→ErrServerUnreachable/ErrTmuxTimeout
// @inv mouse_any_flag=0 时绝不发送原始字节（防止污染裸壳命令行）；copy-mode 进入幂等（已在 copy-mode 时直接 scroll）
func (p *Pane) InjectScroll(ctx context.Context, delta int32) (enteredCopyMode bool, err error) {
	if err := p.requirePane(ctx); err != nil {
		return false, err
	}

	count := delta
	if count < 0 {
		count = -count
	}

	mouseOut, err := runTmux(ctx, p.socket, p.timeout,
		"display-message", "-p", "-t", p.target, "#{mouse_any_flag}")
	if err != nil {
		return false, err
	}
	if strings.TrimSpace(string(mouseOut)) == "1" {
		// App has mouse tracking on: forward real wheel bytes. copy-mode is
		// never entered on this path — see doc comment for why the earlier
		// "ineffective" verdict did not apply to Claude Code.
		return false, p.injectWheelBytes(ctx, delta < 0, int(count))
	}

	// No mouse tracking: copy-mode fallback.
	out, err := runTmux(ctx, p.socket, p.timeout,
		"display-message", "-p", "-t", p.target, "#{pane_in_mode}")
	if err != nil {
		return false, err
	}
	paneInMode := strings.TrimSpace(string(out)) == "1"

	direction := "scroll-up"
	if delta >= 0 {
		direction = "scroll-down"
	}
	if !paneInMode {
		if _, err = runTmux(ctx, p.socket, p.timeout, "copy-mode", "-e", "-t", p.target); err != nil {
			return false, err
		}
		enteredCopyMode = true
	}
	_, err = runTmux(ctx, p.socket, p.timeout,
		"send-keys", "-X", "-N", strconv.Itoa(int(count)), "-t", p.target, direction)
	return enteredCopyMode, err
}

// injectWheelBytes sends count SGR-1006 mouse-wheel events (button 64 = up,
// 65 = down) as raw bytes in one `send-keys -H` call — never via
// `paste-buffer -p`, which wraps its payload in bracketed-paste markers
// (ESC[200~/ESC[201~) and would corrupt the mouse escape sequence. Coordinate
// 1;1 is always in bounds regardless of pane size; the receiving app acts on
// the wheel button code, not the reported cell.
// @contract
// @pre 调用方已确认 mouse_any_flag=1（本函数不重复校验）
// @post 已发送 count 个 SGR 滚轮事件（一次 send-keys -H 调用），不追加 Enter
// @err server 不可达/超时→ErrServerUnreachable/ErrTmuxTimeout
// @inv 只发送裸字节，不进入/退出 copy-mode
func (p *Pane) injectWheelBytes(ctx context.Context, up bool, count int) error {
	button := 65
	if up {
		button = 64
	}
	seq := fmt.Sprintf("\x1b[<%d;1;1M", button)
	hex := make([]string, 0, len(seq)*count)
	for i := 0; i < count; i++ {
		for j := 0; j < len(seq); j++ {
			hex = append(hex, fmt.Sprintf("%02x", seq[j]))
		}
	}
	args := append([]string{"send-keys", "-H", "-t", p.target}, hex...)
	_, err := runTmux(ctx, p.socket, p.timeout, args...)
	return err
}

// PaneInMode reports whether the pane is currently in tmux copy-mode.
// Used by handleInput to detect and clear stale copy-mode before injecting text.
// @contract
// @pre pane 存在（tmux 在调用时惰性判定）
// @post 返回 pane_in_mode 格式变量的布尔值
// @err tmux 失败→ErrPaneNotFound/ErrServerUnreachable/ErrTmuxTimeout
// @inv 纯只读查询
func (p *Pane) PaneInMode(ctx context.Context) (bool, error) {
	out, err := runTmux(ctx, p.socket, p.timeout,
		"display-message", "-p", "-t", p.target, "#{pane_in_mode}")
	if err != nil {
		return false, err
	}
	return strings.TrimSpace(string(out)) == "1", nil
}

// ExitCopyMode sends send-keys -X cancel to exit tmux copy-mode. Used by
// handleInput as a safety bailout: if a pane is in copy-mode when the user
// types, the cancel restores normal input before the text is injected.
// Idempotent: cancel on a pane not in copy-mode is a tmux no-op.
// @contract
// @pre pane 存在（tmux 在调用时惰性判定）
// @post copy-mode 已退出（pane_in_mode 变为 0）
// @err tmux 失败→ErrPaneNotFound/ErrServerUnreachable/ErrTmuxTimeout
// @inv 只发 send-keys -X cancel，不触碰 pane 其他运行态
func (p *Pane) ExitCopyMode(ctx context.Context) error {
	_, err := runTmux(ctx, p.socket, p.timeout, "send-keys", "-X", "-t", p.target, "cancel")
	return err
}

// Socket exposes the socket the Pane is bound to. An empty string means the
// tmux default socket.
func (p *Pane) Socket() string { return p.socket }

// Target exposes the bare pane id (e.g. "%0") the Pane is bound to.
func (p *Pane) Target() string { return p.target }

// Timeout exposes the per-command timeout that bounds every tmux invocation
// the Pane makes.
func (p *Pane) Timeout() time.Duration { return p.timeout }
