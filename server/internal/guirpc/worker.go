// Package guirpc owns a client-created structured agent (Pi RPC) inside its
// own tmux pane and serves its event stream over a private Unix socket.
//
// @contract
// @pre Only an explicit structured launch invokes the worker inside its new pane.
// @post The pane stays a usable line-oriented terminal; GUI clients attach to a
// private JSONL socket that replays a compacted, sequence-numbered history.
// @err Failed child startup or malformed stdout terminates the worker visibly.
// @inv Existing agents are never reconfigured; history, client queues and the
// per-tool update rate are bounded; closing the pane reaps the child and removes
// every file the worker created.
package guirpc

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	// MaxRecord bounds one protocol record, including base64 image inputs.
	MaxRecord = 32 << 20
	// History is compacted (superseded deltas are dropped), so these caps hold
	// long conversations; overflow is explicit through Ready.Truncated.
	maxHistoryBytes   = 8 << 20
	maxHistoryRecords = 4000
	clientQueue       = 256
	// Tool partial results are cumulative snapshots; relaying every chunk is
	// quadratic on slow links. The latest snapshot per tool wins per interval.
	toolUpdateInterval = 150 * time.Millisecond
	helloTimeout       = 5 * time.Second
	writeTimeout       = 5 * time.Second
)

// Dir returns the private socket directory for a daemon state directory,
// keeping socket paths below the AF_UNIX limit on long state roots.
func Dir(stateDir string) string {
	dir := filepath.Join(stateDir, "gui")
	if len(dir)+len("/0123456789abcdef.sock") < 100 {
		return dir
	}
	sum := sha256.Sum256([]byte(stateDir))
	return filepath.Join(os.TempDir(), "corral-gui-"+hex.EncodeToString(sum[:6]))
}

func baseName(ref string) string {
	sum := sha256.Sum256([]byte(ref))
	return hex.EncodeToString(sum[:8])
}

// SocketPath binds one private endpoint to the stable socket/pane ref.
func SocketPath(dir, ref string) string { return filepath.Join(dir, baseName(ref)+".sock") }

func statePath(dir, ref string) string { return filepath.Join(dir, baseName(ref)+".state") }

// Available checks an explicit managed socket without spawning a subprocess.
func Available(dir, ref string) bool {
	if dir == "" {
		return false
	}
	info, err := os.Lstat(SocketPath(dir, ref))
	return err == nil && info.Mode()&os.ModeSocket != 0
}

// Activity reports the managed agent's run state ("working" or "idle") as
// last written by its worker, or "" when unknown.
func Activity(dir, ref string) string {
	data, err := os.ReadFile(statePath(dir, ref))
	if err != nil {
		return ""
	}
	switch state := strings.TrimSpace(string(data)); state {
	case "working", "idle":
		return state
	}
	return ""
}

// Hello is the first line a socket client writes.
type Hello struct {
	Type     string `json:"type"`
	Stream   string `json:"stream,omitempty"`
	AfterSeq uint64 `json:"after_seq,omitempty"`
	// Probe asks only for Ready; the worker closes after writing it.
	Probe bool `json:"probe,omitempty"`
}

// Ready is the first line the worker writes back. Records follow, one per
// line: {"seq":N,"ts":unix_ms,"event":{...}}.
type Ready struct {
	Type      string `json:"type"`
	Stream    string `json:"stream"`
	HeadSeq   uint64 `json:"head_seq"`
	Reset     bool   `json:"reset"`
	Truncated bool   `json:"truncated"`
	Running   bool   `json:"running"`
	Now       int64  `json:"now"`
}

// WaitReady confirms that the worker actually serves its socket, not just
// that tmux created the pane.
func WaitReady(ctx context.Context, dir, ref string) error {
	for {
		if err := probe(ctx, SocketPath(dir, ref)); err == nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(50 * time.Millisecond):
		}
	}
}

func probe(ctx context.Context, path string) error {
	conn, err := (&net.Dialer{Timeout: 200 * time.Millisecond}).DialContext(ctx, "unix", path)
	if err != nil {
		return err
	}
	defer conn.Close()
	deadline := time.Now().Add(time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	_ = conn.SetDeadline(deadline)
	if _, err := conn.Write([]byte(`{"type":"hello","probe":true}` + "\n")); err != nil {
		return err
	}
	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil {
		return err
	}
	var ready Ready
	if json.Unmarshal(line, &ready) != nil || ready.Type != "ready" {
		return errors.New("guirpc: invalid ready")
	}
	return nil
}

type entry struct {
	seq  uint64
	kind string // event type
	key  string // toolCallId for tool records
	sub  string // assistantMessageEvent.type for message_update
	line []byte // encoded record line including '\n'
}

type client struct {
	ch chan []byte
}

type pendingUpdate struct {
	data  []byte
	timer *time.Timer
}

type worker struct {
	mu               sync.Mutex
	stream           string
	seq              uint64
	history          []entry
	size             int
	truncatedThrough uint64
	truncated        bool
	running          bool
	msgStartSeq      uint64
	pending          map[string]*pendingUpdate
	lastTool         map[string]time.Time
	clients          map[*client]struct{}
	now              func() time.Time
	onRunning        func(bool)

	inputMu sync.Mutex
	stdin   io.Writer
}

func newWorker(stdin io.Writer) *worker {
	return &worker{
		stream:    newStreamID(),
		pending:   make(map[string]*pendingUpdate),
		lastTool:  make(map[string]time.Time),
		clients:   make(map[*client]struct{}),
		now:       time.Now,
		onRunning: func(bool) {},
		stdin:     stdin,
	}
}

func newStreamID() string {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}

// childCommand is replaced by tests with a fake agent process.
var childCommand = func(ctx context.Context, name string) *exec.Cmd {
	return exec.CommandContext(ctx, "pi", "--mode", "rpc", "--name", name)
}

// Run is invoked only by agentmirrord gui-worker <private-dir> <name>.
// @contract
// @pre TMUX and TMUX_PANE identify the newly created pane; dir is private.
// @post The child is reaped and the worker's socket/state files removed before return.
// @err Startup/JSONL/child failures return an error; cancellation terminates the group.
// @inv One child, one continuously drained stdout; bounded history and queues.
func Run(ctx context.Context, dir, name string) error {
	socket, _, ok := strings.Cut(os.Getenv("TMUX"), ",")
	pane := os.Getenv("TMUX_PANE")
	if !ok || !filepath.IsAbs(socket) || !strings.HasPrefix(pane, "%") {
		return errors.New("GUI worker requires a tmux pane")
	}
	return run(ctx, dir, socket+"\x1f"+pane, name, os.Stdin, os.Stdout)
}

func run(ctx context.Context, dir, ref, name string, input io.Reader, output io.Writer) error {
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
	cmd := childCommand(ctx, name)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error { return syscall.Kill(-cmd.Process.Pid, syscall.SIGTERM) }
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	cmd.Stderr = os.Stderr
	cmd.WaitDelay = 3 * time.Second
	if err := cmd.Start(); err != nil {
		return err
	}
	defer func() { _ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL) }()
	w := newWorker(stdin)
	w.onRunning = func(running bool) { writeState(state, running) }
	writeState(state, false)
	defer stdin.Close()
	defer w.shutdown()
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
	// The pane stays a readable terminal for this session. It never shows raw
	// JSON and never receives JSON through tmux; GUI input uses the socket.
	fmt.Fprintf(output, "Pi · native conversation · %s\nType a prompt and press Enter; /compact, /new and /help are available.\n", name)
	go func() {
		scan := bufio.NewScanner(input)
		scan.Buffer(make([]byte, 4096), 1<<20)
		for scan.Scan() {
			text := strings.TrimSuffix(strings.TrimPrefix(scan.Text(), "\x1b[200~"), "\x1b[201~")
			if strings.TrimSpace(text) == "" {
				continue
			}
			command := map[string]any{"type": "prompt", "message": text}
			switch text {
			case "/compact":
				command = map[string]any{"type": "compact"}
			case "/new", "/clear":
				command = map[string]any{"type": "new_session"}
			case "/help":
				fmt.Fprintln(output, "/compact — summarize context · /new — fresh session · Ctrl-C — close this agent")
				continue
			default:
				if w.isRunning() {
					command["streamingBehavior"] = "steer"
				}
			}
			data, _ := json.Marshal(command)
			if w.send(data) != nil {
				return
			}
		}
		cancel()
	}()
	scan := bufio.NewScanner(stdout)
	scan.Buffer(make([]byte, 4096), MaxRecord)
	for scan.Scan() {
		data := append([]byte(nil), scan.Bytes()...)
		if !json.Valid(data) {
			cancel()
			break
		}
		w.ingest(data)
		printTerminal(output, data)
	}
	if scan.Err() != nil {
		cancel()
	}
	waitErr := cmd.Wait()
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if scan.Err() != nil {
		return scan.Err()
	}
	return waitErr
}

func writeState(path string, running bool) {
	state := "idle"
	if running {
		state = "working"
	}
	tmp := path + ".tmp"
	if os.WriteFile(tmp, []byte(state), 0o600) == nil {
		_ = os.Rename(tmp, path)
	}
}

func (w *worker) isRunning() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.running
}

func (w *worker) send(data []byte) error {
	w.inputMu.Lock()
	defer w.inputMu.Unlock()
	_, err := w.stdin.Write(append(append([]byte(nil), data...), '\n'))
	return err
}

func (w *worker) shutdown() {
	w.mu.Lock()
	defer w.mu.Unlock()
	for key, p := range w.pending {
		p.timer.Stop()
		delete(w.pending, key)
	}
	for c := range w.clients {
		delete(w.clients, c)
		close(c.ch)
	}
}

// header is the routing subset of one agent record.
type header struct {
	Type       string `json:"type"`
	ToolCallID string `json:"toolCallId"`
	Command    string `json:"command"`
	Success    *bool  `json:"success"`
	Method     string `json:"method"`
	ID         string `json:"id"`
	Message    *struct {
		Role string `json:"role"`
	} `json:"message"`
	Update *struct {
		Type string `json:"type"`
	} `json:"assistantMessageEvent"`
	Data *struct {
		Cancelled bool `json:"cancelled"`
	} `json:"data"`
}

// ingest projects one stdout record for GUI clients. Records the GUI never
// renders (system prompts, duplicated tool results, extension chrome) are
// dropped here, which removes tens of KiB per turn from the phone link.
func (w *worker) ingest(raw []byte) {
	var h header
	if json.Unmarshal(raw, &h) != nil {
		return
	}
	switch h.Type {
	case "message_start", "message_end":
		role := ""
		if h.Message != nil {
			role = h.Message.Role
		}
		if role == "system" || role == "toolResult" {
			return
		}
		w.mu.Lock()
		seq := w.publish(raw, entry{kind: h.Type}, true)
		if role == "assistant" {
			if h.Type == "message_start" {
				w.msgStartSeq = seq
			} else {
				w.compactMessage(w.msgStartSeq, seq)
				w.msgStartSeq = 0
			}
		}
		w.mu.Unlock()
	case "message_update":
		sub := ""
		if h.Update != nil {
			sub = h.Update.Type
		}
		data := withoutFields(raw, "usage")
		w.mu.Lock()
		w.publish(data, entry{kind: h.Type, sub: sub}, true)
		w.mu.Unlock()
	case "turn_start", "turn_end":
		// Turn boundaries are implied by messages and tool executions.
	case "agent_start", "agent_settled":
		w.mu.Lock()
		running := h.Type == "agent_start"
		changed := w.running != running
		w.running = running
		w.publish(raw, entry{kind: h.Type}, true)
		w.mu.Unlock()
		if changed {
			w.onRunning(running)
		}
	case "agent_end":
		w.mu.Lock()
		w.publish(onlyFields(raw, "type", "willRetry"), entry{kind: h.Type}, false)
		w.mu.Unlock()
	case "response":
		data := raw
		switch h.Command {
		case "get_commands":
			data = projectCommands(raw)
		case "get_state":
			data = projectState(raw)
		}
		w.mu.Lock()
		w.publish(data, entry{kind: h.Type}, false)
		if h.Command == "new_session" && h.Success != nil && *h.Success && (h.Data == nil || !h.Data.Cancelled) {
			w.resetHistory()
			w.publish([]byte(`{"type":"session_reset"}`), entry{kind: "session_reset"}, true)
		}
		w.mu.Unlock()
	case "tool_execution_update":
		w.mu.Lock()
		w.toolUpdate(raw, h.ToolCallID)
		w.mu.Unlock()
	case "tool_execution_end":
		w.mu.Lock()
		if p := w.pending[h.ToolCallID]; p != nil {
			p.timer.Stop()
			delete(w.pending, h.ToolCallID)
		}
		delete(w.lastTool, h.ToolCallID)
		w.removeHistory("tool_execution_update", h.ToolCallID)
		w.publish(raw, entry{kind: h.Type, key: h.ToolCallID}, true)
		w.mu.Unlock()
	case "queue_update":
		w.mu.Lock()
		w.removeHistory(h.Type, "")
		w.publish(raw, entry{kind: h.Type}, true)
		w.mu.Unlock()
	case "extension_ui_request":
		switch h.Method {
		case "confirm", "select", "input", "editor":
			// No headless approval UI exists yet: cancel explicitly, never
			// leave the agent waiting forever or approve on the user's behalf.
			response, _ := json.Marshal(map[string]any{"type": "extension_ui_response", "id": h.ID, "cancelled": true})
			_ = w.send(response)
		case "notify":
		default:
			return // status/widget/title chrome has no GUI surface
		}
		w.mu.Lock()
		w.publish(raw, entry{kind: h.Type}, true)
		w.mu.Unlock()
	default:
		w.mu.Lock()
		w.publish(raw, entry{kind: h.Type}, true)
		w.mu.Unlock()
	}
}

// publish assigns the next seq and fans the record out. Caller holds w.mu.
func (w *worker) publish(data []byte, e entry, retain bool) uint64 {
	w.seq++
	e.seq = w.seq
	e.line = encodeRecord(w.seq, w.now().UnixMilli(), data)
	if retain {
		w.appendHistory(e)
	}
	for c := range w.clients {
		select {
		case c.ch <- e.line:
		default:
			// A reader that cannot keep up resumes with after_seq instead of
			// growing memory without bound.
			delete(w.clients, c)
			close(c.ch)
		}
	}
	return e.seq
}

func encodeRecord(seq uint64, ts int64, data []byte) []byte {
	line := make([]byte, 0, len(data)+48)
	line = append(line, `{"seq":`...)
	line = strconv.AppendUint(line, seq, 10)
	line = append(line, `,"ts":`...)
	line = strconv.AppendInt(line, ts, 10)
	line = append(line, `,"event":`...)
	line = append(line, data...)
	return append(line, '}', '\n')
}

func (w *worker) appendHistory(e entry) {
	if len(e.line) > maxHistoryBytes {
		w.truncatedThrough = e.seq
		w.truncated = true
		return
	}
	w.history = append(w.history, e)
	w.size += len(e.line)
	for w.size > maxHistoryBytes || len(w.history) > maxHistoryRecords {
		w.size -= len(w.history[0].line)
		w.truncatedThrough = w.history[0].seq
		w.truncated = true
		w.history[0] = entry{}
		w.history = w.history[1:]
	}
}

// compactMessage drops streaming deltas superseded by message_end. Block
// starts stay: they are tiny and carry the block timing.
func (w *worker) compactMessage(startSeq, endSeq uint64) {
	if startSeq == 0 {
		return
	}
	w.filterHistory(func(e entry) bool {
		return e.kind == "message_update" && e.seq > startSeq && e.seq < endSeq && !strings.HasSuffix(e.sub, "_start")
	})
}

func (w *worker) removeHistory(kind, key string) {
	w.filterHistory(func(e entry) bool { return e.kind == kind && e.key == key })
}

func (w *worker) filterHistory(drop func(entry) bool) {
	kept := w.history[:0]
	for _, e := range w.history {
		if drop(e) {
			w.size -= len(e.line)
			continue
		}
		kept = append(kept, e)
	}
	for i := len(kept); i < len(w.history); i++ {
		w.history[i] = entry{}
	}
	w.history = kept
}

func (w *worker) resetHistory() {
	for key, p := range w.pending {
		p.timer.Stop()
		delete(w.pending, key)
	}
	w.history = nil
	w.size = 0
	w.truncatedThrough = w.seq
	w.truncated = false
	w.msgStartSeq = 0
}

// toolUpdate rate-limits cumulative partial results per tool call. Caller
// holds w.mu; the deferred flush re-acquires it.
func (w *worker) toolUpdate(raw []byte, key string) {
	if p := w.pending[key]; p != nil {
		p.data = raw
		return
	}
	now := w.now()
	since := now.Sub(w.lastTool[key])
	if since >= toolUpdateInterval {
		w.lastTool[key] = now
		w.removeHistory("tool_execution_update", key)
		w.publish(raw, entry{kind: "tool_execution_update", key: key}, true)
		return
	}
	p := &pendingUpdate{data: raw}
	w.pending[key] = p
	p.timer = time.AfterFunc(toolUpdateInterval-since, func() {
		w.mu.Lock()
		defer w.mu.Unlock()
		if w.pending[key] != p {
			return
		}
		delete(w.pending, key)
		w.lastTool[key] = w.now()
		w.removeHistory("tool_execution_update", key)
		w.publish(p.data, entry{kind: "tool_execution_update", key: key}, true)
	})
}

// attach registers a live client and returns its Ready and replay atomically,
// so no record is lost or duplicated between replay and live delivery.
func (w *worker) attach(hello Hello, live bool) (Ready, [][]byte, *client) {
	w.mu.Lock()
	defer w.mu.Unlock()
	ready := Ready{
		Type:      "ready",
		Stream:    w.stream,
		HeadSeq:   w.seq,
		Truncated: w.truncated,
		Running:   w.running,
		Now:       w.now().UnixMilli(),
	}
	after := hello.AfterSeq
	if hello.Stream != w.stream || after < w.truncatedThrough || after > w.seq {
		ready.Reset = true
		after = 0
	}
	var replay [][]byte
	for _, e := range w.history {
		if e.seq > after {
			replay = append(replay, e.line)
		}
	}
	if !live {
		return ready, nil, nil
	}
	c := &client{ch: make(chan []byte, clientQueue)}
	w.clients[c] = struct{}{}
	return ready, replay, c
}

func (w *worker) detach(c *client) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if _, ok := w.clients[c]; ok {
		delete(w.clients, c)
		close(c.ch)
	}
}

func (w *worker) serve(ctx context.Context, conn net.Conn) {
	defer conn.Close()
	scan := bufio.NewScanner(conn)
	scan.Buffer(make([]byte, 4096), MaxRecord)
	_ = conn.SetReadDeadline(time.Now().Add(helloTimeout))
	if !scan.Scan() {
		return
	}
	var hello Hello
	if json.Unmarshal(scan.Bytes(), &hello) != nil || hello.Type != "hello" {
		return
	}
	_ = conn.SetReadDeadline(time.Time{})
	ready, replay, c := w.attach(hello, !hello.Probe)
	write := func(line []byte) error {
		_ = conn.SetWriteDeadline(time.Now().Add(writeTimeout))
		_, err := conn.Write(line)
		return err
	}
	readyLine, _ := json.Marshal(ready)
	if write(append(readyLine, '\n')) != nil || c == nil {
		if c != nil {
			w.detach(c)
		}
		return
	}
	defer w.detach(c)
	go func() {
		for scan.Scan() {
			if !json.Valid(scan.Bytes()) || w.send(scan.Bytes()) != nil {
				break
			}
		}
		conn.Close()
	}()
	for _, line := range replay {
		if write(line) != nil {
			return
		}
	}
	for {
		select {
		case <-ctx.Done():
			return
		case line, ok := <-c.ch:
			if !ok || write(line) != nil {
				return
			}
		}
	}
}

func withoutFields(raw []byte, keys ...string) []byte {
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil {
		return raw
	}
	for _, key := range keys {
		delete(fields, key)
	}
	out, err := json.Marshal(fields)
	if err != nil {
		return raw
	}
	return out
}

func onlyFields(raw []byte, keys ...string) []byte {
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil {
		return raw
	}
	kept := make(map[string]json.RawMessage, len(keys))
	for _, key := range keys {
		if value, ok := fields[key]; ok {
			kept[key] = value
		}
	}
	out, err := json.Marshal(kept)
	if err != nil {
		return raw
	}
	return out
}

// projectCommands keeps what a slash-command sheet renders.
func projectCommands(raw []byte) []byte {
	var resp struct {
		ID      string `json:"id,omitempty"`
		Type    string `json:"type"`
		Command string `json:"command"`
		Success bool   `json:"success"`
		Error   string `json:"error,omitempty"`
		Data    struct {
			Commands []struct {
				Name        string `json:"name"`
				Description string `json:"description,omitempty"`
				Source      string `json:"source,omitempty"`
			} `json:"commands"`
		} `json:"data"`
	}
	if json.Unmarshal(raw, &resp) != nil {
		return raw
	}
	out, err := json.Marshal(resp)
	if err != nil {
		return raw
	}
	return out
}

// projectState keeps the header metadata; the full model object carries
// pricing tiers and endpoints the phone never shows.
func projectState(raw []byte) []byte {
	var resp struct {
		ID      string `json:"id,omitempty"`
		Type    string `json:"type"`
		Command string `json:"command"`
		Success bool   `json:"success"`
		Error   string `json:"error,omitempty"`
		Data    struct {
			Model *struct {
				ID       string `json:"id"`
				Name     string `json:"name,omitempty"`
				Provider string `json:"provider,omitempty"`
			} `json:"model,omitempty"`
			ThinkingLevel       string `json:"thinkingLevel,omitempty"`
			IsStreaming         bool   `json:"isStreaming"`
			IsCompacting        bool   `json:"isCompacting"`
			SessionName         string `json:"sessionName,omitempty"`
			MessageCount        int    `json:"messageCount"`
			PendingMessageCount int    `json:"pendingMessageCount"`
		} `json:"data"`
	}
	if json.Unmarshal(raw, &resp) != nil {
		return raw
	}
	out, err := json.Marshal(resp)
	if err != nil {
		return raw
	}
	return out
}

func printTerminal(output io.Writer, data []byte) {
	var event struct {
		Type     string `json:"type"`
		ToolName string `json:"toolName"`
		IsError  bool   `json:"isError"`
		Error    string `json:"error"`
		Success  *bool  `json:"success"`
		Message  struct {
			Role    string          `json:"role"`
			Content json.RawMessage `json:"content"`
		} `json:"message"`
		Update struct {
			Type  string `json:"type"`
			Delta string `json:"delta"`
		} `json:"assistantMessageEvent"`
	}
	if json.Unmarshal(data, &event) != nil {
		return
	}
	switch event.Type {
	case "message_start":
		if event.Message.Role == "user" {
			fmt.Fprintf(output, "\n› %s\n\n", contentText(event.Message.Content))
		}
	case "message_update":
		if event.Update.Type == "text_delta" {
			fmt.Fprint(output, event.Update.Delta)
		}
	case "message_end":
		if event.Message.Role == "assistant" {
			fmt.Fprintln(output)
		}
	case "tool_execution_start":
		fmt.Fprintf(output, "\n  ⏵ %s\n", event.ToolName)
	case "tool_execution_end":
		if event.IsError {
			fmt.Fprintf(output, "  ✗ %s failed\n", event.ToolName)
		} else {
			fmt.Fprintf(output, "  ✓ %s\n", event.ToolName)
		}
	case "response":
		if event.Success != nil && !*event.Success {
			fmt.Fprintln(output, "Request failed:", event.Error)
		}
	}
}

func contentText(content json.RawMessage) string {
	var text string
	if json.Unmarshal(content, &text) == nil {
		return text
	}
	var blocks []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	_ = json.Unmarshal(content, &blocks)
	parts := make([]string, 0, len(blocks))
	for _, block := range blocks {
		if block.Type == "text" {
			parts = append(parts, block.Text)
		}
	}
	return strings.Join(parts, "\n")
}
