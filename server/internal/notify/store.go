// Package notify implements the daemon-owned notification history and commit path.
package notify

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

const MaxRecords = 1000

var (
	ErrRequestConflict = errors.New("notification request_id conflicts with an existing notification")
	ErrRetentionGap    = errors.New("notification cursor is older than retained history")
)

func IsConflict(err error) bool     { return errors.Is(err, ErrRequestConflict) }
func IsRetentionGap(err error) bool { return errors.Is(err, ErrRetentionGap) }

type Request struct {
	RequestID       string
	Title           string
	Body            string
	SessionRef      string
	SessionInstance string
	Workspace       string
	AgentName       string
	Level           string
}

type diskState struct {
	HostID   string       `json:"host_id"`
	StreamID string       `json:"stream_id"`
	NextSeq  uint64       `json:"next_seq"`
	Records  []diskRecord `json:"records"`
}

type diskRecord struct {
	Record      protocol.NotificationRecord `json:"record"`
	RequestID   string                      `json:"request_id,omitempty"`
	Fingerprint string                      `json:"fingerprint,omitempty"`
}

type Store struct {
	mu         sync.RWMutex
	dir        string
	hostID     string
	streamID   string
	nextSeq    uint64
	records    []protocol.NotificationRecord
	requestIDs map[string]string
	byRequest  map[string]protocol.NotificationRecord
}

func New(dir string) (*Store, error) {
	s := &Store{dir: dir, requestIDs: make(map[string]string), byRequest: make(map[string]protocol.NotificationRecord)}
	if dir == "" {
		var err error
		s.hostID, err = newHostID()
		if err != nil {
			return nil, err
		}
		s.streamID, err = newUUIDv4()
		if err != nil {
			return nil, err
		}
		s.nextSeq = 1
		return s, nil
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("notify: create state dir: %w", err)
	}
	path := filepath.Join(dir, "notifications.json")
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		s.hostID, err = loadOrCreateIdentity(filepath.Join(dir, "notification-host-id"))
		if err != nil {
			return nil, err
		}
		s.streamID, err = newUUIDv4()
		if err != nil {
			return nil, err
		}
		s.nextSeq = 1
		if err := s.persistLocked(); err != nil {
			return nil, err
		}
		return s, nil
	}
	if err != nil {
		return nil, fmt.Errorf("notify: read store: %w", err)
	}
	var d diskState
	if err := json.Unmarshal(data, &d); err != nil {
		return nil, fmt.Errorf("notify: corrupt store: %w", err)
	}
	if d.HostID == "" || d.StreamID == "" || d.NextSeq == 0 {
		return nil, fmt.Errorf("notify: corrupt store identity")
	}
	s.hostID, s.streamID, s.nextSeq = d.HostID, d.StreamID, d.NextSeq
	if len(d.Records) > MaxRecords {
		return nil, fmt.Errorf("notify: corrupt store record count")
	}
	for _, item := range d.Records {
		if err := item.Record.Validate(); err != nil {
			return nil, fmt.Errorf("notify: corrupt record: %w", err)
		}
		s.records = append(s.records, item.Record)
		if item.RequestID != "" {
			s.requestIDs[item.RequestID] = item.Fingerprint
			s.byRequest[item.RequestID] = item.Record
		}
	}
	return s, nil
}

func (s *Store) HostID() string   { s.mu.RLock(); defer s.mu.RUnlock(); return s.hostID }
func (s *Store) StreamID() string { s.mu.RLock(); defer s.mu.RUnlock(); return s.streamID }

func (s *Store) State() protocol.NotificationState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	head := "0"
	if len(s.records) > 0 {
		head = s.records[len(s.records)-1].Seq
	}
	retained := "0"
	if len(s.records) > 0 {
		retained = s.records[0].Seq
	}
	return protocol.NotificationState{HostID: s.hostID, StreamID: s.streamID, HeadSeq: head, RetainedFromSeq: retained}
}

func (s *Store) Accept(req Request) (protocol.NotificationRecord, bool, error) {
	if len(req.RequestID) > 128 || strings.ContainsAny(req.RequestID, "\x00\r\n") {
		return protocol.NotificationRecord{}, false, fmt.Errorf("invalid request_id")
	}
	title := req.Title
	if title == "" {
		title = "Agent 任务通知"
	}
	level := req.Level
	if level == "" {
		level = "success"
	}
	fingerprintBytes, err := json.Marshal(struct{ Title, Body, SessionRef, SessionInstance, Workspace, AgentName, Level string }{title, req.Body, req.SessionRef, req.SessionInstance, req.Workspace, req.AgentName, level})
	if err != nil {
		return protocol.NotificationRecord{}, false, err
	}
	fingerprint := string(fingerprintBytes)
	s.mu.Lock()
	defer s.mu.Unlock()
	if req.RequestID != "" {
		if old, ok := s.requestIDs[req.RequestID]; ok {
			if old != fingerprint {
				return protocol.NotificationRecord{}, false, ErrRequestConflict
			}
			return s.byRequest[req.RequestID], true, nil
		}
	}
	if s.nextSeq == 0 {
		s.nextSeq = 1
	}
	id, err := newUUIDv4()
	if err != nil {
		return protocol.NotificationRecord{}, false, err
	}
	record := protocol.NotificationRecord{ID: id, HostID: s.hostID, StreamID: s.streamID, Seq: strconv.FormatUint(s.nextSeq, 10), Timestamp: time.Now().UTC().Format("2006-01-02T15:04:05.000Z"), Title: title, Body: req.Body, Level: level}
	if req.SessionRef != "" {
		record.SessionRef = stringPtr(req.SessionRef)
		if req.SessionInstance != "" {
			record.SessionInstance = stringPtr(req.SessionInstance)
		}
	}
	if req.Workspace != "" {
		record.Workspace = stringPtr(req.Workspace)
	}
	if req.AgentName != "" {
		record.AgentName = stringPtr(req.AgentName)
	}
	if err := record.Validate(); err != nil {
		return protocol.NotificationRecord{}, false, err
	}
	s.records = append(s.records, record)
	if req.RequestID != "" {
		s.requestIDs[req.RequestID] = fingerprint
		s.byRequest[req.RequestID] = record
	}
	if len(s.records) > MaxRecords {
		s.records = append([]protocol.NotificationRecord(nil), s.records[len(s.records)-MaxRecords:]...)
		kept := make(map[string]struct{}, len(s.records))
		for _, keptRecord := range s.records {
			kept[keptRecord.ID] = struct{}{}
		}
		for requestID, saved := range s.byRequest {
			if _, ok := kept[saved.ID]; !ok {
				delete(s.byRequest, requestID)
				delete(s.requestIDs, requestID)
			}
		}
	}
	s.nextSeq++
	if err := s.persistLocked(); err != nil {
		return protocol.NotificationRecord{}, false, err
	}
	return record, false, nil
}

// View is a bounded immutable history snapshot. It can safely be paged without
// holding the store lock or delaying the websocket hot path.
type View struct {
	HostID, StreamID, HeadSeq, RetainedFromSeq string
	Records                                    []protocol.NotificationRecord
	Pos                                        int
	ResetReason                                string
}

type Page struct {
	Items           []protocol.NotificationRecord
	SnapshotCursor  protocol.NotificationCursor
	RetainedFromSeq string
	Next            bool
	ResumeCursor    *protocol.NotificationCursor
	ResetReason     string
}

func (s *Store) NewView(cursor *protocol.NotificationCursor) View {
	s.mu.RLock()
	defer s.mu.RUnlock()
	v := View{HostID: s.hostID, StreamID: s.streamID, HeadSeq: "0", RetainedFromSeq: "0"}
	if len(s.records) > 0 {
		v.HeadSeq = s.records[len(s.records)-1].Seq
		v.RetainedFromSeq = s.records[0].Seq
	}
	v.Records = append([]protocol.NotificationRecord(nil), s.records...)
	start := uint64(0)
	if cursor != nil {
		if cursor.StreamID != s.streamID {
			v.ResetReason = "stream_reset"
		} else if n, _ := strconv.ParseUint(cursor.Seq, 10, 64); len(s.records) > 0 && n+1 < mustSeq(s.records[0].Seq) {
			v.ResetReason = "retention_gap"
		} else {
			start = n
		}
	}
	for i, r := range v.Records {
		n := mustSeq(r.Seq)
		if n > start {
			v.Pos = i
			break
		}
		v.Pos = len(v.Records)
	}
	return v
}

func (v *View) Page(pageSize int) Page {
	if pageSize <= 0 {
		pageSize = 50
	}
	if pageSize > 100 {
		pageSize = 100
	}
	start := v.Pos
	end := start + pageSize
	if end > len(v.Records) {
		end = len(v.Records)
	}
	items := append([]protocol.NotificationRecord(nil), v.Records[start:end]...)
	v.Pos = end
	p := Page{Items: items, RetainedFromSeq: v.RetainedFromSeq, Next: end < len(v.Records), ResetReason: v.ResetReason, SnapshotCursor: protocol.NotificationCursor{StreamID: v.StreamID, Seq: v.HeadSeq}}
	if len(items) > 0 {
		last := items[len(items)-1]
		p.ResumeCursor = &protocol.NotificationCursor{StreamID: last.StreamID, Seq: last.Seq}
	}
	return p
}

func (s *Store) persistLocked() error {
	if s.dir == "" {
		return nil
	}
	d := diskState{HostID: s.hostID, StreamID: s.streamID, NextSeq: s.nextSeq}
	for _, record := range s.records {
		item := diskRecord{Record: record}
		for requestID, saved := range s.byRequest {
			if saved.ID == record.ID {
				item.RequestID = requestID
				item.Fingerprint = s.requestIDs[requestID]
				break
			}
		}
		d.Records = append(d.Records, item)
	}
	data, err := json.MarshalIndent(d, "", "  ")
	if err != nil {
		return err
	}
	tmp := filepath.Join(s.dir, "notifications.json.tmp")
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return fmt.Errorf("notify: write store: %w", err)
	}
	if err := os.Rename(tmp, filepath.Join(s.dir, "notifications.json")); err != nil {
		return fmt.Errorf("notify: commit store: %w", err)
	}
	return nil
}

func loadOrCreateIdentity(path string) (string, error) {
	if b, err := os.ReadFile(path); err == nil {
		return strings.TrimSpace(string(b)), nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", err
	}
	id, err := newHostID()
	if err != nil {
		return "", err
	}
	if err := os.WriteFile(path, []byte(id+"\n"), 0o600); err != nil {
		return "", err
	}
	return id, nil
}
func newHostID() (string, error) { id, err := newUUIDv4(); return "host_" + id, err }
func stringPtr(s string) *string { return &s }
func mustSeq(s string) uint64    { n, _ := strconv.ParseUint(s, 10, 64); return n }
func newUUIDv4() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	buf := make([]byte, 36)
	hex.Encode(buf[0:8], b[0:4])
	buf[8] = '-'
	hex.Encode(buf[9:13], b[4:6])
	buf[13] = '-'
	hex.Encode(buf[14:18], b[6:8])
	buf[18] = '-'
	hex.Encode(buf[19:23], b[8:10])
	buf[23] = '-'
	hex.Encode(buf[24:36], b[10:16])
	return string(buf), nil
}
