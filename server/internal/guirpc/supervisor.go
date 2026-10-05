package guirpc

// supervisor.go keeps one managed pane alive across an in-pane mode switch:
// the structured agent (pi --mode rpc, piped, this package's stream) and Pi's
// own interactive TUI (pi --session-id, on the pane's tty) take turns on the
// same Pi session. Only the worker process owns the pane; a switch is a
// request on its private socket, never keystrokes injected through tmux.
//
// @contract
// @pre a switch names "tui" or "rpc"; force is the user's explicit consent to
// interrupt work in flight.
// @post the pane, its ref and the Pi session stay the same; exactly one child
// reads the tty at any moment; a switch answers only after the new child runs.
// @err busy without force, an unknown session or a child that will not start
// fail visibly with the reason; the previous mode is kept when possible.
// @inv no orphaned child: each one is reaped before the next starts.

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
	"golang.org/x/term"
)

const (
	switchReplyTimeout = 3 * time.Second
	abortSettleTimeout = 10 * time.Second
	childStopTimeout   = 4 * time.Second
	// terminalReset undoes whatever an interrupted TUI left on the pane:
	// alternate screen, hidden cursor, mouse and paste modes, attributes.
	terminalReset = "\x1b[?1049l\x1b[?25h\x1b[?1000l\x1b[?1002l\x1b[?1003l\x1b[?1006l\x1b[?2004l\x1b[0m\x1b[2J\x1b[H"
)

// childCommand starts the structured agent; sessionID resumes Pi's own
// session. Tests replace it with a fake agent process.
var childCommand = func(ctx context.Context, name, sessionID string) *exec.Cmd {
	if sessionID != "" {
		return exec.CommandContext(ctx, "pi", "--mode", "rpc", "--session-id", sessionID)
	}
	return exec.CommandContext(ctx, "pi", "--mode", "rpc", "--name", name)
}

// tuiCommand starts Pi's interactive UI on the same session.
var tuiCommand = func(ctx context.Context, sessionID string) *exec.Cmd {
	return exec.CommandContext(ctx, "pi", "--session-id", sessionID)
}

// Run is invoked only by agentmirrord gui-worker <private-dir> <name>.
// @contract
// @pre TMUX and TMUX_PANE identify the newly created pane; dir is private.
// @post The child is reaped and the worker's socket/state files removed before return.
// @err Startup/JSONL/child failures return an error; cancellation terminates the group.
// @inv One child at a time, one continuously drained stdout; bounded history and queues.
func Run(ctx context.Context, dir, name string) error {
	socket, _, ok := strings.Cut(os.Getenv("TMUX"), ",")
	pane := os.Getenv("TMUX_PANE")
	if !ok || !filepath.IsAbs(socket) || !strings.HasPrefix(pane, "%") {
		return errors.New("GUI worker requires a tmux pane")
	}
	return run(ctx, dir, socket+"\x1f"+pane, name, os.Stdin, os.Stdout)
}

type supervisor struct {
	ctx    context.Context
	cancel context.CancelFunc
	w      *worker
	name   string
	tty    *os.File
	out    io.Writer
	state  string
	// The pane's line discipline at start; restored after every TUI.
	termState *term.State

	mu      sync.Mutex
	target  string        // mode a switch asked for; "" when a child ends on its own
	started chan error    // the next child's start result, for the waiting switch
	stop    func()        // ends the current child for a switch
	busy    bool          // a switch is in progress
	console *consoleInput // the RPC console's tty reader
	tuiFrom time.Time     // when the current TUI started
}

func run(ctx context.Context, dir, ref, name string, tty *os.File, output io.Writer) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	path := SocketPath(dir, ref)
	state := statePath(dir, ref)
	// A crashed predecessor on a reused pane id must not block this listener.
	_ = os.Remove(path)
	listener, err := net.Listen("unix", path)
	if err != nil {
		return fmt.Errorf("GUI socket unavailable: %w", err)
	}
	defer func() { listener.Close(); os.Remove(path); os.Remove(state) }()
	if err := os.Chmod(path, 0o600); err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	w := newWorker(nil)
	w.onRunning = func(running bool) { writeState(state, running) }
	writeState(state, false)
	defer w.shutdown()
	s := &supervisor{ctx: ctx, cancel: cancel, w: w, name: name, tty: tty, out: output, state: state}
	if term.IsTerminal(int(tty.Fd())) {
		s.termState, _ = term.GetState(int(tty.Fd()))
	}
	w.onSwitch = s.switchTo
	go func() {
		<-ctx.Done()
		listener.Close()
	}()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go w.serve(ctx, conn)
		}
	}()
	return s.loop()
}

func (s *supervisor) loop() error {
	mode, resume := ModeRPC, ""
	for {
		var next string
		var err error
		if mode == ModeRPC {
			next, err = s.runRPC(resume)
		} else {
			next, err = s.runTUI(resume)
		}
		if next == "" {
			return err
		}
		mode = next
		s.w.mu.Lock()
		resume = s.w.sessionID
		s.w.mu.Unlock()
	}
}

// takeTarget reports (and clears) the mode a switch asked for.
func (s *supervisor) takeTarget() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	t := s.target
	s.target = ""
	return t
}

func (s *supervisor) signalStarted(err error) {
	s.mu.Lock()
	ch := s.started
	s.started = nil
	s.mu.Unlock()
	if ch != nil {
		ch <- err
	}
}

// runRPC runs the structured agent until it exits or a switch stops it.
func (s *supervisor) runRPC(resume string) (string, error) {
	cmd := childCommand(s.ctx, s.name, resume)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error { return syscall.Kill(-cmd.Process.Pid, syscall.SIGTERM) }
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return "", err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return "", err
	}
	cmd.Stderr = os.Stderr
	cmd.WaitDelay = 3 * time.Second
	if err := cmd.Start(); err != nil {
		s.signalStarted(err)
		return "", err
	}
	defer func() { _ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL) }()
	w := s.w
	w.setInput(stdin)
	if resume != "" {
		// A new process on the same session: clients rebuild from Pi's own record.
		w.mu.Lock()
		w.running, w.compacting, w.queued = false, false, 0
		w.resetHistory()
		w.publish([]byte(`{"type":"session_reset"}`), entry{kind: "session_reset"}, true)
		w.mu.Unlock()
	}
	w.setMode(ModeRPC)
	writeState(s.state, false)
	var stopOnce sync.Once
	s.mu.Lock()
	s.stop = func() {
		stopOnce.Do(func() {
			// EOF on stdin is Pi's own clean exit; the group kill is the fallback.
			_ = stdin.Close()
			time.AfterFunc(childStopTimeout, func() { _ = syscall.Kill(-cmd.Process.Pid, syscall.SIGTERM) })
		})
	}
	s.mu.Unlock()
	// The pane stays a readable terminal for this session. It never shows raw
	// JSON and never receives JSON through tmux; GUI input uses the socket.
	// Bracketed paste is requested so tmux wraps a multi-line paste and the
	// console submits it as one prompt instead of one prompt per line.
	fmt.Fprintf(s.out, "%sPi · native conversation · %s\nType a prompt and press Enter; /compact, /new and /help are available.\n", pasteOn, s.name)
	console := startConsole(s.tty, s.consoleLine, s.cancel)
	s.mu.Lock()
	s.console = console
	s.mu.Unlock()
	go s.prime(resume != "")
	s.signalStarted(nil)

	scan := bufio.NewScanner(stdout)
	scan.Buffer(make([]byte, 4096), MaxRecord)
	for scan.Scan() {
		data := append([]byte(nil), scan.Bytes()...)
		if !json.Valid(data) {
			s.cancel()
			break
		}
		w.ingest(data)
		printTerminal(s.out, data)
	}
	scanErr := scan.Err()
	console.stop()
	fmt.Fprint(s.out, pasteOff)
	w.setInput(nil)
	waitErr := cmd.Wait()
	if target := s.takeTarget(); target != "" && s.ctx.Err() == nil {
		return target, nil
	}
	if scanErr != nil {
		s.cancel()
	}
	if s.ctx.Err() != nil {
		return "", s.ctx.Err()
	}
	if scanErr != nil {
		return "", scanErr
	}
	return "", waitErr
}

// prime learns the session identity and, on a resume, replays Pi's record.
func (s *supervisor) prime(resumed bool) {
	w := s.w
	if _, err := w.request(map[string]any{"type": "get_state"}, switchReplyTimeout); err != nil {
		return
	}
	if !resumed {
		return
	}
	data, err := w.request(map[string]any{"type": "get_messages"}, 2*switchReplyTimeout)
	if err == nil {
		var resp struct {
			Messages []json.RawMessage `json:"messages"`
		}
		if json.Unmarshal(data, &resp) == nil {
			for _, record := range replayRecords(resp.Messages) {
				w.ingest(record)
			}
			fmt.Fprintf(s.out, "— resumed this session (%d messages) —\n", len(resp.Messages))
		}
	}
	w.publishEvent(map[string]any{"type": "worker_mode", "mode": ModeRPC}, false)
}

// consoleLine handles one submitted line of the RPC console.
func (s *supervisor) consoleLine(text string) {
	w := s.w
	command := map[string]any{"type": "prompt", "message": text}
	switch text {
	case "/compact":
		command = map[string]any{"type": "compact"}
	case "/new", "/clear":
		command = map[string]any{"type": "new_session"}
	case "/help":
		fmt.Fprintln(s.out, "/compact — summarize context · /new — fresh session · Ctrl-C — close this agent")
		return
	default:
		if w.isRunning() {
			command["streamingBehavior"] = "steer"
		}
	}
	data, _ := json.Marshal(command)
	_ = w.send(data)
}

// runTUI hands the pane's tty to Pi's interactive UI until it exits.
func (s *supervisor) runTUI(sessionID string) (string, error) {
	fmt.Fprint(s.out, terminalReset)
	cmd := tuiCommand(s.ctx, sessionID)
	// Same process group as the worker: the TUI must be the tty's foreground
	// reader (its own group would stop on SIGTTIN). Raw mode keeps ^C a key.
	cmd.Stdin, cmd.Stdout, cmd.Stderr = s.tty, s.out, os.Stderr
	cmd.Cancel = func() error { return cmd.Process.Signal(syscall.SIGTERM) }
	cmd.WaitDelay = childStopTimeout
	if err := cmd.Start(); err != nil {
		s.signalStarted(err)
		return "", err
	}
	w := s.w
	s.mu.Lock()
	s.tuiFrom = time.Now().Add(-time.Second)
	s.mu.Unlock()
	w.setMode(ModeTUI)
	writeStateValue(s.state, ModeTUI)
	w.publishEvent(map[string]any{"type": "worker_mode", "mode": ModeTUI}, false)
	var stopOnce sync.Once
	s.mu.Lock()
	s.stop = func() {
		stopOnce.Do(func() {
			// Pi's SIGTERM path restores the terminal and kills its detached children.
			_ = cmd.Process.Signal(syscall.SIGTERM)
			time.AfterFunc(childStopTimeout, func() { _ = cmd.Process.Kill() })
		})
	}
	s.mu.Unlock()
	s.signalStarted(nil)
	waitErr := cmd.Wait()
	s.restoreTerminal()
	if target := s.takeTarget(); target != "" && s.ctx.Err() == nil {
		s.adoptTUISession()
		return target, nil
	}
	// Quitting Pi's own UI ends this agent, exactly like ^C in the console.
	s.cancel()
	if s.ctx.Err() != nil && waitErr == nil {
		return "", nil
	}
	return "", waitErr
}

func (s *supervisor) restoreTerminal() {
	if s.termState != nil {
		_ = term.Restore(int(s.tty.Fd()), s.termState)
	}
	fmt.Fprint(s.out, terminalReset)
}

// switchTo is a client's switch_mode: it answers once the new child runs.
func (s *supervisor) switchTo(target string, force bool) (map[string]any, error) {
	if target != ModeRPC && target != ModeTUI {
		return nil, errors.New("unknown mode")
	}
	s.mu.Lock()
	if s.busy {
		s.mu.Unlock()
		return nil, errors.New("切换正在进行")
	}
	s.busy = true
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		s.busy = false
		s.mu.Unlock()
	}()
	w := s.w
	current := w.currentMode()
	if current == target {
		return map[string]any{"mode": target}, nil
	}
	if target == ModeTUI {
		if w.busy() {
			if !force {
				return map[string]any{"mode": current, "busy": true}, errors.New("当前任务正在运行")
			}
			// The user chose to drop the work in flight: nothing queued may run after.
			_, _ = w.request(map[string]any{"type": "clear_queue"}, switchReplyTimeout)
			_, _ = w.request(map[string]any{"type": "abort"}, switchReplyTimeout)
			deadline := time.Now().Add(abortSettleTimeout)
			for w.busy() && time.Now().Before(deadline) {
				time.Sleep(50 * time.Millisecond)
			}
			if w.busy() {
				return map[string]any{"mode": current, "busy": true}, errors.New("当前任务未能在时限内停止")
			}
		}
		// The session Pi itself reports now: a /new since start changed it.
		if _, err := w.request(map[string]any{"type": "get_state"}, switchReplyTimeout); err != nil {
			return nil, fmt.Errorf("读取会话失败：%w", err)
		}
	} else if !force && sessionBusy(s.tuiSessionFile()) {
		return map[string]any{"mode": current, "busy": true}, errors.New("当前任务正在运行")
	}
	w.mu.Lock()
	id := w.sessionID
	w.mu.Unlock()
	if id == "" {
		return nil, errors.New("会话尚未建立，无法切换")
	}
	started := make(chan error, 1)
	s.mu.Lock()
	s.target, s.started = target, started
	stop := s.stop
	s.mu.Unlock()
	stop()
	select {
	case err := <-started:
		if err != nil {
			return nil, fmt.Errorf("启动失败：%w", err)
		}
		return map[string]any{"mode": target}, nil
	case <-time.After(childStopTimeout + 2*switchReplyTimeout):
		return nil, errors.New("切换超时")
	case <-s.ctx.Done():
		return nil, s.ctx.Err()
	}
}

// tuiSessionFile is the session Pi's TUI is on now. /new or /resume inside the
// TUI moves it to another file of the same project; the newest file written
// since the TUI started, recorded for this cwd, is that session.
func (s *supervisor) tuiSessionFile() string {
	s.w.mu.Lock()
	current := s.w.sessionFile
	s.w.mu.Unlock()
	s.mu.Lock()
	since := s.tuiFrom
	s.mu.Unlock()
	if current == "" {
		return ""
	}
	cwd, _ := os.Getwd()
	entries, err := os.ReadDir(filepath.Dir(current))
	if err != nil {
		return current
	}
	best, bestTime := current, time.Time{}
	if info, err := os.Stat(current); err == nil {
		bestTime = info.ModTime()
	}
	for _, e := range entries {
		info, err := e.Info()
		if err != nil || !strings.HasSuffix(e.Name(), ".jsonl") || info.ModTime().Before(since) || !info.ModTime().After(bestTime) {
			continue
		}
		path := filepath.Join(filepath.Dir(current), e.Name())
		if id, dir := sessionHeader(path); id != "" && dir == cwd {
			best, bestTime = path, info.ModTime()
		}
	}
	return best
}

// adoptTUISession makes the TUI's current session the one the agent resumes.
func (s *supervisor) adoptTUISession() {
	path := s.tuiSessionFile()
	id, _ := sessionHeader(path)
	if id == "" {
		return
	}
	s.w.mu.Lock()
	s.w.sessionID, s.w.sessionFile = id, path
	s.w.mu.Unlock()
}

// sessionHeader reads a session file's first entry: its id and cwd.
func sessionHeader(path string) (string, string) {
	f, err := os.Open(path)
	if err != nil {
		return "", ""
	}
	defer f.Close()
	line, _ := bufio.NewReader(io.LimitReader(f, 64<<10)).ReadBytes('\n')
	var h struct {
		Type string `json:"type"`
		ID   string `json:"id"`
		Cwd  string `json:"cwd"`
	}
	if json.Unmarshal(line, &h) != nil || h.Type != "session" {
		return "", ""
	}
	return h.ID, h.Cwd
}

// sessionBusy reads the tail of Pi's session file: a turn is in flight while
// the last message is the user's, a tool call or a tool result (Pi appends the
// final assistant message only when the turn ends).
func sessionBusy(path string) bool {
	if path == "" {
		return false
	}
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	const tail = 256 << 10
	if info, err := f.Stat(); err == nil && info.Size() > tail {
		_, _ = f.Seek(info.Size()-tail, io.SeekStart)
	}
	data, _ := io.ReadAll(f)
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		var e struct {
			Type    string `json:"type"`
			Message *struct {
				Role       string `json:"role"`
				StopReason string `json:"stopReason"`
			} `json:"message"`
		}
		if json.Unmarshal([]byte(lines[i]), &e) != nil || e.Type != "message" || e.Message == nil {
			continue
		}
		switch e.Message.Role {
		case "user", "toolResult":
			return true
		case "assistant":
			return e.Message.StopReason == "toolUse"
		}
		return false
	}
	return false
}

// replayRecords turns Pi's message list (get_messages) into the events a
// client reduces, so a resumed stream shows the whole conversation again.
func replayRecords(messages []json.RawMessage) [][]byte {
	var out [][]byte
	add := func(v map[string]any) {
		raw, _ := json.Marshal(v)
		out = append(out, raw)
	}
	for _, raw := range messages {
		var m struct {
			Role       string          `json:"role"`
			ToolCallID string          `json:"toolCallId"`
			ToolName   string          `json:"toolName"`
			Content    json.RawMessage `json:"content"`
			IsError    bool            `json:"isError"`
		}
		if json.Unmarshal(raw, &m) != nil {
			continue
		}
		switch m.Role {
		case "system":
		case "toolResult":
			add(map[string]any{
				"type": "tool_execution_end", "toolCallId": m.ToolCallID, "toolName": m.ToolName,
				"result": map[string]any{"content": m.Content}, "isError": m.IsError,
			})
		case "user", "assistant":
			add(map[string]any{"type": "message_start", "message": json.RawMessage(raw)})
			add(map[string]any{"type": "message_end", "message": json.RawMessage(raw)})
		default:
			add(map[string]any{"type": "message_start", "message": json.RawMessage(raw)})
		}
	}
	return out
}

// consoleInput reads the RPC console from the tty and can be stopped without
// closing it, so the next child (Pi's TUI) inherits a tty nobody else reads.
type consoleInput struct {
	cancelW *os.File
	done    chan struct{}
	once    sync.Once
}

func startConsole(tty *os.File, onLine func(string), onEOF func()) *consoleInput {
	r, wr, err := os.Pipe()
	c := &consoleInput{cancelW: wr, done: make(chan struct{})}
	if err != nil {
		close(c.done)
		return c
	}
	go func() {
		defer close(c.done)
		defer r.Close()
		fd, cancelFd := int(tty.Fd()), int(r.Fd())
		buf := make([]byte, 4096)
		var line []byte
		var paste pasteJoiner
		for {
			var set unix.FdSet
			set.Zero()
			set.Set(fd)
			set.Set(cancelFd)
			if _, err := unix.Select(max(fd, cancelFd)+1, &set, nil, nil, nil); err != nil {
				if errors.Is(err, unix.EINTR) {
					continue
				}
				return
			}
			if set.IsSet(cancelFd) {
				return
			}
			n, err := unix.Read(fd, buf)
			if n <= 0 {
				if errors.Is(err, unix.EINTR) || errors.Is(err, unix.EAGAIN) {
					continue
				}
				onEOF()
				return
			}
			line = append(line, buf[:n]...)
			for {
				at := strings.IndexByte(string(line), '\n')
				if at < 0 {
					break
				}
				text := strings.TrimSuffix(string(line[:at]), "\r")
				line = line[at+1:]
				if joined, complete := paste.line(text); complete && strings.TrimSpace(joined) != "" {
					onLine(joined)
				}
			}
			if len(line) > 1<<20 {
				line = line[:0]
			}
		}
	}()
	return c
}

// stop ends the reader and waits for it, so no byte typed afterwards is taken.
func (c *consoleInput) stop() {
	c.once.Do(func() {
		_, _ = c.cancelW.Write([]byte{0})
		<-c.done
		_ = c.cancelW.Close()
	})
}
