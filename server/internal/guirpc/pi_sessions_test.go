package guirpc

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/agentmirror/agentmirror/internal/bridge"
)

func writePiSessionFixture(t *testing.T, path, id, cwd string, entries ...map[string]any) {
	t.Helper()
	rows := []map[string]any{{"type": "session", "version": 3, "id": id, "cwd": cwd, "timestamp": "2026-10-06T01:00:00Z"}}
	rows = append(rows, entries...)
	var data []byte
	for _, row := range rows {
		raw, err := json.Marshal(row)
		if err != nil {
			t.Fatal(err)
		}
		data = append(append(data, raw...), '\n')
	}
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
}

func TestPiSessionCatalogUsesHeaderScopeAndOfficialMetadata(t *testing.T) {
	dir := t.TempDir()
	cwd := filepath.Join(dir, "workspace")
	writePiSessionFixture(t, filepath.Join(dir, "arbitrary-renamed-file.jsonl"), "current-id", cwd,
		map[string]any{"type": "session_info", "name": "old name"},
		map[string]any{"type": "message", "timestamp": "2026-10-06T01:00:01Z", "message": map[string]any{"role": "user", "content": []any{map[string]any{"type": "text", "text": "first user"}, map[string]any{"type": "text", "text": "sentence"}}, "timestamp": int64(1791248401000)}},
		map[string]any{"type": "session_info", "name": "Final\nTitle"})
	writePiSessionFixture(t, filepath.Join(dir, "second.jsonl"), "other-id", cwd,
		map[string]any{"type": "message", "message": map[string]any{"role": "user", "content": "newest", "timestamp": int64(1791248405000)}})
	writePiSessionFixture(t, filepath.Join(dir, "foreign.jsonl"), "foreign-id", filepath.Join(dir, "foreign-workspace"))
	outside := filepath.Join(t.TempDir(), "outside.jsonl")
	writePiSessionFixture(t, outside, "symlink-id", cwd)
	if err := os.Symlink(outside, filepath.Join(dir, "link.jsonl")); err != nil {
		t.Fatal(err)
	}
	rows, err := listPiSessionFiles(context.Background(), dir, cwd, "current-id")
	if err != nil || len(rows) != 2 {
		t.Fatalf("catalog rows=%d err=%v", len(rows), err)
	}
	if rows[0].ID != "other-id" || rows[1].ID != "current-id" || rows[1].Name != "Final Title" || rows[1].FirstMessage != "first user sentence" || !rows[1].Current || rows[0].Current {
		t.Fatalf("wrong native metadata: %+v", rows)
	}
	raw, _ := json.Marshal(rows[1].SessionInfo)
	var public map[string]any
	_ = json.Unmarshal(raw, &public)
	if _, ok := public["path"]; ok {
		t.Fatal("native absolute session path escaped into list payload")
	}
}

func TestPiSessionStoreUsesExplicitLastOptionThenReportedFileThenDefault(t *testing.T) {
	root := t.TempDir()
	cwd := filepath.Join(root, "workspace")
	t.Setenv("PI_CODING_AGENT_DIR", filepath.Join(root, "own-agent"))
	process := bridge.NativeProcess{Args: []string{"pi", "--session-dir", "older", "--session-dir", "configured"}}
	got, err := piSessionDir(process, cwd, filepath.Join(root, "outside", "loaded.jsonl"))
	if err != nil || got != filepath.Join(cwd, "configured") {
		t.Fatalf("explicit store %q err=%v", got, err)
	}
	got, err = piSessionDir(bridge.NativeProcess{}, cwd, filepath.Join(root, "reported", "loaded.jsonl"))
	if err != nil || got != filepath.Join(root, "reported") {
		t.Fatalf("reported store %q err=%v", got, err)
	}
	got, err = piSessionDir(bridge.NativeProcess{}, cwd, "")
	if err != nil || filepath.Dir(got) != filepath.Join(root, "own-agent", "sessions") {
		t.Fatalf("default store %q err=%v", got, err)
	}
	if _, err = piSessionDir(bridge.NativeProcess{}, cwd, "relative.jsonl"); err == nil {
		t.Fatal("unknown relative native path accepted")
	}
}

func TestPiSessionCatalogDoesNotGuessDuplicateIDsAndHonorsCancellation(t *testing.T) {
	dir := t.TempDir()
	cwd := filepath.Join(dir, "workspace")
	writePiSessionFixture(t, filepath.Join(dir, "one.jsonl"), "duplicate", cwd)
	writePiSessionFixture(t, filepath.Join(dir, "two.jsonl"), "duplicate", cwd)
	if _, err := listPiSessionFiles(context.Background(), dir, cwd, ""); err == nil {
		t.Fatal("ambiguous native ID silently selected")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := listPiSessionFiles(ctx, dir, cwd, ""); err != context.Canceled {
		t.Fatalf("cancellation lost: %v", err)
	}
}
