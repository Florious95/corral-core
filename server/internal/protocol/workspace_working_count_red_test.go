package protocol_test

import (
	"encoding/json"
	"reflect"
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// setWorkingCount deliberately uses reflection so this test compiles against
// the frozen pre-fix source and reports the missing wire contract as a product
// failure, rather than making the test itself unbuildable.
func setWorkingCount(t *testing.T, ws *protocol.Workspace, count int) {
	t.Helper()
	field := reflect.ValueOf(ws).Elem().FieldByName("WorkingCount")
	if !field.IsValid() {
		t.Fatalf("protocol.Workspace.WorkingCount is missing; JSON contract cannot carry working_count")
	}
	if field.Kind() != reflect.Int {
		t.Fatalf("protocol.Workspace.WorkingCount kind = %s, want int", field.Kind())
	}
	field.SetInt(int64(count))
}

func workspaceJSON(t *testing.T, frame protocol.Typed) map[string]any {
	t.Helper()
	wire, err := protocol.MarshalFrame(frame)
	if err != nil {
		t.Fatalf("MarshalFrame(%T): %v", frame, err)
	}
	var envelope struct {
		Payload json.RawMessage `json:"payload"`
	}
	if err := json.Unmarshal(wire, &envelope); err != nil {
		t.Fatalf("decode envelope: %v; wire=%s", err, wire)
	}
	var payload struct {
		Workspaces        []map[string]any `json:"workspaces"`
		ChangedWorkspaces []map[string]any `json:"changed_workspaces"`
	}
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		t.Fatalf("decode payload: %v; wire=%s", err, wire)
	}
	if len(payload.Workspaces) == 1 {
		return payload.Workspaces[0]
	}
	if len(payload.ChangedWorkspaces) == 1 {
		return payload.ChangedWorkspaces[0]
	}
	t.Fatalf("wire contains no single workspace: %s", wire)
	return nil
}

func TestListingAndDeltaSerializeWorkingCountIncludingZero(t *testing.T) {
	for _, count := range []int{2, 0} {
		t.Run("count_"+string(rune('0'+count)), func(t *testing.T) {
			ws := protocol.Workspace{Cwd: "/repo", SessionCount: 3}
			setWorkingCount(t, &ws, count)

			listingWS := workspaceJSON(t, protocol.Listing{
				ReqID: 1, Seq: 1, Workspaces: []protocol.Workspace{ws},
			})
			if got, ok := listingWS["working_count"]; !ok {
				t.Fatalf("Listing wire omits working_count: %v", listingWS)
			} else if got != float64(count) {
				t.Fatalf("Listing working_count = %v, want %d", got, count)
			}

			deltaWS := workspaceJSON(t, protocol.ListDelta{
				Seq: 2, ChangedWorkspaces: []protocol.Workspace{ws},
			})
			if got, ok := deltaWS["working_count"]; !ok {
				t.Fatalf("ListDelta wire omits working_count: %v", deltaWS)
			} else if got != float64(count) {
				t.Fatalf("ListDelta working_count = %v, want %d", got, count)
			}
		})
	}
}

func TestLegacyListingWithoutWorkingCountDecodesAsZero(t *testing.T) {
	frame, err := protocol.UnmarshalFrame([]byte(`{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":[{"cwd":"/repo","session_count":3}]}}`))
	if err != nil {
		t.Fatalf("UnmarshalFrame legacy listing: %v", err)
	}
	listing, ok := frame.(protocol.Listing)
	if !ok || len(listing.Workspaces) != 1 {
		t.Fatalf("decoded frame = %#v, want one workspace listing", frame)
	}
	if got := listing.Workspaces[0].WorkingCount; got != 0 {
		t.Fatalf("legacy working_count = %d, want 0", got)
	}
}
