package guirpc

// Reuse the same native-leaf history commit for resume, fork, clone and new.
// @contract
// @pre the mutation input gate and hydration barrier are held
// @post native ID/title and bounded active branch share one replay generation
// @err uncertain identity, branch or concurrent live work closes only the bridge
// @inv in-memory sessions never open an empty/guessed filesystem path

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
)

func (s *session) commitPiHistory(ctx context.Context, expected, expectedFile string) (map[string]any, error) {
	fail := func(err error) (map[string]any, error) { s.cancel(); return nil, err }
	state, err := s.w.requestContext(ctx, map[string]any{"type": "get_state"}, switchReplyTimeout)
	if err != nil {
		return fail(err)
	}
	var meta struct {
		ID   string `json:"sessionId"`
		Name string `json:"sessionName"`
	}
	if json.Unmarshal(state, &meta) != nil || meta.ID == "" || (expected != "" && meta.ID != expected) {
		return fail(errors.New("Pi 恢复后的会话身份不一致"))
	}
	if _, err = s.verifiedPi(ctx); err != nil {
		return fail(err)
	}
	s.w.mu.Lock()
	path := s.w.sessionFile
	s.w.mu.Unlock()
	if expectedFile != "" && filepath.Clean(path) != filepath.Clean(expectedFile) {
		return fail(errors.New("Pi 会话文件身份不一致"))
	}
	history, err := s.currentPiHistory(ctx, path)
	if err != nil {
		return fail(err)
	}
	s.mu.Lock()
	for _, record := range s.pending {
		var e header
		_ = json.Unmarshal(record, &e)
		if strings.HasPrefix(e.Type, "message_") || strings.HasPrefix(e.Type, "tool_execution_") || e.Type == "agent_start" {
			s.mu.Unlock()
			return fail(errors.New("Pi 历史读取期间出现并发任务；请重连核对"))
		}
	}
	s.pending = nil
	s.pendingBytes = 0
	stream, head := s.w.replacePiHistory(history, state, meta.ID)
	s.hydrating = false
	s.mu.Unlock()
	return map[string]any{"session_id": meta.ID, "sessionName": meta.Name, "stream": stream, "head_seq": head, "history_truncated": history.truncated, "content_clipped": history.clipped}, nil
}

func (s *session) currentPiHistory(ctx context.Context, path string) (piHistory, error) {
	var file *os.File
	last := ""
	if path != "" {
		if !filepath.IsAbs(path) {
			return piHistory{}, errors.New("Pi 未报告绝对会话路径")
		}
		var err error
		file, err = openPiSession(path)
		if err != nil && !os.IsNotExist(err) {
			return piHistory{}, errors.New("原生会话历史不可读")
		}
		// New persistent sessions report a path before first flush, with native
		// model/thinking entries already in memory. Follow that exact native leaf.
		if file != nil {
			defer file.Close()
			last, err = lastPiEntry(ctx, file)
			if err != nil {
				return piHistory{}, err
			}
		}
	}
	command := map[string]any{"type": "get_entries"}
	if last != "" {
		command["since"] = last
	}
	raw, err := s.w.requestContext(ctx, command, switchReplyTimeout)
	if err != nil {
		return piHistory{}, err
	}
	var snapshot struct {
		Entries []piSessionEntry `json:"entries"`
		Leaf    *string          `json:"leafId"`
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &snapshot) != nil || json.Unmarshal(raw, &fields) != nil || fields["leafId"] == nil {
		return piHistory{}, errors.New("Pi 未报告历史分支")
	}
	leaf := ""
	if snapshot.Leaf != nil {
		leaf = *snapshot.Leaf
	}
	return readPiBranch(ctx, file, leaf, snapshot.Entries)
}
