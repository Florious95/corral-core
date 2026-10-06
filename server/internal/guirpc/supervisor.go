package guirpc

// The daemon observes native pane processes and bridges their standard I/O.
// It never launches an IPC worker in a pane. A mode switch replaces only the
// verified Pi process, resuming the exact session that Pi reported.
// @consumes internal/bridge
// @consumes internal/discovery
// @contract
// @pre callers use discovered structural pane identities
// @post one shared bounded bridge per native Pi; shutdown leaves Pi alive
// @err startup, framing, identity and switch failures have bounded results
// @inv no private UDS, fixed-rate polling or private pane command

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
)

const agentStartupTimeout = 30 * time.Second
const switchReplyTimeout = 5 * time.Second
const maxBridges = 64

// Transport is the local daemon bridge boundary; Open's connection is an
// in-memory stream, not a filesystem endpoint or an agent lifecycle owner.
type Transport interface {
	Open(context.Context, discovery.Pane) (net.Conn, error)
	Detect(context.Context, discovery.Pane) string
	Available(string) bool
	Activity(string) string
	Prune(string, time.Time, map[string]struct{})
	Close()
}

// Manager owns demand-created bridges for observed native panes, not agents.
type Manager struct {
	mu       sync.Mutex
	ctx      context.Context
	cancel   context.CancelFunc
	sessions map[string]*session
}

// NewManager constructs an idle bridge registry; no subprocess is launched.
func NewManager() *Manager {
	ctx, cancel := context.WithCancel(context.Background())
	return &Manager{ctx: ctx, cancel: cancel, sessions: make(map[string]*session)}
}

func refOf(p discovery.Pane) string { return p.Socket + "\x1f" + p.PaneID }

func (m *Manager) Available(ref string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	s := m.sessions[ref]
	return s != nil && s.ctx.Err() == nil
}

func (m *Manager) Activity(ref string) string {
	m.mu.Lock()
	s := m.sessions[ref]
	m.mu.Unlock()
	if s == nil || s.ctx.Err() != nil || s.w.currentMode() != ModeRPC {
		return ""
	}
	if s.w.busy() {
		return "working"
	}
	return "idle"
}

// Detect runs only on the existing listing cadence, never its own timer. It
// does not attach I/O until a GUI subscriber requests it.
func (m *Manager) Detect(ctx context.Context, p discovery.Pane) string {
	ref := refOf(p)
	m.mu.Lock()
	s := m.sessions[ref]
	m.mu.Unlock()
	if s != nil && s.isSwitching() {
		s.mu.Lock()
		provider := s.process.Provider
		s.mu.Unlock()
		return provider
	}
	if p.Command != "node" && p.Command != "pi" && p.Command != "pi-rpc" && p.Command != "bun" && p.Command != "grok" && p.Command != "grok-native" && s == nil {
		return ""
	}
	process, err := bridge.NewPane(p.Socket, p.PaneID).NativeAgent(ctx)
	if s != nil {
		s.mu.Lock()
		same := err == nil && process.PID == s.process.PID && process.Started == s.process.Started && process.Mode == s.process.Mode && process.Provider == s.process.Provider
		s.mu.Unlock()
		if !same || s.ctx.Err() != nil {
			m.mu.Lock()
			if m.sessions[ref] == s {
				delete(m.sessions, ref)
			}
			m.mu.Unlock()
			s.cancel()
			s = nil
		}
	}
	if err == nil && (process.Mode == ModeRPC || process.Provider == "grok" || s != nil) {
		return process.Provider
	}
	return ""
}

func (m *Manager) sessionFor(ctx context.Context, p discovery.Pane) (*session, error) {
	ref := refOf(p)
	m.mu.Lock()
	for key, old := range m.sessions {
		if old.ctx.Err() != nil {
			delete(m.sessions, key)
		}
	}
	s := m.sessions[ref]
	if s == nil {
		if m.ctx.Err() != nil || len(m.sessions) >= maxBridges {
			m.mu.Unlock()
			return nil, errors.New("native bridge capacity unavailable")
		}
		live, cancel := context.WithCancel(m.ctx)
		s = &session{ctx: live, cancel: cancel, pane: p, created: time.Now(), bridge: bridge.NewPane(p.Socket, p.PaneID), ready: make(chan struct{}), done: make(chan struct{}), w: newWorker(nil)}
		s.w.onSwitch = s.switchTo
		m.sessions[ref] = s
		go s.start()
	}
	m.mu.Unlock()
	select {
	case <-s.ready:
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-m.ctx.Done():
		return nil, m.ctx.Err()
	}
	if s.err != nil {
		return nil, s.err
	}
	if s.ctx.Err() != nil {
		return nil, s.ctx.Err()
	}
	return s, nil
}

func (m *Manager) Open(ctx context.Context, p discovery.Pane) (net.Conn, error) {
	s, err := m.sessionFor(ctx, p)
	if err != nil {
		return nil, err
	}
	client, server := net.Pipe()
	go s.w.serve(s.ctx, server)
	return client, nil
}

// Prune retires refs absent from an accepted inventory, not from a partial
// scan of another workspace. A pane opened after the inventory started is
// not evidence of deletion. Cleanup finishes before its capacity is reused.
func (m *Manager) Prune(cwd string, before time.Time, keep map[string]struct{}) {
	m.mu.Lock()
	var retired []*session
	for ref, s := range m.sessions {
		if cwd != "" && s.pane.CWD != cwd || s.created.After(before) {
			continue
		}
		if _, live := keep[ref]; live {
			continue
		}
		delete(m.sessions, ref)
		retired = append(retired, s)
	}
	m.mu.Unlock()
	for _, s := range retired {
		s.cancel()
	}
	for _, s := range retired {
		<-s.done
	}
}

func (m *Manager) Close() {
	m.cancel()
	m.mu.Lock()
	sessions := m.sessions
	m.sessions = make(map[string]*session)
	m.mu.Unlock()
	for _, s := range sessions {
		s.cancel()
		<-s.done
	}
}

type inputFunc func([]byte) error

func (f inputFunc) Write(raw []byte) (int, error) {
	if err := f(raw); err != nil {
		return 0, err
	}
	return len(raw), nil
}

type session struct {
	ctx          context.Context
	cancel       context.CancelFunc
	pane         discovery.Pane
	created      time.Time
	bridge       *bridge.Pane
	w            *worker
	ready        chan struct{}
	done         chan struct{}
	err          error // immutable after ready is closed
	mu           sync.Mutex
	process      bridge.NativeProcess
	grok         *grokACP
	grokSource   bridge.NativeProcess
	switching    bool
	tuiFrom      time.Time
	stopIO       func()
	hydrating    bool
	pending      [][]byte
	pendingBytes int
}

func (s *session) isSwitching() bool { s.mu.Lock(); defer s.mu.Unlock(); return s.switching }

func (s *session) start() {
	defer close(s.done)
	stopShutdown := context.AfterFunc(s.ctx, s.w.shutdown)
	defer stopShutdown()
	ctx, cancel := context.WithTimeout(s.ctx, agentStartupTimeout)
	defer cancel()
	process, err := s.waitProcess(ctx, "", 0)
	if err == nil && process.Provider == "grok" && process.Mode == ModeTUI {
		var id string
		id, err = s.bridge.NativeSession(ctx, process)
		if err == nil {
			s.mu.Lock()
			s.process, s.grokSource = process, process
			s.mu.Unlock()
			s.w.mu.Lock()
			s.w.sessionID, s.w.mode = id, ModeTUI
			s.w.mu.Unlock()
		}
	} else if err == nil {
		s.mu.Lock()
		s.process = process
		s.mu.Unlock()
		err = s.attachRPC(ctx)
	}
	s.err = err
	if err != nil {
		s.cancel()
	}
	close(s.ready)
	<-s.ctx.Done()
	s.detachRPC()
	s.w.shutdown()
}

func (s *session) waitProcess(ctx context.Context, mode string, previous int) (bridge.NativeProcess, error) {
	for {
		process, err := s.bridge.NativeAgent(ctx)
		if err == nil && (process.Mode == mode || (mode == "" && (process.Mode == ModeRPC || process.Provider == "grok"))) && process.PID != previous {
			return process, nil
		}
		select {
		case <-ctx.Done():
			return bridge.NativeProcess{}, ctx.Err()
		case <-time.After(50 * time.Millisecond):
		}
	}
}

func (s *session) detachRPC() {
	s.w.setInput(nil)
	s.mu.Lock()
	stop := s.stopIO
	s.stopIO = nil
	s.mu.Unlock()
	if stop != nil {
		stop()
	}
}

func (s *session) attachRPC(ctx context.Context) error {
	s.mu.Lock()
	process := s.process
	s.hydrating = true
	s.pending = nil
	s.pendingBytes = 0
	s.mu.Unlock()
	chunks, loss, detach, err := s.bridge.SubscribeWithLoss(s.ctx)
	if err != nil {
		return err
	}
	write, restore, err := s.bridge.RPCInput(s.ctx, process)
	if err != nil {
		detach()
		return err
	}
	live, stop := context.WithCancel(s.ctx)
	var grok *grokACP
	if process.Provider == "grok" {
		grok = newGrokACP(live, write, s.ingest, s.cancel)
		grok.remember = func(id string) error {
			if err := s.bridge.RememberNativeSession(live, process, id); err != nil {
				return err
			}
			s.w.mu.Lock()
			s.w.sessionID = id
			s.w.mu.Unlock()
			return nil
		}
	}
	s.mu.Lock()
	s.grok = grok
	s.mu.Unlock()
	done := make(chan struct{})
	go func() {
		defer close(done)
		var buffered []byte
		for {
			select {
			case <-live.Done():
				return
			case data, ok := <-chunks:
				if !ok {
					if live.Err() == nil {
						s.cancel()
					}
					return
				}
				buffered = append(buffered, data...)
				for {
					at := bytes.IndexByte(buffered, '\n')
					if at < 0 {
						break
					}
					line := bytes.TrimSuffix(buffered[:at], []byte{'\r'})
					if len(line) > MaxRecord {
						s.cancel()
						return
					}
					if json.Valid(line) {
						if grok != nil {
							grok.ingest(append([]byte(nil), line...))
						} else {
							s.ingest(append([]byte(nil), line...))
						}
					}
					buffered = buffered[at+1:]
				}
				if len(buffered) > MaxRecord {
					s.cancel()
					return
				}
			case err, ok := <-loss:
				if ok && err != nil {
					s.cancel()
					return
				}
				loss = nil
			}
		}
	}()
	var once sync.Once
	s.mu.Lock()
	s.stopIO = func() {
		once.Do(func() {
			stop()
			if grok != nil {
				grok.close()
			}
			detach()
			<-done
			restore()
		})
	}
	s.mu.Unlock()
	if grok != nil {
		s.w.setInput(grok)
		if err = s.startGrok(ctx, process, grok); err != nil {
			return err
		}
		s.mu.Lock()
		for _, raw := range s.pending {
			s.w.ingest(raw)
		}
		s.pending = nil
		s.pendingBytes = 0
		s.hydrating = false
		s.mu.Unlock()
		return nil
	}
	s.w.setInput(inputFunc(write))
	deadline := agentStartupTimeout
	if end, ok := ctx.Deadline(); ok {
		deadline = time.Until(end)
	}
	if err = s.confirmState(deadline); err != nil {
		return err
	}
	if _, err = s.w.request(map[string]any{"type": "get_session_stats"}, switchReplyTimeout); err != nil {
		return err
	}
	messages, err := s.w.request(map[string]any{"type": "get_messages"}, switchReplyTimeout)
	if err != nil {
		return err
	}
	var snapshot struct {
		Messages []json.RawMessage `json:"messages"`
	}
	if json.Unmarshal(messages, &snapshot) != nil {
		return errors.New("native Pi history unavailable")
	}
	// History comes from the official API, not rendered terminal text or a
	// guessed newest session file. Live events arriving during hydration follow.
	s.mu.Lock()
	s.w.mu.Lock()
	s.w.resetHistory()
	if s.w.seq > 0 {
		// The clients key messages by stream seq, not Pi timestamps. Clearing
		// server history alone would duplicate the pre-TUI transcript on replay.
		s.w.publish([]byte(`{"type":"session_reset"}`), entry{kind: "session_reset"}, true)
	}
	s.w.mu.Unlock()
	for _, msg := range snapshot.Messages {
		for _, kind := range []string{"message_start", "message_end"} {
			raw, _ := json.Marshal(map[string]any{"type": kind, "message": msg})
			s.w.ingest(raw)
		}
	}
	for _, raw := range s.pending {
		s.w.ingest(raw)
	}
	s.pending = nil
	s.pendingBytes = 0
	s.hydrating = false
	s.mu.Unlock()
	return nil
}

func (s *session) ingest(raw []byte) {
	var h header
	if json.Unmarshal(raw, &h) != nil {
		return
	}
	if h.Type == "response" && h.Command == "get_state" && h.Success != nil && *h.Success {
		s.mu.Lock()
		provider := s.process.Provider
		s.mu.Unlock()
		// Pi does not have Grok ACP's agentProvider field. Stamp the verified
		// CLI identity so a reused pane cannot retain an old provider in the UI.
		if provider == "pi" {
			var response map[string]json.RawMessage
			var state map[string]json.RawMessage
			if json.Unmarshal(raw, &response) == nil && json.Unmarshal(response["data"], &state) == nil && state != nil {
				state["agentProvider"] = json.RawMessage(`"pi"`)
				response["data"], _ = json.Marshal(state)
				raw, _ = json.Marshal(response)
			}
		}
	}
	if h.Type == "response" && len(h.ID) >= len(internalID) && h.ID[:len(internalID)] == internalID {
		s.w.ingest(raw)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.hydrating {
		if s.pendingBytes+len(raw) > maxHistoryBytes {
			s.cancel()
			return
		}
		s.pending = append(s.pending, raw)
		s.pendingBytes += len(raw)
		return
	}
	s.w.ingest(raw)
}

// A missing state field is unknown, not idle. Never stop a native process on
// an incomplete or projected metadata reply.
func (s *session) confirmState(timeout time.Duration) error {
	return s.confirmStateContext(context.Background(), timeout)
}

func (s *session) confirmStateContext(ctx context.Context, timeout time.Duration) error {
	data, err := s.w.requestContext(ctx, map[string]any{"type": "get_state"}, timeout)
	if err != nil {
		return err
	}
	var state struct {
		Streaming  *bool `json:"isStreaming"`
		Compacting *bool `json:"isCompacting"`
		Pending    *int  `json:"pendingMessageCount"`
	}
	if json.Unmarshal(data, &state) != nil || state.Streaming == nil || state.Compacting == nil || state.Pending == nil {
		return errors.New("Pi 未报告完整任务状态，未置换")
	}
	return nil
}

func (s *session) switchTo(target string, force bool) (map[string]any, error) {
	if target != ModeRPC && target != ModeTUI {
		return nil, errors.New("unknown mode")
	}
	s.mu.Lock()
	if s.switching {
		s.mu.Unlock()
		return nil, errors.New("切换正在进行")
	}
	s.switching = true
	process := s.process
	s.mu.Unlock()
	defer func() { s.mu.Lock(); s.switching = false; s.mu.Unlock() }()
	s.w.setSwitching(true)
	defer s.w.setSwitching(false)
	ctx, cancel := context.WithTimeout(s.ctx, agentStartupTimeout+10*time.Second)
	defer cancel()
	current, err := s.bridge.NativeAgent(ctx)
	if err != nil || current.PID != process.PID || current.Started != process.Started {
		return nil, errors.New("Agent 进程身份已改变，未切换")
	}
	if current.Provider == "grok" {
		return s.switchGrok(ctx, current, target, force)
	}
	if target == current.Mode {
		return map[string]any{"mode": target}, nil
	}
	if current.Mode == ModeRPC {
		if err = s.confirmState(switchReplyTimeout); err != nil {
			return nil, err
		}
		if s.w.busy() {
			if !force {
				return map[string]any{"mode": current.Mode, "busy": true}, errors.New("当前任务正在运行")
			}
			if _, err = s.w.request(map[string]any{"type": "clear_queue"}, switchReplyTimeout); err != nil {
				return nil, err
			}
			if _, err = s.w.request(map[string]any{"type": "abort"}, switchReplyTimeout); err != nil {
				return nil, err
			}
			for s.w.busy() {
				if ctx.Err() != nil {
					return nil, errors.New("当前任务未能在时限内停止")
				}
				time.Sleep(50 * time.Millisecond)
				if err = s.confirmState(switchReplyTimeout); err != nil {
					return nil, err
				}
			}
		}
		if _, err = s.w.request(map[string]any{"type": "get_session_stats"}, switchReplyTimeout); err != nil {
			return nil, err
		}
	} else if !force {
		s.w.mu.Lock()
		file, empty := s.w.sessionFile, s.w.totalMessages != nil && *s.w.totalMessages == 0
		s.w.mu.Unlock()
		_, statErr := os.Stat(file)
		if !(empty && os.IsNotExist(statErr)) && sessionBusy(file) {
			return map[string]any{"mode": current.Mode, "busy": true}, errors.New("当前任务正在运行")
		}
	}
	s.w.mu.Lock()
	id, file := s.w.sessionID, s.w.sessionFile
	empty := s.w.totalMessages != nil && *s.w.totalMessages == 0
	s.w.mu.Unlock()
	if id == "" || file == "" {
		return nil, errors.New("会话没有可恢复的持久身份，未切换")
	}
	_, statErr := os.Stat(file)
	if statErr != nil && !(os.IsNotExist(statErr) && empty) {
		return nil, errors.New("会话尚未持久化，未切换")
	}
	// TUI /new or /resume has no native external state API. A newer sibling
	// session makes identity ambiguous: refuse instead of resuming stale work.
	if current.Mode == ModeTUI {
		s.mu.Lock()
		since := s.tuiFrom
		s.mu.Unlock()
		entries, readErr := os.ReadDir(filepath.Dir(file))
		if readErr != nil && !os.IsNotExist(readErr) {
			return nil, errors.New("终端会话身份无法确认，未切换")
		}
		for _, entry := range entries {
			if entry.Name() == filepath.Base(file) || filepath.Ext(entry.Name()) != ".jsonl" {
				continue
			}
			if info, err := entry.Info(); err != nil || info.ModTime().After(since) {
				return nil, errors.New("终端可能已更换会话，当前身份无法确认，未切换")
			}
		}
	}
	args := bridge.PiCommand(current, target, file)
	if os.IsNotExist(statErr) && empty {
		// Pi creates the durable file lazily. Its official explicit-ID option
		// keeps an untouched empty session's UUID without inventing a file.
		args[len(args)-2], args[len(args)-1] = "--session-id", id
	}
	s.detachRPC()
	if err = s.bridge.ReplaceNativeAgent(ctx, current, s.pane.CWD, args); err != nil {
		s.cancel()
		return nil, err
	}
	next, err := s.waitProcess(ctx, target, current.PID)
	if err != nil {
		s.cancel()
		return nil, errors.New("原生 Pi 启动未确认，面板已保留")
	}
	s.mu.Lock()
	s.process = next
	s.mu.Unlock()
	if target == ModeRPC {
		if err = s.attachRPC(ctx); err != nil {
			s.cancel()
			return nil, err
		}
		s.w.mu.Lock()
		same := s.w.sessionID == id
		s.w.mu.Unlock()
		if !same {
			s.cancel()
			return nil, errors.New("恢复后会话身份不一致")
		}
	}
	s.mu.Lock()
	if target == ModeTUI {
		s.tuiFrom = time.Now()
	}
	s.mu.Unlock()
	s.w.setMode(target)
	s.w.publishEvent(map[string]any{"type": "worker_mode", "mode": target}, false)
	return map[string]any{"mode": target}, nil
}

// sessionBusy examines only the exact previously reported durable session.
func sessionBusy(path string) bool {
	f, err := os.Open(path)
	if err != nil {
		return true
	}
	defer f.Close()
	const tail = 256 << 10
	if info, err := f.Stat(); err == nil && info.Size() > tail {
		_, _ = f.Seek(info.Size()-tail, io.SeekStart)
	}
	data, err := io.ReadAll(f)
	if err != nil {
		return true
	}
	lines := bytes.Split(bytes.TrimSpace(data), []byte{'\n'})
	for i := len(lines) - 1; i >= 0; i-- {
		var entry struct {
			Type    string `json:"type"`
			Message *struct {
				Role       string          `json:"role"`
				Content    json.RawMessage `json:"content"`
				StopReason string          `json:"stopReason"`
			} `json:"message"`
		}
		if json.Unmarshal(lines[i], &entry) != nil || entry.Type != "message" || entry.Message == nil {
			continue
		}
		if entry.Message.Role == "user" || entry.Message.Role == "toolResult" {
			return true
		}
		if entry.Message.Role == "assistant" {
			if entry.Message.StopReason == "toolUse" {
				return true
			}
			var content []struct {
				Type string `json:"type"`
			}
			_ = json.Unmarshal(entry.Message.Content, &content)
			for _, c := range content {
				if c.Type == "toolCall" {
					return true
				}
			}
			return false
		}
	}
	return false
}
