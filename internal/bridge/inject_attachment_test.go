package bridge

// inject_attachment_test.go: red tests for InjectWithAttachment
// (feat-image-upload-inline). All argv-shape tests use a fake tmux binary
// (fakeTmuxAttachScript) so the assertions pin the exact sequence of tmux
// subcommands dispatched, not just "the function was called" — per leader's
// verdict on the round-1 regression (mechanism-triggered ≠ user-visible
// result), THIS file only asserts wire-level tmux argv shape; the end-to-end
// "did the message actually land and submit" proof is a separate manual
// probe against a real `claude` pane (not something a unit test can assert),
// reported alongside this round's diff.
//
// T1: attachmentPath set + text set → load-buffer → paste-buffer -d -p →
//     send-keys -l → send-keys Enter, in that exact order
// T2: attachmentPath set + text empty → load-buffer → paste-buffer -d -p →
//     send-keys Enter — NO send-keys -l call (never dispatch an empty
//     literal-text injection)
// T3: attachmentPath empty → byte-identical argv to calling Inject directly
//     (no load-buffer/paste-buffer at all)
// T4: mutation self-check — merging steps ① and ② into a single paste must
//     turn the order assertion red (done manually, see round report)
// T5: real-tmux behavioral smoke test — content actually lands in a `cat` pane

import (
	"context"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// fakeTmuxAttachScript writes a fake tmux that answers list-panes
// deterministically and appends every load-buffer / paste-buffer / send-keys
// invocation's argv (socket flag stripped) to logPath, one line per call.
// load-buffer's stdin content is captured to stdinLogPath so a test can
// assert the pasted buffer is exactly the attachment path — never the path
// mixed with caption text.
func fakeTmuxAttachScript(t *testing.T) (script, argvLog, stdinLog string) {
	t.Helper()
	dir := t.TempDir()
	argvLog = filepath.Join(dir, "argv.log")
	stdinLog = filepath.Join(dir, "stdin.log")
	script = filepath.Join(dir, "fake-tmux")
	body := `#!/bin/sh
case "$3" in
  list-panes) echo "%0"; exit 0;;
  load-buffer)
    shift 2
    echo "$@" >> "$ARGS_LOG"
    cat >> "$STDIN_LOG"
    exit 0;;
  paste-buffer) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  send-keys) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  *) exit 1;;
esac
`
	if err := os.WriteFile(script, []byte(body), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	return script, argvLog, stdinLog
}

// bufNameRe normalizes the dynamic buffer name (tb-<pid>-<seq>) so argv
// assertions can pin the surrounding structure without hardcoding a value
// that changes per test run / execution order.
var bufNameRe = regexp.MustCompile(`tb-\d+-\d+`)

func normalizeBufName(s string) string {
	return bufNameRe.ReplaceAllString(s, "<buf>")
}

func readLoggedArgv(t *testing.T, path string) []string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	trimmed := strings.TrimSpace(string(data))
	if trimmed == "" {
		return nil
	}
	lines := strings.Split(trimmed, "\n")
	for i, l := range lines {
		lines[i] = normalizeBufName(l)
	}
	return lines
}

// TestInjectWithAttachmentArgvOrderWithText pins the exact tmux call
// sequence when both an attachment and caption text are present: paste the
// path alone (load-buffer + paste-buffer -d -p), THEN a separate literal
// send-keys -l for the caption, THEN one Enter. The order itself is the
// assertion — a implementation that merged path+text into one paste would
// still "call load-buffer and send-keys", but in the wrong shape.
func TestInjectWithAttachmentArgvOrderWithText(t *testing.T) {
	script, argvLog, stdinLog := fakeTmuxAttachScript(t)
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", argvLog)
	t.Setenv("STDIN_LOG", stdinLog)

	p := NewPane("/sock/x", "%0")
	if err := p.InjectWithAttachment(context.Background(), "look at this", "/host/img.png"); err != nil {
		t.Fatalf("InjectWithAttachment: %v", err)
	}

	got := readLoggedArgv(t, argvLog)
	want := []string{
		"load-buffer -b <buf> -",
		"paste-buffer -b <buf> -t %0 -d -p",
		"send-keys -t %0 -l -- look at this",
		"send-keys -t %0 Enter",
	}
	if len(got) != len(want) {
		t.Fatalf("tmux calls = %q, want %q", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("call %d = %q, want %q", i, got[i], want[i])
		}
	}

	// The pasted buffer must be exactly the path — never the path mixed with
	// caption text, or Claude Code's own paste-path recognition would miss it.
	pastedContent, err := os.ReadFile(stdinLog)
	if err != nil {
		t.Fatalf("read stdin log: %v", err)
	}
	if string(pastedContent) != "/host/img.png" {
		t.Errorf("pasted buffer = %q, want exactly the path with no caption mixed in", pastedContent)
	}
}

// TestInjectWithAttachmentArgvOrderEmptyText pins the boundary case: an
// attachment with no caption text must dispatch only steps ① and ③ — no
// send-keys -l call at all (never inject an empty literal string).
func TestInjectWithAttachmentArgvOrderEmptyText(t *testing.T) {
	script, argvLog, stdinLog := fakeTmuxAttachScript(t)
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", argvLog)
	t.Setenv("STDIN_LOG", stdinLog)

	p := NewPane("/sock/x", "%0")
	if err := p.InjectWithAttachment(context.Background(), "", "/host/img.png"); err != nil {
		t.Fatalf("InjectWithAttachment: %v", err)
	}

	got := readLoggedArgv(t, argvLog)
	want := []string{
		"load-buffer -b <buf> -",
		"paste-buffer -b <buf> -t %0 -d -p",
		"send-keys -t %0 Enter",
	}
	if len(got) != len(want) {
		t.Fatalf("tmux calls = %q, want %q (no send-keys -l for empty text)", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("call %d = %q, want %q", i, got[i], want[i])
		}
	}
	for _, line := range got {
		if strings.Contains(line, "-l") {
			t.Errorf("empty text must never dispatch a send-keys -l call, got %q", line)
		}
	}
}

// TestInjectWithAttachmentEmptyPathMatchesInjectByteForByte verifies the
// no-attachment path is byte-identical to calling Inject directly: same
// argv, same call count, in particular zero load-buffer/paste-buffer calls
// for single-line text (InjectWithAttachment must not accidentally start
// pasting when there is nothing to attach).
func TestInjectWithAttachmentEmptyPathMatchesInjectByteForByte(t *testing.T) {
	runWith := func(fn func(p *Pane) error) []string {
		script, argvLog, stdinLog := fakeTmuxAttachScript(t)
		old := tmuxBin
		tmuxBin = script
		defer func() { tmuxBin = old }()
		t.Setenv("ARGS_LOG", argvLog)
		t.Setenv("STDIN_LOG", stdinLog)
		p := NewPane("/sock/x", "%0")
		if err := fn(p); err != nil {
			t.Fatalf("inject: %v", err)
		}
		return readLoggedArgv(t, argvLog)
	}

	viaInject := runWith(func(p *Pane) error {
		return p.Inject(context.Background(), "ls -la")
	})
	viaAttachmentEmptyPath := runWith(func(p *Pane) error {
		return p.InjectWithAttachment(context.Background(), "ls -la", "")
	})

	if len(viaInject) != len(viaAttachmentEmptyPath) {
		t.Fatalf("argv count differs: Inject=%q InjectWithAttachment(empty path)=%q", viaInject, viaAttachmentEmptyPath)
	}
	for i := range viaInject {
		if viaInject[i] != viaAttachmentEmptyPath[i] {
			t.Errorf("call %d differs: Inject=%q InjectWithAttachment(empty path)=%q", i, viaInject[i], viaAttachmentEmptyPath[i])
		}
	}
	for _, line := range viaAttachmentEmptyPath {
		if strings.Contains(line, "load-buffer") || strings.Contains(line, "paste-buffer") {
			t.Errorf("empty attachmentPath with single-line text must not touch load-buffer/paste-buffer, got %q", line)
		}
	}
}

// TestInjectWithAttachmentLandsInPane is a real-tmux behavioral smoke test:
// against a `cat` pane (not Claude Code — that requires a real probe, see
// round report), both the pasted path and the typed caption must actually
// reach the pane's screen content, and in the right relative order.
func TestInjectWithAttachmentLandsInPane(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")

	ch, cancel, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer cancel()

	if err := p.InjectWithAttachment(context.Background(), "ATTACH_CAPTION_9", "/fake/ATTACH_PATH_9.png"); err != nil {
		t.Fatalf("InjectWithAttachment: %v", err)
	}
	if !waitForStream(t, ch, "ATTACH_PATH_9") {
		t.Fatal("pasted attachment path never appeared in the incremental stream")
	}
	if !waitForStream(t, ch, "ATTACH_CAPTION_9") {
		t.Fatal("caption text never appeared in the incremental stream")
	}
}

// TestInjectWithAttachmentDeadPaneFails mirrors Inject's decidable-ack
// contract (requirement 003) for the attachment path.
func TestInjectWithAttachmentDeadPaneFails(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.deadPane(t)

	if err := p.InjectWithAttachment(context.Background(), "x", "/fake/path.png"); !isErrPaneNotFound(err) {
		t.Fatalf("InjectWithAttachment on dead pane: want ErrPaneNotFound, got %v", err)
	}
}
