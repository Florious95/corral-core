package api

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/guirpc"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// API routing fixture implements the daemon-local transport; native tmux/Pi
// behavior is independently exercised by guirpc's actual CLI lifecycle test.
type fakeConversations struct {
	mu      sync.Mutex
	workers map[string]*fakeWorker
}
type fakeWorker struct {
	hellos   chan guirpc.Hello
	commands chan map[string]any
	conns    chan net.Conn
	records  []string
	activity string
}

func (f *fakeConversations) Open(ctx context.Context, p discovery.Pane) (net.Conn, error) {
	f.mu.Lock()
	w := f.workers[sessionRef(p)]
	f.mu.Unlock()
	if w == nil {
		return nil, net.ErrClosed
	}
	client, server := net.Pipe()
	go func() {
		defer server.Close()
		scan := bufio.NewScanner(server)
		if !scan.Scan() {
			return
		}
		var hello guirpc.Hello
		json.Unmarshal(scan.Bytes(), &hello)
		w.hellos <- hello
		ready, _ := json.Marshal(guirpc.Ready{Type: "ready", Stream: "s1", HeadSeq: uint64(len(w.records)), Reset: hello.Stream != "s1", Now: 1, Mode: guirpc.ModeRPC})
		if _, err := server.Write(append(ready, '\n')); err != nil {
			return
		}
		for _, r := range w.records {
			if _, err := server.Write([]byte(r + "\n")); err != nil {
				return
			}
		}
		w.conns <- server
		for scan.Scan() {
			var command map[string]any
			json.Unmarshal(scan.Bytes(), &command)
			w.commands <- command
		}
	}()
	return client, nil
}
func (f *fakeConversations) Available(ref string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.workers[ref] != nil
}
func (f *fakeConversations) Detect(_ context.Context, p discovery.Pane) bool {
	return f.Available(sessionRef(p))
}
func (f *fakeConversations) Activity(ref string) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	if w := f.workers[ref]; w != nil {
		return w.activity
	}
	return ""
}
func (f *fakeConversations) Prune(_ string, _ time.Time, _ map[string]struct{}) {}
func (f *fakeConversations) Close() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.workers = map[string]*fakeWorker{}
}
func startFakeWorker(t *testing.T, f *fakeConversations, ref string, records ...string) *fakeWorker {
	t.Helper()
	w := &fakeWorker{hellos: make(chan guirpc.Hello, 4), commands: make(chan map[string]any, 4), conns: make(chan net.Conn, 4), records: records}
	f.mu.Lock()
	f.workers[ref] = w
	f.mu.Unlock()
	return w
}

func conversationEnv(t *testing.T) (*wsEnv, *fakeConversations, discovery.Pane) {
	t.Helper()
	transport := &fakeConversations{workers: map[string]*fakeWorker{}}
	pane := discovery.Pane{Socket: "/synthetic/conv-test.sock", PaneID: "%3", WindowName: "pi", CWD: "/work/p", Command: "pi", Width: 80, Height: 24}
	model := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: pane.CWD, Panes: []discovery.Pane{pane}}}}
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: model}, ListInterval: time.Hour, ConversationBridge: transport})
	ack := sendRawControl(t, e, `{"v":1,"type":"auth","payload":{"token":"test-token","capabilities":["conversation_v1"]}}`)
	var payload protocol.AuthAck
	json.Unmarshal(ack.Payload, &payload)
	if ack.Type != "auth_ack" || len(payload.Capabilities) != 1 || payload.Capabilities[0] != protocol.ConversationCapability {
		t.Fatalf("conversation_v1 not negotiated: %s %s", ack.Type, ack.Payload)
	}
	return e, transport, pane
}

func readUntil[T protocol.Typed](t *testing.T, e *wsEnv) T {
	t.Helper()
	for i := 0; i < 20; i++ {
		if got, ok := readControlWithTimeout(t, e, 5*time.Second).(T); ok {
			return got
		}
	}
	var zero T
	t.Fatalf("no %T frame", zero)
	return zero
}

func TestConversationCapabilityCanBeExplicitlyDisabled(t *testing.T) {
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: &discovery.Model{}}, ListInterval: time.Hour, DisableConversations: true})
	ack := sendRawControl(t, e, `{"v":1,"type":"auth","payload":{"token":"test-token","capabilities":["conversation_v1"]}}`)
	if strings.Contains(string(ack.Payload), "conversation_v1") {
		t.Fatalf("disabled native bridge acknowledged: %s", ack.Payload)
	}
	e.sendFrame(&protocol.ConversationSubscribe{Ref: "x"})
	if got, ok := readControlWithTimeout(t, e, 5*time.Second).(protocol.ErrorFrame); !ok || got.Code != protocol.ErrCodeUnsupportedType {
		t.Fatalf("un-negotiated conversation frame must be refused, got %#v", got)
	}
}

func TestConversationCapabilityNeedsNoPrivateDirectory(t *testing.T) {
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: &discovery.Model{}}, ListInterval: time.Hour})
	ack := sendRawControl(t, e, `{"v":1,"type":"auth","payload":{"token":"test-token","capabilities":["conversation_v1"]}}`)
	if !strings.Contains(string(ack.Payload), "conversation_v1") {
		t.Fatal("native capability not enabled by default")
	}
}

func TestConversationSubscribeRelaysReplayAndCommands(t *testing.T) {
	e, transport, pane := conversationEnv(t)
	ref := sessionRef(pane)
	worker := startFakeWorker(t, transport, ref,
		`{"seq":1,"ts":10,"event":{"type":"agent_start"}}`,
		`{"seq":2,"ts":11,"event":{"type":"message_start","message":{"role":"user","content":"hi","timestamp":1}}}`)
	e.sendFrame(&protocol.ConversationSubscribe{Ref: ref, Stream: "s1", AfterSeq: 0})
	ready := readUntil[protocol.ConversationReady](t, e)
	if ready.Ref != ref || ready.Stream != "s1" || ready.HeadSeq != 2 || ready.Reset {
		t.Fatalf("ready = %+v", ready)
	}
	if hello := <-worker.hellos; hello.Stream != "s1" || hello.Type != "hello" {
		t.Fatalf("hello = %+v", hello)
	}
	first := readUntil[protocol.ConversationEvent](t, e)
	second := readUntil[protocol.ConversationEvent](t, e)
	if first.Ref != ref || first.Seq != 1 || first.TS != 10 || string(first.Event) != `{"type":"agent_start"}` || second.Seq != 2 {
		t.Fatalf("events = %+v / %+v", first, second)
	}
	e.sendFrame(&protocol.ConversationCommand{Ref: ref, ID: "c1", Command: json.RawMessage(`{"type":"prompt","message":"hello"}`)})
	select {
	case command := <-worker.commands:
		if command["id"] != "c1" || command["type"] != "prompt" || command["message"] != "hello" {
			t.Fatalf("forwarded command = %v", command)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("command never reached native bridge")
	}
	e.sendFrame(&protocol.ConversationCommand{Ref: ref, ID: "c2", Command: json.RawMessage(`{"type":"bash","command":"rm -rf /"}`)})
	rejected := readUntil[protocol.ConversationEvent](t, e)
	if rejected.Seq != 0 || !strings.Contains(string(rejected.Event), `"success":false`) || !strings.Contains(string(rejected.Event), `"id":"c2"`) {
		t.Fatalf("non-whitelisted command must be rejected visibly: %s", rejected.Event)
	}
	(<-worker.conns).Close()
	if closed := readUntil[protocol.ConversationClosed](t, e); closed.Reason != protocol.ConversationLost {
		t.Fatalf("closed = %+v", closed)
	}
	e.sendFrame(&protocol.ConversationSubscribe{Ref: ref, Stream: "s1", AfterSeq: 2})
	readUntil[protocol.ConversationReady](t, e)
	transport.mu.Lock()
	delete(transport.workers, ref)
	transport.mu.Unlock()
	(<-worker.conns).Close()
	if closed := readUntil[protocol.ConversationClosed](t, e); closed.Reason != protocol.ConversationExited {
		t.Fatalf("closed after exit = %+v", closed)
	}
}

func TestConversationSubscribeToTerminalPaneIsUnavailable(t *testing.T) {
	e, _, pane := conversationEnv(t)
	e.sendFrame(&protocol.ConversationSubscribe{Ref: sessionRef(pane)})
	if closed := readUntil[protocol.ConversationClosed](t, e); closed.Reason != protocol.ConversationUnavailable {
		t.Fatalf("closed = %+v", closed)
	}
}

func TestListingMarksNativeConversationPanes(t *testing.T) {
	e, transport, pane := conversationEnv(t)
	ref := sessionRef(pane)
	worker := startFakeWorker(t, transport, ref)
	transport.mu.Lock()
	worker.activity = "working"
	transport.mu.Unlock()
	e.sendFrame(&protocol.List{ReqID: 1})
	listing := readUntil[protocol.Listing](t, e)
	session := listing.Workspaces[0].Sessions[0]
	if !session.Conversation || session.Provider != "pi" || session.Activity != "working" || session.Health != protocol.SessionHealthNormal {
		t.Fatalf("native pane = %+v", session)
	}
}

func TestConversationModelCommandsAreValidatedAndMinimal(t *testing.T) {
	s := &Server{maxInput: 1 << 20}
	for _, tc := range []struct {
		raw, reason, forwarded string
	}{
		{`{"type":"get_available_models"}`, "", `{"id":"c1","type":"get_available_models"}`},
		{`{"type":"get_available_thinking_levels"}`, "", `{"id":"c1","type":"get_available_thinking_levels"}`},
		{`{"type":"set_model","provider":"openai-codex","modelId":"gpt-6-luna","baseUrl":"http://evil"}`, "",
			`{"id":"c1","modelId":"gpt-6-luna","provider":"openai-codex","type":"set_model"}`},
		{`{"type":"set_model","provider":"a b","modelId":"x"}`, "model is missing or malformed", ""},
		{`{"type":"set_model","provider":"p"}`, "model is missing or malformed", ""},
		{`{"type":"set_thinking_level","level":"max"}`, "", `{"id":"c1","level":"max","type":"set_thinking_level"}`},
		{`{"type":"set_thinking_level","level":"ultra"}`, "thinking level is not supported", ""},
		{`{"type":"cycle_model"}`, "command is not available from the phone", ""},
		{`{"type":"switch_mode","mode":"tui","force":true,"argv":["sh"]}`, "", `{"force":true,"id":"c1","mode":"tui","type":"switch_mode"}`},
		{`{"type":"switch_mode","mode":"rpc"}`, "", `{"force":false,"id":"c1","mode":"rpc","type":"switch_mode"}`},
		{`{"type":"switch_mode","mode":"bash"}`, "mode is not supported", ""},
		{`{"type":"switch_mode","mode":"tui","force":"yes"}`, "force must be a boolean", ""},
	} {
		_, forwarded, reason := s.conversationCommand("c1", json.RawMessage(tc.raw))
		if reason != tc.reason || string(forwarded) != tc.forwarded {
			t.Fatalf("%s → %q %q, want %q %q", tc.raw, forwarded, reason, tc.forwarded, tc.reason)
		}
	}
}
