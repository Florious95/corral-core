package api

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

// Real tmux is an additional oracle, never a substitute for the request argv /
// byte-count test. The caller supplies a short hosted-only scratch root; missing
// tmux/root is a failure, not a skip. No user shell or discovered pane is used.
func TestPerf14RealStaticPagesAndActualGeometry(t *testing.T) {
	root := os.Getenv("PERF14_REAL_ROOT")
	if root == "" || !filepath.IsAbs(root) {
		t.Fatal("PERF14_REAL_ROOT must name an absolute isolated hosted scratch root")
	}
	if err := os.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	dir, err := os.MkdirTemp(root, "r")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	socket := filepath.Join(dir, "s")
	if len(socket) > 95 {
		t.Fatalf("private socket too long: %d (no fallback permitted)", len(socket))
	}
	tmux, err := exec.LookPath("tmux")
	if err != nil {
		t.Fatalf("required real tmux unavailable, not skip: %v", err)
	}
	python, err := exec.LookPath("python3")
	if err != nil {
		t.Fatal(err)
	}
	env := make([]string, 0)
	for _, item := range os.Environ() {
		if strings.HasPrefix(item, "TMUX=") || strings.HasPrefix(item, "TMUX_TMPDIR=") {
			continue
		}
		env = append(env, item)
	}
	run := func(args ...string) (string, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, tmux, append([]string{"-S", socket, "-f", "/dev/null"}, args...)...)
		cmd.Env = env
		out, err := cmd.CombinedOutput()
		return strings.TrimSpace(string(out)), err
	}
	source := filepath.Join(dir, "source.py")
	if err := os.WriteFile(source, []byte(`import pathlib,sys,time
for i in range(60):
 sys.stdout.write('ROW%03d\n'%i)
sys.stdout.flush()
while not pathlib.Path(__file__).with_name('alternate').exists():
 time.sleep(0.05)
sys.stdout.write('\033[?1049h\033[2J\033[HALT000\nALT001\n')
sys.stdout.flush()
time.sleep(120)
`), 0600); err != nil {
		t.Fatal(err)
	}
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }
	if out, err := run("new-session", "-d", "-x", "40", "-y", "10", "-s", "perf14", "-c", dir, "exec "+quote(python)+" -u "+quote(source)); err != nil {
		t.Fatalf("private fixture start: %v %s", err, out)
	}
	actual, err := run("list-sessions", "-F", "#{socket_path}")
	if err != nil || actual != socket {
		t.Fatalf("private socket identity not established: %q err=%v", actual, err)
	}
	t.Cleanup(func() {
		if out, err := run("kill-server"); err != nil {
			t.Errorf("private fixture cleanup: %v %s", err, out)
		}
		if out, err := run("list-sessions"); err == nil {
			t.Errorf("private server survived cleanup: %s", out)
		}
	})
	paneID, err := run("display-message", "-p", "-t", "perf14:0.0", "#{pane_id}")
	if err != nil {
		t.Fatal(err)
	}
	// 60 newline-terminated lines on 10 rows => 51 history lines, nine text
	// screen rows and one blank cursor row. No capture-derived expected content.
	ready := false
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		out, err := run("display-message", "-p", "-t", paneID, "#{history_size},#{pane_height},#{cursor_y}")
		if err != nil {
			t.Fatal(err)
		}
		if out == "51,10,9" {
			ready = true
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !ready {
		t.Fatal("static source geometry/history boundary not reached; apparatus failure")
	}
	pane := discovery.Pane{Socket: socket, PaneID: paneID, Height: 40, Width: 40} // deliberately stale catalog
	ref := sessionRef(pane)
	oldest := []string{"ROW000", "ROW001", "ROW002", "ROW003"}
	perf14AssertRefReply(t, perf14PaneRequest(t, pane, -100, 4), ref, -51, oldest)
	perf14AssertRefReply(t, perf14PaneRequest(t, pane, -3, 3), ref, -3, []string{"ROW048", "ROW049", "ROW050"})
	perf14AssertRefReply(t, perf14PaneRequest(t, pane, -1, 3), ref, -1, []string{"ROW050", "ROW051", "ROW052"})
	perf14AssertRefReply(t, perf14PaneRequest(t, pane, 8, 2), ref, 8, []string{"ROW059", ""})
	perf14AssertRefReply(t, perf14PaneRequest(t, pane, 30, 3), ref, 7, []string{"ROW058", "ROW059", ""})
	for i := 0; i < 3; i++ { // repeat one immutable page without output/resize mutations
		perf14AssertRefReply(t, perf14PaneRequest(t, pane, -3, 3), ref, -3, []string{"ROW048", "ROW049", "ROW050"})
	}
	t.Logf("real static oracle history=51 actual_height=10 catalog_height=40 ref=%q", ref)
	// Switch only this synthetic source to the alternate screen. This regression
	// checks visible-row paging; it does not invent an alternate-history policy.
	if err := os.WriteFile(filepath.Join(dir, "alternate"), []byte("go"), 0600); err != nil {
		t.Fatal(err)
	}
	ready = false
	deadline = time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		out, err := run("display-message", "-p", "-t", paneID, "#{alternate_on},#{cursor_y}")
		if err != nil {
			t.Fatal(err)
		}
		if out == "1,2" {
			ready = true
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !ready {
		t.Fatal("alternate-screen static boundary not reached; apparatus failure")
	}
	perf14AssertRefReply(t, perf14PaneRequest(t, pane, 0, 2), ref, 0, []string{"ALT000", "ALT001"})
}
