package guirpc

// Export runs official native exporters against a verified current session.
// The client selects only a scoped session ID, never an arbitrary host path.
// @consumes internal/discovery
// @contract
// @pre authenticated caller, current native birth and exact session identity
// @post private bounded artifact is streamed, then removed; no model/agent launch
// @err export/path/size/process failure is visible without native stderr leakage
// @inv no public directory listing, shell, credential transfer or idle polling

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

// MaxArtifactBytes bounds published exports and the phone's private download.
const MaxArtifactBytes int64 = 64 << 20

// SessionArtifact owns one private native export and its cleanup lifetime.
type SessionArtifact struct {
	File       *os.File
	Name, MIME string
	close      func()
}

// Close releases the file and removes its private export directory once.
func (a *SessionArtifact) Close() {
	if a.close != nil {
		a.close()
		a.close = nil
	}
}

// SessionExporter provides ID-scoped artifacts without exposing host paths.
type SessionExporter interface {
	ExportSession(context.Context, discovery.Pane, string) (*SessionArtifact, error)
}

// ExportSession uses the verified current native session's official exporter.
func (m *Manager) ExportSession(ctx context.Context, pane discovery.Pane, id string) (*SessionArtifact, error) {
	if id == "" || len(id) > 128 || strings.HasPrefix(id, "-") {
		return nil, errors.New("导出会话身份无效")
	}
	s, err := m.sessionFor(ctx, pane)
	if err != nil {
		return nil, err
	}
	if err = s.beginHistory(); err != nil {
		return nil, err
	}
	defer s.endHistory()
	s.mu.Lock()
	process := s.process
	s.mu.Unlock()
	if process.Provider == "pi" {
		_, err = s.verifiedPi(ctx)
	} else {
		_, _, err = s.verifiedGrok(ctx)
	}
	if err != nil {
		return nil, err
	}
	state, err := s.w.requestContext(ctx, map[string]any{"type": "get_state"}, switchReplyTimeout)
	if err != nil {
		return nil, errors.New("原生会话状态未确认")
	}
	var current struct {
		ID string `json:"sessionId"`
	}
	if json.Unmarshal(state, &current) != nil || current.ID == "" || current.ID != id {
		return nil, errors.New("会话身份已改变，未导出")
	}
	directory, err := os.MkdirTemp("", "corral-session-export-")
	if err != nil {
		return nil, errors.New("无法创建导出文件")
	}
	ok := false
	defer func() {
		if !ok {
			os.RemoveAll(directory)
		}
	}()
	name, mime := "pi-session.html", "text/html; charset=utf-8"
	if process.Provider == "grok" {
		name, mime = "grok-session.md", "text/markdown; charset=utf-8"
	}
	output := filepath.Join(directory, name)
	if process.Provider == "pi" {
		result, callErr := s.w.requestContext(ctx, map[string]any{"type": "export_html", "outputPath": output}, 30*time.Second)
		var exported struct {
			Path string `json:"path"`
		}
		if callErr != nil || json.Unmarshal(result, &exported) != nil || filepath.Clean(exported.Path) != output {
			return nil, errors.New("Pi 未确认 HTML 导出")
		}
	} else {
		if len(process.Args) == 0 {
			return nil, errors.New("Grok 原生导出程序身份缺失")
		}
		// The binary comes from the verified native process, not client input.
		binary := process.Args[0]
		if !filepath.IsAbs(binary) {
			binary, err = exec.LookPath(binary)
			if err != nil {
				return nil, errors.New("Grok 导出程序不可用")
			}
		}
		bounded, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		command := exec.CommandContext(bounded, binary, "export", id, output)
		command.Dir = pane.CWD
		// export is a local read-only CLI command, not agent/stdin replacement.
		// Never log stdout/stderr (may include native locations or auth details).
		command.Stdout, command.Stderr = nil, nil
		if err = command.Run(); err != nil {
			return nil, errors.New("Grok Markdown 导出未完成")
		}
	}
	file, err := openPiSession(output) // shared NOFOLLOW + regular-file safeguard
	if err != nil {
		return nil, errors.New("原生导出文件不可读")
	}
	stat, err := file.Stat()
	if err != nil || stat.Size() == 0 || stat.Size() > MaxArtifactBytes {
		file.Close()
		return nil, errors.New("导出为空或超过 64 MiB 上限")
	}
	// A concurrent native replacement must not return another session's file.
	if process.Provider == "pi" {
		_, err = s.verifiedPi(ctx)
	} else {
		_, _, err = s.verifiedGrok(ctx)
	}
	if err != nil {
		file.Close()
		return nil, err
	}
	final, stateErr := s.w.requestContext(ctx, map[string]any{"type": "get_state"}, switchReplyTimeout)
	if stateErr != nil || json.Unmarshal(final, &current) != nil || current.ID != id {
		file.Close()
		return nil, errors.New("导出期间会话身份改变，未返回文件")
	}
	ok = true
	return &SessionArtifact{File: file, Name: name, MIME: mime, close: func() { file.Close(); os.RemoveAll(directory) }}, nil
}
