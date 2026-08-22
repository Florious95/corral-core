package api

// upload.go implements POST /upload (docs/protocol.md §8): a multipart HTTP
// endpoint on the same port as the WebSocket API. The server writes the
// uploaded file to the host disk and returns its absolute path as JSON; the
// client then injects that path as input.text so the CLI loads the image
// (requirement 003 image pipeline — no multimodal API involved).

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// serveUpload handles POST /upload.
func (s *Server) serveUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if token, reason := uploadBearerToken(r); reason != "" {
		writeUploadError(w, http.StatusUnauthorized, "unauthorized", reason)
		return
	} else if !s.tokenValidator.ValidateToken(r.Context(), token) {
		writeUploadError(w, http.StatusUnauthorized, "unauthorized", "invalid bearer token")
		return
	}

	// Bound the body so a hostile or buggy peer cannot allocate without limit.
	// The cap includes the file itself plus multipart framing slack so a file
	// exactly at the byte limit is not rejected before its size is inspected.
	r.Body = http.MaxBytesReader(w, r.Body, s.maxUpload+uploadHeaderSlack)
	reader, err := r.MultipartReader()
	if err != nil {
		http.Error(w, "request must be multipart/form-data", http.StatusBadRequest)
		return
	}

	part, err := findFilePart(reader)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	defer part.Close()

	// Enforce the size limit on the streamed file itself (the body cap above is
	// only a coarse bound; a declared form field could eat into it).
	limited := &limitedReader{r: part, remaining: s.maxUpload}
	data, err := io.ReadAll(limited)
	if err != nil {
		if errors.Is(err, errTooLarge) {
			http.Error(w, "file exceeds size limit", http.StatusRequestEntityTooLarge)
			return
		}
		http.Error(w, "read upload failed", http.StatusBadRequest)
		return
	}
	if len(data) == 0 {
		http.Error(w, "empty file", http.StatusBadRequest)
		return
	}

	dir, err := s.resolveUploadDir()
	if err != nil {
		s.log.Error("upload: resolve dir", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	// Serialize the quota check with the write so concurrent uploads cannot
	// each observe spare capacity and collectively cross the directory cap.
	s.uploadMu.Lock()
	defer s.uploadMu.Unlock()
	used, err := uploadDirSize(dir)
	if err != nil {
		s.log.Error("upload: measure dir", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if used > s.maxUploadDir-int64(len(data)) {
		writeUploadError(w, http.StatusInsufficientStorage, "storage_limit_exceeded", "upload directory size limit exceeded")
		return
	}
	path, err := writeUpload(dir, part.FileName(), data)
	if err != nil {
		s.log.Error("upload: write file", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	body, err := json.Marshal(protocolUploadResp{Path: path})
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(body)
}

// uploadBearerToken parses the standard Authorization: Bearer credential.
// It returns only fixed, token-free rejection reasons so malformed input can
// never be reflected into a response or log.
func uploadBearerToken(r *http.Request) (string, string) {
	values := r.Header.Values("Authorization")
	if len(values) == 0 {
		return "", "missing bearer token"
	}
	if len(values) != 1 {
		return "", "invalid authorization header"
	}
	fields := strings.Fields(values[0])
	if len(fields) != 2 || !strings.EqualFold(fields[0], "Bearer") || fields[1] == "" {
		return "", "invalid authorization header"
	}
	return fields[1], ""
}

type uploadError struct {
	Code   string `json:"code"`
	Reason string `json:"reason"`
}

func writeUploadError(w http.ResponseWriter, status int, code, reason string) {
	w.Header().Set("Content-Type", "application/json")
	if status == http.StatusUnauthorized {
		w.Header().Set("WWW-Authenticate", "Bearer")
	}
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(uploadError{Code: code, Reason: reason})
}

// protocolUploadResp mirrors protocol.UploadResp as an HTTP JSON body. It is
// not a control frame, so it is marshaled directly rather than through
// MarshalFrame (which would wrap it in a version envelope).
type protocolUploadResp struct {
	Path string `json:"path"`
}

// findFilePart scans the multipart stream and returns the first file part
// (a part carrying a filename). Non-file form fields are skipped, so the field
// name the client uses is not part of the contract.
func findFilePart(reader *multipart.Reader) (*multipart.Part, error) {
	for {
		part, err := reader.NextPart()
		if err == io.EOF {
			return nil, fmt.Errorf("no file part in request")
		}
		if err != nil {
			return nil, fmt.Errorf("read multipart: %w", err)
		}
		if part.FileName() != "" {
			return part, nil
		}
		// A plain form field: drain and skip it.
		_, _ = io.Copy(io.Discard, part)
		part.Close()
	}
}

// resolveUploadDir returns the configured upload directory, defaulting to
// $HOME/Downloads/agentmirror-uploads and creating it on demand.
func (s *Server) resolveUploadDir() (string, error) {
	dir := s.uploadDir
	if dir == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", fmt.Errorf("resolve home dir: %w", err)
		}
		dir = filepath.Join(home, "Downloads", defaultUploadSubdir)
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return dir, nil
}

// uploadDirSize measures the regular files in the flat directory used by the
// uploader. Symlinks and subdirectories are ignored: the endpoint creates
// neither, and following them could escape a user-configured directory.
func uploadDirSize(dir string) (int64, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0, err
	}
	var total int64
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil {
			return 0, err
		}
		if info.Mode().IsRegular() {
			total += info.Size()
		}
	}
	return total, nil
}

// writeUpload writes data to dir under a safe unique filename and returns the
// absolute path. The client-supplied name is sanitized to its basename (so a
// path cannot be smuggled in) and prefixed with a timestamp + sequence so
// concurrent uploads never collide.
func writeUpload(dir, clientName string, data []byte) (string, error) {
	base := sanitizeBaseName(clientName)
	path := filepath.Join(dir, fmt.Sprintf("upload-%s-%s", time.Now().UTC().Format("20060102T150405"), base))
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		// Extremely unlikely collision; retry once with a different name.
		path = filepath.Join(dir, fmt.Sprintf("upload-%s-%d-%s", time.Now().UTC().Format("20060102T150405000"), time.Now().Nanosecond(), base))
		f, err = os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if err != nil {
			return "", err
		}
	}
	defer f.Close()
	if _, err := f.Write(data); err != nil {
		_ = os.Remove(path)
		return "", err
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		_ = os.Remove(path)
		return "", err
	}
	return abs, nil
}

// sanitizeBaseName reduces a client-supplied filename to a safe basename:
// path separators and control characters stripped, empty names replaced so
// the resulting path can never escape the upload directory.
func sanitizeBaseName(name string) string {
	name = filepath.Base(name)
	var b strings.Builder
	for _, r := range name {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9',
			r == '.', r == '-', r == '_':
			b.WriteRune(r)
		default:
			b.WriteRune('_')
		}
	}
	if b.Len() == 0 {
		return "file"
	}
	return b.String()
}

// errTooLarge is returned by limitedReader when the streamed file exceeds the
// configured cap.
var errTooLarge = errors.New("upload: file too large")

// limitedReader is an io.Reader that fails once more than limit bytes have
// been read, giving a precise size check on the file part itself.
type limitedReader struct {
	r         io.Reader
	remaining int64
}

func (l *limitedReader) Read(p []byte) (int, error) {
	if l.remaining <= 0 {
		return 0, errTooLarge
	}
	if int64(len(p)) > l.remaining {
		p = p[:l.remaining]
	}
	n, err := l.r.Read(p)
	l.remaining -= int64(n)
	return n, err
}
