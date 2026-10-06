package api

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestConversationHistoryCommandsRejectPathsAndMalformedSelections(t *testing.T) {
	server := &Server{maxInput: 1 << 20}
	for _, tc := range []struct{ raw, reason string }{
		{`{"type":"list_sessions","path":"/private"}`, ""},
		{`{"type":"resume_session","sessionId":"selected","sessionPath":"/private","force":true}`, ""},
		{`{"type":"resume_session","sessionId":""}`, "sessionId is missing or malformed"},
		{`{"type":"resume_session","sessionId":"selected","force":"yes"}`, "force must be a boolean"},
		{`{"type":"switch_session","sessionPath":"/private"}`, "command is not available from the phone"},
	} {
		_, out, reason := server.conversationCommand("test", json.RawMessage(tc.raw))
		if reason != tc.reason {
			t.Fatalf("command %s: %q", tc.raw, reason)
		}
		if strings.Contains(string(out), "private") || strings.Contains(string(out), "sessionPath") {
			t.Fatal("client path forwarded")
		}
	}
}

// Native Pi + authenticated WS. The only model endpoint is this test's local
// fixture, so continuation proves native context selection without touching
// any real account, credential, production port, or user tmux server.
func TestConversationHistoryRealPiWSResumeAndContinue(t *testing.T) {
	root := os.Getenv("ISSUE55_WS_NATIVE_ROOT")
	if root == "" {
		t.Skip("set ISSUE55_WS_NATIVE_ROOT to project-local isolated directory")
	}
	sock := filepath.Join(root, "ws.sock")
	if !filepath.IsAbs(root) || len([]byte(sock)) >= 100 {
		t.Fatal("invalid isolated root")
	}
	dir, agent := filepath.Join(root, "sessions"), filepath.Join(root, "agent")
	for _, path := range []string{dir, agent} {
		if err := os.MkdirAll(path, 0700); err != nil {
			t.Fatal(err)
		}
	}
	requests := make(chan string, 4)
	var modelCalls atomic.Int32
	modelStop := make(chan struct{})
	modelServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		data, err := io.ReadAll(io.LimitReader(r.Body, 8<<20))
		if err != nil {
			http.Error(w, "fixture request unavailable", 400)
			return
		}
		requests <- string(data)
		if modelCalls.Add(1) > 1 {
			select {
			case <-r.Context().Done():
			case <-modelStop:
			}
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintln(w, `data: {"id":"fixture-response","object":"chat.completion.chunk","created":1,"model":"resume-fixture","choices":[{"index":0,"delta":{"role":"assistant","content":"Continuation on selected native context"},"finish_reason":null}]}`)
		fmt.Fprintln(w)
		fmt.Fprintln(w, `data: {"id":"fixture-response","object":"chat.completion.chunk","created":1,"model":"resume-fixture","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}`)
		fmt.Fprintln(w)
		fmt.Fprint(w, "data: [DONE]\n\n")
	}))
	defer modelServer.Close()
	defer close(modelStop)
	config, _ := json.Marshal(map[string]any{"providers": map[string]any{"issue55-fixture": map[string]any{"baseUrl": modelServer.URL + "/v1", "api": "openai-completions", "apiKey": "fixture-dummy", "models": []any{map[string]any{"id": "resume-fixture", "name": "Local resume fixture", "contextWindow": 200000, "maxTokens": 1024, "input": []string{"text"}}}}}})
	if err := os.WriteFile(filepath.Join(agent, "models.json"), config, 0600); err != nil {
		t.Fatal(err)
	}
	ids := []string{"11111111-2222-4333-8444-555555555555", "22222222-3333-4444-8555-666666666666", "44444444-5555-4666-8777-888888888888"}
	fixture := func(path, id, title, marker string, count int, cwd string) {
		t.Helper()
		rows := []map[string]any{{"type": "session", "version": 3, "id": id, "cwd": cwd, "timestamp": "2026-10-06T02:00:00Z"},
			{"type": "model_change", "id": "model", "parentId": nil, "provider": "issue55-fixture", "modelId": "resume-fixture", "timestamp": "2026-10-06T02:00:00Z"}}
		parent := "model"
		for i := 0; i < count; i++ {
			entryID := fmt.Sprintf("e%d", i)
			role := "user"
			if i%2 == 1 {
				role = "assistant"
			}
			message := map[string]any{"role": role, "content": []any{map[string]any{"type": "text", "text": fmt.Sprintf("%s-%d", marker, i)}}, "timestamp": int64(1791252000000 + i)}
			if role == "assistant" {
				message["api"] = "openai-completions"
				message["provider"] = "issue55-fixture"
				message["model"] = "resume-fixture"
				message["stopReason"] = "stop"
				message["usage"] = map[string]any{"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0, "totalTokens": 0, "cost": map[string]any{"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0, "total": 0}}
			}
			rows = append(rows, map[string]any{"type": "message", "id": entryID, "parentId": parent, "message": message, "timestamp": "2026-10-06T02:00:00Z"})
			parent = entryID
		}
		rows = append(rows, map[string]any{"type": "session_info", "id": "title", "parentId": parent, "name": title, "timestamp": "2026-10-06T02:00:00Z"})
		var body []byte
		for _, row := range rows {
			raw, _ := json.Marshal(row)
			body = append(append(body, raw...), '\n')
		}
		if err := os.WriteFile(path, body, 0600); err != nil {
			t.Fatal(err)
		}
	}
	active := filepath.Join(dir, "current.jsonl")
	fixture(active, ids[0], "Active A", "context-A", 2, root)
	fixture(filepath.Join(dir, "target-renamed.jsonl"), ids[1], "History B", "context-B", 2, root)
	largePath := filepath.Join(dir, "large.jsonl")
	fixture(largePath, ids[2], "Large history C", "context-C", 1700, root)
	fixture(filepath.Join(dir, "foreign.jsonl"), "33333333-4444-4555-8666-777777777777", "Foreign", "foreign", 2, root+"-foreign")
	run := func(args ...string) string {
		t.Helper()
		cmd := exec.Command("tmux", append([]string{"-f", "/dev/null", "-S", sock}, args...)...)
		cmd.Env = append(os.Environ(), "TMUX=")
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("tmux %s: %v", args[0], err)
		}
		return strings.TrimSpace(string(out))
	}
	pi, err := exec.LookPath("pi")
	if err != nil {
		t.Fatal(err)
	}
	run("new-session", "-d", "-s", "issue55-ws", "-c", root, "-e", "PI_CODING_AGENT_DIR="+agent, pi, "--mode", "rpc", "--session", active, "--offline", "--no-extensions", "--no-skills", "--no-context-files", "--no-approve", "--provider", "issue55-fixture", "--model", "resume-fixture")
	t.Cleanup(func() { exec.Command("tmux", "-S", sock, "kill-server").Run() })
	if run("list-sessions", "-F", "#{session_name}") != "issue55-ws" {
		t.Fatal("isolated socket selfcheck failed")
	}
	pane := discovery.Pane{Socket: sock, PaneID: run("list-panes", "-F", "#{pane_id}"), CWD: root, Command: run("display-message", "-p", "#{pane_current_command}"), Width: 80, Height: 24}
	model := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: root, Panes: []discovery.Pane{pane}}}}
	env := startWS(t, Options{Token: "issue55-test-token", Discoverer: scriptedDiscoverer{model: model}, ListInterval: time.Hour})
	ack := sendRawControl(t, env, `{"v":1,"type":"auth","payload":{"token":"issue55-test-token","capabilities":["conversation_v1"]}}`)
	if !strings.Contains(string(ack.Payload), "conversation_v1") {
		t.Fatal("conversation capability not negotiated")
	}
	env.sendFrame(&protocol.List{ReqID: 1})
	listing := readUntil[protocol.Listing](t, env)
	if len(listing.Workspaces) != 1 || len(listing.Workspaces[0].Sessions) != 1 || !listing.Workspaces[0].Sessions[0].Conversation {
		t.Fatal("actual Pi not advertised")
	}
	ref := sessionRef(pane)
	env.sendFrame(&protocol.ConversationListSessions{ReqID: 7, Workspace: root})
	sessions := readUntil[protocol.ConversationSessions](t, env)
	if !sessions.OK || sessions.Ref != ref {
		t.Fatalf("list: %+v", sessions)
	}
	var choices []struct {
		ID      string `json:"session_id"`
		Name    string `json:"name"`
		Current bool   `json:"current"`
	}
	_ = json.Unmarshal(sessions.Sessions, &choices)
	if len(choices) != 3 || strings.Contains(string(sessions.Sessions), "foreign") || strings.Contains(string(sessions.Sessions), root) {
		t.Fatalf("wrong scoped catalog %s", sessions.Sessions)
	}
	env.sendFrame(&protocol.ConversationSubscribe{Ref: ref})
	initial := readUntil[protocol.ConversationReady](t, env)
	drainReplay := func(ready protocol.ConversationReady) string {
		t.Helper()
		var body strings.Builder
		if ready.HeadSeq == 0 {
			return ""
		}
		for i := 0; i < 5000; i++ {
			record := readUntil[protocol.ConversationEvent](t, env)
			body.Write(record.Event)
			if record.Seq == ready.HeadSeq {
				return body.String()
			}
		}
		t.Fatal("replay did not settle")
		return ""
	}
	drainReplay(initial)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	native := bridge.NewPane(sock, pane.PaneID)
	before, err := native.NativePi(ctx)
	if err != nil {
		t.Fatal(err)
	}
	resumeAndReplay := func(id, stream string) (protocol.ConversationReady, string) {
		t.Helper()
		env.sendFrame(&protocol.ConversationResumeSession{Ref: ref, SessionID: id})
		got := readUntil[protocol.ConversationSessionResumed](t, env)
		if !got.OK {
			t.Fatalf("native resume %s: %s", id, got.Reason)
		}
		env.sendFrame(&protocol.ConversationSubscribe{Ref: ref, Stream: stream})
		ready := readUntil[protocol.ConversationReady](t, env)
		if !ready.Reset || ready.Stream == stream {
			t.Fatal("target stream did not reset")
		}
		return ready, drainReplay(ready)
	}
	ready, replay := resumeAndReplay(ids[1], initial.Stream)
	if !strings.Contains(replay, "context-B") || strings.Contains(replay, "context-A") {
		t.Fatal("WS replay mixed histories")
	}
	largeReady, largeReplay := resumeAndReplay(ids[2], ready.Stream)
	if !largeReady.HistoryTruncated || !strings.Contains(largeReplay, `"older_omitted":true`) || !strings.Contains(largeReplay, "context-C-1699") || strings.Contains(largeReplay, `"text":"context-C-0"`) {
		t.Fatal("large history did not retain latest disclosed window")
	}
	original, _ := os.ReadFile(largePath)
	if !strings.Contains(string(original), "context-C-0") {
		t.Fatal("original host history lost")
	}
	// Android's existing command correlation uses the same list/resume controls.
	env.sendFrame(&protocol.ConversationCommand{Ref: ref, ID: "android-list", Command: json.RawMessage(`{"type":"list_sessions"}`)})
	response := func(id string) map[string]any {
		t.Helper()
		for i := 0; i < 5000; i++ {
			record := readUntil[protocol.ConversationEvent](t, env)
			var event map[string]any
			_ = json.Unmarshal(record.Event, &event)
			if event["id"] == id {
				return event
			}
		}
		t.Fatal("missing correlated response")
		return nil
	}
	if result := response("android-list"); result["success"] != true || !strings.Contains(fmt.Sprint(result["data"]), "History B") {
		t.Fatal("Android list alias failed")
	}
	raw, _ := json.Marshal(map[string]any{"type": "resume_session", "sessionId": ids[0]})
	env.sendFrame(&protocol.ConversationCommand{Ref: ref, ID: "android-resume", Command: raw})
	if result := response("android-resume"); result["success"] != true {
		t.Fatalf("Android resume alias failed: %v", result)
	}
	env.sendFrame(&protocol.ConversationSubscribe{Ref: ref, Stream: largeReady.Stream})
	final := readUntil[protocol.ConversationReady](t, env)
	finalReplay := drainReplay(final)
	if !strings.Contains(finalReplay, "context-A") || strings.Contains(finalReplay, "context-C") {
		t.Fatal("Android alias restored wrong transcript")
	}
	env.sendFrame(&protocol.ConversationCommand{Ref: ref, ID: "continue", Command: json.RawMessage(`{"type":"prompt","message":"Continue the selected history"}`)})
	answer := false
	for i := 0; i < 100; i++ {
		record := readUntil[protocol.ConversationEvent](t, env)
		if strings.Contains(string(record.Event), "Continuation on selected native context") {
			answer = true
		}
		if strings.Contains(string(record.Event), `"type":"agent_settled"`) {
			break
		}
	}
	if !answer {
		t.Fatal("native continuation produced no model answer")
	}
	select {
	case request := <-requests:
		if !strings.Contains(request, "context-A") || strings.Contains(request, "context-B") || strings.Contains(request, "context-C") || !strings.Contains(request, "Continue the selected history") {
			t.Fatal("native model request used wrong context")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no native model request")
	}
	// A real in-flight native prompt must remain untouched without consent.
	env.sendFrame(&protocol.ConversationCommand{Ref: ref, ID: "busy-prompt", Command: json.RawMessage(`{"type":"prompt","message":"Wait for explicit stop consent"}`)})
	for i := 0; i < 20; i++ {
		record := readUntil[protocol.ConversationEvent](t, env)
		if strings.Contains(string(record.Event), `"type":"agent_start"`) {
			break
		}
	}
	select {
	case <-requests:
	case <-time.After(5 * time.Second):
		t.Fatal("busy native prompt did not reach local model fixture")
	}
	env.sendFrame(&protocol.ConversationResumeSession{Ref: ref, SessionID: ids[1]})
	refused := readUntil[protocol.ConversationSessionResumed](t, env)
	if refused.OK || !strings.Contains(string(refused.Data), `"busy":true`) {
		t.Fatalf("busy resume did not require consent: %+v", refused)
	}
	env.sendFrame(&protocol.ConversationResumeSession{Ref: ref, SessionID: ids[1], Force: true})
	forced := readUntil[protocol.ConversationSessionResumed](t, env)
	if !forced.OK {
		t.Fatalf("force consent did not abort/resume: %+v", forced)
	}
	env.sendFrame(&protocol.ConversationSubscribe{Ref: ref, Stream: final.Stream})
	forcedReady := readUntil[protocol.ConversationReady](t, env)
	forcedReplay := drainReplay(forcedReady)
	if !strings.Contains(forcedReplay, "context-B") || strings.Contains(forcedReplay, "Wait for explicit stop consent") {
		t.Fatal("force resume mixed aborted old context")
	}
	after, err := native.NativePi(ctx)
	if err != nil || after.PID != before.PID || after.Started != before.Started {
		t.Fatal("resume/continuation restarted Pi")
	}
	t.Logf("real WS PASS: native PID=%d, catalog=3, direct+Android resume, latest=1500/1700, continued correct native context", after.PID)
}
