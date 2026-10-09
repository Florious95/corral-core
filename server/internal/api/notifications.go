package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/agentmirror/agentmirror/internal/notify"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/agentmirror/agentmirror/internal/sessionname"
)

// NotificationIPCRequest is the private HTTP-over-UDS request used by
// corral-notify. It deliberately contains no daemon or websocket fields.
type NotificationIPCRequest struct {
	RequestID  string `json:"request_id,omitempty"`
	Title      string `json:"title,omitempty"`
	Body       string `json:"body"`
	SessionRef string `json:"session_ref,omitempty"`
	Level      string `json:"level,omitempty"`
}

type NotificationIPCResponse struct {
	Accepted     bool   `json:"accepted"`
	ID           string `json:"id,omitempty"`
	HostID       string `json:"host_id,omitempty"`
	StreamID     string `json:"stream_id,omitempty"`
	Seq          string `json:"seq,omitempty"`
	RequestID    string `json:"request_id,omitempty"`
	Deduplicated bool   `json:"deduplicated,omitempty"`
	Warning      string `json:"warning,omitempty"`
	Error        string `json:"error,omitempty"`
}

// PublishNotification validates, durably commits, and then best-effort
// broadcasts one notification. Persistence is deliberately before broadcast.
func (s *Server) PublishNotification(ctx context.Context, req notify.Request) (protocol.NotificationRecord, bool, error) {
	if s.notifications == nil {
		return protocol.NotificationRecord{}, false, errors.New("notifications unavailable")
	}
	// Agent identity is authoritative server-side. Ignore any caller-provided
	// value and derive it from the catalog entry resolved by session_ref.
	req.AgentName = ""
	if req.SessionRef != "" {
		if e := s.notificationSessionEntry(req.SessionRef); e != nil {
			req.SessionRef = e.ref
			req.SessionInstance = sessionInstance(e)
			if req.Workspace == "" {
				req.Workspace = e.pane.CWD
			}
			req.AgentName = notificationAgentName(e)
		} else {
			// A stale/unknown pane must never become an unguarded historical link.
			req.SessionRef, req.SessionInstance = "", ""
		}
	}
	record, deduplicated, err := s.notifications.Accept(req)
	if err != nil {
		return protocol.NotificationRecord{}, false, err
	}
	if deduplicated {
		return record, true, nil
	}
	s.trackersMu.Lock()
	clients := make([]*wsConn, 0, len(s.trackers))
	for c := range s.trackers {
		clients = append(clients, c)
	}
	s.trackersMu.Unlock()
	for _, c := range clients {
		if c.notificationsCap.Load() {
			c.sendNotification(record)
		}
	}
	return record, deduplicated, nil
}

// Resolve macOS's /tmp and /private/tmp spellings to the authoritative catalog
// ref. Keep catalogEntry's fresh workspace overlay semantics and do not invent
// links for panes absent from the catalog.
func (s *Server) notificationSessionEntry(ref string) *sessionEntry {
	if e := s.catalogEntry(ref); e != nil {
		return e
	}
	socket, pane, ok := parseSessionRef(ref)
	if !ok || runtime.GOOS != "darwin" {
		return nil
	}
	switch {
	case strings.HasPrefix(socket, "/private/tmp/"):
		socket = strings.TrimPrefix(socket, "/private")
	case strings.HasPrefix(socket, "/tmp/"):
		socket = "/private" + socket
	default:
		return nil
	}
	return s.catalogEntry(socket + "\x1f" + pane)
}

func notificationAgentName(e *sessionEntry) string {
	// Reuse the pane-name projection instead of guessing from Provider or
	// trusting callers. A tmux session name is the identity fallback when the
	// projection could only provide a project name/placeholder.
	name := sessionname.Resolve(e.pane.WindowName, e.pane.PaneTitle, e.pane.CWD, e.pane.Command)
	if name.Source == sessionname.SourceProject || name.Source == sessionname.SourcePlaceholder {
		name.Value = strings.TrimSpace(e.pane.Session)
	}
	if strings.HasSuffix(strings.ToLower(name.Value), "leader") {
		return "Leader"
	}
	return name.Value
}

func (s *Server) NotificationStore() *notify.Store { return s.notifications }

// ListenNotificationSocket creates the private daemon IPC endpoint. The
// caller owns the listener and must close it during daemon shutdown.
func NotificationSocketPath(stateDir string) string {
	path := filepath.Join(stateDir, "notify.sock")
	// AF_UNIX paths are typically limited to 104-108 bytes on macOS/Linux;
	// test sandboxes often have much longer temp roots.
	if len(path) < 100 {
		return path
	}
	sum := sha256.Sum256([]byte(stateDir))
	return filepath.Join(os.TempDir(), "corral-notify-"+hex.EncodeToString(sum[:8])+".sock")
}

func ListenNotificationSocket(path string) (net.Listener, error) {
	if path == "" {
		return nil, errors.New("notification socket path is empty")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	_ = os.Remove(path)
	ln, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0o600); err != nil {
		_ = ln.Close()
		_ = os.Remove(path)
		return nil, err
	}
	return ln, nil
}

func (c *wsConn) handleNotificationsSync(req protocol.NotificationsSync) {
	if c.s.notifications == nil {
		c.sendError(protocol.ErrCodeUnsupportedType, "notifications unavailable")
		return
	}
	pageSize := int(req.PageSize)
	if pageSize == 0 {
		pageSize = 50
	}
	var view notify.View
	if req.PageToken != "" {
		c.notificationViewsMu.Lock()
		stored, ok := c.notificationViews[req.PageToken]
		if ok {
			view = stored
			delete(c.notificationViews, req.PageToken)
		}
		c.notificationViewsMu.Unlock()
		if !ok {
			c.sendError(protocol.ErrCodeInvalidField, "invalid or expired notification page_token")
			return
		}
	} else {
		view = c.s.notifications.NewView(req.Cursor)
	}
	page := view.Page(pageSize)
	response := protocol.NotificationsPage{ReqID: req.ReqID, OK: true, HostID: view.HostID, StreamID: view.StreamID, SnapshotCursor: &page.SnapshotCursor, RetainedFromSeq: page.RetainedFromSeq, Order: "asc", Items: page.Items, ResumeCursor: page.ResumeCursor}
	if response.Items == nil {
		response.Items = []protocol.NotificationRecord{}
	}
	if page.ResetReason != "" {
		response.ResetReason = &page.ResetReason
	}
	if page.Next {
		token, err := newNotificationPageToken()
		if err != nil {
			c.sendError(protocol.ErrCodeInternal, "notification paging unavailable")
			return
		}
		c.notificationViewsMu.Lock()
		c.notificationViews[token] = view
		c.notificationViewsMu.Unlock()
		response.NextPageToken = token
	}
	c.send(response)
}

func newNotificationPageToken() (string, error) {
	var b [24]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b[:]), nil
}

// NotificationHandler is mounted only on the daemon's private Unix socket.
// It is intentionally not included in Handler(), which is exposed to LAN and
// tailnet listeners.
func (s *Server) NotificationHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/v1/notifications" {
			writeIPCError(w, http.StatusNotFound, "not_found")
			return
		}
		defer r.Body.Close()
		var in NotificationIPCRequest
		dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 128<<10))
		if err := dec.Decode(&in); err != nil {
			writeIPCError(w, http.StatusBadRequest, "invalid_json")
			return
		}
		record, deduplicated, err := s.PublishNotification(r.Context(), notify.Request{RequestID: in.RequestID, Title: in.Title, Body: in.Body, SessionRef: in.SessionRef, Level: in.Level})
		if err != nil {
			status := http.StatusBadRequest
			if notify.IsConflict(err) {
				status = http.StatusConflict
			}
			writeIPCError(w, status, stableNotifyError(err))
			return
		}
		writeJSON(w, http.StatusOK, NotificationIPCResponse{Accepted: true, ID: record.ID, HostID: record.HostID, StreamID: record.StreamID, Seq: record.Seq, RequestID: in.RequestID, Deduplicated: deduplicated})
	})
}

func writeIPCError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, NotificationIPCResponse{Accepted: false, Error: code})
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func ptrNotificationState(state protocol.NotificationState) *protocol.NotificationState {
	return &state
}

func stableNotifyError(err error) string {
	if notify.IsConflict(err) {
		return "request_id_conflict"
	}
	if strings.Contains(err.Error(), "unavailable") {
		return "notifications_unavailable"
	}
	return "not_accepted"
}

func sessionInstance(e *sessionEntry) string {
	// PaneID alone is reusable after a tmux restart. Including the socket and
	// pane process incarnation gives a stable marker across daemon restarts and
	// changes when the same socket/pane slot is recreated.
	h := sha256.New()
	fmt.Fprintf(h, "%s\x00%s\x00%d", e.pane.Socket, e.pane.PaneID, e.pane.PanePID)
	return "pane-" + hex.EncodeToString(h.Sum(nil))[:32]
}
