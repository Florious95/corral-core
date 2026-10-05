package api

import (
	"bufio"
	"encoding/json"
	"net"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/guirpc"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// fakeWorker speaks the guirpc socket protocol for one ref.
type fakeWorker struct {
	t        *testing.T
	listener net.Listener
	hellos   chan guirpc.Hello
	commands chan map[string]any
	conns    chan net.Conn
}

func startFakeWorker(t *testing.T, dir, ref string, records ...string) *fakeWorker {
	t.Helper()
	listener, err := net.Listen("unix", guirpc.SocketPath(dir, ref))
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	w := &fakeWorker{t: t, listener: listener, hellos: make(chan guirpc.Hello, 4), commands: make(chan map[string]any, 4), conns: make(chan net.Conn, 4)}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				scan := bufio.NewScanner(conn)
				if !scan.Scan() {
					return
				}
				var hello guirpc.Hello
				_ = json.Unmarshal(scan.Bytes(), &hello)
				w.hellos <- hello
				ready, _ := json.Marshal(guirpc.Ready{Type: "ready", Stream: "s1", HeadSeq: uint64(len(records)), Reset: hello.Stream != "s1", Now: 1})
				conn.Write(append(ready, '\n'))
				for _, r := range records {
					conn.Write([]byte(r + "\n"))
				}
				w.conns <- conn
				for scan.Scan() {
					var command map[string]any
					_ = json.Unmarshal(scan.Bytes(), &command)
					w.commands <- command
				}
			}()
		}
	}()
	return w
}

func conversationEnv(t *testing.T) (*wsEnv, string, discovery.Pane) {
	t.Helper()
	dir, err := os.MkdirTemp(".", "g")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	pane := discovery.Pane{Socket: "/tmp/conv-test.sock", PaneID: "%3", WindowName: "pi", CWD: "/work/p", Command: "agentmirrord", Width: 80, Height: 24}
	model := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: pane.CWD, Panes: []discovery.Pane{pane}}}}
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: model}, ListInterval: time.Hour, GUIDir: dir})
	ack := sendRawControl(t, e, `{"v":1,"type":"auth","payload":{"token":"test-token","capabilities":["conversation_v1"]}}`)
	var payload protocol.AuthAck
	_ = json.Unmarshal(ack.Payload, &payload)
	if ack.Type != "auth_ack" || len(payload.Capabilities) != 1 || payload.Capabilities[0] != protocol.ConversationCapability {
		t.Fatalf("conversation_v1 not negotiated: %s %s", ack.Type, ack.Payload)
	}
	return e, dir, pane
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

func TestConversationCapabilityRequiresGUIDir(t *testing.T) {
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: &discovery.Model{}}, ListInterval: time.Hour})
	ack := sendRawControl(t, e, `{"v":1,"type":"auth","payload":{"token":"test-token","capabilities":["conversation_v1"]}}`)
	if strings.Contains(string(ack.Payload), "conversation_v1") {
		t.Fatalf("server without a GUI dir must not acknowledge conversation_v1: %s", ack.Payload)
	}
	e.sendFrame(&protocol.ConversationSubscribe{Ref: "x"})
	if got, ok := readControlWithTimeout(t, e, 5*time.Second).(protocol.ErrorFrame); !ok || got.Code != protocol.ErrCodeUnsupportedType {
		t.Fatalf("un-negotiated conversation frame must be refused, got %#v", got)
	}
}

func TestConversationSubscribeRelaysReplayAndCommands(t *testing.T) {
	e, dir, pane := conversationEnv(t)
	ref := sessionRef(pane)
	worker := startFakeWorker(t, dir, ref,
		`{"seq":1,"ts":10,"event":{"type":"agent_start"}}`,
		`{"seq":2,"ts":11,"event":{"type":"message_start","message":{"role":"user","content":"hi","timestamp":1}}}`,
	)

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
		t.Fatal("command never reached the worker")
	}

	e.sendFrame(&protocol.ConversationCommand{Ref: ref, ID: "c2", Command: json.RawMessage(`{"type":"bash","command":"rm -rf /"}`)})
	rejected := readUntil[protocol.ConversationEvent](t, e)
	if rejected.Seq != 0 || !strings.Contains(string(rejected.Event), `"success":false`) || !strings.Contains(string(rejected.Event), `"id":"c2"`) {
		t.Fatalf("non-whitelisted command must be rejected visibly: %s", rejected.Event)
	}

	// The worker drops the stream while alive: the client is told to resume.
	(<-worker.conns).Close()
	if closed := readUntil[protocol.ConversationClosed](t, e); closed.Reason != protocol.ConversationLost {
		t.Fatalf("closed = %+v", closed)
	}
	// Socket gone: the agent exited.
	e.sendFrame(&protocol.ConversationSubscribe{Ref: ref, Stream: "s1", AfterSeq: 2})
	readUntil[protocol.ConversationReady](t, e)
	worker.listener.Close()
	os.Remove(guirpc.SocketPath(dir, ref))
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

func TestListingMarksManagedConversationPanes(t *testing.T) {
	e, dir, pane := conversationEnv(t)
	ref := sessionRef(pane)
	startFakeWorker(t, dir, ref)
	state := strings.TrimSuffix(guirpc.SocketPath(dir, ref), ".sock") + ".state"
	if err := os.WriteFile(state, []byte("working"), 0o600); err != nil {
		t.Fatal(err)
	}
	e.sendFrame(&protocol.List{ReqID: 1})
	listing := readUntil[protocol.Listing](t, e)
	session := listing.Workspaces[0].Sessions[0]
	if !session.Conversation || session.Provider != "pi" || session.Activity != "working" || session.Health != protocol.SessionHealthNormal {
		t.Fatalf("managed pane = %+v", session)
	}
}
