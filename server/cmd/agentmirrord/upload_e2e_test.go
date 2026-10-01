package main

// upload_e2e_test.go starts a real temporary agentmirrord (isolated listen /
// state / HOME, never :9900) and checks POST /upload 200 + content hash,
// single-instance refusal, and zero leftover process/port.

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"net/textproto"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

const e2eToken = "upload-e2e-token"

var (
	daemonBuildOnce sync.Once
	daemonBinPath   string
	daemonBuildErr  error
	daemonBinDir    string
)

func TestMain(m *testing.M) {
	code := m.Run()
	if daemonBinDir != "" {
		_ = os.RemoveAll(daemonBinDir)
	}
	os.Exit(code)
}

func TestUploadDaemonE2E(t *testing.T) {
	bin := builtDaemon(t)
	port := freeLocalPort(t)
	state := t.TempDir()
	uploadDir := t.TempDir()
	home := t.TempDir()

	cmd, _ := startIsolatedDaemon(t, bin, []string{
		"-listen", "127.0.0.1:" + port,
		"-host", "127.0.0.1",
		"-token", e2eToken,
		"-state-dir", state,
		"-upload-dir", uploadDir,
		"-list-interval", "1h",
		"-log-level", "error",
	}, home)
	waitForListen(t, "127.0.0.1:"+port)

	content := []byte("\xff\xd8\xff\xe0e2e-jpeg")
	resp, body := postE2EUpload(t, "http://127.0.0.1:"+port, e2eToken, "photo.jpg", content)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("daemon upload status = %d", resp.StatusCode)
	}
	var parsed struct {
		Path string `json:"path"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil || parsed.Path == "" {
		t.Fatalf("daemon upload JSON missing path")
	}
	got, err := os.ReadFile(parsed.Path)
	if err != nil {
		t.Fatalf("read uploaded file: %v", err)
	}
	if sha256.Sum256(got) != sha256.Sum256(content) {
		t.Fatalf("daemon upload sha256 mismatch")
	}
	if bytes.Contains(body, []byte(e2eToken)) {
		t.Fatal("daemon success JSON leaked token")
	}

	second := exec.Command(bin,
		"-listen", "127.0.0.1:"+freeLocalPort(t),
		"-host", "127.0.0.1",
		"-token", e2eToken,
		"-state-dir", state,
		"-upload-dir", uploadDir,
		"-list-interval", "1h",
		"-log-level", "error",
	)
	second.Env = isolatedDaemonEnv(home)
	second.Dir = t.TempDir()
	if err := second.Start(); err != nil {
		t.Fatalf("start second instance: %v", err)
	}
	done := make(chan error, 1)
	go func() { done <- second.Wait() }()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("second instance exited 0; want single-instance refusal")
		}
	case <-time.After(8 * time.Second):
		_ = second.Process.Kill()
		<-done
		t.Fatal("second instance did not exit (single-instance guard failed)")
	}

	stopDaemon(t, cmd)
	assertPortClosed(t, "127.0.0.1:"+port)
}

func TestUploadDaemonDefaultDirE2E(t *testing.T) {
	bin := builtDaemon(t)
	port := freeLocalPort(t)
	state := t.TempDir()
	home := t.TempDir()

	cmd, _ := startIsolatedDaemon(t, bin, []string{
		"-listen", "127.0.0.1:" + port,
		"-host", "127.0.0.1",
		"-token", e2eToken,
		"-state-dir", state,
		"-list-interval", "1h",
		"-log-level", "error",
	}, home)
	waitForListen(t, "127.0.0.1:"+port)

	content := []byte("default-dir-bytes")
	resp, body := postE2EUpload(t, "http://127.0.0.1:"+port, e2eToken, "photo.jpg", content)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("default-dir daemon upload status = %d", resp.StatusCode)
	}
	var parsed struct {
		Path string `json:"path"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		t.Fatalf("decode path: %v", err)
	}
	if !strings.Contains(filepath.ToSlash(parsed.Path), "/Downloads/agentmirror-uploads/") {
		t.Fatalf("default daemon path is not last-good Downloads dir")
	}
	got, err := os.ReadFile(parsed.Path)
	if err != nil {
		t.Fatalf("read uploaded file: %v", err)
	}
	if sha256.Sum256(got) != sha256.Sum256(content) {
		t.Fatalf("default-dir daemon sha256 mismatch")
	}
	stopDaemon(t, cmd)
	assertPortClosed(t, "127.0.0.1:"+port)
}

func TestUploadDaemonLongJpgE2E(t *testing.T) {
	bin := builtDaemon(t)
	port := freeLocalPort(t)
	state := t.TempDir()
	uploadDir := t.TempDir()
	home := t.TempDir()

	cmd, _ := startIsolatedDaemon(t, bin, []string{
		"-listen", "127.0.0.1:" + port,
		"-host", "127.0.0.1",
		"-token", e2eToken,
		"-state-dir", state,
		"-upload-dir", uploadDir,
		"-list-interval", "1h",
		"-log-level", "error",
	}, home)
	waitForListen(t, "127.0.0.1:"+port)

	cases := []struct {
		name string
		file string
		body []byte
	}{
		{"ascii", strings.Repeat("n", 300) + ".jpg", []byte("ascii-long-jpg")},
		{"utf8", strings.Repeat("测", 200) + ".jpg", []byte("utf8-long-jpg")},
	}
	seen := map[string]struct{}{}
	for _, tc := range cases {
		resp, body := postE2EUpload(t, "http://127.0.0.1:"+port, e2eToken, tc.file, tc.body)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("%s long jpg status = %d", tc.name, resp.StatusCode)
		}
		var parsed struct {
			Path string `json:"path"`
		}
		if err := json.Unmarshal(body, &parsed); err != nil || parsed.Path == "" {
			t.Fatalf("%s long jpg missing path", tc.name)
		}
		stored := filepath.Base(parsed.Path)
		if len(stored) > 255 {
			t.Fatalf("%s stored name too long: %q", tc.name, stored)
		}
		if !strings.HasSuffix(stored, ".jpg") {
			t.Fatalf("%s stored name lost .jpg: %q", tc.name, stored)
		}
		if stored == "." || stored == ".." || strings.ContainsAny(stored, `/\`) {
			t.Fatalf("%s stored name unsafe: %q", tc.name, stored)
		}
		if _, dup := seen[parsed.Path]; dup {
			t.Fatalf("%s collided with earlier upload", tc.name)
		}
		seen[parsed.Path] = struct{}{}
		got, err := os.ReadFile(parsed.Path)
		if err != nil {
			t.Fatalf("%s read: %v", tc.name, err)
		}
		if sha256.Sum256(got) != sha256.Sum256(tc.body) {
			t.Fatalf("%s hash mismatch", tc.name)
		}
	}

	stopDaemon(t, cmd)
	assertPortClosed(t, "127.0.0.1:"+port)
}

func TestUploadDaemonWriteDenied507(t *testing.T) {
	bin := builtDaemon(t)
	port := freeLocalPort(t)
	state := t.TempDir()
	uploadDir := t.TempDir()
	home := t.TempDir()
	if err := os.Chmod(uploadDir, 0o555); err != nil {
		t.Fatalf("chmod: %v", err)
	}
	t.Cleanup(func() { _ = os.Chmod(uploadDir, 0o700) })

	cmd, _ := startIsolatedDaemon(t, bin, []string{
		"-listen", "127.0.0.1:" + port,
		"-host", "127.0.0.1",
		"-token", e2eToken,
		"-state-dir", state,
		"-upload-dir", uploadDir,
		"-list-interval", "1h",
		"-log-level", "error",
	}, home)
	waitForListen(t, "127.0.0.1:"+port)
	resp, body := postE2EUpload(t, "http://127.0.0.1:"+port, e2eToken, "photo.jpg", []byte("daemon-507"))
	if resp.StatusCode != 507 {
		t.Fatalf("unwritable daemon upload status = %d body_len=%d", resp.StatusCode, len(body))
	}
	if bytes.Contains(body, []byte(e2eToken)) {
		t.Fatal("daemon 507 leaked token")
	}
	stopDaemon(t, cmd)
	assertPortClosed(t, "127.0.0.1:"+port)
}

func builtDaemon(t *testing.T) string {
	t.Helper()
	daemonBuildOnce.Do(func() {
		dir, err := os.MkdirTemp("", "agentmirrord-upload-e2e-")
		if err != nil {
			daemonBuildErr = err
			return
		}
		daemonBinDir = dir
		daemonBinPath = filepath.Join(dir, "agentmirrord")
		cmd := exec.Command("go", "build", "-o", daemonBinPath, "./cmd/agentmirrord")
		cmd.Dir = serverDir()
		out, err := cmd.CombinedOutput()
		if err != nil {
			daemonBuildErr = fmt.Errorf("go build: %w", err)
			_ = os.WriteFile(filepath.Join(dir, "build.log"), out, 0o600)
		}
	})
	if daemonBuildErr != nil {
		t.Fatalf("build agentmirrord: %v", daemonBuildErr)
	}
	return daemonBinPath
}

func serverDir() string {
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		return "."
	}
	return filepath.Clean(filepath.Join(filepath.Dir(file), "..", ".."))
}

func startIsolatedDaemon(t *testing.T, bin string, args []string, home string) (*exec.Cmd, *bytes.Buffer) {
	t.Helper()
	cmd := exec.Command(bin, args...)
	cmd.Env = isolatedDaemonEnv(home)
	cmd.Dir = t.TempDir()
	var sink bytes.Buffer
	cmd.Stdout = &sink
	cmd.Stderr = &sink
	if err := cmd.Start(); err != nil {
		t.Fatalf("start daemon: %v", err)
	}
	t.Cleanup(func() { stopDaemon(t, cmd) })
	return cmd, &sink
}

func isolatedDaemonEnv(home string) []string {
	out := make([]string, 0, 16)
	for _, e := range os.Environ() {
		key, _, found := strings.Cut(e, "=")
		if !found {
			continue
		}
		uk := strings.ToUpper(key)
		if uk == "TS_AUTHKEY" || uk == "TS_CONTROL_URL" || uk == "XDG_CONFIG_HOME" || strings.HasPrefix(uk, "AGENTMIRROR_") {
			continue
		}
		out = append(out, e)
	}
	return append(out, "HOME="+home)
}

func stopDaemon(t *testing.T, cmd *exec.Cmd) {
	t.Helper()
	if cmd == nil || cmd.Process == nil {
		return
	}
	if cmd.ProcessState != nil && cmd.ProcessState.Exited() {
		return
	}
	_ = cmd.Process.Signal(syscall.SIGTERM)
	done := make(chan struct{})
	go func() {
		_ = cmd.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		_ = cmd.Process.Kill()
		<-done
	}
}

func waitForListen(t *testing.T, addr string) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if err == nil {
			_ = c.Close()
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("daemon did not listen on %s", addr)
}

func assertPortClosed(t *testing.T, addr string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if err != nil {
			return
		}
		_ = c.Close()
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("port %s still accepting after daemon stop", addr)
}

func freeLocalPort(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()
	if port == 9900 {
		t.Fatal("refusing production port 9900")
	}
	return strconv.Itoa(port)
}

func postE2EUpload(t *testing.T, base, token, filename string, data []byte) (*http.Response, []byte) {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	if err := mw.SetBoundary("AgentMirrorBoundary1"); err != nil {
		t.Fatalf("boundary: %v", err)
	}
	part, err := mw.CreatePart(textproto.MIMEHeader{
		"Content-Disposition": {`form-data; name="file"; filename="` + filename + `"`},
		"Content-Type":        {"image/jpeg"},
	})
	if err != nil {
		t.Fatalf("part: %v", err)
	}
	if _, err := part.Write(data); err != nil {
		t.Fatalf("write part: %v", err)
	}
	if err := mw.Close(); err != nil {
		t.Fatalf("close multipart: %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(base, "/")+"/upload", bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("upload: %v", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	return resp, body
}
