package discovery

import (
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"testing"
)

// TestParsePaneLineValid checks that a well-formed paneFormat line maps to the
// expected Pane fields, including the trailing window name.
func TestParsePaneLineValid(t *testing.T) {
	line := "alpha|2|%3|/workspaces/eng|zsh|4242|✳ eng-shell|120x30|wiki-r5-acceptance-tester"
	p, ok := parsePaneLine(line)
	if !ok {
		t.Fatalf("parsePaneLine(%q) unexpectedly rejected a valid line", line)
	}
	want := Pane{
		Session:     "alpha",
		WindowIndex: 2,
		WindowName:  "wiki-r5-acceptance-tester",
		PaneTitle:   "✳ eng-shell",
		PaneID:      "%3",
		CWD:         "/workspaces/eng",
		Command:     "zsh",
		PanePID:     4242,
		Width:       120,
		Height:      30,
	}
	if !reflect.DeepEqual(p, want) {
		t.Fatalf("parsePaneLine(%q) = %+v, want %+v", line, p, want)
	}
}

// TestParsePaneLineMalformed checks that malformed lines are rejected with
// ok=false (and never panic), so the scan can skip them.
func TestParsePaneLineMalformed(t *testing.T) {
	cases := []string{
		"",                                       // empty
		"a|0|%0|cwd|cmd",                         // too few fields (5)
		"a|0|%0|cwd|cmd|80x24",                   // too few fields (6)
		"a|0|%0|cwd|cmd|7|80x24",                 // too few fields (8, missing title)
		"a|0|%0|cwd|cmd|7|title|80x24|win|extra", // too many fields (10)
		"a|notanint|%0|cwd|cmd|7|title|80x24|win", // non-integer window index
		"a|0|%0|cwd|cmd|7|title|80|win",           // size missing "x"
		"a|0|%0|cwd|cmd|7|title|ax24|win",         // non-integer width
		"a|0|%0|cwd|cmd|7|title|80xb|win",         // non-integer height
	}
	for _, line := range cases {
		if p, ok := parsePaneLine(line); ok {
			t.Errorf("parsePaneLine(%q) = %+v, ok=true; want rejected", line, p)
		}
	}
}

// TestBuildModelAggregatesByCWD checks the core two-level aggregation
// (requirement 002): panes from different servers/sessions sharing a CWD land
// in the same workspace, and distinct CWDs become separate workspaces.
func TestBuildModelAggregatesByCWD(t *testing.T) {
	panes := []Pane{
		{Session: "alpha", CWD: "/ws/a", PaneID: "%0"},
		{Session: "beta", CWD: "/ws/a", PaneID: "%0"}, // same CWD, other session
		{Session: "gamma", CWD: "/ws/b", PaneID: "%0"},
	}
	m := buildModel(panes)

	if len(m.Workspaces) != 2 {
		t.Fatalf("buildModel produced %d workspaces, want 2", len(m.Workspaces))
	}
	wa := m.Workspace("/ws/a")
	if wa == nil || wa.Count() != 2 {
		t.Fatalf("workspace /ws/a missing or wrong count: %+v", wa)
	}
	wb := m.Workspace("/ws/b")
	if wb == nil || wb.Count() != 1 {
		t.Fatalf("workspace /ws/b missing or wrong count: %+v", wb)
	}
}

// TestBuildModelSortsDeterministically checks that workspaces are ordered by
// CWD and panes by session/window/pane id, so two scans produce identical
// output regardless of input order.
func TestBuildModelSortsDeterministically(t *testing.T) {
	panes := []Pane{
		{Session: "zeta", CWD: "/ws/z", PaneID: "%2"},
		{Session: "alpha", CWD: "/ws/a", PaneID: "%0"},
		{Session: "alpha", CWD: "/ws/a", PaneID: "%1"},
		{Session: "beta", CWD: "/ws/a", PaneID: "%0"},
	}
	m := buildModel(panes)

	if m.Workspaces[0].CWD != "/ws/a" || m.Workspaces[1].CWD != "/ws/z" {
		t.Fatalf("workspace order = [%q, %q], want [/ws/a /ws/z]",
			m.Workspaces[0].CWD, m.Workspaces[1].CWD)
	}
	got := []string{m.Workspaces[0].Panes[0].Session, m.Workspaces[0].Panes[1].Session, m.Workspaces[0].Panes[2].Session}
	want := []string{"alpha", "alpha", "beta"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("pane order = %v, want %v", got, want)
	}
}

// TestDefaultSocketDirsNoDuplicates checks the invariant that no two returned
// socket dirs resolve to the same directory (on macOS /tmp and /private/tmp
// are the same), preventing double-counted panes.
func TestDefaultSocketDirsNoDuplicates(t *testing.T) {
	dirs := DefaultSocketDirs()
	if len(dirs) == 0 {
		t.Fatal("DefaultSocketDirs returned no directories")
	}
	seen := make(map[string]bool)
	for _, d := range dirs {
		resolved, err := filepath.EvalSymlinks(d)
		if err != nil {
			resolved = filepath.Clean(d)
		}
		if seen[resolved] {
			t.Errorf("duplicate resolved socket dir %q (from %q)", resolved, d)
		}
		seen[resolved] = true
	}
}

// TestDefaultSocketDirsHonorsTMUXTMPDIR checks that when TMUX_TMPDIR is set,
// its tmux-<uid> tree leads the returned directory list.
func TestDefaultSocketDirsHonorsTMUXTMPDIR(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("TMUX_TMPDIR", tmp)

	dirs := DefaultSocketDirs()
	want := filepath.Join(tmp, "tmux-"+strconv.Itoa(os.Getuid()))
	found := false
	for _, d := range dirs {
		if d == want {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("DefaultSocketDirs() = %v, missing TMUX_TMPDIR tree %q", dirs, want)
	}
}
