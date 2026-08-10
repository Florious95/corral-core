package api

// api_test.go covers the no-tmux surface of the API: auth (including the
// unauthorized red line), listing construction and aggregate computation,
// unknown-frame handling, and the /upload endpoint. Real-tmux mirroring paths
// (subscribe/input/scrollback/resize) live in api_tmux_test.go.

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// TestUnauthorizedBeforeAuth is the red-line test: any operation before a
// validated auth must be refused with error: unauthorized (docs/protocol.md
// §3, §9).
func TestUnauthorizedBeforeAuth(t *testing.T) {
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: testModel()}})

	e.sendFrame(&protocol.List{ReqID: 1})
	err := e.readControl()
	if err.FrameType() != protocol.TypeError {
		t.Fatalf("expected error frame, got %v", err.FrameType())
	}
	ef := err.(protocol.ErrorFrame)
	if ef.Code != protocol.ErrCodeUnauthorized {
		t.Fatalf("error code = %q, want %q", ef.Code, protocol.ErrCodeUnauthorized)
	}
}

// TestAuthGoodToken verifies a valid token yields auth_ack ok.
func TestAuthGoodToken(t *testing.T) {
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: testModel()}})
	e.auth() // fails the test on reject
}

// TestAuthBadToken verifies an invalid token yields auth_ack ok:false and the
// connection is then closed (docs/protocol.md §4.2).
func TestAuthBadToken(t *testing.T) {
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: testModel()}})

	e.sendFrame(&protocol.Auth{Token: "wrong-token"})
	ack := e.readControl()
	if ack.FrameType() != protocol.TypeAuthAck {
		t.Fatalf("expected auth_ack, got %v", ack.FrameType())
	}
	aa := ack.(protocol.AuthAck)
	if aa.OK {
		t.Fatal("auth_ack must not be ok for a bad token")
	}
	if aa.Reason == "" {
		t.Fatal("rejected auth_ack must carry a reason")
	}
	// The connection must be closed right after the rejection. Bound the read
	// so a delayed close (e.g. behind queued list_delta frames in a busy suite)
	// cannot hang the test.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, _, err := e.conn.Read(ctx); err == nil {
		t.Fatal("connection must close after auth rejection")
	}
}

// TestListReturnsFullListing verifies list → listing carries the two-level
// model with server-computed aggregates (requirement 012) and a seq >= 1.
func TestListReturnsFullListing(t *testing.T) {
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: testModel()}})
	e.auth()

	e.sendFrame(&protocol.List{ReqID: 7})
	got := e.readControl()
	if got.FrameType() != protocol.TypeListing {
		t.Fatalf("expected listing, got %v", got.FrameType())
	}
	l := got.(protocol.Listing)
	if l.ReqID != 7 {
		t.Errorf("listing req_id = %d, want 7", l.ReqID)
	}
	if l.Seq < 1 {
		t.Errorf("listing seq = %d, want >= 1", l.Seq)
	}
	if len(l.Workspaces) != 1 {
		t.Fatalf("listing has %d workspaces, want 1", len(l.Workspaces))
	}
	ws := l.Workspaces[0]
	if ws.Cwd != "/ws/a" || ws.SessionCount != 2 {
		t.Fatalf("workspace = %+v, want cwd /ws/a count 2", ws)
	}
	// Aggregate: both panes are unknown (default state provider), so the
	// workspace aggregates to unknown (012 rule 3).
	if ws.AggregateState != protocol.StateUnknown {
		t.Errorf("aggregate_state = %q, want %q", ws.AggregateState, protocol.StateUnknown)
	}
	if len(ws.Sessions) != 2 {
		t.Fatalf("workspace has %d sessions, want 2", len(ws.Sessions))
	}
	// Sessions are ordered deterministically by ref.
	if ws.Sessions[0].Ref == "" || ws.Sessions[1].Ref == "" {
		t.Fatalf("sessions must carry refs, got %+v", ws.Sessions)
	}
}

// TestListDeltaSeqMonotonic is the red test for the listing loop: when the
// model changes, a list_delta with a strictly greater seq is pushed.
func TestListDeltaSeqMonotonic(t *testing.T) {
	md := &mutableDiscoverer{model: testModel()}
	e := startWS(t, Options{Token: "test-token", Discoverer: md})
	e.auth()

	// Let the baseline scan run with the original model (several ticks of the
	// 50ms loop emit nothing since the model is unchanged), so the diff below
	// is against a real baseline rather than racing the first scan.
	time.Sleep(150 * time.Millisecond)

	// Swap to a model with a new workspace; the next tick must emit a delta.
	md.set(&discovery.Model{
		Workspaces: []discovery.Workspace{
			{
				CWD: "/ws/a",
				Panes: []discovery.Pane{
					{Socket: "/tmp/sock1", Session: "alpha", PaneID: "%0", CWD: "/ws/a", Command: "claude", Width: 100, Height: 40},
					{Socket: "/tmp/sock1", Session: "beta", PaneID: "%1", CWD: "/ws/a", Command: "codex", Width: 80, Height: 24},
				},
			},
			{
				CWD: "/ws/c",
				Panes: []discovery.Pane{
					{Socket: "/tmp/sock2", Session: "gamma", PaneID: "%2", CWD: "/ws/c", Command: "zsh", Width: 50, Height: 20},
				},
			},
		},
	})

	// Read until a list_delta arrives (deltas and the occasional earlier frame
	// share the channel; skip anything that is not a delta).
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			continue
		}
		if typ != websocket.MessageText {
			continue
		}
		typed, err := protocol.UnmarshalFrame(data)
		if err != nil {
			continue
		}
		d, ok := typed.(protocol.ListDelta)
		if !ok {
			continue
		}
		if len(d.AddedSessions) != 1 || d.AddedSessions[0].Cwd != "/ws/c" {
			t.Fatalf("delta added = %+v, want single /ws/c session", d.AddedSessions)
		}
		if d.Seq < 1 {
			t.Errorf("delta seq = %d, want >= 1", d.Seq)
		}
		return
	}
	t.Fatal("timed out waiting for list_delta")
}

// TestUnknownFrameType verifies an unknown type yields unsupported_type.
func TestUnknownFrameType(t *testing.T) {
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: testModel()}})
	e.auth()

	// A hand-built envelope with an unknown type (protocol codec rejects it,
	// the server maps to unsupported_type).
	body := []byte(`{"v":1,"type":"bogus","payload":{}}`)
	if err := e.conn.Write(context.Background(), websocket.MessageText, body); err != nil {
		t.Fatalf("write: %v", err)
	}
	got := e.readControl()
	if got.FrameType() != protocol.TypeError {
		t.Fatalf("expected error frame, got %v", got.FrameType())
	}
	ef := got.(protocol.ErrorFrame)
	if ef.Code != protocol.ErrCodeUnsupportedType {
		t.Fatalf("error code = %q, want %q", ef.Code, protocol.ErrCodeUnsupportedType)
	}
}

// TestUploadPersistsFileAndReturnsPath verifies POST /upload writes the file
// to the host disk and returns its absolute path in the JSON body, and the
// returned path exists (knowledge-base red test).
func TestUploadPersistsFileAndReturnsPath(t *testing.T) {
	uploadDir := t.TempDir()
	srv := NewServer(Options{
		Token:      "test-token",
		UploadDir:  uploadDir,
		Discoverer: scriptedDiscoverer{model: testModel()},
	})
	hsrv := httptest.NewServer(srv.Handler())
	defer hsrv.Close()

	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, err := mw.CreateFormFile("file", "hello.png")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	content := []byte("\x89PNG fake image bytes")
	if _, err := fw.Write(content); err != nil {
		t.Fatalf("write part: %v", err)
	}
	mw.Close()

	req, err := http.NewRequest(http.MethodPost, hsrv.URL+"/upload", &buf)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer test-token")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do upload: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body := new(bytes.Buffer)
		body.ReadFrom(resp.Body)
		t.Fatalf("upload status = %d, body %s", resp.StatusCode, body.String())
	}

	var ur protocol.UploadResp
	if err := json.NewDecoder(resp.Body).Decode(&ur); err != nil {
		t.Fatalf("decode upload resp: %v", err)
	}
	if ur.Path == "" || !filepath.IsAbs(ur.Path) {
		t.Fatalf("upload path %q must be absolute", ur.Path)
	}
	// The red test: the returned path must exist on disk with the uploaded
	// bytes.
	got, err := os.ReadFile(ur.Path)
	if err != nil {
		t.Fatalf("uploaded file unreadable: %v", err)
	}
	if !bytes.Equal(got, content) {
		t.Fatalf("uploaded content = %q, want %q", got, content)
	}
}

// TestUploadAuthentication locks the HTTP upload contract to the same pairing
// token used by WebSocket auth. Rejections are visible but never echo either
// the configured token or the presented credential.
func TestUploadAuthentication(t *testing.T) {
	tests := []struct {
		name       string
		auth       string
		wantStatus int
		wantFiles  int
	}{
		{name: "missing credential", wantStatus: http.StatusUnauthorized},
		{name: "wrong credential", auth: "Bearer wrong-token", wantStatus: http.StatusUnauthorized},
		{name: "valid credential", auth: "Bearer test-token", wantStatus: http.StatusOK, wantFiles: 1},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			uploadDir := t.TempDir()
			srv := NewServer(Options{
				Token:      "test-token",
				UploadDir:  uploadDir,
				Discoverer: scriptedDiscoverer{model: testModel()},
			})
			defer srv.Close()
			hsrv := httptest.NewServer(srv.Handler())
			defer hsrv.Close()

			var buf bytes.Buffer
			mw := multipart.NewWriter(&buf)
			fw, err := mw.CreateFormFile("file", "auth.png")
			if err != nil {
				t.Fatalf("create form file: %v", err)
			}
			if _, err := fw.Write([]byte("image")); err != nil {
				t.Fatalf("write part: %v", err)
			}
			if err := mw.Close(); err != nil {
				t.Fatalf("close multipart: %v", err)
			}

			req, err := http.NewRequest(http.MethodPost, hsrv.URL+"/upload", &buf)
			if err != nil {
				t.Fatalf("new request: %v", err)
			}
			req.Header.Set("Content-Type", mw.FormDataContentType())
			if tt.auth != "" {
				req.Header.Set("Authorization", tt.auth)
			}
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatalf("do upload: %v", err)
			}
			defer resp.Body.Close()
			body, err := io.ReadAll(resp.Body)
			if err != nil {
				t.Fatalf("read response: %v", err)
			}
			if resp.StatusCode != tt.wantStatus {
				t.Fatalf("upload status = %d, want %d, body %s", resp.StatusCode, tt.wantStatus, body)
			}
			if bytes.Contains(body, []byte("test-token")) || bytes.Contains(body, []byte("wrong-token")) {
				t.Fatalf("upload response echoed credential: %s", body)
			}
			if tt.wantStatus == http.StatusUnauthorized {
				var rejection struct {
					Code   string `json:"code"`
					Reason string `json:"reason"`
				}
				if err := json.Unmarshal(body, &rejection); err != nil {
					t.Fatalf("decode rejection: %v", err)
				}
				if rejection.Code != "unauthorized" || rejection.Reason == "" {
					t.Fatalf("rejection = %+v, want unauthorized with non-empty reason", rejection)
				}
			}
			entries, err := os.ReadDir(uploadDir)
			if err != nil {
				t.Fatalf("read upload dir: %v", err)
			}
			if len(entries) != tt.wantFiles {
				t.Fatalf("stored files = %d, want %d", len(entries), tt.wantFiles)
			}
		})
	}
}

// TestUploadTooLarge verifies an upload exceeding the byte cap is rejected.
func TestUploadTooLarge(t *testing.T) {
	srv := NewServer(Options{
		Token:          "test-token",
		UploadDir:      t.TempDir(),
		MaxUploadBytes: 32,
		Discoverer:     scriptedDiscoverer{model: testModel()},
	})
	hsrv := httptest.NewServer(srv.Handler())
	defer hsrv.Close()

	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", "big.png")
	fw.Write(bytes.Repeat([]byte("x"), 100))
	mw.Close()

	req, err := http.NewRequest(http.MethodPost, hsrv.URL+"/upload", &buf)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer test-token")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do upload: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize upload status = %d, want 413", resp.StatusCode)
	}
}

// TestUploadDirectoryLimit verifies the endpoint rejects an upload that would
// cross the fixed total-directory quota without deleting existing files.
func TestUploadDirectoryLimit(t *testing.T) {
	uploadDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(uploadDir, "existing.png"), bytes.Repeat([]byte("x"), 8), 0o600); err != nil {
		t.Fatalf("seed upload dir: %v", err)
	}
	srv := NewServer(Options{
		Token:      "test-token",
		UploadDir:  uploadDir,
		Discoverer: scriptedDiscoverer{model: testModel()},
	})
	defer srv.Close()
	srv.maxUploadDir = 10
	hsrv := httptest.NewServer(srv.Handler())
	defer hsrv.Close()

	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, err := mw.CreateFormFile("file", "new.png")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := fw.Write([]byte("abc")); err != nil {
		t.Fatalf("write part: %v", err)
	}
	if err := mw.Close(); err != nil {
		t.Fatalf("close multipart: %v", err)
	}
	req, err := http.NewRequest(http.MethodPost, hsrv.URL+"/upload", &buf)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer test-token")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do upload: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusInsufficientStorage {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("upload status = %d, want 507, body %s", resp.StatusCode, body)
	}
	var rejection uploadError
	if err := json.NewDecoder(resp.Body).Decode(&rejection); err != nil {
		t.Fatalf("decode rejection: %v", err)
	}
	if rejection.Code != "storage_limit_exceeded" || rejection.Reason == "" {
		t.Fatalf("rejection = %+v, want storage_limit_exceeded with reason", rejection)
	}
	entries, err := os.ReadDir(uploadDir)
	if err != nil {
		t.Fatalf("read upload dir: %v", err)
	}
	if len(entries) != 1 || entries[0].Name() != "existing.png" {
		t.Fatalf("quota rejection changed upload dir: %+v", entries)
	}
}
