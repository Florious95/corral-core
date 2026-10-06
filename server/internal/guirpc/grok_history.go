package guirpc

// Grok historical sessions come from its official cwd-scoped catalog. Loading
// replays the original native history on the existing ACP PID, not a new agent.
// @consumes internal/bridge
// @contract
// @pre verified Grok ACP birth identity and a catalog-issued session ID
// @post successful load atomically replaces stream/history; omissions disclosed
// @err unknown stop/load outcomes close only the bridge, never kill the agent
// @inv no newest-file guessing, user credentials or host paths reach the phone

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"sort"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
)

func (s *session) verifiedGrok(ctx context.Context) (*grokACP, bridge.NativeProcess, error) {
	s.mu.Lock()
	previous, g := s.process, s.grok
	s.mu.Unlock()
	current, err := s.bridge.NativeAgent(ctx)
	if err != nil || g == nil || current.Provider != "grok" || current.Mode != ModeRPC || current.PID != previous.PID || current.Started != previous.Started || current.TTY != previous.TTY {
		return nil, bridge.NativeProcess{}, errors.New("Grok ACP 进程身份已改变，未提交操作")
	}
	return g, current, nil
}

func (s *session) listGrokSessions(ctx context.Context) ([]SessionInfo, error) {
	g, _, err := s.verifiedGrok(ctx)
	if err != nil {
		return nil, err
	}
	g.mu.Lock()
	current := g.session
	g.mu.Unlock()
	result := make([]SessionInfo, 0)
	ids, cursors := make(map[string]bool), make(map[string]bool)
	cursor, bytes := "", 0
	for {
		params := map[string]any{"cwd": s.pane.CWD}
		if cursor != "" {
			params["cursor"] = cursor
		}
		raw, err := g.request(ctx, "session/list", params)
		if err != nil {
			return nil, errors.New("无法读取当前目录的 Grok 历史会话")
		}
		bytes += len(raw)
		if bytes > maxSessionCatalogBytes {
			return nil, errors.New("Grok 历史目录过大，未返回不完整列表")
		}
		var page struct {
			Sessions []struct {
				ID      string `json:"sessionId"`
				CWD     string `json:"cwd"`
				Title   string `json:"title"`
				Updated string `json:"updatedAt"`
			} `json:"sessions"`
			Next string `json:"nextCursor"`
		}
		var fields map[string]json.RawMessage
		if json.Unmarshal(raw, &page) != nil || json.Unmarshal(raw, &fields) != nil || fields["sessions"] == nil {
			return nil, errors.New("Grok 未报告可信的历史目录")
		}
		for _, item := range page.Sessions {
			if filepath.Clean(item.CWD) != filepath.Clean(s.pane.CWD) {
				continue
			}
			if item.ID == "" || len(item.ID) > 128 || ids[item.ID] {
				return nil, errors.New("Grok 历史会话身份缺失或重复")
			}
			ids[item.ID] = true
			modified := int64(0)
			if stamp, err := time.Parse(time.RFC3339Nano, item.Updated); err == nil {
				modified = stamp.UnixMilli()
			}
			result = append(result, SessionInfo{ID: item.ID, Name: clipSessionText(item.Title, 160), Modified: modified, Current: item.ID == current})
		}
		if page.Next == "" {
			break
		}
		if len(page.Next) > 4096 || cursors[page.Next] {
			return nil, errors.New("Grok 历史目录分页身份无效")
		}
		cursors[page.Next] = true
		cursor = page.Next
	}
	if _, _, err = s.verifiedGrok(ctx); err != nil {
		return nil, err
	}
	sort.SliceStable(result, func(i, j int) bool { return result[i].Modified > result[j].Modified })
	return result, nil
}

func (s *session) resumeGrok(ctx context.Context, id string, force bool) (map[string]any, error) {
	sessions, err := s.listGrokSessions(ctx)
	if err != nil {
		return nil, err
	}
	found := false
	for _, item := range sessions {
		if item.ID == id {
			found = true
			break
		}
	}
	if !found {
		return nil, errors.New("当前目录中不存在该 Grok 历史会话")
	}
	g, process, err := s.verifiedGrok(ctx)
	if err != nil {
		return nil, err
	}
	busy := func() bool {
		g.mu.Lock()
		defer g.mu.Unlock()
		return g.running || g.queued > 0 || g.promptID != "" || g.sessionChanging
	}
	if busy() {
		if !force {
			return map[string]any{"busy": true}, errors.New("当前任务正在运行，确认停止后才能恢复历史")
		}
		g.mu.Lock()
		current := g.session
		g.mu.Unlock()
		if err = g.wire(map[string]any{"jsonrpc": "2.0", "method": "session/cancel", "params": map[string]any{"sessionId": current}}); err != nil {
			return nil, err
		}
		for busy() {
			select {
			case <-ctx.Done():
				return nil, errors.New("Grok 当前任务未确认停止，未恢复")
			case <-time.After(50 * time.Millisecond):
			}
		}
	}
	capture := newWorker(nil)
	defer capture.shutdown()
	s.mu.Lock()
	s.hydrating, s.replay = true, capture
	s.mu.Unlock()
	defer func() { s.mu.Lock(); s.hydrating, s.replay = false, nil; s.mu.Unlock() }()
	g.mu.Lock()
	g.sessionChanging, g.replaying = true, true
	g.session = id
	g.mu.Unlock()
	defer func() { g.mu.Lock(); g.sessionChanging, g.replaying = false, false; g.mu.Unlock() }()
	fail := func(message string) (map[string]any, error) { s.cancel(); return nil, errors.New(message) }
	raw, err := g.request(ctx, "session/load", map[string]any{"sessionId": id, "cwd": s.pane.CWD, "mcpServers": []any{}})
	if err != nil {
		return fail("Grok 未确认历史加载，桥接已关闭；主机进程未终止")
	}
	g.metadata(raw)
	g.mu.Lock()
	if !g.running {
		g.finishMessageLocked("end_turn")
	}
	g.finishUserLocked()
	actual := g.session
	g.mu.Unlock()
	if actual != id {
		return fail("Grok 恢复后的会话身份不一致")
	}
	if _, _, err = s.verifiedGrok(ctx); err != nil {
		return fail("Grok 恢复期间进程身份改变")
	}
	if err = s.bridge.RememberNativeSession(ctx, process, id); err != nil {
		return fail("Grok 会话身份保存失败，请核对主机会话")
	}
	_, err = s.w.requestContext(ctx, map[string]any{"type": "get_state"}, switchReplyTimeout)
	if err != nil {
		return fail("Grok 恢复状态未确认")
	}
	// Hold the same native-event lock while sampling both state and replay:
	// a late settled event must not be overwritten by an earlier busy snapshot.
	// Lock order matches native publishing (g -> session -> worker).
	g.mu.Lock()
	if g.session != id {
		g.mu.Unlock()
		return fail("Grok 恢复提交时会话身份改变")
	}
	state, _ := json.Marshal(g.stateLocked())
	s.mu.Lock()
	history := capture.historySnapshot()
	stream, head := s.w.replacePiHistory(history, state, id)
	s.replay, s.hydrating = nil, false
	s.mu.Unlock()
	g.mu.Unlock()
	return map[string]any{"session_id": id, "stream": stream, "head_seq": head, "history_truncated": history.truncated, "content_clipped": history.clipped}, nil
}

// stateLocked is shared by live get_state and the atomic replay commit.
func (g *grokACP) stateLocked() map[string]any {
	level, _ := g.thinkingLocked()
	return map[string]any{"sessionId": g.session, "agentProvider": "grok", "model": g.modelLocked(), "thinkingLevel": level, "isStreaming": g.running, "isCompacting": g.promptCommand == "compact" && g.running, "pendingMessageCount": g.queued}
}

func (w *worker) historySnapshot() piHistory {
	w.mu.Lock()
	defer w.mu.Unlock()
	for key, p := range w.pending {
		p.timer.Stop()
		delete(w.pending, key)
		w.publish(p.data, entry{kind: "tool_execution_update", key: key}, true)
	}
	history := piHistory{truncated: w.truncated}
	for _, item := range w.history {
		var record struct {
			TS    int64           `json:"ts"`
			Event json.RawMessage `json:"event"`
		}
		if json.Unmarshal(item.line, &record) != nil {
			continue
		}
		history.records = append(history.records, piHistoryRecord{event: record.Event, ts: record.TS})
		if item.kind == "message_end" {
			history.items++
		}
	}
	return history
}
