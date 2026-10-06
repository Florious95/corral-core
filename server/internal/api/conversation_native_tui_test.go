package api

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestNativeTuiCommandsPreserveDecisionTokenButNeverNativeIDOrPath(t *testing.T) {
	s := &Server{maxInput: 200000}
	token := "0123456789abcdef0123456789abcdef"
	for _, raw := range []string{
		`{"type":"fork_session","pointId":"` + token + `","entryId":"injected","sessionPath":"/private","force":true}`,
		`{"type":"clone_session","newCwd":"/private","sourceSessionId":"injected"}`,
		`{"type":"rename_session","name":"New title","sessionId":"injected"}`,
		`{"type":"interaction_reply","id":"native-callback","requestId":"` + token + `","value":"","confirmed":false,"sessionId":"injected","path":"/private"}`,
	} {
		_, out, reason := s.conversationCommand("outer-command", json.RawMessage(raw))
		if reason != "" {
			t.Fatalf("valid command: %s", reason)
		}
		if strings.Contains(string(out), "private") || strings.Contains(string(out), "injected") || strings.Contains(string(out), "native-callback") {
			t.Fatalf("untrusted identity/path forwarded: %s", out)
		}
		var fields map[string]json.RawMessage
		_ = json.Unmarshal(out, &fields)
		if string(fields["id"]) != `"outer-command"` {
			t.Fatalf("outer correlation=%s", out)
		}
		if strings.Contains(raw, "requestId") && string(fields["requestId"]) != `"`+token+`"` {
			t.Fatalf("decision token overwritten: %s", out)
		}
	}
	for _, raw := range []string{
		`{"type":"interaction_reply","requestId":"native","cancelled":true}`,
		`{"type":"interaction_reply","requestId":"` + token + `","confirmed":"true"}`,
		`{"type":"rename_session","name":"  "}`,
		`{"type":"fork_session","pointId":"ui-row-123"}`,
		`{"type":"clone_session","force":"yes"}`,
	} {
		if _, _, reason := s.conversationCommand("outer", json.RawMessage(raw)); reason == "" {
			t.Fatalf("invalid command accepted: %s", raw)
		}
	}
}
