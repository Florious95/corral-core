package codexname

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"
)

const (
	maxRecordBytes  = 1 << 20
	maxIndexBytes   = 32 << 20
	indexChunkBytes = 64 << 10
)

type thread struct{ Home, ID string }

var threadID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

var threadFile = regexp.MustCompile(`^rollout-.+-([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\.jsonl$`)

func threadPath(path string) thread {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return thread{}
	}
	match := threadFile.FindStringSubmatch(filepath.Base(path))
	if match == nil {
		return thread{}
	}
	dir := filepath.Dir(path)
	for _, width := range []int{2, 2, 4} {
		part := filepath.Base(dir)
		if len(part) != width || strings.Trim(part, "0123456789") != "" {
			return thread{}
		}
		dir = filepath.Dir(dir)
	}
	if filepath.Base(dir) != "sessions" {
		return thread{}
	}
	return thread{filepath.Dir(dir), strings.ToLower(match[1])}
}

// interactiveThread examines only the first session_meta record of already
// open rollout files. It never scans transcripts, history.jsonl, argv, env or
// auth/config files. Subagent rollouts in the same Codex process must not be
// mistaken for the interactive CLI thread. Multiple CLI roots are ambiguous;
// neither cwd, newest mtime nor the first descriptor is a safe tie-breaker.
func interactiveThread(ctx context.Context, paths []string) thread {
	var selected thread
	seen := make(map[string]bool)
	for _, path := range paths {
		if ctx.Err() != nil {
			return thread{}
		}
		th := threadPath(path)
		if th.ID == "" || seen[path] {
			continue
		}
		seen[path] = true
		f := openRegular(path)
		if f == nil {
			return thread{}
		}
		// Bound the header; a missing/oversize header is unavailable, not a
		// reason to inspect later records which may contain conversation.
		line, err := bufio.NewReader(io.LimitReader(f, maxRecordBytes+1)).ReadBytes('\n')
		f.Close()
		if (err != nil && err != io.EOF) || len(line) > maxRecordBytes {
			return thread{}
		}
		var meta struct {
			Type    string `json:"type"`
			Payload struct {
				ID     string          `json:"id"`
				Source json.RawMessage `json:"source"`
			} `json:"payload"`
		}
		if json.Unmarshal(line, &meta) != nil || meta.Type != "session_meta" || !threadID.MatchString(meta.Payload.ID) {
			return thread{}
		}
		// A rollout filename may contain a RolloutId override. The header id
		// is the stable ThreadId used by session_index.jsonl.
		th.ID = strings.ToLower(meta.Payload.ID)
		var source string
		if json.Unmarshal(meta.Payload.Source, &source) != nil {
			// The documented noninteractive subagent form is an object.
			var subagent map[string]json.RawMessage
			if json.Unmarshal(meta.Payload.Source, &subagent) != nil || subagent["subagent"] == nil {
				return thread{}
			}
			continue
		}
		if source == "exec" {
			continue
		}
		if source != "cli" {
			return thread{}
		}
		if selected.ID != "" && selected != th {
			return thread{}
		}
		selected = th
	}
	return selected
}

// indexNames reads the append-only name index backwards: physical append
// order (not the wall-clock timestamp) is authoritative. Stop once every
// requested id has its newest valid record. There is no sticky rename cache;
// replacement, truncation and same-size rewrites are read on the next scan.
// A bounded scan may return a subset, but never borrows a different id's name.
func indexNames(ctx context.Context, path string, ids map[string]bool) map[string]string {
	names := make(map[string]string)
	if len(ids) == 0 || ctx.Err() != nil {
		return names
	}
	f := openRegular(path)
	if f == nil {
		return names
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return names
	}
	offset := info.Size()
	budget := int64(maxIndexBytes)
	var carry []byte
	for offset > 0 && budget > 0 && len(names) < len(ids) && ctx.Err() == nil {
		size := int64(indexChunkBytes)
		if size > offset {
			size = offset
		}
		if size > budget {
			size = budget
		}
		offset -= size
		budget -= size
		block := make([]byte, int(size))
		if _, err := f.ReadAt(block, offset); err != nil {
			break
		}
		block = append(block, carry...)
		lines := bytes.Split(block, []byte{'\n'})
		first := 0
		if offset > 0 {
			first = 1
			carry = bytes.Clone(lines[0])
		} else {
			carry = nil
		}
		if len(carry) > maxRecordBytes {
			break
		}
		for i := len(lines) - 1; i >= first && len(names) < len(ids); i-- {
			if ctx.Err() != nil {
				return names
			}
			line := lines[i]
			if len(line) == 0 || len(line) > maxRecordBytes || !utf8.Valid(line) {
				continue
			}
			var entry struct {
				ID   string  `json:"id"`
				Name *string `json:"thread_name"`
			}
			if json.Unmarshal(line, &entry) != nil || entry.Name == nil {
				continue
			}
			id := strings.ToLower(entry.ID)
			if _, exists := names[id]; !exists && ids[id] {
				names[id] = *entry.Name
			}
		}
	}
	return names
}

func openRegular(path string) *os.File {
	// Do not block on FIFOs/devices or follow an index symlink into credentials.
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() {
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	actual, err := f.Stat()
	if err != nil || !actual.Mode().IsRegular() || !os.SameFile(info, actual) {
		f.Close()
		return nil
	}
	return f
}
