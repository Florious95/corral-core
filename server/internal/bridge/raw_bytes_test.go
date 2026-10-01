package bridge

// raw_bytes_test.go — 输入透传第 1 步红测：裸字节进 pty、混合段合并、老路径兼容。

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestRawBytesControlByteReachesPty is R1: a control byte the named-key
// closed set cannot express as raw pty bytes (ESC 0x1b, not the name "esc")
// must reach the pane. tmux 回显 ESC 为 "^["（与 Keys 路径同一观察面）。
func TestRawBytesControlByteReachesPty(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")

	ch, cancel, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer cancel()

	// ESC [ A (CSI CUU). 0x1b is a C0 控制字节; 0x03 is asserted on the
	// fake-tmux argv path (R2) because ISIG would turn ETX into SIGINT
	// before cat could echo it.
	raw := []byte{0x1b, '[', 'A'}
	if err := p.InjectRaw(context.Background(), raw); err != nil {
		t.Fatalf("InjectRaw control bytes: %v", err)
	}
	// tmux / 行规程把 ESC 回显成 caret 记法 "^["（与 TestInputKeysInjectsNamedKey
	// 同一观察面）。原字节已进 pty；pipe-pane 见到的是渲染后的 "^[[A"。
	if !waitForStream(t, ch, "^[[A") {
		t.Fatal("R1: control-byte sequence never appeared in the pty stream")
	}
}

// TestRawBytesAtomicSgrStaysOneTmuxInvocation protects terminal parsers from
// seeing ESC as a standalone key before the rest of a mouse CSI sequence.
func TestRawBytesAtomicSgrStaysOneTmuxInvocation(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "argv.log")
	script := filepath.Join(dir, "fake-tmux")
	if err := os.WriteFile(script, []byte(`#!/bin/sh
case "$3" in
  list-panes) echo "%0"; exit 0;;
  send-keys) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  *) exit 1;;
esac
`), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)

	p := NewPane("/sock/x", "%0")
	if err := p.InjectRawAtomic(context.Background(), []byte("\x1b[<0;24;9M")); err != nil {
		t.Fatalf("InjectRawAtomic SGR: %v", err)
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	got := strings.TrimSpace(string(data))
	want := "send-keys -t %0 -H -- 1b 5b 3c 30 3b 32 34 3b 39 4d"
	if got != want {
		t.Fatalf("atomic SGR argv = %q, want %q", got, want)
	}
}

// TestMixedInputBoundedTmuxInvocations is R2: printable+control mixed payload
// keeps order, and send-keys invocations are a bounded few (合并), not one
// per byte. 调用次数 is counted from the fake-tmux argv log.
func TestMixedInputBoundedTmuxInvocations(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "argv.log")
	script := filepath.Join(dir, "fake-tmux")
	if err := os.WriteFile(script, []byte(`#!/bin/sh
case "$3" in
  list-panes) echo "%0"; exit 0;;
  send-keys) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  *) exit 1;;
esac
`), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)

	p := NewPane("/sock/x", "%0")
	// "ab" printable, 0x03 control, "cd" printable, 0x1b control, "[A" printable
	raw := []byte{'a', 'b', 0x03, 'c', 'd', 0x1b, '[', 'A'}
	if err := p.InjectRaw(context.Background(), raw); err != nil {
		t.Fatalf("InjectRaw mixed: %v", err)
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	callCount := len(lines) // invocations of send-keys
	if callCount != 5 {
		t.Fatalf("R2: send-keys 调用次数 = %d, want 5 (ab / 03 / cd / 1b / [A); got %q", callCount, lines)
	}
	want := []string{
		"send-keys -t %0 -l -- ab",
		"send-keys -t %0 -H -- 03",
		"send-keys -t %0 -l -- cd",
		"send-keys -t %0 -H -- 1b",
		"send-keys -t %0 -l -- [A",
	}
	for i := range want {
		if lines[i] != want[i] {
			t.Errorf("R2 argv[%d] = %q, want %q", i, lines[i], want[i])
		}
	}
}

// TestPassthroughCompatTextAndKeys is R3: 老 Text/Keys 路径（TypeKeys / SendKeys）
// 兼容，must still inject the same argv as before this change.
func TestPassthroughCompatTextAndKeys(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "argv.log")
	script := filepath.Join(dir, "fake-tmux")
	if err := os.WriteFile(script, []byte(`#!/bin/sh
case "$3" in
  list-panes) echo "%0"; exit 0;;
  send-keys) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  *) exit 1;;
esac
`), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)

	p := NewPane("/sock/x", "%0")
	if err := p.TypeKeys(context.Background(), "h", "i"); err != nil {
		t.Fatalf("TypeKeys compat: %v", err)
	}
	if err := p.SendKeys(context.Background(), "esc", "ctrl_c"); err != nil {
		t.Fatalf("SendKeys compat: %v", err)
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	got := strings.Split(strings.TrimSpace(string(data)), "\n")
	want := []string{
		"send-keys -t %0 -l -- h",
		"send-keys -t %0 -l -- i",
		"send-keys -t %0 -- Escape C-c",
	}
	if len(got) != len(want) {
		t.Fatalf("compat argv lines = %d, want %d: %q", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("compat argv[%d] = %q, want %q", i, got[i], want[i])
		}
	}
}

// TestRawBytesChineseStaysOnLiteralFlag is a pin: UTF-8 中文 must use -l, not -H.
func TestRawBytesChineseStaysOnLiteralFlag(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "argv.log")
	script := filepath.Join(dir, "fake-tmux")
	if err := os.WriteFile(script, []byte(`#!/bin/sh
case "$3" in
  list-panes) echo "%0"; exit 0;;
  send-keys) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  *) exit 1;;
esac
`), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)

	p := NewPane("/sock/x", "%0")
	raw := append([]byte("你"), 0x03)
	raw = append(raw, []byte("好")...)
	if err := p.InjectRaw(context.Background(), raw); err != nil {
		t.Fatalf("InjectRaw CJK: %v", err)
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	got := strings.TrimSpace(string(data))
	if !strings.Contains(got, "-l -- 你") || !strings.Contains(got, "-H -- 03") || !strings.Contains(got, "-l -- 好") {
		t.Fatalf("CJK must stay on -l, control on -H; got %q", got)
	}
	if bytes.Count(data, []byte("\n"))+1 > 4 {
		t.Fatalf("CJK+control should be 3 send-keys 调用次数, got log %q", got)
	}
}
