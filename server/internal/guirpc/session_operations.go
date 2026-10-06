package guirpc

// Native session mutations share the existing switch/input/replay barrier.
// @consumes internal/bridge
// @contract
// @pre verified birth identity; selected nodes belong to the current native revision
// @post committed native ID/title and replay replace one same-ref stream atomically
// @err busy needs explicit stop consent; unknown outcomes close only the bridge
// @inv host paths stay host-side; no model prompt, file surgery or new agent

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

// NativeMutationTimeout leaves time for the bounded human decision and replay commit.
const NativeMutationTimeout = interactionTTL + 25*time.Second

var errMutationBusy = errors.New("当前任务正在运行；确认停止后才能继续")

type sessionPoint struct {
	Native        string
	Index         int
	SID, Revision string
}

// SessionOperator returns mutation receipts on the authenticated WS even when
// its old replay subscription is atomically replaced.
type SessionOperator interface {
	OperateSession(context.Context, discovery.Pane, map[string]json.RawMessage) (map[string]any, error)
}

func (m *Manager) OperateSession(ctx context.Context, p discovery.Pane, command map[string]json.RawMessage) (map[string]any, error) {
	s, err := m.sessionFor(ctx, p)
	if err != nil {
		return nil, err
	}
	return s.operationContext(ctx, command)
}
func (s *session) operation(command map[string]json.RawMessage) (map[string]any, error) {
	return s.operationContext(s.ctx, command)
}
func (s *session) operationContext(parent context.Context, command map[string]json.RawMessage) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(parent, NativeMutationTimeout)
	defer cancel()
	if err := s.beginHistory(); err != nil {
		return nil, err
	}
	defer s.endHistory()
	var stream string
	_ = json.Unmarshal(command["_stream"], &stream)
	s.w.mu.Lock()
	current := stream == "" || stream == s.w.stream
	s.w.mu.Unlock()
	if !current {
		return nil, errors.New("历史已切换，旧操作未提交")
	}
	var kind, name, token string
	var force bool
	_ = json.Unmarshal(command["type"], &kind)
	_ = json.Unmarshal(command["name"], &name)
	_ = json.Unmarshal(command["pointId"], &token)
	_ = json.Unmarshal(command["force"], &force)
	if kind == "fork_points" || kind == "rewind_points" {
		return s.points(ctx, kind)
	}
	if kind == "rename_session" {
		return s.rename(ctx, name)
	}
	if err := s.stopForMutation(ctx, force); err != nil {
		return map[string]any{"busy": errors.Is(err, errMutationBusy)}, err
	}
	if s.process.Provider == "grok" {
		return s.mutateGrok(ctx, kind, token)
	}
	return s.mutatePi(ctx, kind, token)
}

func (s *session) stopForMutation(ctx context.Context, force bool) error {
	if s.process.Provider == "grok" {
		g, _, err := s.verifiedGrok(ctx)
		if err != nil {
			return err
		}
		g.mu.Lock()
		busy, sid := g.running || g.queued > 0 || g.promptID != "" || g.sessionChanging, g.session
		g.mu.Unlock()
		if !busy {
			return nil
		}
		if !force {
			return errMutationBusy
		}
		g.cancelPermissions()
		if err = g.wire(map[string]any{"jsonrpc": "2.0", "method": "session/cancel", "params": map[string]any{"sessionId": sid}}); err != nil {
			return err
		}
		for {
			g.mu.Lock()
			busy = g.running || g.queued > 0 || g.promptID != "" || g.sessionChanging
			g.mu.Unlock()
			if !busy {
				return nil
			}
			select {
			case <-ctx.Done():
				return errors.New("当前任务未确认停止")
			case <-time.After(50 * time.Millisecond):
			}
		}
	}
	if _, err := s.verifiedPi(ctx); err != nil {
		return err
	}
	if err := s.confirmStateContext(ctx, switchReplyTimeout); err != nil {
		return err
	}
	if !s.w.busy() {
		return nil
	}
	if !force {
		return errMutationBusy
	}
	for _, kind := range []string{"clear_queue", "abort"} {
		if _, err := s.w.requestContext(ctx, map[string]any{"type": kind}, switchReplyTimeout); err != nil {
			return err
		}
	}
	for s.w.busy() {
		if err := s.confirmStateContext(ctx, switchReplyTimeout); err != nil {
			return err
		}
		select {
		case <-ctx.Done():
			return errors.New("当前任务未确认停止")
		case <-time.After(50 * time.Millisecond):
		}
	}
	return nil
}

func revision(raw []byte) string { sum := sha256.Sum256(raw); return hex.EncodeToString(sum[:]) }

func (s *session) nativePoints(ctx context.Context, kind string) (string, json.RawMessage, error) {
	if s.process.Provider == "grok" {
		g, _, err := s.verifiedGrok(ctx)
		if err != nil {
			return "", nil, err
		}
		g.mu.Lock()
		sid := g.session
		g.mu.Unlock()
		if kind == "fork_points" {
			return sid, nil, errors.New("Grok 原生只确认完整克隆；按消息节点分叉尚未证实，请使用克隆会话")
		}
		raw, err := g.request(ctx, "_x.ai/rewind/points", map[string]any{"session_id": sid})
		return sid, raw, err
	}
	if _, err := s.verifiedPi(ctx); err != nil {
		return "", nil, err
	}
	if kind == "rewind_points" {
		return "", nil, errors.New("Pi 官方 RPC 未提供 rewind 或树导航回滚；可分叉到用户消息之前，原会话保持不变")
	}
	if err := s.confirmStateContext(ctx, switchReplyTimeout); err != nil {
		return "", nil, err
	}
	s.w.mu.Lock()
	sid := s.w.sessionID
	s.w.mu.Unlock()
	raw, err := s.w.requestContext(ctx, map[string]any{"type": "get_fork_messages"}, switchReplyTimeout)
	return sid, raw, err
}

func (s *session) points(ctx context.Context, kind string) (map[string]any, error) {
	sid, raw, err := s.nativePoints(ctx, kind)
	if err != nil {
		return nil, err
	}
	rev := revision(raw)
	s.pointsByToken = map[string]sessionPoint{}
	result := make([]map[string]any, 0)
	if s.process.Provider == "pi" {
		var page struct {
			Messages []struct {
				ID   string `json:"entryId"`
				Text string `json:"text"`
			} `json:"messages"`
		}
		if json.Unmarshal(raw, &page) != nil || page.Messages == nil || len(page.Messages) > maxHistoryItems {
			return nil, errors.New("原生分叉目录缺失或过大")
		}
		for _, item := range page.Messages {
			if item.ID == "" {
				return nil, errors.New("分叉节点身份缺失")
			}
			token := newStreamID() + newStreamID()
			s.pointsByToken[token] = sessionPoint{Native: item.ID, SID: sid, Revision: rev}
			result = append(result, map[string]any{"id": token, "text": clipSessionText(item.Text, 800)})
		}
	} else {
		var page struct {
			Points []struct {
				Index int    `json:"prompt_index"`
				Text  string `json:"prompt_preview"`
				Files bool   `json:"has_file_changes"`
			} `json:"rewind_points"`
		}
		if json.Unmarshal(raw, &page) != nil || page.Points == nil || len(page.Points) > maxHistoryItems {
			return nil, errors.New("原生回滚目录缺失或过大")
		}
		for _, item := range page.Points {
			token := newStreamID() + newStreamID()
			s.pointsByToken[token] = sessionPoint{Index: item.Index, SID: sid, Revision: rev}
			result = append(result, map[string]any{"id": token, "text": clipSessionText(item.Text, 800), "index": item.Index, "hasFileChanges": item.Files})
		}
	}
	return map[string]any{"session_id": sid, "points": result}, nil
}

func (s *session) selectedPoint(ctx context.Context, kind, token string) (sessionPoint, error) {
	p, ok := s.pointsByToken[token]
	if !ok {
		return p, errors.New("节点已过期，请重新加载目录")
	}
	sid, raw, err := s.nativePoints(ctx, kind)
	if err != nil {
		return p, err
	}
	if p.SID != sid || p.Revision != revision(raw) {
		return p, errors.New("会话或原生节点目录已改变，请重新选择")
	}
	return p, nil
}

func (s *session) mutatePi(ctx context.Context, kind, token string) (map[string]any, error) {
	native := map[string]any{}
	switch kind {
	case "new_session":
		native["type"] = "new_session"
	case "clone_session":
		native["type"] = "clone"
	case "fork_session":
		p, err := s.selectedPoint(ctx, "fork_points", token)
		if err != nil {
			return nil, err
		}
		native["type"], native["entryId"] = "fork", p.Native
	default:
		return nil, errors.New("Pi 未提供此原生回滚操作")
	}
	s.w.mu.Lock()
	old := s.w.sessionID
	s.w.mu.Unlock()
	s.mu.Lock()
	s.hydrating = true
	s.pending = nil
	s.pendingBytes = 0
	s.mu.Unlock()
	committed := false
	defer func() {
		s.mu.Lock()
		if !committed {
			for _, raw := range s.pending {
				s.w.ingest(raw)
			}
		}
		s.pending = nil
		s.pendingBytes = 0
		s.hydrating = false
		s.mu.Unlock()
	}()
	raw, err := s.w.requestContext(ctx, native, interactionTTL+5*time.Second)
	if err != nil {
		s.cancel()
		return nil, errors.New("Pi 未确认原生会话操作；桥接已关闭，进程未终止")
	}
	var outcome struct {
		Cancelled *bool   `json:"cancelled"`
		Text      *string `json:"text"`
	}
	if json.Unmarshal(raw, &outcome) != nil || outcome.Cancelled == nil {
		s.cancel()
		return nil, errors.New("Pi 原生操作结果不完整")
	}
	if *outcome.Cancelled {
		return map[string]any{"cancelled": true}, errors.New("Pi 扩展取消了操作，原会话未改变")
	}
	data, err := s.commitPiHistory(ctx, "", "")
	if err != nil {
		s.cancel()
		return nil, err
	}
	if data["session_id"] == old {
		s.cancel()
		return nil, errors.New("Pi 未报告新的原生会话身份")
	}
	committed = true
	s.pointsByToken = nil
	if kind == "fork_session" && outcome.Text != nil {
		data["draft"] = *outcome.Text
	}
	return data, nil
}

func (s *session) mutateGrok(ctx context.Context, kind, token string) (map[string]any, error) {
	g, _, err := s.verifiedGrok(ctx)
	if err != nil {
		return nil, err
	}
	g.mu.Lock()
	sid := g.session
	g.mu.Unlock()
	if kind == "new_session" {
		raw, err := g.request(ctx, "session/new", map[string]any{"cwd": s.pane.CWD, "mcpServers": []any{}})
		if err != nil {
			s.cancel()
			return nil, err
		}
		var r struct {
			ID string `json:"sessionId"`
		}
		if json.Unmarshal(raw, &r) != nil || r.ID == "" || r.ID == sid {
			s.cancel()
			return nil, errors.New("Grok 未报告新会话身份")
		}
		// Loading verifies the new ID and captures its native state through one barrier.
		g.mu.Lock()
		g.sessionName = ""
		g.mu.Unlock()
		return s.loadGrok(ctx, r.ID, "")
	}
	if kind == "clone_session" {
		raw, err := g.request(ctx, "_x.ai/session/fork", map[string]any{"sourceSessionId": sid, "sourceCwd": s.pane.CWD, "newCwd": s.pane.CWD})
		if err != nil {
			return nil, err
		}
		var r struct {
			ID     string `json:"newSessionId"`
			CWD    string `json:"newCwd"`
			Parent string `json:"parentSessionId"`
		}
		if json.Unmarshal(raw, &r) != nil || r.ID == "" || r.ID == sid || r.CWD != s.pane.CWD || r.Parent != sid {
			return nil, errors.New("Grok 克隆身份/目录未确认，未重复创建")
		}
		data, err := s.loadGrok(ctx, r.ID, "")
		if err != nil {
			return map[string]any{"created_session_id": r.ID}, fmt.Errorf("已创建克隆，但加载未确认：%w", err)
		}
		return data, nil
	}
	if kind == "rewind_session" {
		p, err := s.selectedPoint(ctx, "rewind_points", token)
		if err != nil {
			return nil, err
		}
		raw, err := g.request(ctx, "_x.ai/rewind/execute", map[string]any{"session_id": sid, "targetPromptIndex": p.Index, "mode": "conversation_only"})
		if err != nil {
			return nil, err
		}
		var r struct {
			Success *bool   `json:"success"`
			Error   *string `json:"error"`
			Text    *string `json:"prompt_text"`
		}
		if json.Unmarshal(raw, &r) != nil || r.Success == nil {
			s.cancel()
			return nil, errors.New("Grok 未报告回滚结果，桥接已关闭")
		}
		if !*r.Success {
			message := "Grok 原生回滚未成功（success=false），未宣称截断或恢复文件"
			if r.Error != nil {
				message += ": " + clipSessionText(*r.Error, 400)
			}
			return nil, errors.New(message)
		}
		g.mu.Lock()
		title := g.sessionName
		g.mu.Unlock()
		data, err := s.loadGrok(ctx, sid, title)
		if err == nil && r.Text != nil {
			data["draft"] = *r.Text
		}
		return data, err
	}
	return nil, errors.New("Grok 未确认按消息节点分叉；仅支持完整克隆")
}

func (s *session) rename(ctx context.Context, name string) (map[string]any, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > 640 {
		return nil, errors.New("标题不能为空或超过160个字符")
	}
	if s.process.Provider == "grok" {
		g, _, err := s.verifiedGrok(ctx)
		if err != nil {
			return nil, err
		}
		g.mu.Lock()
		sid := g.session
		g.mu.Unlock()
		raw, err := g.request(ctx, "_x.ai/session/rename", map[string]any{"sessionId": sid, "title": name})
		if err != nil {
			return nil, err
		}
		var result struct {
			Success *bool `json:"success"`
		}
		if json.Unmarshal(raw, &result) != nil || result.Success == nil || !*result.Success {
			return nil, errors.New("Grok 未确认原生标题变更")
		}
		catalog, err := s.listGrokSessions(ctx)
		if err != nil {
			return nil, err
		}
		confirmed := false
		for _, row := range catalog {
			if row.ID == sid && row.Name == name {
				confirmed = true
				break
			}
		}
		g.mu.Lock()
		same := g.session == sid
		if confirmed && same {
			g.sessionName = name
		}
		g.mu.Unlock()
		if !confirmed || !same {
			return nil, errors.New("Grok 标题目录或会话身份未确认")
		}
		s.w.publishEvent(map[string]any{"type": "session_info_changed", "sessionId": sid, "name": name, "titleIsManual": true}, true)
		return map[string]any{"session_id": sid, "sessionName": name}, nil
	}
	if _, err := s.verifiedPi(ctx); err != nil {
		return nil, err
	}
	s.w.mu.Lock()
	sid := s.w.sessionID
	s.w.mu.Unlock()
	if _, err := s.w.requestContext(ctx, map[string]any{"type": "set_session_name", "name": name}, switchReplyTimeout); err != nil {
		return nil, err
	}
	state, err := s.w.requestContext(ctx, map[string]any{"type": "get_state"}, switchReplyTimeout)
	if err != nil {
		return nil, err
	}
	var actual struct {
		ID   string `json:"sessionId"`
		Name string `json:"sessionName"`
	}
	if json.Unmarshal(state, &actual) != nil || actual.ID != sid || actual.Name != name {
		return nil, errors.New("原生标题或会话身份未确认")
	}
	s.w.publishEvent(map[string]any{"type": "session_info_changed", "sessionId": sid, "name": name}, true)
	return map[string]any{"session_id": sid, "sessionName": name}, nil
}
