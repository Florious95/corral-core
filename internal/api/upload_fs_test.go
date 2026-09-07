package api

// upload_fs_test.go is the regression for POST /upload HTTP 500 on filesystem
// branches: a normal image in the default writable directory must be 200 with
// matching bytes; invalid/unwritable custom dirs, long names, and
// resolve/measure/write failures must return bounded, path-free diagnoses
// instead of internal 500.

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
	"unicode/utf8"
)

const uploadTestToken = "upload-fs-test-token"

func TestUploadDefaultWritableDirReturns200AndHash(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	srv := NewServer(Options{
		Token:      uploadTestToken,
		Discoverer: scriptedDiscoverer{model: testModel()},
	})
	defer srv.Close()
	hsrv := httptest.NewServer(srv.Handler())
	defer hsrv.Close()

	content := []byte("\xff\xd8\xff\xe0fake-jpeg-bytes")
	resp, body := doUpload(t, hsrv.URL, uploadTestToken, clientShapedMultipart(t, "photo.jpg", "image/jpeg", content))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("default-dir upload status = %d, body %s", resp.StatusCode, body)
	}
	var ur protocolUploadResp
	if err := json.Unmarshal(body, &ur); err != nil {
		t.Fatalf("decode upload resp: %v", err)
	}
	if ur.Path == "" || !filepath.IsAbs(ur.Path) {
		t.Fatalf("upload path %q must be absolute", ur.Path)
	}
	wantPrefix, err := defaultUploadDir()
	if err != nil {
		t.Fatalf("defaultUploadDir: %v", err)
	}
	if !strings.Contains(filepath.ToSlash(wantPrefix), "/Downloads/agentmirror-uploads") {
		t.Fatalf("last-good default dir = %q, want .../Downloads/agentmirror-uploads", wantPrefix)
	}
	if !strings.HasPrefix(ur.Path, wantPrefix+string(os.PathSeparator)) && ur.Path != wantPrefix {
		t.Fatalf("upload path %q is not under last-good default dir %q", ur.Path, wantPrefix)
	}
	got, err := os.ReadFile(ur.Path)
	if err != nil {
		t.Fatalf("uploaded file unreadable: %v", err)
	}
	if sha256.Sum256(got) != sha256.Sum256(content) {
		t.Fatalf("uploaded sha256 mismatch")
	}
	if bytes.Contains(body, []byte(uploadTestToken)) {
		t.Fatal("success response leaked token")
	}
}

func TestUploadUnreadableButWritableDirStillWrites(t *testing.T) {
	// Last-good handler 500'd here: uploadDirSize/ReadDir needs read, write
	// does not. Recent TCC on Downloads is this boundary. Restore write.
	dir := t.TempDir()
	if err := os.Chmod(dir, 0o333); err != nil {
		t.Fatalf("chmod wx-only: %v", err)
	}
	t.Cleanup(func() { _ = os.Chmod(dir, 0o700) })

	content := []byte("img")
	resp, body := uploadToDir(t, dir, "photo.jpg", content)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("unlistable-but-writable status = %d, body %s", resp.StatusCode, body)
	}
	var ur protocolUploadResp
	if err := json.Unmarshal(body, &ur); err != nil {
		t.Fatalf("decode upload resp: %v", err)
	}
	got, err := os.ReadFile(ur.Path)
	if err != nil {
		t.Fatalf("uploaded file unreadable: %v", err)
	}
	if sha256.Sum256(got) != sha256.Sum256(content) {
		t.Fatalf("unlistable-but-writable sha256 mismatch")
	}
}

func TestUploadNotDirectoryIsNotInternal500(t *testing.T) {
	dir := t.TempDir()
	filePath := filepath.Join(dir, "not-a-dir")
	if err := os.WriteFile(filePath, []byte("x"), 0o600); err != nil {
		t.Fatalf("seed file: %v", err)
	}
	resp, body := uploadToDir(t, filePath, "photo.jpg", []byte("img"))
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusInternalServerError {
		t.Fatalf("invalid dir mapped to 500: %s", body)
	}
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("invalid dir status = %d, want 400, body %s", resp.StatusCode, body)
	}
	assertUploadJSONCode(t, body, "upload_dir_invalid")
	assertNoLeak(t, body, filePath, uploadTestToken, dir)
}

func TestUploadUnwritableDirIsNotInternal500(t *testing.T) {
	dir := t.TempDir()
	if err := os.Chmod(dir, 0o555); err != nil {
		t.Fatalf("chmod rx-only: %v", err)
	}
	t.Cleanup(func() { _ = os.Chmod(dir, 0o700) })

	resp, body := uploadToDir(t, dir, "photo.jpg", []byte("img"))
	defer resp.Body.Close()
	assertDiagnosableStorageFailure(t, resp, body, dir, "upload_write_failed")
}

func TestUploadLongFileNameIsNotInternal500(t *testing.T) {
	dir := t.TempDir()
	content := []byte("long-name-payload")
	longName := strings.Repeat("n", 300) + ".jpg"
	resp, body := uploadToDir(t, dir, longName, content)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("long-name upload status = %d, body %s", resp.StatusCode, body)
	}
	var ur protocolUploadResp
	if err := json.Unmarshal(body, &ur); err != nil {
		t.Fatalf("decode upload resp: %v", err)
	}
	got, err := os.ReadFile(ur.Path)
	if err != nil {
		t.Fatalf("uploaded file unreadable: %v", err)
	}
	if sha256.Sum256(got) != sha256.Sum256(content) {
		t.Fatalf("long-name upload sha256 mismatch")
	}
	stored := filepath.Base(ur.Path)
	if len(stored) > maxUploadFileNameBytes {
		t.Fatalf("stored name %q exceeds %d bytes", stored, maxUploadFileNameBytes)
	}
	if !strings.HasSuffix(stored, ".jpg") {
		t.Fatalf("stored name %q lost .jpg suffix", stored)
	}
	assertSafeStoredName(t, stored)
}

func TestUploadExternalAddDoesNotBypassQuota(t *testing.T) {
	// Independent-acceptance counterexample (cec running-total NO-GO):
	// CAP-50, external files appear after a warmup POST, then another 50B
	// upload must stay 507. Scanning every POST (d557) sees the new bytes;
	// a stale used cache would 200 and leave the dir at CAP+100.
	const capBytes = 50
	dir := t.TempDir()
	srv := NewServer(Options{
		Token:      uploadTestToken,
		UploadDir:  dir,
		Discoverer: scriptedDiscoverer{model: testModel()},
	})
	defer srv.Close()
	srv.maxUploadDir = capBytes
	hsrv := httptest.NewServer(srv.Handler())
	defer hsrv.Close()

	warm := bytes.Repeat([]byte("w"), capBytes)
	resp, body := doUpload(t, hsrv.URL, uploadTestToken, clientShapedMultipart(t, "warm.jpg", "image/jpeg", warm))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("warmup status = %d %s", resp.StatusCode, body)
	}

	external := bytes.Repeat([]byte("e"), capBytes)
	if err := os.WriteFile(filepath.Join(dir, "external.bin"), external, 0o600); err != nil {
		t.Fatalf("external add: %v", err)
	}

	incoming := bytes.Repeat([]byte("x"), capBytes)
	resp2, body2 := doUpload(t, hsrv.URL, uploadTestToken, clientShapedMultipart(t, "next.jpg", "image/jpeg", incoming))
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusInsufficientStorage {
		t.Fatalf("after external add, CAP-%d upload %dB status = %d want 507 body %s", capBytes, capBytes, resp2.StatusCode, body2)
	}
	assertUploadJSONCode(t, body2, "storage_limit_exceeded")

	used, err := uploadDirSize(dir)
	if err != nil {
		t.Fatalf("measure: %v", err)
	}
	// warmup 50 + external 50 = 100; a 200 would make 150 = CAP+100.
	if used != int64(2*capBytes) {
		t.Fatalf("dir total = %d, want %d (rejected third %dB)", used, 2*capBytes, capBytes)
	}
}

func TestUploadErrorResponsesDoNotLeakPathOrToken(t *testing.T) {
	dir := t.TempDir()
	filePath := filepath.Join(dir, "blocked")
	if err := os.WriteFile(filePath, []byte("x"), 0o600); err != nil {
		t.Fatalf("seed file: %v", err)
	}
	resp, body := uploadToDir(t, filePath, "secret.jpg", []byte("img"))
	defer resp.Body.Close()
	assertNoLeak(t, body, filePath, uploadTestToken, dir, "blocked", homeHint(t))
}

func TestClipUploadFileNameBoundsComponent(t *testing.T) {
	name := clipUploadFileName("upload-20260101T000000-", strings.Repeat("a", 400))
	if len(name) > maxUploadFileNameBytes {
		t.Fatalf("clipped name len = %d, want <= %d", len(name), maxUploadFileNameBytes)
	}
	if !strings.HasPrefix(name, "upload-20260101T000000-") {
		t.Fatalf("clipped name lost prefix: %q", name)
	}
}

func TestClipUploadLongASCIIKeepsJpg(t *testing.T) {
	prefix := "upload-20260101T000000-"
	name := clipUploadFileName(prefix, strings.Repeat("n", 300)+".jpg")
	if len(name) > maxUploadFileNameBytes {
		t.Fatalf("clipped ASCII jpg len = %d", len(name))
	}
	if !strings.HasSuffix(name, ".jpg") {
		t.Fatalf("clipped ASCII jpg lost suffix: %q", name)
	}
	if !strings.HasPrefix(name, prefix) {
		t.Fatalf("clipped ASCII jpg lost uniqueness prefix: %q", name)
	}
	assertSafeStoredName(t, name)
}

func TestClipUploadLongUTF8KeepsJpgOnRuneBoundary(t *testing.T) {
	prefix := "upload-20260101T000000-"
	name := clipUploadFileName(prefix, strings.Repeat("测", 200)+".jpg")
	if len(name) > maxUploadFileNameBytes {
		t.Fatalf("clipped UTF-8 jpg len = %d", len(name))
	}
	if !utf8.ValidString(name) {
		t.Fatalf("clipped UTF-8 jpg is not valid UTF-8: %q", name)
	}
	if !strings.HasSuffix(name, ".jpg") {
		t.Fatalf("clipped UTF-8 jpg lost suffix: %q", name)
	}
	stem := strings.TrimSuffix(strings.TrimPrefix(name, prefix), ".jpg")
	if stem == "" {
		t.Fatal("clipped UTF-8 jpg stem is empty")
	}
	if !utf8.ValidString(stem) {
		t.Fatalf("clipped stem split a rune: %q", stem)
	}
	assertSafeStoredName(t, name)
}

func TestClipUploadRetryPrefixStillKeepsJpg(t *testing.T) {
	prefix := "upload-20260101T000000000-123456789-"
	name := clipUploadFileName(prefix, strings.Repeat("n", 300)+".jpg")
	if len(name) > maxUploadFileNameBytes || !strings.HasSuffix(name, ".jpg") {
		t.Fatalf("retry-prefix clip = %q (len %d)", name, len(name))
	}
}

func TestUploadLongUTF8FileNameKeepsJpg(t *testing.T) {
	dir := t.TempDir()
	content := []byte("utf8-long-name-payload")
	longName := strings.Repeat("测", 200) + ".jpg"
	resp, body := uploadToDir(t, dir, longName, content)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("UTF-8 long-name upload status = %d, body %s", resp.StatusCode, body)
	}
	var ur protocolUploadResp
	if err := json.Unmarshal(body, &ur); err != nil {
		t.Fatalf("decode upload resp: %v", err)
	}
	stored := filepath.Base(ur.Path)
	if len(stored) > maxUploadFileNameBytes {
		t.Fatalf("stored UTF-8 name %q exceeds %d bytes", stored, maxUploadFileNameBytes)
	}
	if !strings.HasSuffix(stored, ".jpg") {
		t.Fatalf("stored UTF-8 name %q lost .jpg suffix", stored)
	}
	got, err := os.ReadFile(ur.Path)
	if err != nil {
		t.Fatalf("uploaded file unreadable: %v", err)
	}
	if sha256.Sum256(got) != sha256.Sum256(content) {
		t.Fatalf("UTF-8 long-name upload sha256 mismatch")
	}
	assertSafeStoredName(t, stored)
}

func TestUploadLongJpgDoesNotOverwrite(t *testing.T) {
	dir := t.TempDir()
	first := []byte("first-bytes")
	second := []byte("second-bytes-xx")
	longName := strings.Repeat("n", 300) + ".jpg"
	r1, b1 := uploadToDir(t, dir, longName, first)
	defer r1.Body.Close()
	r2, b2 := uploadToDir(t, dir, longName, second)
	defer r2.Body.Close()
	if r1.StatusCode != http.StatusOK || r2.StatusCode != http.StatusOK {
		t.Fatalf("overwrite probe status %d/%d", r1.StatusCode, r2.StatusCode)
	}
	var u1, u2 protocolUploadResp
	if err := json.Unmarshal(b1, &u1); err != nil {
		t.Fatalf("decode first: %v", err)
	}
	if err := json.Unmarshal(b2, &u2); err != nil {
		t.Fatalf("decode second: %v", err)
	}
	if u1.Path == "" || u1.Path == u2.Path {
		t.Fatalf("long jpg uploads collided: %q %q", u1.Path, u2.Path)
	}
	g1, err := os.ReadFile(u1.Path)
	if err != nil {
		t.Fatalf("read first: %v", err)
	}
	g2, err := os.ReadFile(u2.Path)
	if err != nil {
		t.Fatalf("read second: %v", err)
	}
	if sha256.Sum256(g1) != sha256.Sum256(first) || sha256.Sum256(g2) != sha256.Sum256(second) {
		t.Fatal("long jpg collision overwrote bytes")
	}
}

func assertSafeStoredName(t *testing.T, stored string) {
	t.Helper()
	if stored == "" || stored == "." || stored == ".." {
		t.Fatalf("stored name empty or traversal: %q", stored)
	}
	if strings.Contains(stored, "/") || strings.Contains(stored, "\\") {
		t.Fatalf("stored name has path separator: %q", stored)
	}
	if filepath.Base(stored) != stored {
		t.Fatalf("stored name is not a basename: %q", stored)
	}
}

func uploadToDir(t *testing.T, uploadDir, filename string, content []byte) (*http.Response, []byte) {
	t.Helper()
	srv := NewServer(Options{
		Token:      uploadTestToken,
		UploadDir:  uploadDir,
		Discoverer: scriptedDiscoverer{model: testModel()},
	})
	t.Cleanup(func() { srv.Close() })
	hsrv := httptest.NewServer(srv.Handler())
	t.Cleanup(hsrv.Close)
	resp, body := doUpload(t, hsrv.URL, uploadTestToken, clientShapedMultipart(t, filename, "image/jpeg", content))
	return resp, body
}

func doUpload(t *testing.T, baseURL, token string, body *uploadBody) (*http.Response, []byte) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, baseURL+"/upload", bytes.NewReader(body.raw))
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.Header.Set("Content-Type", body.contentType)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do upload: %v", err)
	}
	got, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	return resp, got
}

type uploadBody struct {
	raw         []byte
	contentType string
}

func clientShapedMultipart(t *testing.T, filename, mime string, data []byte) *uploadBody {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	if err := mw.SetBoundary("AgentMirrorBoundary1"); err != nil {
		t.Fatalf("set boundary: %v", err)
	}
	part, err := mw.CreatePart(textproto.MIMEHeader{
		"Content-Disposition": {`form-data; name="file"; filename="` + filename + `"`},
		"Content-Type":        {mime},
	})
	if err != nil {
		t.Fatalf("create part: %v", err)
	}
	if _, err := part.Write(data); err != nil {
		t.Fatalf("write part: %v", err)
	}
	if err := mw.Close(); err != nil {
		t.Fatalf("close multipart: %v", err)
	}
	return &uploadBody{raw: buf.Bytes(), contentType: mw.FormDataContentType()}
}

func assertDiagnosableStorageFailure(t *testing.T, resp *http.Response, body []byte, dir, wantCode string) {
	t.Helper()
	if resp.StatusCode == http.StatusInternalServerError {
		t.Fatalf("storage branch mapped to 500 (code %s): %s", wantCode, body)
	}
	if resp.StatusCode != http.StatusInsufficientStorage && resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("storage branch status = %d, want 400 or 507, body %s", resp.StatusCode, body)
	}
	var rejection uploadError
	if err := json.Unmarshal(body, &rejection); err != nil {
		t.Fatalf("decode rejection: %v body %s", err, body)
	}
	if rejection.Code != wantCode {
		t.Fatalf("rejection code = %q, want %q (body %s)", rejection.Code, wantCode, body)
	}
	if rejection.Reason == "" {
		t.Fatalf("rejection reason empty for %s", wantCode)
	}
	assertNoLeak(t, body, dir, uploadTestToken)
}

func assertUploadJSONCode(t *testing.T, body []byte, want string) {
	t.Helper()
	var rejection uploadError
	if err := json.Unmarshal(body, &rejection); err != nil {
		t.Fatalf("decode rejection: %v body %s", err, body)
	}
	if rejection.Code != want || rejection.Reason == "" {
		t.Fatalf("rejection = %+v, want code %s with reason", rejection, want)
	}
}

func assertNoLeak(t *testing.T, body []byte, secrets ...string) {
	t.Helper()
	for _, secret := range secrets {
		if secret != "" && bytes.Contains(body, []byte(secret)) {
			t.Fatal("upload error response leaked a path or token")
		}
	}
}

func homeHint(t *testing.T) string {
	t.Helper()
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return home
}
