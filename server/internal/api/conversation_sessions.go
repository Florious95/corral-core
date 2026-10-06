package api

// Session controls share the authenticated WS and native bridge. Disk/RPC work
// is asynchronous and bounded independently of terminal/input handling.
// @consumes internal/bridge
// @consumes internal/guirpc
// @consumes internal/protocol
// @contract
// @pre a discovered current ref, or an unambiguous Pi RPC pane in a listed cwd
// @post lists and switches use ID-only selections and correlated visible replies
// @err unsupported providers, ambiguity, busy and expired operations fail visibly
// @inv no arbitrary client workspace is used as a filesystem path

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/guirpc"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func (c *wsConn) historyRequest(run func(context.Context), reject func(string)) {
	c.convMu.Lock()
	if c.convHistoryRequests >= 2 {
		c.convMu.Unlock()
		reject("历史会话请求正在进行，请稍后重试")
		return
	}
	c.convHistoryRequests++
	c.convMu.Unlock()
	go func() {
		defer func() { c.convMu.Lock(); c.convHistoryRequests--; c.convMu.Unlock() }()
		ctx, cancel := context.WithTimeout(c.ctx, 40*time.Second)
		defer cancel()
		run(ctx)
	}()
}

func (s *Server) historyPane(ctx context.Context, ref, workspace string) (discovery.Pane, error) {
	if ref != "" {
		entry := s.catalogEntry(ref)
		if entry == nil || (workspace != "" && entry.pane.CWD != workspace) {
			return discovery.Pane{}, errors.New("当前会话或目录已失效")
		}
		return entry.pane, nil
	}
	s.snapMu.Lock()
	var panes []discovery.Pane
	catalog := s.catalog
	if scoped, ok := s.workspaceCatalogs[workspace]; ok {
		catalog = scoped.catalog
	}
	for _, entry := range catalog.list() {
		if entry.pane.CWD == workspace {
			panes = append(panes, entry.pane)
		}
	}
	s.snapMu.Unlock()
	var selected *discovery.Pane
	for _, pane := range panes {
		if err := ctx.Err(); err != nil {
			return discovery.Pane{}, err
		}
		process, err := bridge.NewPane(pane.Socket, pane.PaneID).NativePi(ctx)
		if err != nil || process.Mode != guirpc.ModeRPC {
			continue
		}
		if selected != nil {
			return discovery.Pane{}, errors.New("该目录有多个 Pi 对话，请指定 ref")
		}
		copy := pane
		selected = &copy
	}
	if selected == nil {
		return discovery.Pane{}, errors.New("当前目录没有可用的原生 Pi RPC 对话")
	}
	return *selected, nil
}

func (s *Server) historyBrowser() (guirpc.SessionBrowser, error) {
	browser, ok := s.conversations.(guirpc.SessionBrowser)
	if !ok {
		return nil, errors.New("服务端不支持历史会话恢复")
	}
	return browser, nil
}

func (c *wsConn) handleConversationListSessions(req protocol.ConversationListSessions) {
	reply := func(ref string, sessions []guirpc.SessionInfo, err error) {
		result := protocol.ConversationSessions{ReqID: req.ReqID, Ref: ref, OK: err == nil}
		if err != nil {
			result.Reason = err.Error()
		} else {
			result.Sessions, _ = json.Marshal(sessions)
		}
		c.send(&result)
	}
	c.historyRequest(func(ctx context.Context) {
		pane, err := c.s.historyPane(ctx, req.Ref, req.Workspace)
		if err != nil {
			reply(req.Ref, nil, err)
			return
		}
		browser, err := c.s.historyBrowser()
		if err != nil {
			reply(sessionRef(pane), nil, err)
			return
		}
		sessions, err := browser.ListSessions(ctx, pane)
		reply(sessionRef(pane), sessions, err)
	}, func(reason string) { reply(req.Ref, nil, errors.New(reason)) })
}

func (c *wsConn) handleConversationResumeSession(req protocol.ConversationResumeSession) {
	reply := func(data map[string]any, err error) {
		result := protocol.ConversationSessionResumed{ReqID: req.ReqID, Ref: req.Ref, OK: err == nil}
		if data != nil {
			result.Data, _ = json.Marshal(data)
		}
		if err != nil {
			result.Reason = err.Error()
		}
		c.send(&result)
	}
	c.historyRequest(func(ctx context.Context) {
		pane, err := c.s.historyPane(ctx, req.Ref, "")
		if err != nil {
			reply(nil, err)
			return
		}
		browser, err := c.s.historyBrowser()
		if err != nil {
			reply(nil, err)
			return
		}
		data, err := browser.ResumeSession(ctx, pane, req.SessionID, req.Force)
		reply(data, err)
	}, func(reason string) { reply(nil, errors.New(reason)) })
}

// Android reuses its existing command correlation and callbacks. The same
// operations are also available as explicit frames for other clients.
func (c *wsConn) handleConversationHistoryCommand(cmd protocol.ConversationCommand, kind string, raw []byte) {
	reply := func(data map[string]any, err error) {
		response := map[string]any{"type": "response", "id": cmd.ID, "command": kind, "success": err == nil}
		if data != nil {
			response["data"] = data
		}
		if err != nil {
			response["error"] = err.Error()
		}
		event, _ := json.Marshal(response)
		c.send(&protocol.ConversationEvent{Ref: cmd.Ref, Event: event})
	}
	sub := c.conversationSub(cmd.Ref)
	if sub == nil {
		reply(nil, errors.New("conversation is not attached"))
		return
	}
	sub.writeMu.Lock()
	stream := sub.stream
	sub.writeMu.Unlock()
	c.historyRequest(func(ctx context.Context) {
		pane, err := c.s.historyPane(ctx, cmd.Ref, "")
		if err != nil {
			reply(nil, err)
			return
		}
		if kind != "list_sessions" && kind != "resume_session" {
			operator, ok := c.s.conversations.(guirpc.SessionOperator)
			if !ok {
				reply(nil, errors.New("服务端不支持原生会话操作"))
				return
			}
			var command map[string]json.RawMessage
			_ = json.Unmarshal(raw, &command)
			command["_stream"], _ = json.Marshal(stream)
			data, err := operator.OperateSession(ctx, pane, command)
			reply(data, err)
			return
		}
		browser, err := c.s.historyBrowser()
		if err != nil {
			reply(nil, err)
			return
		}
		if kind == "list_sessions" {
			sessions, err := browser.ListSessions(ctx, pane)
			reply(map[string]any{"sessions": sessions}, err)
			return
		}
		var selection struct {
			ID    string `json:"sessionId"`
			Force bool   `json:"force"`
		}
		_ = json.Unmarshal(raw, &selection)
		data, err := browser.ResumeSession(ctx, pane, selection.ID, selection.Force)
		reply(data, err)
	}, func(reason string) { reply(nil, errors.New(reason)) })
}
