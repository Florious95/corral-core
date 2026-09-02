package nodeprobe

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

type fakeSampler struct {
	mu      sync.Mutex
	reports map[string]Report
	err     error
	calls   map[string]int
}

func (f *fakeSampler) Sample(_ context.Context, socket string) (Report, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.calls == nil {
		f.calls = map[string]int{}
	}
	f.calls[socket]++
	return f.reports[socket], f.err
}
func model(panes ...discovery.Pane) *discovery.Model {
	return &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/w", Panes: panes}}}
}
func pane(socket, session, id string, wi int) discovery.Pane {
	return discovery.Pane{Socket: socket, Session: session, PaneID: id, WindowIndex: wi, CWD: "/w"}
}
func report(socket string, nodes ...Node) Report {
	return Report{SchemaVersion: 1, Socket: socket, Nodes: nodes}
}
func node(session, id string, wi int, provider, activity, health string) Node {
	return Node{Session: session, PaneID: id, WindowIndex: wi, Provider: provider, State: activity, Activity: activity, Health: health}
}

func TestSampleModelStructuralJoinAndOncePerSocket(t *testing.T) {
	fs := &fakeSampler{reports: map[string]Report{
		"/s1": report("/s1", node("a", "%0", 2, "pi", "working", "normal")),
		"/s2": report("/s2", node("b", "%1", 0, "unknown", "unknown", "unknown")),
	}}
	got, err := SampleModel(context.Background(), model(pane("/s1", "a", "%0", 2), pane("/s1", "missing", "%9", 0), pane("/s2", "b", "%1", 0)), fs)
	if err != nil {
		t.Fatal(err)
	}
	if fs.calls["/s1"] != 1 || fs.calls["/s2"] != 1 {
		t.Fatalf("calls=%v", fs.calls)
	}
	if got["/s1\x1f%0"].Activity != "working" || got["/s2\x1f%1"].Provider != "unknown" {
		t.Fatalf("observations=%+v", got)
	}
	if got["/s1\x1f%9"] != Unknown() {
		t.Fatalf("zero match=%+v", got["/s1\x1f%9"])
	}
}

func TestSampleModelDuplicateIsUnknown(t *testing.T) {
	n := node("a", "%0", 0, "pi", "idle", "normal")
	fs := &fakeSampler{reports: map[string]Report{"/s": report("/s", n, n)}}
	got, err := SampleModel(context.Background(), model(pane("/s", "a", "%0", 0)), fs)
	if err != nil {
		t.Fatal(err)
	}
	if got["/s\x1f%0"] != Unknown() {
		t.Fatalf("duplicate selected row: %+v", got)
	}
}

func TestSampleModelNoSocketsDoesNotRun(t *testing.T) {
	fs := &fakeSampler{}
	got, err := SampleModel(context.Background(), &discovery.Model{}, fs)
	if err != nil || len(got) != 0 || len(fs.calls) != 0 {
		t.Fatalf("got=%v err=%v calls=%v", got, err, fs.calls)
	}
}

func TestRunnerRejectsErrorMalformedVersionNonzeroAndTimeout(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("shell fixture")
	}
	cases := []struct {
		name, body string
		timeout    time.Duration
	}{
		{"error", `printf '%s' '{"schema_version":1,"socket":"/s","nodes":[],"error":{"kind":"tmux_inventory","message":"gone"}}'`, time.Second},
		{"malformed", `printf 'not-json'`, time.Second},
		{"version", `printf '%s' '{"schema_version":2,"socket":"/s","nodes":[]}'`, time.Second},
		{"nonzero", `exit 7`, time.Second},
		{"timeout", `sleep 1`, 10 * time.Millisecond},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			d := t.TempDir()
			bin := filepath.Join(d, "nodeprobe")
			if err := os.WriteFile(bin, []byte("#!/bin/sh\n"+tc.body+"\n"), 0700); err != nil {
				t.Fatal(err)
			}
			r := &Runner{capability: Capability{Binary: bin, Titles: "/titles", Providers: "/providers"}, timeout: tc.timeout}
			if _, err := r.Sample(context.Background(), "/s"); err == nil {
				t.Fatal("expected error")
			}
		})
	}
}

func TestRunnerValidAndClosedAxes(t *testing.T) {
	d := t.TempDir()
	bin := filepath.Join(d, "nodeprobe")
	body := `#!/bin/sh
printf '%s' '{"schema_version":1,"socket":"/s","sampled_at":"x","nodes":[{"socket":"/s","workspace_path":"/tmp/project","project_name":"project","session":"a","window_index":0,"window_name":"win","pane_id":"%0","name":"win","provider":"pi","state":"idle","activity":"idle","session_name":null,"health":"normal","background_tasks":"unknown","evidence":{"method":"pi_activity_channel","detail":"x","comms":[]}}]}'
`
	if err := os.WriteFile(bin, []byte(body), 0700); err != nil {
		t.Fatal(err)
	}
	r := &Runner{capability: Capability{Binary: bin, Titles: "/titles", Providers: "/providers"}, timeout: time.Second}
	got, err := r.Sample(context.Background(), "/s")
	if err != nil {
		t.Fatal(err)
	}
	n := got.Nodes[0]
	if n.Activity != "idle" || n.SessionName != nil || n.WorkspacePath != "/tmp/project" || n.ProjectName != "project" || n.Socket != "/s" {
		t.Fatalf("got=%+v", n)
	}
}

func TestAcceptedUniquePiMissingChannelHealthIsNormal(t *testing.T) {
	// 863c pi_process_health: unique Pi + missing/unset channel => health=normal.
	// Consumers must not remap this to unknown.
	n := node("a", "%0", 0, "pi", "unknown", "normal")
	if err := validateNode(n); err != nil {
		t.Fatal(err)
	}
}
