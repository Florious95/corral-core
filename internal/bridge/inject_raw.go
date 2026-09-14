package bridge

import (
	"context"
	"fmt"
)

// isControlByte reports whether b must go through send-keys -H rather than
// -l. C0 (0x00–0x1F) and DEL (0x7F) are control; everything else — including
// UTF-8 continuation bytes 0x80–0xFF — is a literal run for -l.
func isControlByte(b byte) bool {
	return b < 0x20 || b == 0x7F
}

// InjectRawAtomic injects raw bytes in one tmux send-keys -H invocation.
// Keeping an escape sequence in one PTY write is essential for terminal UIs:
// if ESC is sent separately, readline/TUI parsers may commit it as a standalone
// Escape key before the remaining CSI bytes arrive.
func (p *Pane) InjectRawAtomic(ctx context.Context, raw []byte) error {
	if err := p.requirePane(ctx); err != nil {
		return err
	}
	if len(raw) == 0 {
		return nil
	}
	args := make([]string, 0, 5+len(raw))
	args = append(args, "send-keys", "-t", p.target, "-H", "--")
	for _, b := range raw {
		args = append(args, fmt.Sprintf("%02x", b))
	}
	_, err := runTmux(ctx, p.socket, p.timeout, args...)
	return err
}

// InjectRaw injects an arbitrary byte sequence into the pane PTY.
// Printable runs (including UTF-8 中文) share one `send-keys -l` invocation;
// consecutive control bytes (C0 / DEL) share one `send-keys -H` invocation
// with hex args (`03`, `1b`, no `0x` prefix). Mixed payloads are split on
// kind changes so tmux 调用次数 equals the number of runs, not the number
// of bytes. Empty payload is a no-op after the pane existence check.
//
// @contract
// @pre pane 存在（requirePane 前置检查）
// @post raw 按可打印/控制段切分后按序注入，不追加 Enter
// @err pane 不存在→ErrPaneNotFound；server 不可达/超时→ErrServerUnreachable/ErrTmuxTimeout
// @inv 从不把可打印字节走 -H；同一种连续字节合并为一次 tmux 调用
func (p *Pane) InjectRaw(ctx context.Context, raw []byte) error {
	if err := p.requirePane(ctx); err != nil {
		return err
	}
	if len(raw) == 0 {
		return nil
	}
	i := 0
	for i < len(raw) {
		ctrl := isControlByte(raw[i])
		j := i + 1
		for j < len(raw) && isControlByte(raw[j]) == ctrl {
			j++
		}
		run := raw[i:j]
		i = j
		if ctrl {
			args := make([]string, 0, 5+len(run))
			args = append(args, "send-keys", "-t", p.target, "-H", "--")
			for _, b := range run {
				args = append(args, fmt.Sprintf("%02x", b))
			}
			if _, err := runTmux(ctx, p.socket, p.timeout, args...); err != nil {
				return err
			}
			continue
		}
		if _, err := runTmux(ctx, p.socket, p.timeout,
			"send-keys", "-t", p.target, "-l", "--", string(run)); err != nil {
			return err
		}
	}
	return nil
}
