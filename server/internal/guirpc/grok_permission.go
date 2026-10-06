package guirpc

// ACP callback IDs (including numeric zero) never become GUI command IDs.
// @contract
// @pre native ACP requests an option for the currently loaded session
// @post the chosen original optionId or cancelled outcome resolves exactly once
// @err stale session and missing/invalid options fail closed
// @inv no automatic approval; at most 16 callbacks per native connection

import (
	"encoding/json"
	"errors"
)

type grokPermission struct {
	id      json.RawMessage
	sid     string
	options map[string]bool
}

func (g *grokACP) permission(id, raw json.RawMessage) {
	var p struct {
		SID  string `json:"sessionId"`
		Tool struct {
			ID    string          `json:"toolCallId"`
			Title string          `json:"title"`
			Input json.RawMessage `json:"rawInput"`
		} `json:"toolCall"`
		Options []struct {
			ID   string `json:"optionId"`
			Name string `json:"name"`
			Kind string `json:"kind"`
		} `json:"options"`
	}
	cancel := func() {
		_ = g.wire(map[string]any{"jsonrpc": "2.0", "id": id, "result": map[string]any{"outcome": map[string]any{"outcome": "cancelled"}}})
	}
	if json.Unmarshal(raw, &p) != nil || p.Tool.ID == "" || len(p.Options) == 0 || len(p.Options) > 128 {
		cancel()
		return
	}
	g.mu.Lock()
	if p.SID != g.session || len(g.permissions) >= 16 {
		g.mu.Unlock()
		cancel()
		return
	}
	token := newStreamID() + newStreamID()
	callback := grokPermission{id: id, sid: p.SID, options: map[string]bool{}}
	options := make([]map[string]any, 0, len(p.Options))
	for _, o := range p.Options {
		switch o.Kind {
		case "allow_once", "allow_always", "reject_once", "reject_always":
		default:
			g.mu.Unlock()
			cancel()
			return
		}
		if o.ID == "" || callback.options[o.ID] {
			g.mu.Unlock()
			cancel()
			return
		}
		callback.options[o.ID] = true
		options = append(options, map[string]any{"optionId": o.ID, "name": o.Name, "kind": o.Kind})
	}
	g.permissions[token] = callback
	g.mu.Unlock()
	g.publish(map[string]any{"type": "extension_ui_request", "id": token, "method": "permission", "title": p.Tool.Title, "message": string(p.Tool.Input), "toolCallId": p.Tool.ID, "options": options})
}

func (g *grokACP) permissionReply(raw []byte) error {
	var c struct {
		ID     string  `json:"id"`
		Cancel bool    `json:"cancelled"`
		Value  *string `json:"value"`
	}
	if json.Unmarshal(raw, &c) != nil {
		return errors.New("无效审批回复")
	}
	g.mu.Lock()
	p, ok := g.permissions[c.ID]
	if !ok || p.sid != g.session {
		g.mu.Unlock()
		return errors.New("原生审批已过期")
	}
	outcome := map[string]any{"outcome": "cancelled"}
	if !c.Cancel {
		if c.Value == nil || !p.options[*c.Value] {
			g.mu.Unlock()
			return errors.New("不是原生审批选项")
		}
		outcome = map[string]any{"outcome": "selected", "optionId": *c.Value}
	}
	delete(g.permissions, c.ID)
	g.mu.Unlock()
	return g.wire(map[string]any{"jsonrpc": "2.0", "id": p.id, "result": map[string]any{"outcome": outcome}})
}

func (g *grokACP) cancelPermissions() {
	g.mu.Lock()
	pending := g.permissions
	g.permissions = map[string]grokPermission{}
	g.mu.Unlock()
	for _, p := range pending {
		_ = g.wire(map[string]any{"jsonrpc": "2.0", "id": p.id, "result": map[string]any{"outcome": map[string]any{"outcome": "cancelled"}}})
	}
}
