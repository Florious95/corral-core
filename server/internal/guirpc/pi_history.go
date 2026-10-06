package guirpc

// Raw Pi history is reconstructed on the official current leaf, including
// pre-compaction messages. File size does not determine bridge memory use.
// @contract
// @pre the file belongs to the verified cwd/session and Pi supplies leafId
// @post the newest bounded branch window is ordered; omitted content is explicit
// @err missing branch parents and oversized single records fail visibly
// @inv no abandoned branch, signature, endpoint or credential is projected

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"strings"
	"time"
)

const maxHistoryItems = 1500
const maxHistoryText = 200000

// reverseJSONL reads fixed-size blocks, retaining at most one protocol line.
// Append-only Pi entries always precede their descendants, so walking parents
// backwards requires no unbounded index of the session tree.
type reverseJSONL struct {
	file   *os.File
	offset int64
	buffer []byte
}

func newReverseJSONL(file *os.File) (*reverseJSONL, error) {
	stat, err := file.Stat()
	if err != nil {
		return nil, err
	}
	return &reverseJSONL{file: file, offset: stat.Size()}, nil
}

func (r *reverseJSONL) next(ctx context.Context) ([]byte, error) {
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if at := bytes.LastIndexByte(r.buffer, '\n'); at >= 0 {
			line := append([]byte(nil), r.buffer[at+1:]...)
			r.buffer = r.buffer[:at]
			if len(bytes.TrimSpace(line)) > 0 {
				return line, nil
			}
			continue
		}
		if r.offset == 0 {
			if len(r.buffer) == 0 {
				return nil, io.EOF
			}
			line := r.buffer
			r.buffer = nil
			return line, nil
		}
		size := int64(64 << 10)
		if size > r.offset {
			size = r.offset
		}
		r.offset -= size
		block := make([]byte, int(size))
		if _, err := r.file.ReadAt(block, r.offset); err != nil {
			return nil, err
		}
		r.buffer = append(block, r.buffer...)
		if len(r.buffer) > MaxRecord {
			return nil, errors.New("Pi history has an oversized record")
		}
	}
}

func lastPiEntry(ctx context.Context, file *os.File) (string, error) {
	lines, err := newReverseJSONL(file)
	if err != nil {
		return "", err
	}
	for {
		raw, err := lines.next(ctx)
		if err == io.EOF {
			return "", nil
		}
		if err != nil {
			return "", err
		}
		var e piSessionEntry
		if json.Unmarshal(raw, &e) == nil && e.Type != "session" && e.ID != "" {
			return e.ID, nil
		}
	}
}

type piHistoryRecord struct {
	event []byte
	ts    int64
	items int
	tools []string
}
type piHistory struct {
	records            []piHistoryRecord
	truncated, clipped bool
	items              int
}

// projectHistoryEntry emits only rendered content, not opaque model metadata.
func projectHistoryEntry(e piSessionEntry) (piHistoryRecord, bool) {
	var m map[string]json.RawMessage
	if e.Type != "message" || json.Unmarshal(e.Message, &m) != nil {
		return piHistoryRecord{}, false
	}
	var role string
	_ = json.Unmarshal(m["role"], &role)
	clipped := false
	clip := func(raw json.RawMessage) json.RawMessage {
		var text string
		if json.Unmarshal(raw, &text) != nil {
			return raw
		}
		units := 0
		for at, char := range text {
			width := 1
			if char > 0xffff {
				width = 2
			}
			if units+width > maxHistoryText {
				text = text[:at]
				clipped = true
				break
			}
			units += width
		}
		out, _ := json.Marshal(text)
		return out
	}
	var content []map[string]json.RawMessage
	var plain string
	if json.Unmarshal(m["content"], &plain) == nil {
		m["content"] = clip(m["content"])
	} else if json.Unmarshal(m["content"], &content) == nil {
		var kept []map[string]json.RawMessage
		for _, block := range content {
			var kind string
			_ = json.Unmarshal(block["type"], &kind)
			clean := map[string]json.RawMessage{"type": block["type"]}
			switch kind {
			case "text":
				clean["text"] = clip(block["text"])
			case "thinking":
				clean["thinking"] = clip(block["thinking"])
				if v := block["redacted"]; v != nil {
					clean["redacted"] = v
				}
			case "toolCall":
				for _, key := range []string{"id", "name", "arguments"} {
					if v := block[key]; v != nil {
						clean[key] = v
					}
				}
			case "image": // The phone renders an attachment count, not base64 history.
			default:
				continue
			}
			kept = append(kept, clean)
		}
		if len(kept) > maxHistoryItems {
			kept = kept[len(kept)-maxHistoryItems:]
			clipped = true
		}
		m["content"], _ = json.Marshal(kept)
		content = kept
	}
	parsed, _ := time.Parse(time.RFC3339Nano, e.Timestamp)
	var ts int64
	if !parsed.IsZero() {
		ts = parsed.UnixMilli()
	}
	var stamp int64
	_ = json.Unmarshal(m["timestamp"], &stamp)
	if stamp > 0 {
		ts = stamp
	}
	items := 1
	var tools []string
	var event map[string]any
	switch role {
	case "user", "assistant", "custom":
		if role == "custom" {
			var display bool
			_ = json.Unmarshal(m["display"], &display)
			if !display {
				return piHistoryRecord{}, false
			}
		}
		if role == "assistant" {
			items = 0
			for _, block := range content {
				var kind, text, id string
				_ = json.Unmarshal(block["type"], &kind)
				switch kind {
				case "text":
					_ = json.Unmarshal(block["text"], &text)
					if strings.TrimSpace(text) != "" {
						items++
					}
				case "thinking":
					_ = json.Unmarshal(block["thinking"], &text)
					var redacted bool
					_ = json.Unmarshal(block["redacted"], &redacted)
					if strings.TrimSpace(text) != "" || redacted {
						items++
					}
				case "toolCall":
					_ = json.Unmarshal(block["id"], &id)
					if id != "" {
						items++
						tools = append(tools, id)
					}
				}
			}
			var stop string
			_ = json.Unmarshal(m["stopReason"], &stop)
			if stop == "error" || stop == "aborted" || stop == "length" {
				items++
			}
		}
		raw, _ := json.Marshal(m)
		event = map[string]any{"type": "message_end", "message": json.RawMessage(onlyFields(raw, "role", "content", "timestamp", "stopReason", "errorMessage", "display"))}
		// Start delivers a completed stored user turn without timestamp-based
		// message_end deduplication merging distinct prompts in the same ms.
		// Custom display messages also enter through message_start.
		if role == "custom" || role == "user" {
			event["type"] = "message_start"
		}
	case "toolResult":
		var id string
		_ = json.Unmarshal(m["toolCallId"], &id)
		if id != "" {
			tools = []string{id}
		}
		raw, _ := json.Marshal(m)
		event = map[string]any{"type": "tool_execution_end", "toolCallId": m["toolCallId"], "toolName": m["toolName"], "isError": m["isError"], "result": json.RawMessage(onlyFields(raw, "content", "structuredContent"))}
	case "bashExecution":
		result := map[string]any{"content": []any{map[string]any{"type": "text", "text": json.RawMessage(clip(m["output"]))}}, "structuredContent": map[string]any{"exit_code": m["exitCode"], "truncated": m["truncated"]}}
		event = map[string]any{"type": "tool_execution_end", "toolCallId": "history-bash:" + e.ID, "toolName": "bash", "result": result, "isError": false}
	default:
		return piHistoryRecord{}, false
	}
	raw, _ := json.Marshal(event)
	return piHistoryRecord{event: raw, ts: ts, items: items, tools: tools}, clipped
}

func readPiBranch(ctx context.Context, file *os.File, leaf string, extra []piSessionEntry) (piHistory, error) {
	var lines *reverseJSONL
	if file != nil {
		var err error
		lines, err = newReverseJSONL(file)
		if err != nil {
			return piHistory{}, err
		}
	}
	result := piHistory{}
	bytesUsed := 0
	full := false
	tools := make(map[string]bool)
	accept := func(e piSessionEntry) {
		record, clipped := projectHistoryEntry(e)
		if record.event == nil {
			return
		}
		result.clipped = result.clipped || clipped
		// A stored tool result and its assistant toolCall render one shared
		// card. Counting both would falsely truncate normal <1500-item sessions.
		for _, id := range record.tools {
			if tools[id] {
				record.items--
			}
		}
		// Leave room for reset, window disclosure and header metadata.
		if full || result.items+record.items > maxHistoryItems || bytesUsed+len(record.event)+100 > maxHistoryBytes-(64<<10) || len(result.records) >= maxHistoryRecords-4 {
			result.truncated = true
			full = true
			return
		}
		result.records = append(result.records, record)
		for _, id := range record.tools {
			tools[id] = true
		}
		result.items += record.items
		bytesUsed += len(record.event) + 100
	}
	for i := len(extra) - 1; i >= 0 && leaf != "" && !full; i-- {
		if extra[i].ID == leaf {
			accept(extra[i])
			leaf = extra[i].ParentID
		}
	}
	for leaf != "" && !full {
		if lines == nil {
			return piHistory{}, errors.New("Pi native memory branch parent is missing")
		}
		raw, err := lines.next(ctx)
		if err == io.EOF {
			return piHistory{}, errors.New("Pi history branch parent is missing")
		}
		if err != nil {
			return piHistory{}, err
		}
		var e piSessionEntry
		if json.Unmarshal(raw, &e) != nil || e.Type == "session" || e.ID != leaf {
			continue
		}
		accept(e)
		leaf = e.ParentID
	}
	for i, j := 0, len(result.records)-1; i < j; i, j = i+1, j-1 {
		result.records[i], result.records[j] = result.records[j], result.records[i]
	}
	return result, nil
}
