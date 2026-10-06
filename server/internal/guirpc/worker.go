// Package guirpc bridges native foreground Pi RPC through tmux, in the daemon.
// The pane runs only the official CLI; replay and projection are in memory.
// @consumes internal/bridge
// @contract
// @pre an exact native Pi process has been detected in a scoped pane
// @post clients receive bounded, sequence-numbered history and live records
// @err process loss, framing loss and input failure are visible to clients
// @inv no wrapper process or private filesystem socket is created
// @inv Existing agents are never reconfigured; history, client queues and the
// per-tool update rate are bounded; closing the pane reaps the child and removes
// every file the worker created.
package guirpc

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
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
	// Mode is what runs in the pane: "rpc" (structured, this stream) or "tui"
	// (Pi's own interactive UI on the same session; the stream is idle).
	Mode string `json:"mode,omitempty"`
}

type entry struct {
	seq  uint64
	kind string // event type
	key  string // toolCallId for tool records
	sub  string // assistantMessageEvent.type for message_update
	line []byte // encoded record line including '\n'
}

type client struct {
	ch    chan []byte
	close func()
}

type pendingUpdate struct {
	data  []byte
	timer *time.Timer
}

type worker struct {
	mu               sync.Mutex
	done             chan struct{}
	closeOnce        sync.Once
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
	// Real work in flight, as the agent reports it: a turn, a compaction or queued input.
	compacting bool
	queued     int
	// The agent's own session identity (get_state), kept for an in-pane mode switch.
	sessionID     string
	sessionFile   string
	totalMessages *int
	mode          string
	// Worker-internal commands ("worker:<n>") answer here, never to clients.
	waiters map[string]chan []byte
	reqSeq  uint64
	// onSwitch performs a client's switch_mode; set by the supervisor.
	onSwitch func(target string, force bool) (map[string]any, error)

	inputMu   sync.Mutex
	stdin     io.Writer
	switching bool // inputMu: user input cannot race the switch's busy check.
}

func newWorker(stdin io.Writer) *worker {
	return &worker{
		stream:    newStreamID(),
		done:      make(chan struct{}),
		pending:   make(map[string]*pendingUpdate),
		lastTool:  make(map[string]time.Time),
		clients:   make(map[*client]struct{}),
		now:       time.Now,
		onRunning: func(bool) {},
		stdin:     stdin,
		mode:      ModeRPC,
		waiters:   make(map[string]chan []byte),
	}
}

const (
	ModeRPC = "rpc"
	ModeTUI = "tui"
	// internalID prefixes the worker's own commands to the agent.
	internalID = "worker:"
)

var errNoAgent = errors.New("guirpc: no structured agent attached")

func newStreamID() string {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}

func (w *worker) isRunning() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.running
}

func (w *worker) send(data []byte) error { return w.sendInput(data, false) }

func (w *worker) sendUser(data []byte) error { return w.sendInput(data, true) }

func (w *worker) sendInput(data []byte, user bool) error {
	return w.sendInputInStream(data, user, "")
}

func (w *worker) sendInputInStream(data []byte, user bool, stream string) error {
	w.inputMu.Lock()
	defer w.inputMu.Unlock()
	if stream != "" {
		w.mu.Lock()
		current := stream == w.stream
		w.mu.Unlock()
		if !current {
			return errors.New("历史会话已切换，旧输入未提交")
		}
	}
	if user && w.switching {
		return errors.New("会话正在切换，输入未提交")
	}
	if w.stdin == nil {
		return errNoAgent
	}
	_, err := w.stdin.Write(append(append([]byte(nil), data...), '\n'))
	return err
}

func (w *worker) setSwitching(value bool) {
	w.inputMu.Lock()
	w.switching = value
	w.inputMu.Unlock()
}

// setInput swaps the agent's stdin (nil while no structured agent runs).
func (w *worker) setInput(stdin io.Writer) {
	w.inputMu.Lock()
	w.stdin = stdin
	w.inputMu.Unlock()
}

// request sends a worker-internal command and waits for the agent's response.
func (w *worker) request(command map[string]any, timeout time.Duration) (json.RawMessage, error) {
	return w.requestContext(context.Background(), command, timeout)
}

func (w *worker) requestContext(ctx context.Context, command map[string]any, timeout time.Duration) (json.RawMessage, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	w.mu.Lock()
	w.reqSeq++
	id := internalID + strconv.FormatUint(w.reqSeq, 10)
	ch := make(chan []byte, 1)
	w.waiters[id] = ch
	w.mu.Unlock()
	defer func() {
		w.mu.Lock()
		delete(w.waiters, id)
		w.mu.Unlock()
	}()
	command["id"] = id
	data, _ := json.Marshal(command)
	if err := w.send(data); err != nil {
		return nil, err
	}
	select {
	case raw := <-ch:
		var resp struct {
			Success bool            `json:"success"`
			Error   string          `json:"error"`
			Data    json.RawMessage `json:"data"`
		}
		if json.Unmarshal(raw, &resp) != nil || !resp.Success {
			return nil, fmt.Errorf("%s: %s", command["type"], resp.Error)
		}
		return resp.Data, nil
	case <-w.done:
		return nil, errNoAgent
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-time.After(timeout):
		return nil, fmt.Errorf("%s: no response in %s", command["type"], timeout)
	}
}

// busy is real work the agent is doing or about to do.
func (w *worker) busy() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.running || w.compacting || w.queued > 0
}

func (w *worker) currentMode() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.mode
}

func (w *worker) setMode(mode string) {
	w.mu.Lock()
	w.mode = mode
	w.mu.Unlock()
}

func (w *worker) shutdown() {
	w.closeOnce.Do(func() { close(w.done) })
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
	if h.Type == "response" && (h.Command == "get_state" || h.Command == "get_session_stats") {
		w.noteSession(raw)
	}
	if h.Type == "response" && strings.HasPrefix(h.ID, internalID) {
		w.mu.Lock()
		ch := w.waiters[h.ID]
		w.mu.Unlock()
		if ch != nil {
			select {
			case ch <- raw:
			default:
			}
		}
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
		if !running {
			w.compacting = false
		}
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
		case "get_available_models", "set_model":
			data = projectModels(raw)
		case "get_session_stats":
			data = projectStats(raw)
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
		var q struct {
			Steering []json.RawMessage `json:"steering"`
			FollowUp []json.RawMessage `json:"followUp"`
		}
		_ = json.Unmarshal(raw, &q)
		w.mu.Lock()
		w.queued = len(q.Steering) + len(q.FollowUp)
		w.removeHistory(h.Type, "")
		w.publish(raw, entry{kind: h.Type}, true)
		w.mu.Unlock()
	case "compaction_start", "compaction_end":
		w.mu.Lock()
		w.compacting = h.Type == "compaction_start"
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

// noteSession keeps the agent's session identity from any get_state response.
func (w *worker) noteSession(raw []byte) {
	var resp struct {
		Success bool   `json:"success"`
		Command string `json:"command"`
		Data    struct {
			SessionID     string `json:"sessionId"`
			SessionFile   string `json:"sessionFile"`
			TotalMessages *int   `json:"totalMessages"`
			Streaming     *bool  `json:"isStreaming"`
			Compacting    *bool  `json:"isCompacting"`
			Pending       *int   `json:"pendingMessageCount"`
		} `json:"data"`
	}
	if json.Unmarshal(raw, &resp) != nil || !resp.Success {
		return
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if resp.Data.SessionID != "" {
		w.sessionID, w.sessionFile = resp.Data.SessionID, resp.Data.SessionFile
	}
	if resp.Command == "get_session_stats" {
		w.totalMessages = resp.Data.TotalMessages
	}
	if resp.Command == "get_state" {
		if resp.Data.Streaming != nil {
			w.running = *resp.Data.Streaming
		}
		if resp.Data.Compacting != nil {
			w.compacting = *resp.Data.Compacting
		}
		if resp.Data.Pending != nil {
			w.queued = *resp.Data.Pending
		}
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
		Mode:      w.mode,
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
	stopClose := context.AfterFunc(ctx, func() { conn.Close() })
	defer stopClose()
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
	if c != nil {
		w.mu.Lock()
		c.close = func() { _ = conn.Close() }
		_, attached := w.clients[c]
		w.mu.Unlock()
		if !attached {
			return
		}
	}
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
			if !json.Valid(scan.Bytes()) {
				break
			}
			w.mu.Lock()
			current := w.stream == ready.Stream
			w.mu.Unlock()
			if !current {
				break
			}
			w.commandInStream(append([]byte(nil), scan.Bytes()...), ready.Stream)
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

// command routes one client record: switch_mode is the worker's own; everything
// else goes to the structured agent, or fails visibly while none is attached.
func (w *worker) command(raw []byte) { w.commandInStream(raw, "") }

func (w *worker) commandInStream(raw []byte, stream string) {
	var c struct {
		ID    string `json:"id"`
		Type  string `json:"type"`
		Mode  string `json:"mode"`
		Force bool   `json:"force"`
	}
	_ = json.Unmarshal(raw, &c)
	if c.Type == "switch_mode" {
		go func() {
			data, err := map[string]any(nil), errNoAgent
			if w.onSwitch != nil {
				data, err = w.onSwitch(c.Mode, c.Force)
			}
			w.respond(c.ID, c.Type, data, err)
		}()
		return
	}
	if err := w.sendInputInStream(raw, true, stream); err != nil {
		if errors.Is(err, errNoAgent) {
			err = errors.New("Agent 正在终端中运行，切回原生对话后再发送")
		}
		w.respond(c.ID, c.Type, nil, err)
	}
}

// respond publishes a worker-made response to every client (not retained).
func (w *worker) respond(id, command string, data map[string]any, err error) {
	resp := map[string]any{"type": "response", "id": id, "command": command, "success": err == nil}
	if err != nil {
		resp["error"] = err.Error()
	}
	if data != nil {
		resp["data"] = data
	}
	raw, _ := json.Marshal(resp)
	w.mu.Lock()
	w.publish(raw, entry{kind: "response"}, false)
	w.mu.Unlock()
}

// publishEvent fans a worker-made event out; retained ones survive reconnects.
func (w *worker) publishEvent(event map[string]any, retain bool) {
	raw, _ := json.Marshal(event)
	w.mu.Lock()
	w.publish(raw, entry{kind: fmt.Sprint(event["type"])}, retain)
	w.mu.Unlock()
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
			Model               *modelSummary `json:"model,omitempty"`
			AgentProvider       string        `json:"agentProvider,omitempty"`
			ThinkingLevel       string        `json:"thinkingLevel,omitempty"`
			IsStreaming         bool          `json:"isStreaming"`
			IsCompacting        bool          `json:"isCompacting"`
			SessionName         string        `json:"sessionName,omitempty"`
			SessionID           string        `json:"sessionId,omitempty"`
			MessageCount        int           `json:"messageCount"`
			PendingMessageCount int           `json:"pendingMessageCount"`
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

// projectStats keeps Pi's session statistics verbatim (cost stays the native
// decimal literal) but drops sessionFile: the host path never reaches a phone.
func projectStats(raw []byte) []byte {
	var resp map[string]json.RawMessage
	var data map[string]json.RawMessage
	if json.Unmarshal(raw, &resp) != nil || json.Unmarshal(resp["data"], &data) != nil {
		return raw
	}
	delete(data, "sessionFile")
	resp["data"], _ = json.Marshal(data)
	out, err := json.Marshal(resp)
	if err != nil {
		return raw
	}
	return out
}

// modelSummary is what a model picker needs; pricing, endpoints and headers stay on the host.
type modelSummary struct {
	ID        string `json:"id"`
	Name      string `json:"name,omitempty"`
	Provider  string `json:"provider,omitempty"`
	Reasoning bool   `json:"reasoning,omitempty"`
}

// projectModels slims get_available_models (data.models) and set_model (data is one model).
func projectModels(raw []byte) []byte {
	var resp struct {
		ID      string          `json:"id,omitempty"`
		Type    string          `json:"type"`
		Command string          `json:"command"`
		Success bool            `json:"success"`
		Error   string          `json:"error,omitempty"`
		Data    json.RawMessage `json:"data,omitempty"`
	}
	if json.Unmarshal(raw, &resp) != nil {
		return raw
	}
	if len(resp.Data) > 0 {
		var data any
		if resp.Command == "set_model" {
			var model modelSummary
			if json.Unmarshal(resp.Data, &model) != nil {
				return raw
			}
			data = model
		} else {
			var list struct {
				Models []modelSummary `json:"models"`
			}
			if json.Unmarshal(resp.Data, &list) != nil {
				return raw
			}
			data = list
		}
		resp.Data, _ = json.Marshal(data)
	}
	out, err := json.Marshal(resp)
	if err != nil {
		return raw
	}
	return out
}
