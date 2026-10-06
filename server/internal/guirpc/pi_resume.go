package guirpc

// History browsing and resume operate on the existing official Pi RPC stdin.
// They never restart Pi or expose host session paths to clients.
// @consumes internal/bridge
// @consumes internal/discovery
// @contract
// @pre a verified native Pi RPC pane and a cwd-scoped session ID
// @post successful switch_session replaces the stream on the same native PID
// @err busy, cancelled, missing and ambiguous identities are visible
// @inv bounded replay is disclosed; original host history/model context is intact

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
)

// SessionBrowser selects each verified provider's native catalog/load protocol.
// Providers without a mapped protocol never receive another agent's commands.
type SessionBrowser interface {
	ListSessions(context.Context, discovery.Pane) ([]SessionInfo, error)
	ResumeSession(context.Context, discovery.Pane, string, bool) (map[string]any, error)
}

func (m *Manager) ListSessions(ctx context.Context, p discovery.Pane) ([]SessionInfo, error) {
	s, err := m.sessionFor(ctx, p)
	if err != nil {
		return nil, err
	}
	if err = s.beginHistory(); err != nil {
		return nil, err
	}
	defer s.endHistory()
	if s.process.Provider == "grok" {
		return s.listGrokSessions(ctx)
	}
	files, err := s.sessionFiles(ctx)
	if err != nil {
		return nil, err
	}
	result := make([]SessionInfo, 0, len(files))
	for _, file := range files {
		result = append(result, file.SessionInfo)
	}
	return result, nil
}

func (m *Manager) ResumeSession(ctx context.Context, p discovery.Pane, id string, force bool) (map[string]any, error) {
	if id == "" || len(id) > 128 {
		return nil, errors.New("无效的历史会话 ID")
	}
	s, err := m.sessionFor(ctx, p)
	if err != nil {
		return nil, err
	}
	if err = s.beginHistory(); err != nil {
		return nil, err
	}
	defer s.endHistory()
	if s.process.Provider == "grok" {
		return s.resumeGrok(ctx, id, force)
	}
	return s.resumePi(ctx, id, force)
}

func (s *session) beginHistory() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.switching {
		return errors.New("会话正在切换，请稍后重试")
	}
	if (s.process.Provider != "pi" && s.process.Provider != "grok") || s.process.Mode != ModeRPC {
		return errors.New("历史恢复需要已验证的原生对话连接")
	}
	s.switching = true
	s.w.setSwitching(true)
	return nil
}
func (s *session) endHistory() {
	s.mu.Lock()
	s.switching = false
	s.mu.Unlock()
	s.w.setSwitching(false)
}
func (s *session) verifiedPi(ctx context.Context) (bridge.NativeProcess, error) {
	s.mu.Lock()
	previous := s.process
	s.mu.Unlock()
	current, err := s.bridge.NativePi(ctx)
	if err != nil || current.Mode != ModeRPC || current.PID != previous.PID || current.Started != previous.Started || current.TTY != previous.TTY {
		return bridge.NativeProcess{}, errors.New("Pi RPC 进程身份已改变，未提交操作")
	}
	return current, nil
}

func piSessionDir(process bridge.NativeProcess, cwd, file string) (string, error) {
	var explicit string
	for i, arg := range process.Args {
		dir := ""
		if arg == "--session-dir" && i+1 < len(process.Args) {
			dir = process.Args[i+1]
		}
		if strings.HasPrefix(arg, "--session-dir=") {
			dir = strings.TrimPrefix(arg, "--session-dir=")
		}
		if dir != "" {
			explicit = dir
		}
	}
	if explicit != "" {
		if !filepath.IsAbs(explicit) {
			explicit = filepath.Join(cwd, explicit)
		}
		return filepath.Clean(explicit), nil
	}
	if file != "" {
		if !filepath.IsAbs(file) {
			return "", errors.New("Pi 未报告绝对会话路径")
		}
		return filepath.Dir(file), nil
	}
	// Persistent sessions report their actual file, including settings/env
	// overrides. A no-session pane has no reported store; use Pi's documented
	// default (or this daemon's explicitly configured Pi agent root).
	root := os.Getenv("PI_CODING_AGENT_DIR")
	if root == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		root = filepath.Join(home, ".pi", "agent")
	}
	encoded := strings.NewReplacer("/", "-", "\\", "-", ":", "-").Replace(strings.TrimLeft(filepath.Clean(cwd), "/\\"))
	return filepath.Join(root, "sessions", "--"+encoded+"--"), nil
}

func (s *session) sessionFiles(ctx context.Context) ([]piSessionFile, error) {
	process, err := s.verifiedPi(ctx)
	if err != nil {
		return nil, err
	}
	if err = s.confirmStateContext(ctx, switchReplyTimeout); err != nil {
		return nil, err
	}
	s.w.mu.Lock()
	file, id := s.w.sessionFile, s.w.sessionID
	s.w.mu.Unlock()
	dir, err := piSessionDir(process, s.pane.CWD, file)
	if err != nil {
		return nil, err
	}
	files, err := listPiSessionFiles(ctx, dir, s.pane.CWD, id)
	if err != nil {
		return nil, errors.New("无法读取当前目录的 Pi 历史会话：" + sessionStoreError(err))
	}
	// Pi's explicit session-file option may select a file outside its configured
	// store. Include that reported file, not its unrelated parent directory.
	if file != "" && filepath.IsAbs(file) && filepath.Dir(file) != dir {
		current, readErr := readPiSessionInfo(ctx, file, s.pane.CWD, id)
		if readErr != nil && !os.IsNotExist(readErr) {
			return nil, errors.New("当前会话文件不可读")
		}
		if current != nil {
			for _, item := range files {
				if item.ID == current.ID {
					return nil, errors.New("Pi session identity is duplicated; no unambiguous selection")
				}
			}
			files = append(files, *current)
		}
	}
	return files, nil
}
func sessionStoreError(err error) string {
	// OS errors may contain host paths. Only controlled, path-free reasons leave
	// the bridge; no public selection accepts or returns a filesystem path.
	var pathErr *os.PathError
	if errors.As(err, &pathErr) {
		return "会话文件不可读"
	}
	return err.Error()
}

func (s *session) resumePi(ctx context.Context, id string, force bool) (map[string]any, error) {
	files, err := s.sessionFiles(ctx)
	if err != nil {
		return nil, err
	}
	var target *piSessionFile
	for i := range files {
		if files[i].ID == id {
			target = &files[i]
			break
		}
	}
	if target == nil {
		return nil, errors.New("当前目录中不存在该历史会话")
	}
	if s.w.busy() {
		if !force {
			return map[string]any{"busy": true}, errors.New("当前任务正在运行，确认停止后才能恢复历史")
		}
		for _, kind := range []string{"clear_queue", "abort"} {
			if _, err = s.w.requestContext(ctx, map[string]any{"type": kind}, switchReplyTimeout); err != nil {
				return nil, err
			}
		}
		for s.w.busy() {
			select {
			case <-ctx.Done():
				return nil, errors.New("当前任务未能在时限内停止")
			case <-time.After(50 * time.Millisecond):
			}
			if err = s.confirmStateContext(ctx, switchReplyTimeout); err != nil {
				return nil, err
			}
		}
	}
	// Resolve and validate again immediately before handing the exact host path
	// to Pi. No filename guessing, arbitrary client path, or process replacement.
	info, err := readPiSessionInfo(ctx, target.path, s.pane.CWD, "")
	if err != nil || info == nil || info.ID != id {
		return nil, errors.New("历史会话文件身份已改变，未恢复")
	}
	if _, err = s.verifiedPi(ctx); err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.hydrating = true
	s.pending = nil
	s.pendingBytes = 0
	s.mu.Unlock()
	switched := false
	defer func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		if !switched {
			for _, raw := range s.pending {
				s.w.ingest(raw)
			}
		}
		s.pending = nil
		s.pendingBytes = 0
		s.hydrating = false
	}()
	reply, err := s.w.requestContext(ctx, map[string]any{"type": "switch_session", "sessionPath": target.path}, 15*time.Second)
	if err != nil {
		s.cancel()
		return nil, errors.New("Pi 未确认会话切换，桥接已关闭；主机进程未终止")
	}
	var outcome struct {
		Cancelled *bool `json:"cancelled"`
	}
	if json.Unmarshal(reply, &outcome) != nil || outcome.Cancelled == nil {
		s.cancel()
		return nil, errors.New("Pi 会话切换结果不完整")
	}
	if *outcome.Cancelled {
		return map[string]any{"cancelled": true}, errors.New("Pi 扩展取消了会话切换")
	}
	switched = true
	return s.commitPiHistory(ctx, id, target.path)
}

func (w *worker) replacePiHistory(history piHistory, state json.RawMessage, id string) (string, uint64) {
	w.mu.Lock()
	defer w.mu.Unlock()
	for c := range w.clients {
		delete(w.clients, c)
		close(c.ch)
		if c.close != nil {
			c.close()
		}
	}
	w.stream = newStreamID()
	w.resetHistory()
	w.lastTool = make(map[string]time.Time)
	var meta struct {
		Name string `json:"sessionName"`
	}
	_ = json.Unmarshal(state, &meta)
	reset, _ := json.Marshal(map[string]any{"type": "session_reset", "sessionId": id, "sessionName": meta.Name, "replace": true})
	w.publish(reset, entry{kind: "session_reset"}, true)
	for _, record := range history.records {
		w.seq++
		w.appendHistory(entry{seq: w.seq, kind: "history", line: encodeRecord(w.seq, record.ts, record.event)})
	}
	window, _ := json.Marshal(map[string]any{"type": "history_window", "loaded_items": history.items, "older_omitted": history.truncated, "content_clipped": history.clipped})
	w.publish(window, entry{kind: "history_window"}, true)
	header, _ := json.Marshal(map[string]any{"type": "response", "command": "get_state", "success": true, "data": state})
	w.publish(projectState(header), entry{kind: "response"}, true)
	w.truncated = history.truncated || history.clipped
	return w.stream, w.seq
}
