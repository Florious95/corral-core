package api

// conversation.go multiplexes native Pi conversations over the existing /ws
// connection. Each subscription joins a daemon-local stream; the pane keeps
// its official command and JSON records are spliced without re-encoding.
//
// @consumes internal/guirpc
// @contract
// @pre the connection negotiated conversation_v1 in auth/auth_ack
// @post subscribe → conversation_ready + replay + live events, or
// conversation_closed{unavailable|exited|lost}; command → the agent's own
// response record, or a synthesized failed response; create → conversation_created
// @err invalid commands, oversized input and files outside uploadDir never reach the agent
// @inv no second network socket; terminal mirror paths are untouched; per-connection
// subscriptions are bounded and torn down with the connection

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/guirpc"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

const (
	maxConversationSubs     = 8
	conversationDialTimeout = 35 * time.Second
	conversationIOTimeout   = 5 * time.Second
	maxConversationImages   = 5
)

type conversationSub struct {
	ref        string
	conn       net.Conn
	ctx        context.Context
	cancel     context.CancelFunc
	writeMu    sync.Mutex
	switchable bool // ready.mode is the D5 supervisor capability, not Pi's.
	mode       string
}

func (c *wsConn) conversationSub(ref string) *conversationSub {
	c.convMu.Lock()
	defer c.convMu.Unlock()
	return c.convSubs[ref]
}

func (c *wsConn) handleConversationSubscribe(s protocol.ConversationSubscribe) {
	c.stopConversation(s.Ref)
	entry := c.s.catalogEntry(s.Ref)
	if c.s.conversations == nil || entry == nil || (!entry.observation.Conversation && !c.s.conversations.Available(s.Ref)) {
		c.send(&protocol.ConversationClosed{Ref: s.Ref, Reason: protocol.ConversationUnavailable})
		return
	}
	c.convMu.Lock()
	if len(c.convSubs) >= maxConversationSubs {
		c.convMu.Unlock()
		c.send(&protocol.ConversationClosed{Ref: s.Ref, Reason: protocol.ConversationLost})
		return
	}
	ctx, cancel := context.WithCancel(c.ctx)
	sub := &conversationSub{ref: s.Ref, ctx: ctx, cancel: cancel}
	c.convSubs[s.Ref] = sub
	c.convMu.Unlock()
	go c.relayConversation(sub, guirpc.Hello{Type: "hello", Stream: s.Stream, AfterSeq: s.AfterSeq})
}

func (c *wsConn) handleConversationUnsubscribe(u protocol.ConversationUnsubscribe) {
	c.stopConversation(u.Ref)
}

func (c *wsConn) stopConversation(ref string) {
	c.convMu.Lock()
	sub := c.convSubs[ref]
	delete(c.convSubs, ref)
	c.convMu.Unlock()
	if sub != nil {
		sub.cancel()
	}
}

func (c *wsConn) closeConversations() {
	c.convMu.Lock()
	subs := c.convSubs
	c.convSubs = make(map[string]*conversationSub)
	c.convMu.Unlock()
	for _, sub := range subs {
		sub.cancel()
	}
}

// relayConversation owns one local stream, never an agent process. Native
// startup and replay run here without blocking the WebSocket read loop.
func (c *wsConn) relayConversation(sub *conversationSub, hello guirpc.Hello) {
	defer sub.cancel()
	entry := c.s.catalogEntry(sub.ref)
	if entry == nil {
		c.endConversation(sub, false)
		return
	}
	dialCtx, stop := context.WithTimeout(sub.ctx, conversationDialTimeout)
	local, err := c.s.conversations.Open(dialCtx, entry.pane)
	stop()
	if err != nil {
		c.endConversation(sub, false)
		return
	}
	defer local.Close()
	context.AfterFunc(sub.ctx, func() { local.Close() })
	sub.writeMu.Lock()
	sub.conn = local
	line, _ := json.Marshal(hello)
	_ = local.SetWriteDeadline(time.Now().Add(conversationIOTimeout))
	_, err = local.Write(append(line, '\n'))
	sub.writeMu.Unlock()
	if err != nil {
		c.endConversation(sub, false)
		return
	}
	scan := bufio.NewScanner(local)
	scan.Buffer(make([]byte, 64<<10), guirpc.MaxRecord+1024)
	if !scan.Scan() {
		c.endConversation(sub, false)
		return
	}
	var ready guirpc.Ready
	if json.Unmarshal(scan.Bytes(), &ready) != nil || ready.Type != "ready" {
		c.endConversation(sub, false)
		return
	}
	sub.writeMu.Lock()
	sub.switchable = ready.Mode == guirpc.ModeRPC || ready.Mode == guirpc.ModeTUI
	sub.mode = ready.Mode
	sub.writeMu.Unlock()
	c.sendConversationFrame(sub.ctx, mustFrame(&protocol.ConversationReady{
		Ref:              sub.ref,
		Stream:           ready.Stream,
		HeadSeq:          ready.HeadSeq,
		Reset:            ready.Reset,
		HistoryTruncated: ready.Truncated,
		Running:          ready.Running,
		ServerTimeMS:     ready.Now,
		Mode:             ready.Mode,
	}))
	prefix := conversationEventPrefix(sub.ref)
	for scan.Scan() {
		record := scan.Bytes()
		// Worker records are {"seq":N,"ts":T,"event":{...}}; splice the ref in
		// front instead of decoding and re-encoding agent JSON per token.
		if !bytes.HasPrefix(record, []byte(`{"seq":`)) {
			break
		}
		frame := make([]byte, 0, len(prefix)+len(record)+2)
		frame = append(frame, prefix...)
		frame = append(frame, record[1:]...)
		frame = append(frame, '}')
		if !c.sendConversationFrame(sub.ctx, frame) {
			return
		}
	}
	c.endConversation(sub, true)
}

func conversationEventPrefix(ref string) []byte {
	quoted, _ := json.Marshal(ref)
	prefix := []byte(`{"v":` + strconv.Itoa(int(protocol.Version)) + `,"type":"` + string(protocol.TypeConversationEvent) + `","payload":{"ref":`)
	prefix = append(prefix, quoted...)
	return append(prefix, ',')
}

// endConversation reports why a stream ended unless the client already
// detached it. A live daemon bridge means resume with after_seq; otherwise
// the native process or bridge exited.
func (c *wsConn) endConversation(sub *conversationSub, attached bool) {
	if sub.ctx.Err() != nil {
		return
	}
	c.convMu.Lock()
	current := c.convSubs[sub.ref] == sub
	if current {
		delete(c.convSubs, sub.ref)
	}
	c.convMu.Unlock()
	if !current {
		return
	}
	reason := protocol.ConversationExited
	if attached && c.s.conversations.Available(sub.ref) {
		reason = protocol.ConversationLost
	}
	c.send(&protocol.ConversationClosed{Ref: sub.ref, Reason: reason})
}

func mustFrame(typed protocol.Typed) []byte {
	body, err := protocol.MarshalFrame(typed)
	if err != nil {
		panic(err)
	}
	return body
}

// sendConversationFrame queues one text frame with backpressure. A full
// writer queue blocks this relay only; the worker then drops the slow socket
// and the client resumes with after_seq.
func (c *wsConn) sendConversationFrame(ctx context.Context, body []byte) bool {
	c.sendMu.RLock()
	defer c.sendMu.RUnlock()
	if c.catalogAborted.Load() || c.ctx.Err() != nil || ctx.Err() != nil {
		return false
	}
	select {
	case c.sendCh <- wsMsg{typ: wsText, data: body}:
		return true
	case <-ctx.Done():
		return false
	case <-c.ctx.Done():
		return false
	}
}

func (c *wsConn) handleConversationCommand(cmd protocol.ConversationCommand) {
	kind, forwarded, reason := c.s.conversationCommand(cmd.ID, cmd.Command)
	if reason != "" {
		c.rejectConversationCommand(cmd, kind, reason)
		return
	}
	if kind == "list_sessions" || kind == "resume_session" {
		c.handleConversationHistoryCommand(cmd, kind, forwarded)
		return
	}
	sub := c.conversationSub(cmd.Ref)
	if sub == nil {
		c.rejectConversationCommand(cmd, kind, "conversation is not attached")
		return
	}
	sub.writeMu.Lock()
	if kind == "switch_mode" && !sub.switchable {
		sub.writeMu.Unlock()
		c.rejectConversationCommand(cmd, kind, "原生桥接模式不受支持，未转发给 Pi")
		return
	}
	err := errNotAttached
	if sub.conn != nil {
		_ = sub.conn.SetWriteDeadline(time.Now().Add(conversationIOTimeout))
		_, err = sub.conn.Write(append(forwarded, '\n'))
	}
	sub.writeMu.Unlock()
	if err != nil {
		c.rejectConversationCommand(cmd, kind, "agent did not accept the command")
	}
}

var errNotAttached = os.ErrClosed

func (c *wsConn) rejectConversationCommand(cmd protocol.ConversationCommand, kind, reason string) {
	event, _ := json.Marshal(map[string]any{
		"type": "response", "id": cmd.ID, "command": kind, "success": false, "error": reason,
	})
	c.send(&protocol.ConversationEvent{Ref: cmd.Ref, Event: event})
}

// conversationCommand restricts clients to the commands the GUI uses and
// stamps the correlation id. Uploaded image paths must resolve inside this
// server's upload directory; arbitrary host files are never read.
func (s *Server) conversationCommand(id string, raw json.RawMessage) (string, []byte, string) {
	var command map[string]json.RawMessage
	if json.Unmarshal(raw, &command) != nil {
		return "", nil, "command must be a JSON object"
	}
	var kind string
	_ = json.Unmarshal(command["type"], &kind)
	switch kind {
	case "prompt", "steer", "follow_up", "abort", "clear_queue", "compact", "new_session", "get_state", "get_commands",
		"get_available_models", "get_available_thinking_levels":
	case "list_sessions", "get_session_stats":
		command = map[string]json.RawMessage{"type": command["type"]}
	case "resume_session":
		var sessionID string
		var force bool
		if json.Unmarshal(command["sessionId"], &sessionID) != nil || sessionID == "" || len(sessionID) > 128 {
			return kind, nil, "sessionId is missing or malformed"
		}
		if raw := command["force"]; raw != nil && json.Unmarshal(raw, &force) != nil {
			return kind, nil, "force must be a boolean"
		}
		forceJSON, _ := json.Marshal(force)
		command = map[string]json.RawMessage{"type": command["type"], "sessionId": command["sessionId"], "force": forceJSON}
	case "set_model":
		// Only a model Pi already lists can be named; Pi itself checks the provider's auth.
		var provider, modelID string
		if json.Unmarshal(command["provider"], &provider) != nil || json.Unmarshal(command["modelId"], &modelID) != nil ||
			!modelToken(provider) || !modelToken(modelID) {
			return kind, nil, "model is missing or malformed"
		}
		command = map[string]json.RawMessage{"type": command["type"], "provider": command["provider"], "modelId": command["modelId"]}
	case "switch_mode":
		// Handled by the daemon bridge, never forwarded as a Pi command.
		var mode string
		var force bool
		_ = json.Unmarshal(command["mode"], &mode)
		if raw := command["force"]; raw != nil && json.Unmarshal(raw, &force) != nil {
			return kind, nil, "force must be a boolean"
		}
		if mode != guirpc.ModeTUI && mode != guirpc.ModeRPC {
			return kind, nil, "mode is not supported"
		}
		forceJSON, _ := json.Marshal(force)
		command = map[string]json.RawMessage{"type": command["type"], "mode": command["mode"], "force": forceJSON}
	case "set_thinking_level":
		var level string
		_ = json.Unmarshal(command["level"], &level)
		switch level {
		case "off", "minimal", "low", "medium", "high", "xhigh", "max":
		default:
			return kind, nil, "thinking level is not supported"
		}
		command = map[string]json.RawMessage{"type": command["type"], "level": command["level"]}
	default:
		return kind, nil, "command is not available from the phone"
	}
	if kind == "prompt" || kind == "steer" || kind == "follow_up" {
		var message string
		if json.Unmarshal(command["message"], &message) != nil || len(message) > s.maxInput {
			return kind, nil, "message is missing or too long"
		}
	}
	if raw := command["attachment_paths"]; raw != nil {
		images, reason := s.conversationImages(kind, raw)
		if reason != "" {
			return kind, nil, reason
		}
		command["images"] = images
		delete(command, "attachment_paths")
	}
	idJSON, _ := json.Marshal(id)
	command["id"] = idJSON
	encoded, err := json.Marshal(command)
	if err != nil || len(encoded) >= guirpc.MaxRecord {
		return kind, nil, "command is too large"
	}
	return kind, encoded, ""
}

// modelToken accepts a provider or model id as Pi names them: short, printable, no spaces.
func modelToken(value string) bool {
	if value == "" || len(value) > 128 {
		return false
	}
	for _, r := range value {
		if r <= ' ' || r == 0x7f {
			return false
		}
	}
	return true
}

func (s *Server) conversationImages(kind string, raw json.RawMessage) (json.RawMessage, string) {
	var paths []string
	if (kind != "prompt" && kind != "steer" && kind != "follow_up") || json.Unmarshal(raw, &paths) != nil || len(paths) > maxConversationImages {
		return nil, "attachments are not allowed here"
	}
	dir, err := s.resolveUploadDir()
	if err == nil {
		dir, err = filepath.EvalSymlinks(dir)
	}
	if err != nil {
		return nil, "upload directory is unavailable"
	}
	images := make([]map[string]string, 0, len(paths))
	total := 0
	for _, path := range paths {
		actual, err := filepath.EvalSymlinks(path)
		if err != nil || filepath.Dir(actual) != dir {
			return nil, "attachment is not an uploaded file"
		}
		file, err := os.Open(actual)
		if err != nil {
			return nil, "attachment is unreadable"
		}
		data, err := io.ReadAll(io.LimitReader(file, s.maxUpload+1))
		file.Close()
		if err != nil || int64(len(data)) > s.maxUpload {
			return nil, "attachment is too large"
		}
		total += len(data)
		// Reserve the text budget and JSON/base64 framing before allocating an
		// encoded record the worker scanner would reject.
		if total > (guirpc.MaxRecord-s.maxInput-1024)*3/4 {
			return nil, "attachments are too large"
		}
		mime := http.DetectContentType(data)
		if mime != "image/jpeg" && mime != "image/png" && mime != "image/gif" && mime != "image/webp" {
			return nil, "only images can be attached"
		}
		images = append(images, map[string]string{"type": "image", "mimeType": mime, "data": base64.StdEncoding.EncodeToString(data)})
	}
	encoded, _ := json.Marshal(images)
	return encoded, ""
}

func (c *wsConn) handleConversationCreate(req protocol.ConversationCreate) {
	result := c.s.createAgent(c.ctx, protocol.CreateAgent{
		ReqID: req.ReqID, Workspace: req.Workspace, AnchorRef: req.AnchorRef, Provider: req.Provider, Name: req.Name,
	}, true)
	c.send(&protocol.ConversationCreated{ReqID: result.ReqID, OK: result.OK, Ref: result.Ref, Name: result.Name, Reason: result.Reason})
}

// identifyConversationWorkers marks verified native structured agents. The
// provider comes from native process identity, never inferred from JSON output.
func (s *Server) identifyConversationWorkers(model *discovery.Model, observations map[string]nodeprobe.Observation) {
	if model == nil || s.conversations == nil {
		return
	}
	for _, workspace := range model.Workspaces {
		for _, pane := range workspace.Panes {
			ref := sessionRef(pane)
			provider := s.conversations.Detect(s.loopCtx, pane)
			if provider == "" {
				continue
			}
			obs, ok := observations[ref]
			if !ok {
				obs = nodeprobe.Unknown()
			}
			obs.Provider = provider
			obs.Conversation = true
			if activity := s.conversations.Activity(ref); activity != "" {
				obs.Activity = activity
				obs.Health = protocol.SessionHealthNormal
			}
			observations[ref] = obs
		}
	}
}
