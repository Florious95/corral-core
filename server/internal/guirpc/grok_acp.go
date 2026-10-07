package guirpc

// Grok's native ACP stream is translated at the daemon boundary, not in a
// pane launcher. No Pi command is ever injected into a Grok process.
// @contract
// @pre exact native grok agent stdio I/O has been attached
// @post clients receive the existing conversation_v1 event/command shapes
// @err bounded request deadlines, unsupported commands and loss fail visibly
// @inv pending requests, active messages and replay are bounded; no approval
// is granted on behalf of a user and no credentials enter model projections

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

var errACPTimeout = errors.New("Grok ACP response deadline exceeded; agent state unknown")

type acpReply struct {
	Result json.RawMessage `json:"result"`
	Error  *struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

type acpPending struct {
	callback func(json.RawMessage, error)
	timer    *time.Timer
}

type acpConfig struct {
	ID       string `json:"id"`
	Category string `json:"category"`
	Current  string `json:"currentValue"`
	Options  []struct {
		Value string `json:"value"`
		Name  string `json:"name"`
	} `json:"options"`
}

type acpModels struct {
	Current   string `json:"currentModelId"`
	Available []struct {
		ID   string `json:"modelId"`
		Name string `json:"name"`
		Meta struct {
			Reasoning bool `json:"supportsReasoningEffort"`
		} `json:"_meta"`
	} `json:"availableModels"`
}

type grokACP struct {
	ctx                                 context.Context
	write                               func([]byte) error
	emit                                func([]byte)
	lost                                func()
	mu                                  sync.Mutex
	prefix                              string
	next                                uint64
	pending                             map[string]*acpPending
	session                             string
	sessionName                         string
	permissions                         map[string]grokPermission
	cwd                                 string
	remember                            func(string) error
	sessionChanging                     bool
	stats                               *grokStatsRead
	usage                               func(context.Context, string) (*grokUsage, error)
	models                              acpModels
	config                              []acpConfig
	commands                            []map[string]any
	running                             bool
	queued                              int
	replaying                           bool
	promptID, promptText, promptCommand string
	admitted                            bool
	blocks                              []map[string]any
	texts                               []*strings.Builder
	blockBytes                          int
	userText                            string
	toolBlocks                          map[string]int
	toolBytes                           map[string]int
}

func newGrokACP(ctx context.Context, write func([]byte) error, emit func([]byte), lost func()) *grokACP {
	return &grokACP{ctx: ctx, write: write, emit: emit, lost: lost, prefix: "corral:" + newStreamID() + ":", pending: make(map[string]*acpPending), permissions: make(map[string]grokPermission), toolBlocks: make(map[string]int), toolBytes: make(map[string]int)}
}

func (g *grokACP) publish(event map[string]any) {
	raw, _ := json.Marshal(event)
	g.emit(raw)
}

func (g *grokACP) response(id, command string, data any, err error) {
	r := map[string]any{"type": "response", "id": id, "command": command, "success": err == nil}
	if err != nil {
		r["error"] = err.Error()
	}
	if data != nil {
		r["data"] = data
	}
	g.publish(r)
}

func (g *grokACP) wire(message map[string]any) error {
	if err := g.ctx.Err(); err != nil {
		return err
	}
	raw, err := json.Marshal(message)
	if err != nil {
		return err
	}
	return g.write(append(raw, '\n'))
}

func (g *grokACP) call(method string, params any, timeout time.Duration, callback func(json.RawMessage, error)) error {
	g.mu.Lock()
	if g.ctx.Err() != nil || len(g.pending) >= 64 {
		g.mu.Unlock()
		return errors.New("Grok ACP request capacity unavailable")
	}
	g.next++
	id := fmt.Sprintf("%s%d", g.prefix, g.next)
	p := &acpPending{callback: callback}
	g.pending[id] = p
	p.timer = time.AfterFunc(timeout, func() {
		g.mu.Lock()
		if g.pending[id] != p {
			g.mu.Unlock()
			return
		}
		delete(g.pending, id)
		g.mu.Unlock()
		callback(nil, errACPTimeout)
		// Optional account metadata is read-only: an unavailable billing
		// endpoint must not disconnect a healthy native agent/session.
		if method != "_x.ai/billing" {
			g.lost()
		}
	})
	g.mu.Unlock()
	if err := g.wire(map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params}); err != nil {
		g.mu.Lock()
		delete(g.pending, id)
		p.timer.Stop()
		g.mu.Unlock()
		return err
	}
	return nil
}

func (g *grokACP) request(ctx context.Context, method string, params any) (json.RawMessage, error) {
	type result struct {
		raw json.RawMessage
		err error
	}
	done := make(chan result, 1)
	timeout := agentStartupTimeout
	if end, ok := ctx.Deadline(); ok {
		timeout = time.Until(end)
	}
	if err := g.call(method, params, timeout, func(raw json.RawMessage, err error) { done <- result{raw, err} }); err != nil {
		return nil, err
	}
	select {
	case r := <-done:
		return r.raw, r.err
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-g.ctx.Done():
		return nil, g.ctx.Err()
	}
}

func (g *grokACP) close() {
	g.mu.Lock()
	defer g.mu.Unlock()
	for id, p := range g.pending {
		p.timer.Stop()
		delete(g.pending, id)
	}
}

func (g *grokACP) metadata(raw json.RawMessage) {
	var r struct {
		Session string      `json:"sessionId"`
		Models  *acpModels  `json:"models"`
		Config  []acpConfig `json:"configOptions"`
		Meta    struct {
			Session  string           `json:"sessionId"`
			Running  string           `json:"x.ai/runningPromptId"`
			Models   *acpModels       `json:"modelState"`
			Commands []map[string]any `json:"availableCommands"`
		} `json:"_meta"`
	}
	if json.Unmarshal(raw, &r) != nil {
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if r.Session == "" {
		r.Session = r.Meta.Session
	}
	if r.Session != "" {
		g.session = r.Session
		g.running = r.Meta.Running != ""
		if g.running {
			g.publish(map[string]any{"type": "agent_start"})
		}
	}
	if r.Models != nil {
		g.models = *r.Models
	} else if r.Meta.Models != nil {
		g.models = *r.Meta.Models
	}
	if r.Config != nil {
		g.config = r.Config
	}
	if r.Meta.Commands != nil {
		g.commands = r.Meta.Commands
	}
}

func (g *grokACP) start(ctx context.Context, cwd, session string) (string, error) {
	raw, err := g.request(ctx, "initialize", map[string]any{"protocolVersion": 1, "clientCapabilities": map[string]any{}, "clientInfo": map[string]any{"name": "corral", "version": "conversation_v1"}})
	if err != nil {
		return "", err
	}
	var init struct {
		Protocol     int `json:"protocolVersion"`
		Capabilities struct {
			Load bool `json:"loadSession"`
		} `json:"agentCapabilities"`
	}
	if json.Unmarshal(raw, &init) != nil || init.Protocol != 1 {
		return "", errors.New("unsupported Grok ACP protocol")
	}
	g.metadata(raw)
	g.mu.Lock()
	g.cwd = cwd
	g.mu.Unlock()
	method := "session/new"
	params := map[string]any{"cwd": cwd, "mcpServers": []any{}}
	if session != "" {
		if !init.Capabilities.Load {
			return "", errors.New("Grok does not support native session loading")
		}
		method = "session/load"
		params["sessionId"] = session
		g.mu.Lock()
		g.session = session
		g.replaying = true
		g.mu.Unlock()
	}
	raw, err = g.request(ctx, method, params)
	if err != nil {
		return "", err
	}
	g.metadata(raw)
	g.mu.Lock()
	if !g.running {
		g.finishMessageLocked("end_turn")
	}
	g.finishUserLocked()
	g.replaying = false
	id := g.session
	g.mu.Unlock()
	if id == "" {
		return "", errors.New("Grok did not report a durable session identity")
	}
	return id, nil
}

func (g *grokACP) modelLocked() modelSummary {
	for _, m := range g.models.Available {
		if m.ID == g.models.Current {
			return modelSummary{ID: m.ID, Name: m.Name, Provider: "grok", Reasoning: m.Meta.Reasoning}
		}
	}
	return modelSummary{ID: g.models.Current, Provider: "grok"}
}

func (g *grokACP) thinkingLocked() (string, []string) {
	for _, c := range g.config {
		if c.Category == "thought_level" {
			var levels []string
			for _, o := range c.Options {
				levels = append(levels, o.Value)
			}
			return c.Current, levels
		}
	}
	return "", nil
}

func (g *grokACP) Write(raw []byte) (int, error) {
	var c struct {
		ID           string            `json:"id"`
		Type         string            `json:"type"`
		Message      string            `json:"message"`
		Model        string            `json:"modelId"`
		Level        string            `json:"level"`
		Images       []json.RawMessage `json:"images"`
		Instructions *string           `json:"customInstructions"`
	}
	if json.Unmarshal(raw, &c) != nil {
		return 0, errors.New("invalid conversation command")
	}
	if c.Type == "extension_ui_response" {
		if err := g.permissionReply(raw); err != nil {
			return 0, err
		}
		return len(raw), nil
	}
	if c.Type == "get_session_stats" {
		if err := g.readStats(c.ID); err != nil {
			return 0, err
		}
		return len(raw), nil
	}
	g.mu.Lock()
	sid := g.session
	_, levels := g.thinkingLocked()
	var data any
	switch c.Type {
	case "get_state":
		data = g.stateLocked()
	case "get_available_models":
		models := make([]modelSummary, 0, len(g.models.Available))
		for _, m := range g.models.Available {
			models = append(models, modelSummary{ID: m.ID, Name: m.Name, Provider: "grok", Reasoning: m.Meta.Reasoning})
		}
		data = map[string]any{"models": models}
	case "get_available_thinking_levels":
		data = map[string]any{"levels": levels}
	case "get_commands":
		data = map[string]any{"commands": g.commands}
	}
	if data != nil {
		g.mu.Unlock()
		g.response(c.ID, c.Type, data, nil)
		return len(raw), nil
	}
	if sid == "" {
		g.mu.Unlock()
		return 0, errors.New("Grok session not initialized")
	}
	if g.sessionChanging {
		g.mu.Unlock()
		return 0, errors.New("Grok 会话正在初始化，输入未提交")
	}
	if c.Type == "new_session" {
		if g.running || g.queued > 0 || g.promptID != "" {
			g.mu.Unlock()
			return 0, errors.New("Grok 当前任务正在运行，未新建会话")
		}
		if g.remember == nil || g.cwd == "" {
			g.mu.Unlock()
			return 0, errors.New("Grok native session persistence unavailable")
		}
		g.sessionChanging = true
		cwd := g.cwd
		g.mu.Unlock()
		err := g.call("session/new", map[string]any{"cwd": cwd, "mcpServers": []any{}}, agentStartupTimeout, func(result json.RawMessage, err error) {
			if err == nil {
				var created struct {
					ID string `json:"sessionId"`
				}
				if json.Unmarshal(result, &created) != nil || created.ID == "" {
					err = errors.New("Grok 未报告新会话身份")
					g.lost() // native creation succeeded but its context is unknowable
				} else {
					err = g.remember(created.ID)
					if err == nil {
						g.metadata(result)
					} else {
						g.lost()
					}
				}
			}
			g.mu.Lock()
			g.sessionChanging = false
			g.mu.Unlock()
			g.response(c.ID, c.Type, nil, err)
		})
		if err != nil {
			g.mu.Lock()
			g.sessionChanging = false
			g.mu.Unlock()
			return 0, err
		}
		return len(raw), nil
	}
	if c.Type == "compact" {
		if c.Instructions != nil && strings.TrimSpace(*c.Instructions) != "" {
			g.mu.Unlock()
			return 0, errors.New("Grok 原生 /compact 尚未确认自定义指令，未提交或丢弃指令")
		}
		c.Message = "/compact"
	}
	if c.Type == "prompt" || c.Type == "compact" {
		if c.ID == "" {
			g.mu.Unlock()
			return 0, errors.New("Grok prompt request ID required")
		}
		if g.running || g.queued > 0 || g.promptID != "" {
			g.mu.Unlock()
			return 0, errors.New("Grok 当前任务正在运行，输入未提交")
		}
		if len(c.Images) > 0 {
			g.mu.Unlock()
			return 0, errors.New("Grok ACP reports image prompts unsupported")
		}
		g.promptID, g.promptText, g.promptCommand, g.admitted = c.ID, c.Message, c.Type, false
		g.mu.Unlock()
		err := g.call("session/prompt", map[string]any{"sessionId": sid, "prompt": []any{map[string]any{"type": "text", "text": c.Message}}}, 10*time.Minute, func(result json.RawMessage, err error) {
			g.mu.Lock()
			defer g.mu.Unlock()
			if errors.Is(err, errACPTimeout) {
				g.response(c.ID, c.Type, nil, err)
				return // timeout is unknown work, never a settled/idle claim
			}
			if !g.admitted {
				if err == nil {
					g.admitLocked()
				} else {
					g.response(c.ID, c.Type, nil, err)
				}
			}
			stop := "error"
			if err == nil {
				var r struct {
					Stop string `json:"stopReason"`
				}
				_ = json.Unmarshal(result, &r)
				stop = r.Stop
			}
			if c.Type == "compact" {
				var feedback strings.Builder
				for _, text := range g.texts {
					if text != nil {
						feedback.WriteString(text.String())
					}
				}
				end := map[string]any{"type": "compaction_end", "reason": "manual", "aborted": stop == "cancelled", "willRetry": false}
				if stop == "cancelled" {
					// No result: the common reducer must retain the cancelled outcome.
				} else if err != nil || stop == "error" || stop == "" {
					end["errorMessage"] = "Grok 原生压缩未完成，请核对会话"
				} else {
					// Slash completion can be a native no-op. Report its feedback,
					// never assert a measured/context-changing compression.
					end["result"] = map[string]any{"summary": clipSessionText(feedback.String(), 1600), "commandOnly": true}
				}
				g.publish(end)
			}
			g.finishMessageLocked(stop)
			g.promptID, g.promptText, g.promptCommand = "", "", ""
			g.running = false
			if err != nil {
				g.publish(map[string]any{"type": "extension_ui_request", "method": "notify", "message": "Grok: " + err.Error()})
			}
			g.publish(map[string]any{"type": "agent_settled"})
		})
		if err != nil {
			g.mu.Lock()
			g.promptID, g.promptText, g.promptCommand = "", "", ""
			g.mu.Unlock()
			return 0, err
		}
		return len(raw), nil
	}
	if c.Type == "abort" {
		g.mu.Unlock()
		g.cancelPermissions()
		err := g.wire(map[string]any{"jsonrpc": "2.0", "method": "session/cancel", "params": map[string]any{"sessionId": sid}})
		g.response(c.ID, c.Type, nil, err)
		return len(raw), nil
	}
	if c.Type == "set_model" || c.Type == "set_thinking_level" {
		configID, value := "model", c.Model
		if c.Type == "set_thinking_level" {
			value = c.Level
			configID = ""
			for _, opt := range g.config {
				if opt.Category == "thought_level" {
					configID = opt.ID
					break
				}
			}
		}
		valid := false
		for _, opt := range g.config {
			if opt.ID == configID {
				for _, choice := range opt.Options {
					if choice.Value == value {
						valid = true
					}
				}
			}
		}
		g.mu.Unlock()
		if !valid {
			return 0, errors.New("Grok 未报告此模型或思维档位")
		}
		err := g.call("session/set_config_option", map[string]any{"sessionId": sid, "configId": configID, "value": value}, agentStartupTimeout, func(result json.RawMessage, err error) {
			if err == nil {
				var confirmation struct {
					Config []acpConfig `json:"configOptions"`
				}
				confirmed := false
				if json.Unmarshal(result, &confirmation) == nil {
					for _, option := range confirmation.Config {
						if option.ID == configID && option.Current == value {
							confirmed = true
						}
					}
				}
				if !confirmed {
					err = errors.New("Grok 未确认配置变更")
				} else {
					g.metadata(result)
				}
			}
			g.mu.Lock()
			var data any
			if err == nil {
				if c.Type == "set_model" {
					g.models.Current = value
					data = g.modelLocked()
				} else {
					g.publish(map[string]any{"type": "thinking_level_changed", "level": value})
				}
			}
			g.mu.Unlock()
			g.response(c.ID, c.Type, data, err)
		})
		if err != nil {
			return 0, err
		}
		return len(raw), nil
	}
	g.mu.Unlock()
	return 0, fmt.Errorf("Grok ACP does not support conversation command %s", c.Type)
}

func (g *grokACP) admitLocked() {
	if g.admitted || g.promptID == "" {
		return
	}
	g.admitted = true
	g.running = true
	if g.promptCommand == "compact" {
		g.publish(map[string]any{"type": "compaction_start", "reason": "manual"})
		g.response(g.promptID, g.promptCommand, nil, nil) // admission, not completion
		return
	}
	message := map[string]any{"role": "user", "content": []any{map[string]any{"type": "text", "text": g.promptText}}}
	g.publish(map[string]any{"type": "message_start", "message": message})
	g.publish(map[string]any{"type": "message_end", "message": message})
	g.publish(map[string]any{"type": "agent_start"})
	g.response(g.promptID, g.promptCommand, nil, nil)
}

func (g *grokACP) ingest(raw []byte) {
	var r struct {
		Version string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Method  string          `json:"method"`
		Params  json.RawMessage `json:"params"`
		acpReply
	}
	if json.Unmarshal(raw, &r) != nil || r.Version != "2.0" {
		return
	}
	if r.Method == "" && len(r.ID) > 0 {
		if len(r.Result) == 0 && r.Error == nil {
			return
		}
		var id string
		if json.Unmarshal(r.ID, &id) != nil {
			return
		}
		g.mu.Lock()
		p := g.pending[id]
		delete(g.pending, id)
		if p != nil {
			p.timer.Stop()
		}
		g.mu.Unlock()
		if p != nil {
			var err error
			if r.Error != nil {
				err = fmt.Errorf("Grok ACP %d: %s", r.Error.Code, r.Error.Message)
			}
			p.callback(r.Result, err)
		}
		return
	}
	if len(r.ID) > 0 {
		if r.Method == "session/request_permission" {
			g.permission(r.ID, r.Params)
		} else {
			_ = g.wire(map[string]any{"jsonrpc": "2.0", "id": r.ID, "error": map[string]any{"code": -32601, "message": "Client capability not supported"}})
		}
		return
	}
	var params struct {
		Session string          `json:"sessionId"`
		Update  json.RawMessage `json:"update"`
	}
	if json.Unmarshal(r.Params, &params) != nil {
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if params.Session == "" || params.Session != g.session {
		return
	}
	if r.Method == "session/update" || r.Method == "_x.ai/session_notification" {
		var title struct {
			Kind    string `json:"sessionUpdate"`
			Title   string `json:"title"`
			Summary string `json:"session_summary"`
		}
		if json.Unmarshal(params.Update, &title) == nil && (title.Kind == "session_info_update" || title.Kind == "session_summary_generated") {
			name := title.Title
			if title.Kind == "session_summary_generated" {
				name = title.Summary
			}
			if name != "" && g.sessionName != name {
				g.sessionName = clipSessionText(name, 160)
				event := map[string]any{"type": "session_info_changed", "sessionId": g.session, "name": g.sessionName}
				var native struct {
					Meta struct {
						Manual *bool `json:"x.ai/titleIsManual"`
					} `json:"_meta"`
				}
				if json.Unmarshal(r.Params, &native) == nil && native.Meta.Manual != nil {
					event["titleIsManual"] = *native.Meta.Manual
				}
				g.publish(event)
			}
			return
		}
	}
	if r.Method == "_x.ai/queue/changed" {
		var queue struct {
			Running string            `json:"runningPromptId"`
			Entries []json.RawMessage `json:"entries"`
		}
		if json.Unmarshal(r.Params, &queue) != nil {
			return
		}
		wasRunning, wasAdmitted := g.running, g.admitted
		g.running, g.queued = queue.Running != "", len(queue.Entries)
		if g.stats != nil {
			return // read-only native queries are not user turns
		}
		if g.running {
			g.admitLocked()
			if !wasRunning && (g.promptID == "" || wasAdmitted) {
				g.publish(map[string]any{"type": "agent_start"})
			}
		}
		return
	}
	if r.Method == "_x.ai/session_notification" {
		var update struct {
			Kind string `json:"sessionUpdate"`
			Stop string `json:"stop_reason"`
		}
		_ = json.Unmarshal(params.Update, &update)
		if g.stats != nil {
			return
		}
		if update.Kind == "turn_completed" && g.promptID == "" {
			g.finishMessageLocked(update.Stop)
			g.running = false
			g.publish(map[string]any{"type": "agent_settled"})
		}
		return
	}
	if r.Method != "session/update" {
		return
	}
	g.updateLocked(params.Update)
}

func (g *grokACP) finishUserLocked() {
	if g.userText == "" {
		return
	}
	message := map[string]any{"role": "user", "content": []any{map[string]any{"type": "text", "text": g.userText}}}
	g.publish(map[string]any{"type": "message_start", "message": message})
	g.publish(map[string]any{"type": "message_end", "message": message})
	g.userText = ""
}

func (g *grokACP) finishMessageLocked(stop string) {
	if len(g.blocks) == 0 {
		return
	}
	for i, text := range g.texts {
		if text != nil {
			field := "text"
			if g.blocks[i]["type"] == "thinking" {
				field = "thinking"
			}
			g.blocks[i][field] = text.String()
		}
	}
	g.publish(map[string]any{"type": "message_end", "message": map[string]any{"role": "assistant", "content": g.blocks, "stopReason": stop}})
	g.blocks = nil
	g.texts = nil
	g.blockBytes = 0
	g.toolBlocks = make(map[string]int)
	g.toolBytes = make(map[string]int)
}

func (g *grokACP) ensureMessageLocked() {
	g.finishUserLocked()
	if len(g.blocks) == 0 {
		g.publish(map[string]any{"type": "message_start", "message": map[string]any{"role": "assistant", "content": []any{}}})
	}
}

func (g *grokACP) updateLocked(raw json.RawMessage) {
	var u struct {
		Kind     string           `json:"sessionUpdate"`
		Content  json.RawMessage  `json:"content"`
		ToolID   string           `json:"toolCallId"`
		Title    string           `json:"title"`
		Input    json.RawMessage  `json:"rawInput"`
		Status   string           `json:"status"`
		Config   []acpConfig      `json:"configOptions"`
		Commands []map[string]any `json:"availableCommands"`
	}
	if json.Unmarshal(raw, &u) != nil {
		return
	}
	if g.stats != nil && (u.Kind == "agent_message_chunk" || u.Kind == "agent_thought_chunk" || u.Kind == "user_message_chunk") {
		if u.Kind == "agent_message_chunk" {
			var content struct{ Type, Text string }
			if json.Unmarshal(u.Content, &content) == nil && content.Type == "text" {
				if g.stats.text.Len()+len(content.Text) > 64<<10 {
					g.stats.overflow = true
				} else {
					g.stats.text.WriteString(content.Text)
				}
			}
		}
		return
	}
	switch u.Kind {
	case "config_option_update":
		g.config = u.Config
		for _, c := range g.config {
			if c.Category == "model" {
				g.models.Current = c.Current
			}
		}
		level, _ := g.thinkingLocked()
		g.publish(map[string]any{"type": "thinking_level_changed", "level": level})
	case "available_commands_update":
		g.commands = u.Commands
		g.response("", "get_commands", map[string]any{"commands": g.commands}, nil)
	case "user_message_chunk":
		var c struct{ Type, Text string }
		_ = json.Unmarshal(u.Content, &c)
		if c.Type == "text" {
			if !g.replaying && g.promptID != "" {
				return // the admitted prompt already has its one user echo
			}
			g.finishMessageLocked("end_turn")
			g.userText += c.Text
			if len(g.userText) > maxHistoryBytes {
				g.lost()
			}
		}
	case "agent_message_chunk", "agent_thought_chunk":
		var c struct{ Type, Text string }
		_ = json.Unmarshal(u.Content, &c)
		if c.Type != "text" || c.Text == "" {
			return
		}
		g.admitLocked()
		g.ensureMessageLocked()
		kind, field := "text", "text"
		if u.Kind == "agent_thought_chunk" {
			kind, field = "thinking", "thinking"
		}
		index := len(g.blocks) - 1
		if index < 0 || g.blocks[index]["type"] != kind {
			index = len(g.blocks)
			g.blocks = append(g.blocks, map[string]any{"type": kind, field: ""})
			g.texts = append(g.texts, &strings.Builder{})
			g.publish(map[string]any{"type": "message_update", "assistantMessageEvent": map[string]any{"type": kind + "_start", "contentIndex": index}})
		}
		g.blockBytes += len(c.Text)
		if g.blockBytes > maxHistoryBytes || len(g.blocks) > maxHistoryRecords {
			g.lost()
			return
		}
		_, _ = g.texts[index].WriteString(c.Text)
		g.publish(map[string]any{"type": "message_update", "assistantMessageEvent": map[string]any{"type": kind + "_delta", "contentIndex": index, "delta": c.Text}})
	case "tool_call":
		if u.ToolID == "" {
			return
		}
		g.admitLocked()
		g.ensureMessageLocked()
		if _, exists := g.toolBlocks[u.ToolID]; exists {
			return
		}
		var args map[string]any
		_ = json.Unmarshal(u.Input, &args)
		g.blockBytes += len(u.Input)
		if g.blockBytes > maxHistoryBytes || len(g.blocks) >= maxHistoryRecords {
			g.lost()
			return
		}
		index := len(g.blocks)
		g.toolBlocks[u.ToolID] = index
		g.toolBytes[u.ToolID] = len(u.Input)
		block := map[string]any{"type": "toolCall", "id": u.ToolID, "name": u.Title, "arguments": args}
		g.blocks = append(g.blocks, block)
		g.texts = append(g.texts, nil)
		g.publish(map[string]any{"type": "message_update", "assistantMessageEvent": map[string]any{"type": "toolcall_end", "contentIndex": index, "toolCall": block}})
		g.publish(map[string]any{"type": "tool_execution_start", "toolCallId": u.ToolID, "toolName": u.Title, "args": args})
	case "tool_call_update":
		if index, exists := g.toolBlocks[u.ToolID]; exists && len(u.Input) > 0 {
			var args map[string]any
			if json.Unmarshal(u.Input, &args) == nil {
				g.blockBytes += len(u.Input) - g.toolBytes[u.ToolID]
				if g.blockBytes > maxHistoryBytes {
					g.lost()
					return
				}
				g.toolBytes[u.ToolID] = len(u.Input)
				g.blocks[index]["arguments"] = args
				if u.Title != "" {
					g.blocks[index]["name"] = u.Title
				}
				g.publish(map[string]any{"type": "message_update", "assistantMessageEvent": map[string]any{"type": "toolcall_end", "contentIndex": index, "toolCall": g.blocks[index]}})
			}
		}
		var content []struct {
			Type    string         `json:"type"`
			Content map[string]any `json:"content"`
		}
		_ = json.Unmarshal(u.Content, &content)
		result := []map[string]any{}
		for _, c := range content {
			if c.Type == "content" {
				result = append(result, c.Content)
			}
		}
		if u.Status == "completed" || u.Status == "failed" {
			g.publish(map[string]any{"type": "tool_execution_end", "toolCallId": u.ToolID, "result": map[string]any{"content": result}, "isError": u.Status == "failed"})
		} else if len(result) > 0 {
			g.publish(map[string]any{"type": "tool_execution_update", "toolCallId": u.ToolID, "partialResult": map[string]any{"content": result}})
		}
	}
}

// Compile-time check: it receives only conversation commands, not raw ACP.
var _ interface{ Write([]byte) (int, error) } = (*grokACP)(nil)
