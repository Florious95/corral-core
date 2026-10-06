package api

// Authenticated session artifacts are downloads, never a public file server.
// @consumes internal/guirpc
// @contract
// @pre valid Bearer token, catalog ref, exact native session identity
// @post one bounded private export is streamed as attachment then cleaned
// @err unauthorized/unsupported/stale/busy/failed exports have finite results
// @inv no host path, stderr, credentials, directory listing or remote redirect

import (
	"context"
	"mime"
	"net/http"
	"strings"
	"time"

	"github.com/agentmirror/agentmirror/internal/guirpc"
)

func (s *Server) serveSessionExport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	token, reason := uploadBearerToken(r)
	if reason != "" || !s.tokenValidator.ValidateToken(r.Context(), token) {
		writeUploadError(w, http.StatusUnauthorized, "unauthorized", "valid bearer token required")
		return
	}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/artifacts/"), "/")
	if len(parts) != 2 || parts[1] != "export" || parts[0] == "" || len(parts[0]) > 128 || strings.HasPrefix(parts[0], "-") {
		writeUploadError(w, http.StatusBadRequest, "invalid_session", "invalid session export")
		return
	}
	// Each GET generates a new immutable file; resuming against a regenerated
	// entity could splice two exports. This endpoint is deliberately one-shot.
	if r.Header.Get("Range") != "" {
		writeUploadError(w, http.StatusRequestedRangeNotSatisfiable, "range_unsupported", "restart the complete export download")
		return
	}
	ref := r.URL.Query().Get("ref")
	entry := s.catalogEntry(ref)
	exporter, ok := s.conversations.(guirpc.SessionExporter)
	if !ok || entry == nil || (!entry.observation.Conversation && !s.conversations.Available(ref)) {
		writeUploadError(w, http.StatusNotFound, "session_unavailable", "native conversation unavailable")
		return
	}
	select {
	case s.artifactSlots <- struct{}{}:
		defer func() { <-s.artifactSlots }()
	default:
		writeUploadError(w, http.StatusTooManyRequests, "export_busy", "too many active exports")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 40*time.Second)
	defer cancel()
	artifact, err := exporter.ExportSession(ctx, entry.pane, parts[0])
	if err != nil {
		writeUploadError(w, http.StatusConflict, "export_failed", "native session export failed; verify the current session and retry")
		return
	}
	defer artifact.Close()
	stat, err := artifact.File.Stat()
	if err != nil || stat.Size() <= 0 || stat.Size() > guirpc.MaxArtifactBytes {
		writeUploadError(w, http.StatusInsufficientStorage, "artifact_unavailable", "export file unavailable")
		return
	}
	// Bound a slow peer as well as native generation; no permanent occupied slot.
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(60 * time.Second))
	defer http.NewResponseController(w).SetWriteDeadline(time.Time{})
	w.Header().Set("Content-Type", artifact.MIME)
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": artifact.Name}))
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "sandbox; default-src 'none'")
	http.ServeContent(w, r, artifact.Name, stat.ModTime(), artifact.File)
}
