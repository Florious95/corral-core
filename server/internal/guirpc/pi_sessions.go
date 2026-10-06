package guirpc

// Pi session discovery reads only the native store selected for this pane.
// IDs are resolved to host-only paths after header cwd validation, never to
// arbitrary client paths or the newest file in a different workspace.
// @contract
// @pre the caller has verified the native Pi RPC identity and store directory
// @post all readable matching session headers have bounded list metadata
// @err unreadable stores and an oversized catalog fail visibly, not partially
// @inv discovery is read-only, cancellable, and does not inspect credentials

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"golang.org/x/sys/unix"
)

const maxSessionCatalogBytes = 2 << 20

// SessionInfo is the list's display metadata; the absolute native path stays
// in the daemon. Timestamps are Unix milliseconds, matching conversation_v1.
type SessionInfo struct {
	ID           string `json:"session_id"`
	Name         string `json:"name,omitempty"`
	FirstMessage string `json:"first_message,omitempty"`
	Created      int64  `json:"created_ms"`
	Modified     int64  `json:"modified_ms"`
	Current      bool   `json:"current"`
}

type piSessionFile struct {
	SessionInfo
	path string
}

type piSessionEntry struct {
	Type      string          `json:"type"`
	ID        string          `json:"id"`
	ParentID  string          `json:"parentId"`
	Timestamp string          `json:"timestamp"`
	CWD       string          `json:"cwd"`
	Name      string          `json:"name"`
	Message   json.RawMessage `json:"message"`
}

func clipSessionText(text string, limit int) string {
	text = strings.TrimSpace(text)
	count := 0
	for at := range text {
		if count == limit {
			return text[:at]
		}
		count++
	}
	return text
}

// openPiSession prevents symlink entries from escaping the verified store.
func openPiSession(path string) (*os.File, error) {
	fd, err := unix.Open(path, unix.O_RDONLY|unix.O_NOFOLLOW|unix.O_NONBLOCK, 0)
	if err != nil {
		return nil, err
	}
	file := os.NewFile(uintptr(fd), path)
	stat, err := file.Stat()
	if err != nil || !stat.Mode().IsRegular() {
		_ = file.Close()
		return nil, errors.New("Pi session is not a regular file")
	}
	return file, nil
}

func sessionText(content json.RawMessage) string {
	var text string
	if json.Unmarshal(content, &text) == nil {
		return text
	}
	var blocks []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if json.Unmarshal(content, &blocks) != nil {
		return ""
	}
	var result strings.Builder
	for _, block := range blocks {
		if block.Type == "text" {
			if result.Len() > 0 {
				result.WriteByte(' ')
			}
			result.WriteString(clipSessionText(block.Text, 240))
			if result.Len() >= 960 {
				break
			} // 240 Unicode code points fit at most 960 UTF-8 bytes.
		}
	}
	return result.String()
}

func readPiSessionInfo(ctx context.Context, path, cwd, current string) (*piSessionFile, error) {
	file, err := openPiSession(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	scan := bufio.NewScanner(file)
	scan.Buffer(make([]byte, 64<<10), MaxRecord)
	var info *piSessionFile
	for scan.Scan() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var entry piSessionEntry
		if json.Unmarshal(scan.Bytes(), &entry) != nil {
			continue // native Pi also ignores malformed JSONL entry lines
		}
		if info == nil {
			if entry.Type != "session" || entry.ID == "" || len(entry.ID) > 128 || filepath.Clean(entry.CWD) != filepath.Clean(cwd) {
				return nil, nil
			}
			created, _ := time.Parse(time.RFC3339Nano, entry.Timestamp)
			var createdMS int64
			if !created.IsZero() {
				createdMS = created.UnixMilli()
			}
			info = &piSessionFile{SessionInfo: SessionInfo{ID: entry.ID, Created: createdMS, Current: entry.ID == current}, path: path}
			continue
		}
		if entry.Type == "session_info" {
			info.Name = clipSessionText(strings.NewReplacer("\r", " ", "\n", " ").Replace(entry.Name), 200)
		}
		if entry.Type != "message" {
			continue
		}
		var message struct {
			Role      string          `json:"role"`
			Content   json.RawMessage `json:"content"`
			Timestamp int64           `json:"timestamp"`
		}
		if json.Unmarshal(entry.Message, &message) != nil || (message.Role != "user" && message.Role != "assistant") {
			continue
		}
		if message.Role == "user" && info.FirstMessage == "" {
			info.FirstMessage = clipSessionText(sessionText(message.Content), 240)
		}
		at := message.Timestamp
		if at <= 0 {
			parsed, _ := time.Parse(time.RFC3339Nano, entry.Timestamp)
			if !parsed.IsZero() {
				at = parsed.UnixMilli()
			}
		}
		if at > info.Modified {
			info.Modified = at
		}
	}
	if err := scan.Err(); err != nil {
		return nil, err
	}
	if info != nil && info.Modified == 0 {
		info.Modified = info.Created
		if info.Modified <= 0 {
			stat, err := file.Stat()
			if err != nil {
				return nil, err
			}
			info.Modified = stat.ModTime().UnixMilli()
		}
	}
	return info, nil
}

func listPiSessionFiles(ctx context.Context, dir, cwd, current string) ([]piSessionFile, error) {
	folder, err := os.Open(dir)
	if os.IsNotExist(err) {
		return []piSessionFile{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("Pi session directory unavailable: %w", err)
	}
	defer folder.Close()
	var sessions []piSessionFile
	bytes := 0
	ids := make(map[string]struct{})
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		entries, readErr := folder.ReadDir(128)
		if readErr != nil && readErr != io.EOF {
			return nil, readErr
		}
		for _, entry := range entries {
			if !strings.HasSuffix(entry.Name(), ".jsonl") || entry.Type()&os.ModeSymlink != 0 || entry.IsDir() {
				continue
			}
			info, err := readPiSessionInfo(ctx, filepath.Join(dir, entry.Name()), cwd, current)
			if os.IsNotExist(err) {
				continue // a deleted session is no longer an option
			}
			if err != nil {
				return nil, fmt.Errorf("Pi session metadata unavailable: %w", err)
			}
			if info == nil {
				continue
			}
			if _, exists := ids[info.ID]; exists {
				return nil, errors.New("Pi session identity is duplicated; no unambiguous selection")
			}
			ids[info.ID] = struct{}{}
			raw, _ := json.Marshal(info.SessionInfo)
			bytes += len(raw)
			if bytes > maxSessionCatalogBytes {
				return nil, errors.New("Pi session catalog exceeds the bounded list size")
			}
			sessions = append(sessions, *info)
		}
		if readErr == io.EOF {
			break
		}
	}
	sort.Slice(sessions, func(i, j int) bool {
		if sessions[i].Modified != sessions[j].Modified {
			return sessions[i].Modified > sessions[j].Modified
		}
		return sessions[i].ID > sessions[j].ID
	})
	return sessions, nil
}
