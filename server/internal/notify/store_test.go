package notify

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestAcceptPersistsAndDeduplicates(t *testing.T) {
	dir := t.TempDir()
	s, err := New(dir)
	if err != nil {
		t.Fatal(err)
	}
	first, duplicate, err := s.Accept(Request{RequestID: "req-1", Body: "first"})
	if err != nil || duplicate {
		t.Fatalf("first accept: record=%+v duplicate=%v err=%v", first, duplicate, err)
	}
	retry, duplicate, err := s.Accept(Request{RequestID: "req-1", Body: "first"})
	if err != nil || !duplicate || retry.ID != first.ID {
		t.Fatalf("retry: record=%+v duplicate=%v err=%v", retry, duplicate, err)
	}
	if _, _, err := s.Accept(Request{RequestID: "req-1", Body: "changed"}); !IsConflict(err) {
		t.Fatalf("conflict error=%v", err)
	}
	restarted, err := New(dir)
	if err != nil {
		t.Fatal(err)
	}
	retry, duplicate, err = restarted.Accept(Request{RequestID: "req-1", Body: "first"})
	if err != nil || !duplicate || retry.ID != first.ID {
		t.Fatalf("restart retry: record=%+v duplicate=%v err=%v", retry, duplicate, err)
	}
}

func TestRingAndFrozenPages(t *testing.T) {
	s, err := New("")
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < MaxRecords+1; i++ {
		if _, _, err := s.Accept(Request{Body: "body"}); err != nil {
			t.Fatal(err)
		}
	}
	state := s.State()
	if state.HeadSeq != "1001" || state.RetainedFromSeq != "2" {
		t.Fatalf("state=%+v", state)
	}
	view := s.NewView(nil)
	page := view.Page(100)
	if len(page.Items) != 100 || !page.Next || page.Items[0].Seq != "2" {
		t.Fatalf("page=%+v", page)
	}
	if page.SnapshotCursor.StreamID != state.StreamID {
		t.Fatal("snapshot stream mismatch")
	}
	cursor := protocol.NotificationCursor{StreamID: state.StreamID, Seq: "0"}
	view = s.NewView(&cursor)
	page = view.Page(100)
	if page.ResetReason != "retention_gap" || page.Items[0].Seq != "2" {
		t.Fatalf("retention page=%+v", page)
	}
}
