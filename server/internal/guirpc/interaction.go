package guirpc

// User decisions are one-shot bridge/session tokens, not native correlation IDs.
// @contract
// @pre an attached native process requests an interactive decision
// @post only an advertised, unexpired decision reaches that native callback
// @err expiry, owner loss and stale replies cancel visibly without approval
// @inv at most 16 pending decisions; stdio never waits for a human

import (
	"encoding/json"
	"errors"
	"time"
)

const interactionTTL = 2 * time.Minute

type interaction struct {
	native, method, sid, stream string
	options                     map[string]string
	timer                       *time.Timer
}

func (w *worker) requestInteraction(raw []byte) {
	var e map[string]json.RawMessage
	var id, method string
	if json.Unmarshal(raw, &e) != nil {
		return
	}
	_ = json.Unmarshal(e["id"], &id)
	_ = json.Unmarshal(e["method"], &method)
	if id == "" {
		return
	}
	p := &interaction{native: id, method: method, options: map[string]string{}}
	if method == "select" {
		var options []string
		if json.Unmarshal(e["options"], &options) != nil || len(options) > 128 {
			w.cancelNativeInteraction(id)
			return
		}
		for _, v := range options {
			p.options[v] = "select"
		}
	}
	if method == "permission" {
		var options []struct {
			ID   string `json:"optionId"`
			Kind string `json:"kind"`
		}
		if json.Unmarshal(e["options"], &options) != nil || len(options) == 0 || len(options) > 128 {
			w.cancelNativeInteraction(id)
			return
		}
		for _, v := range options {
			p.options[v.ID] = v.Kind
		}
	}
	ttl := interactionTTL
	var timeout int64
	if json.Unmarshal(e["timeout"], &timeout) == nil && timeout > 0 && time.Duration(timeout)*time.Millisecond < ttl {
		ttl = time.Duration(timeout) * time.Millisecond
	}
	token := newStreamID() + newStreamID()
	w.mu.Lock()
	if len(w.interactions) >= 16 || len(w.clients) == 0 {
		w.mu.Unlock()
		w.cancelNativeInteraction(id)
		return
	}
	for _, existing := range w.interactions {
		if existing.native == id {
			w.mu.Unlock()
			return
		}
	}
	p.sid, p.stream = w.sessionID, w.stream
	w.interactions[token] = p
	e["id"], _ = json.Marshal(token)
	e["sessionId"], _ = json.Marshal(p.sid)
	e["expiresAt"], _ = json.Marshal(w.now().Add(ttl).UnixMilli())
	projected, _ := json.Marshal(e)
	w.publish(projected, entry{kind: "extension_ui_request", key: token}, true)
	p.timer = time.AfterFunc(ttl, func() { w.expireInteraction(token, "请求已过期，已安全取消") })
	w.mu.Unlock()
}

func (w *worker) cancelNativeInteraction(id string) {
	raw, _ := json.Marshal(map[string]any{"type": "extension_ui_response", "id": id, "cancelled": true})
	// Sending from ingest must not contend with the command's synchronous writer.
	go func() { _ = w.send(raw) }()
}

func (w *worker) expireInteraction(token, reason string) {
	w.mu.Lock()
	p := w.interactions[token]
	if p == nil {
		w.mu.Unlock()
		return
	}
	delete(w.interactions, token)
	p.timer.Stop()
	w.removeHistory("extension_ui_request", token)
	raw, _ := json.Marshal(map[string]any{"type": "interaction_resolved", "id": token, "reason": reason})
	w.publish(raw, entry{kind: "interaction_resolved"}, true)
	w.mu.Unlock()
	w.cancelNativeInteraction(p.native)
}

// Caller holds mu. Cancellation is asynchronous; no lock-order inversion.
func (w *worker) cancelInteractionsLocked(reason string) {
	for token, p := range w.interactions {
		delete(w.interactions, token)
		p.timer.Stop()
		w.removeHistory("extension_ui_request", token)
		raw, _ := json.Marshal(map[string]any{"type": "interaction_resolved", "id": token, "reason": reason})
		w.publish(raw, entry{kind: "interaction_resolved"}, true)
		w.cancelNativeInteraction(p.native)
	}
}

func (w *worker) replyInteraction(raw []byte) error {
	var c struct {
		Token     string  `json:"requestId"`
		Cancel    bool    `json:"cancelled"`
		Confirmed *bool   `json:"confirmed"`
		Value     *string `json:"value"`
		Permanent bool    `json:"confirmPermanent"`
	}
	if json.Unmarshal(raw, &c) != nil {
		return errors.New("交互回复无效")
	}
	w.inputMu.Lock()
	defer w.inputMu.Unlock()
	w.mu.Lock()
	p := w.interactions[c.Token]
	if p == nil || p.stream != w.stream || p.sid != w.sessionID {
		w.mu.Unlock()
		return errors.New("请求已过期或会话已改变，未提交")
	}
	reply := map[string]any{"type": "extension_ui_response", "id": p.native}
	if c.Cancel {
		if c.Confirmed != nil || c.Value != nil || c.Permanent {
			w.mu.Unlock()
			return errors.New("取消不能同时包含决定值")
		}
		reply["cancelled"] = true
	} else {
		switch p.method {
		case "confirm":
			if c.Confirmed == nil || c.Value != nil {
				w.mu.Unlock()
				return errors.New("确认值缺失")
			}
			reply["confirmed"] = *c.Confirmed
		case "select", "permission":
			if c.Value == nil || c.Confirmed != nil {
				w.mu.Unlock()
				return errors.New("选择值缺失")
			}
			kind, ok := p.options[*c.Value]
			if !ok {
				w.mu.Unlock()
				return errors.New("不是原生提供的选项")
			}
			if (kind == "allow_always" || kind == "reject_always") && !c.Permanent {
				w.mu.Unlock()
				return errors.New("永久范围需要再次明确确认")
			}
			reply["value"] = *c.Value
		case "input", "editor":
			if c.Value == nil || c.Confirmed != nil || len(*c.Value) > maxHistoryText {
				w.mu.Unlock()
				return errors.New("输入缺失或过长")
			}
			reply["value"] = *c.Value
		default:
			w.mu.Unlock()
			return errors.New("不支持的交互")
		}
	}
	delete(w.interactions, c.Token)
	p.timer.Stop()
	w.removeHistory("extension_ui_request", c.Token)
	w.mu.Unlock()
	if w.stdin == nil {
		return errNoAgent
	}
	data, _ := json.Marshal(reply)
	if _, err := w.stdin.Write(append(data, '\n')); err != nil {
		return err
	}
	w.publishEvent(map[string]any{"type": "interaction_resolved", "id": c.Token, "reason": "决定已提交；不代表工具已执行"}, true)
	return nil
}
