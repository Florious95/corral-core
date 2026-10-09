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
	"syscall"
	"time"
	"unicode/utf8"
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
		s.log.Error("upload: resolve dir", "code", "upload_dir_unavailable", "errno", uploadErrno(err))
		writeUploadDirError(w, err)
		return
	}

	// Serialize the quota check with the write so concurrent uploads cannot
	// each observe spare capacity and collectively cross the directory cap.
	s.uploadMu.Lock()
	defer s.uploadMu.Unlock()
	used, err := uploadDirSize(dir)
	if err != nil {
		// Last-good wrote to ~/Downloads/agentmirror-uploads. A later TCC /
		// unlistable-but-writable directory made ReadDir fail and the same
		// handler returned HTTP 500 before write. Permission on measure is
		// skipped so the last-good write path still runs; quota cannot be
		// enforced without listing.
		if isPermission(err) {
			s.log.Error("upload: measure dir skipped", "code", "upload_dir_unreadable", "errno", uploadErrno(err))
			used = 0
		} else {
			s.log.Error("upload: measure dir", "code", "upload_dir_unreadable", "errno", uploadErrno(err))
			writeUploadError(w, http.StatusInsufficientStorage, "upload_dir_unreadable", "upload directory could not be measured")
			return
		}
	}
	if used > s.maxUploadDir-int64(len(data)) {
		writeUploadError(w, http.StatusInsufficientStorage, "storage_limit_exceeded", "upload directory size limit exceeded")
		return
	}
	path, err := writeUpload(dir, part.FileName(), data)
	if err != nil {
		s.log.Error("upload: write file", "code", "upload_write_failed", "errno", uploadErrno(err))
		writeUploadError(w, http.StatusInsufficientStorage, "upload_write_failed", "upload write failed")
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

// writeUploadDirError converts resolveUploadDir failures to JSON without
// directory locations. A configured path that exists but is not a directory
// is a configuration error (400); permission and create failures are storage
// unavailability (507). None of these are HTTP 500: the historical 500 hid
// the branch.
func writeUploadDirError(w http.ResponseWriter, err error) {
	if errors.Is(err, errUploadDirInvalid) || isNotDir(err) {
		writeUploadError(w, http.StatusBadRequest, "upload_dir_invalid", "upload directory is not a directory")
		return
	}
	writeUploadError(w, http.StatusInsufficientStorage, "upload_dir_unavailable", "upload directory unavailable")
}

// uploadErrno returns an errno class for logs without directory locations.
// os.PathError.Error includes the absolute location; the inner Err does not.
func uploadErrno(err error) string {
	if err == nil {
		return ""
	}
	var pe *os.PathError
	if errors.As(err, &pe) && pe.Err != nil {
		return pe.Err.Error()
	}
	var le *os.LinkError
	if errors.As(err, &le) && le.Err != nil {
		return le.Err.Error()
	}
	if msg := err.Error(); !strings.Contains(msg, string(os.PathSeparator)) {
		return msg
	}
	return "error"
}

func isNotDir(err error) bool {
	return errors.Is(err, syscall.ENOTDIR)
}

func isPermission(err error) bool {
	return errors.Is(err, os.ErrPermission) || errors.Is(err, syscall.EACCES) || errors.Is(err, syscall.EPERM)
}

// errUploadDirInvalid is returned when the configured upload path exists
// but is not a directory (for example a regular file).
var errUploadDirInvalid = errors.New("upload directory is not a directory")

// errTooManyUploadEntries is returned when measuring the upload directory
// would enumerate more names than the bounded scan allows.
var errTooManyUploadEntries = errors.New("upload directory has too many entries")

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
// the last-good location $HOME/Downloads/agentmirror-uploads and creating
// it on demand.
// @contract
// @pre none — empty uploadDir selects the last-good default
// @post 返回已存在且为目录的路径；空配置为 $HOME/Downloads/agentmirror-uploads
// @err 无法解析 home、MkdirAll 失败、路径存在但不是目录
// @inv HTTP 层不得把绝对路径放进响应
func (s *Server) resolveUploadDir() (string, error) {
	dir := strings.TrimSpace(s.uploadDir)
	if dir == "" {
		var err error
		dir, err = defaultUploadDir()
		if err != nil {
			return "", err
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		if isNotDir(err) {
			return "", errUploadDirInvalid
		}
		return "", err
	}
	st, err := os.Stat(dir)
	if err != nil {
		return "", err
	}
	if !st.IsDir() {
		return "", errUploadDirInvalid
	}
	return dir, nil
}

// defaultUploadDir is the last-good empty-config location.
func defaultUploadDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Downloads", defaultUploadSubdir), nil
}

// uploadDirSize measures the regular files in the flat directory used by the
// uploader. Symlinks and subdirectories are ignored: the endpoint creates
// neither, and following them could escape a user-configured directory.
// Enumeration is batched and capped so a hostile directory cannot hang the
// handler.
func uploadDirSize(dir string) (int64, error) {
	f, err := os.Open(dir)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	var total int64
	seen := 0
	for {
		entries, err := f.ReadDir(uploadDirMeasureBatch)
		for _, entry := range entries {
			seen++
			if seen > maxUploadDirEntries {
				return 0, errTooManyUploadEntries
			}
			info, ierr := entry.Info()
			if ierr != nil {
				if errors.Is(ierr, os.ErrNotExist) {
					continue
				}
				return 0, ierr
			}
			if info.Mode().IsRegular() {
				total += info.Size()
			}
		}
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return 0, err
		}
		if len(entries) == 0 {
			break
		}
	}
	return total, nil
}

// writeUpload writes data to dir under a safe unique filename and returns the
// absolute path. The client-supplied name is sanitized to its basename (so a
// path cannot be smuggled in) and prefixed with a timestamp + sequence so
// concurrent uploads never collide. The final component is clipped to the
// platform filename length so a long client name cannot 500 with ENAMETOOLONG.
func writeUpload(dir, clientName string, data []byte) (string, error) {
	base := sanitizeBaseName(clientName)
	path := filepath.Join(dir, clipUploadFileName("upload-"+time.Now().UTC().Format("20060102T150405")+"-", base))
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		// Extremely unlikely collision; retry once with a different name.
		path = filepath.Join(dir, clipUploadFileName(
			fmt.Sprintf("upload-%s-%d-", time.Now().UTC().Format("20060102T150405000"), time.Now().Nanosecond()),
			base,
		))
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

// clipUploadFileName keeps prefix+stem+ext within one filename component
// (255 bytes). The uniqueness prefix and a short sanitized extension (e.g.
// ".jpg") are reserved; only the stem is cut, on a UTF-8 boundary, so a long
// "….jpg" name cannot lose its suffix.
func clipUploadFileName(prefix, base string) string {
	stem, ext := splitSanitizedExt(base)
	if stem == "" {
		stem = "file"
	}
	budget := maxUploadFileNameBytes - len(prefix) - len(ext)
	if budget < 1 {
		keep := maxUploadFileNameBytes - len(ext)
		if keep < 1 {
			return clipUTF8(ext, maxUploadFileNameBytes)
		}
		return clipUTF8(prefix, keep) + ext
	}
	stem = clipUTF8(stem, budget)
	if stem == "" {
		stem = clipUTF8("file", budget)
	}
	return prefix + stem + ext
}

// splitSanitizedExt returns stem and a short safe extension (".jpg"). A
// leading-dot name or a non-alnum / overlong suffix is treated as stem-only
// so a hostile "extension" cannot eat the uniqueness prefix.
func splitSanitizedExt(base string) (stem, ext string) {
	i := strings.LastIndex(base, ".")
	if i <= 0 {
		return base, ""
	}
	rest := base[i+1:]
	if rest == "" || len(rest) > maxSanitizedExtLetters {
		return base, ""
	}
	for _, r := range rest {
		alnum := r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9'
		if !alnum {
			return base, ""
		}
	}
	stem = base[:i]
	if stem == "" {
		stem = "file"
	}
	return stem, base[i:]
}

func clipUTF8(s string, n int) string {
	if n <= 0 {
		return ""
	}
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
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
