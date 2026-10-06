package guirpc

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func historyMessage(id, parent, role, text string) map[string]any {
	message := map[string]any{"role": role, "content": []any{map[string]any{"type": "text", "text": text}}, "timestamp": 42}
	if role == "assistant" {
		message["api"], message["provider"], message["model"], message["stopReason"] = "openai-responses", "openai", "gpt-4.1", "stop"
		message["usage"] = fixtureUsage()
	}
	return map[string]any{"type": "message", "id": id, "parentId": parent, "timestamp": "2026-10-06T01:00:00Z", "message": message}
}

func fixtureUsage() map[string]any {
	return map[string]any{"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0, "totalTokens": 0, "cost": map[string]any{"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0, "total": 0}}
}
func readFixtureBranch(t *testing.T, path, leaf string) piHistory {
	t.Helper()
	file, err := openPiSession(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	history, err := readPiBranch(context.Background(), file, leaf, nil)
	if err != nil {
		t.Fatal(err)
	}
	return history
}
func TestPiHistoryCurrentLeafRetainsPreCompactionThoughtAndTools(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "history.jsonl")
	thought := historyMessage("a", "u", "assistant", "")
	thought["message"] = map[string]any{"role": "assistant", "content": []any{
		map[string]any{"type": "thinking", "thinking": "original thought", "thinkingSignature": "opaque-secret"},
		map[string]any{"type": "toolCall", "id": "call-1", "name": "read", "arguments": map[string]any{"path": "fixture.txt"}},
	}, "provider": "private", "usage": map[string]any{"private": true}, "stopReason": "toolUse"}
	tool := map[string]any{"type": "message", "id": "t", "parentId": "a", "message": map[string]any{"role": "toolResult", "toolCallId": "call-1", "toolName": "read", "content": []any{map[string]any{"type": "text", "text": "original tool output"}}, "isError": false}}
	writePiSessionFixture(t, path, "session", dir,
		historyMessage("u", "", "user", "original pre-compaction user"), thought, tool,
		map[string]any{"type": "compaction", "id": "c", "parentId": "t", "summary": "compact summary", "firstKeptEntryId": "t"},
		historyMessage("abandoned", "u", "assistant", "must not show abandoned"),
		historyMessage("last", "c", "assistant", "latest branch"))
	history := readFixtureBranch(t, path, "last")
	if history.truncated || history.clipped || len(history.records) != 4 {
		t.Fatalf("unexpected window %+v", history)
	}
	all := bytes.Buffer{}
	for _, r := range history.records {
		all.Write(r.event)
	}
	for _, want := range []string{"original pre-compaction user", "original thought", "original tool output", "call-1", "latest branch"} {
		if !strings.Contains(all.String(), want) {
			t.Fatalf("missing %s", want)
		}
	}
	for _, hidden := range []string{"must not show abandoned", "opaque-secret", "private", "compact summary"} {
		if strings.Contains(all.String(), hidden) {
			t.Fatalf("projected %s", hidden)
		}
	}
	file, _ := os.Open(path)
	defer file.Close()
	id, err := lastPiEntry(context.Background(), file)
	if err != nil || id != "last" {
		t.Fatalf("last id=%q err=%v", id, err)
	}
}
func TestPiHistoryOversizeLoadsLatestWindowWithoutChangingHostFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "large.jsonl")
	var entries []map[string]any
	for i := 0; i < 1700; i++ {
		parent := ""
		if i > 0 {
			parent = fmt.Sprintf("e%d", i-1)
		}
		entries = append(entries, historyMessage(fmt.Sprintf("e%d", i), parent, "user", fmt.Sprintf("message-%d", i)))
	}
	writePiSessionFixture(t, path, "session", dir, entries...)
	before, _ := os.ReadFile(path)
	history := readFixtureBranch(t, path, "e1699")
	if !history.truncated || history.items != maxHistoryItems || len(history.records) != 1500 {
		t.Fatalf("wrong latest window: items=%d records=%d truncated=%v", history.items, len(history.records), history.truncated)
	}
	if !bytes.Contains(history.records[0].event, []byte("message-200")) || !bytes.Contains(history.records[1499].event, []byte("message-1699")) {
		t.Fatal("did not retain newest contiguous history")
	}
	after, _ := os.ReadFile(path)
	if !bytes.Equal(before, after) {
		t.Fatal("history projection modified native store")
	}
	w := newWorker(&syncBuffer{})
	old, _, client := w.attach(Hello{}, true)
	stream, seq := w.replacePiHistory(history, json.RawMessage(`{"sessionId":"session","sessionName":"selected title","isStreaming":false}`), "session")
	ready, replay, _ := w.attach(Hello{Stream: old.Stream}, true)
	if stream == old.Stream || ready.Stream != stream || !ready.Reset || !ready.Truncated || ready.HeadSeq != seq {
		t.Fatalf("replacement not atomic: %+v", ready)
	}
	if _, open := <-client.ch; open {
		t.Fatal("old client remained attached")
	}
	records := decode(t, replay)
	if len(records) != 1503 || !bytes.Contains(records[0].Event, []byte(`"session_reset"`)) || !bytes.Contains(records[len(records)-2].Event, []byte(`"older_omitted":true`)) {
		t.Fatal("reset/window disclosure missing")
	}
	if err := w.sendInputInStream([]byte(`{"type":"prompt"}`), true, old.Stream); err == nil {
		t.Fatal("old-generation prompt accepted")
	}
	w.shutdown()
}
func TestPiHistoryToolResultsShareCardsInsteadOfFalseTruncation(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "tools.jsonl")
	var entries []map[string]any
	parent := ""
	for i := 0; i < 1000; i++ {
		call, result, tool := fmt.Sprintf("call%d", i), fmt.Sprintf("result%d", i), fmt.Sprintf("tool%d", i)
		entries = append(entries,
			map[string]any{"type": "message", "id": call, "parentId": parent, "message": map[string]any{"role": "assistant", "content": []any{map[string]any{"type": "toolCall", "id": tool, "name": "read", "arguments": map[string]any{"path": "fixture"}}}}},
			map[string]any{"type": "message", "id": result, "parentId": call, "message": map[string]any{"role": "toolResult", "toolCallId": tool, "toolName": "read", "content": []any{map[string]any{"type": "text", "text": "result"}}, "isError": false}})
		parent = result
	}
	writePiSessionFixture(t, path, "session", dir, entries...)
	history := readFixtureBranch(t, path, parent)
	if history.truncated || history.items != 1000 || len(history.records) != 2000 {
		t.Fatalf("1000 rendered tool cards falsely truncated: items=%d records=%d truncated=%v", history.items, len(history.records), history.truncated)
	}
}

func TestPiHistoryMissingBranchFailsAndSingleTextClippingIsDisclosed(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "one.jsonl")
	writePiSessionFixture(t, path, "session", dir, historyMessage("one", "missing", "user", "text"))
	file, _ := os.Open(path)
	defer file.Close()
	if _, err := readPiBranch(context.Background(), file, "one", nil); err == nil {
		t.Fatal("missing parent silently accepted")
	}
	e := piSessionEntry{Type: "message", Message: json.RawMessage(fmt.Sprintf(`{"role":"user","content":%q}`, strings.Repeat("x", maxHistoryText+1)))}
	record, clipped := projectHistoryEntry(e)
	if !clipped || len(record.event) == 0 {
		t.Fatal("text truncation not disclosed")
	}
}
