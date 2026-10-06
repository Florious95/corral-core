package guirpc

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

type cloneRejectionWriter struct {
	w                    *worker
	reason, confirmedSID string
}

func (out cloneRejectionWriter) Write(raw []byte) (int, error) {
	var command struct{ Type, ID string }
	if err := json.Unmarshal(raw, &command); err != nil {
		return 0, err
	}
	response := map[string]any{"type": "response", "id": command.ID, "command": command.Type, "success": false, "error": out.reason}
	if command.Type == "get_state" {
		response["success"] = true
		delete(response, "error")
		response["data"] = map[string]any{"sessionId": out.confirmedSID, "isStreaming": false, "isCompacting": false, "pendingMessageCount": 0}
	}
	encoded, _ := json.Marshal(response)
	out.w.ingest(encoded)
	return len(raw), nil
}

func TestCloneNativeRejectionPreservesOnlyConfirmedUnchangedContext(t *testing.T) {
	for _, tc := range []struct {
		name, nativeReason, confirmedSID, visibleReason string
		closed                                          bool
	}{
		{"unsaved", "This session has not been saved yet. Send a message before cloning or forking it.", "original", "尚未持久化", false},
		{"no-leaf", "Cannot clone session: no current entry selected", "original", "没有可克隆", false},
		{"unexpected-native-error", "unknown failure after mutation", "original", "桥接已关闭", true},
		{"changed-despite-rejection", "This session has not been saved yet. Send a message before cloning or forking it.", "different", "桥接已关闭", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			w := newWorker(nil)
			defer w.shutdown()
			w.sessionID = "original"
			w.setInput(cloneRejectionWriter{w, tc.nativeReason, tc.confirmedSID})
			s := &session{ctx: ctx, cancel: cancel, w: w}
			data, err := s.mutatePi(ctx, "clone_session", "")
			if err == nil || !strings.Contains(err.Error(), tc.visibleReason) {
				t.Fatalf("wrong visible native outcome: %v %v", data, err)
			}
			if closed := ctx.Err() != nil; closed != tc.closed {
				t.Fatalf("closed=%v want=%v", closed, tc.closed)
			}
			if !tc.closed && (data["unchanged"] != true || data["session_id"] != "original" || w.sessionID != "original") {
				t.Fatalf("healthy original context not confirmed: %v", data)
			}
		})
	}
}
