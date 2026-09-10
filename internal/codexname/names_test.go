package codexname

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"
)

const idA = "0195a000-1111-7000-8000-111111111111"
const idB = "0195a000-2222-7000-8000-222222222222"

func write(t *testing.T, path, data string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(data), 0600); err != nil {
		t.Fatal(err)
	}
}

func indexRow(id, name string) string {
	data, _ := json.Marshal(map[string]string{"id": id, "thread_name": name, "updated_at": "2026-09-10T00:00:00Z"})
	return string(data) + "\n"
}

func rollout(t *testing.T, home, id, source string) string {
	t.Helper()
	path := filepath.Join(home, "sessions", "2026", "09", "10", "rollout-2026-09-10T00-00-00-"+id+".jsonl")
	write(t, path, fmt.Sprintf(`{"type":"session_meta","payload":{"id":%q,"source":%s}}`+"\nNOT_JSON_TRANSCRIPT_MUST_NOT_BE_PARSED\n", id, source))
	return path
}

func TestIndexNewestRenameWinsWithoutTitleOrClockChange(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session_index.jsonl")
	write(t, path, indexRow(idA, "最初")+indexRow(idB, "另一个会话")+indexRow(idA, "审查 | 最新名字 🚀"))
	got := indexNames(context.Background(), path, map[string]bool{idA: true, idB: true})
	want := map[string]string{idA: "审查 | 最新名字 🚀", idB: "另一个会话"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v want %#v", got, want)
	}
}

func TestIndexPartialAppendThenCompletion(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session_index.jsonl")
	first := indexRow(idA, "旧名称")
	latest := indexRow(idA, "新名称")
	write(t, path, first+latest[:len(latest)/2])
	if got := indexNames(context.Background(), path, map[string]bool{idA: true})[idA]; got != "旧名称" {
		t.Fatal(got)
	}
	write(t, path, first+latest)
	if got := indexNames(context.Background(), path, map[string]bool{idA: true})[idA]; got != "新名称" {
		t.Fatal(got)
	}
}

func TestIndexReplacementTruncationAndMissingNeverCacheOldName(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session_index.jsonl")
	for _, name := range []string{"AAAA", "BBBB"} {
		write(t, path, indexRow(idA, name))
		if got := indexNames(context.Background(), path, map[string]bool{idA: true})[idA]; got != name {
			t.Fatal(got)
		}
	}
	other := path + ".tmp"
	write(t, other, indexRow(idA, "CCCC"))
	if err := os.Rename(other, path); err != nil {
		t.Fatal(err)
	}
	if got := indexNames(context.Background(), path, map[string]bool{idA: true})[idA]; got != "CCCC" {
		t.Fatal(got)
	}
	write(t, path, "")
	if got := indexNames(context.Background(), path, map[string]bool{idA: true}); len(got) != 0 {
		t.Fatal(got)
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if got := indexNames(context.Background(), path, map[string]bool{idA: true}); len(got) != 0 {
		t.Fatal(got)
	}
}

func TestIndexRecordsAcrossChunkBoundaries(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session_index.jsonl")
	name := strings.Repeat("名称", indexChunkBytes/3)
	write(t, path, indexRow(idA, "old")+indexRow(idA, name)+strings.Repeat("bad-json\n", indexChunkBytes/4)+indexRow(idB, "other"))
	got := indexNames(context.Background(), path, map[string]bool{idA: true, idB: true})
	if got[idA] != name || got[idB] != "other" {
		t.Fatalf("names lost across chunks: A len=%d B=%q", len(got[idA]), got[idB])
	}
}

func TestIndexInvalidAndEmptyLatestRecord(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session_index.jsonl")
	write(t, path, indexRow(idA, "old")+"{bad}\n"+indexRow(idA, ""))
	got := indexNames(context.Background(), path, map[string]bool{idA: true})
	if name, ok := got[idA]; !ok || name != "" {
		t.Fatalf("must not revive old name: %#v", got)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if got := indexNames(ctx, path, map[string]bool{idA: true}); len(got) != 0 {
		t.Fatal(got)
	}
}

func TestInteractiveThreadIgnoresSubagentsAndDeduplicatesDescriptors(t *testing.T) {
	home := t.TempDir()
	main := rollout(t, home, idA, `"cli"`)
	child := rollout(t, home, idB, `{"subagent":{"thread_spawn":{"parent_thread_id":"`+idA+`"}}}`)
	got := interactiveThread(context.Background(), []string{child, main, main, "/not/a/rollout"})
	if got != (thread{home, idA}) {
		t.Fatalf("got %#v", got)
	}
}

func TestInteractiveThreadUsesStableMetadataIDNotRolloutID(t *testing.T) {
	home := t.TempDir()
	path := rollout(t, home, idB, `"cli"`)
	write(t, path, fmt.Sprintf(`{"type":"session_meta","payload":{"id":%q,"source":"cli"}}`+"\n", idA))
	if got := interactiveThread(context.Background(), []string{path}); got.ID != idA {
		t.Fatal(got)
	}
}

func TestInteractiveThreadRejectsAmbiguousOrInvalidMetadata(t *testing.T) {
	home := t.TempDir()
	a := rollout(t, home, idA, `"cli"`)
	b := rollout(t, home, idB, `"cli"`)
	if got := interactiveThread(context.Background(), []string{a, b}); got.ID != "" {
		t.Fatal(got)
	}
	for _, data := range []string{`{"type":"event_msg"}`, `{"type":"session_meta","payload":{"id":"invalid","source":"cli"}}`, strings.Repeat("x", maxRecordBytes+1)} {
		write(t, b, data)
		if got := interactiveThread(context.Background(), []string{a, b}); got.ID != "" {
			t.Fatal(got)
		}
	}
}

func TestMetadataDoesNotFollowSymlinksOrUnrelatedPaths(t *testing.T) {
	dir := t.TempDir()
	original := filepath.Join(dir, "metadata")
	link := filepath.Join(dir, "session_index.jsonl")
	write(t, original, indexRow(idA, "not allowed"))
	if err := os.Symlink(original, link); err != nil {
		t.Fatal(err)
	}
	if got := indexNames(context.Background(), link, map[string]bool{idA: true}); len(got) != 0 {
		t.Fatal(got)
	}
	for _, path := range []string{"relative.jsonl", filepath.Join(dir, "auth.json"), filepath.Join(dir, "rollout-2026-09-10T00-00-00-"+idA+".jsonl")} {
		if got := threadPath(path); got.ID != "" {
			t.Fatal(got)
		}
	}
}

func TestCodexProcessSelectionIsPaneScopedAndForegroundSafe(t *testing.T) {
	cases := []struct {
		name, ps, command string
		want              int
	}{
		{"wrapper", "10 1 Ss /bin/bash\n11 10 S /bin/node\n12 11 S+ /opt/codex\n99 1 S+ /opt/codex", "codex", 12},
		{"background leftover", "10 1 Ss+ /bin/zsh\n11 10 S /opt/codex", "zsh", 0},
		{"shell owns foreground but pane still codex", "10 1 Ss+ /bin/zsh\n11 10 S /opt/codex", "codex", 11},
		{"ambiguous", "10 1 Ss /bin/bash\n11 10 S+ /opt/codex\n12 10 S+ /opt/codex", "codex", 0},
		{"foreground wins", "10 1 Ss /bin/bash\n11 10 S /opt/codex\n12 10 S+ /opt/codex", "codex", 12},
		{"stopped", "10 1 Ss /bin/bash\n11 10 T+ /opt/codex", "codex", 0},
		{"root is codex", "10 1 S+ /opt/codex", "codex", 10},
		{"cycle", "10 11 S /bin/bash\n11 10 S /bin/bash", "codex", 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := codexPID(parseProcesses([]byte(tc.ps)), Target{RootPID: 10, Command: tc.command}); got != tc.want {
				t.Fatalf("got %d want %d", got, tc.want)
			}
		})
	}
}

func TestProcessGraphBound(t *testing.T) {
	var ps strings.Builder
	for i := 1; i <= maxProcesses+10; i++ {
		fmt.Fprintf(&ps, "%d %d S /bin/sh\n", i, i-1)
	}
	if got := codexPID(parseProcesses([]byte(ps.String())), Target{RootPID: 1, Command: "codex"}); got != 0 {
		t.Fatal(got)
	}
}

func TestLsofFieldsArePIDScopedAndPreserveUnicodePaths(t *testing.T) {
	data := []byte("p12\x00\nfcwd\x00n/ignore/cwd\x00\nf3\x00n/a b/中文 | 测试.jsonl\x00\np22\x00\nf4\x00n/another.jsonl\x00\nftxt\x00n/ignore/executable\x00\n")
	want := map[int][]string{12: {"/a b/中文 | 测试.jsonl"}, 22: {"/another.jsonl"}}
	if got := parseLsof(data); !reflect.DeepEqual(got, want) {
		t.Fatalf("%#v", got)
	}
}

func TestResolveRefreshesIdleRenameAndResumeForSamePane(t *testing.T) {
	home := t.TempDir()
	a := rollout(t, home, idA, `"cli"`)
	b := rollout(t, home, idB, `"cli"`)
	index := filepath.Join(home, "session_index.jsonl")
	write(t, index, indexRow(idA, "名字甲")+indexRow(idB, "名字乙"))
	selected := a
	probe := probe{
		processes: func(context.Context) ([]byte, error) {
			return []byte("10 1 Ss /bin/bash\n11 10 S+ /bin/codex\n12 11 S /bin/codex\n99 1 S+ /bin/codex"), nil
		},
		files: func(_ context.Context, pids []int) (map[int][]string, error) {
			if !reflect.DeepEqual(pids, []int{11, 12}) {
				t.Fatalf("wrong PIDs: %v", pids)
			}
			return map[int][]string{12: {selected}}, nil // local app-server child owns the rollout
		},
	}
	target := []Target{{Ref: "socket\x1f%0", RootPID: 10, Command: "codex"}}
	for _, name := range []string{"名字甲", "改名甲", "最终甲"} {
		write(t, index, indexRow(idA, name)+indexRow(idB, "名字乙"))
		if got := probe.resolve(context.Background(), target)[target[0].Ref]; got != name {
			t.Fatalf("got %q want %q", got, name)
		}
	}
	selected = b
	if got := probe.resolve(context.Background(), target)[target[0].Ref]; got != "名字乙" {
		t.Fatal(got)
	}
	selected = ""
	if got := probe.resolve(context.Background(), target); len(got) != 0 {
		t.Fatal(got)
	}
}

func TestResolveSameDirectoryAndCustomHomesCannotBorrowNames(t *testing.T) {
	homeA, homeB := t.TempDir(), t.TempDir()
	a := rollout(t, homeA, idA, `"cli"`)
	b := rollout(t, homeB, idA, `"cli"`)
	write(t, filepath.Join(homeA, "session_index.jsonl"), indexRow(idA, "home A"))
	write(t, filepath.Join(homeB, "session_index.jsonl"), indexRow(idA, "home B"))
	probe := probe{
		processes: func(context.Context) ([]byte, error) {
			return []byte("10 1 S+ /bin/codex\n20 1 S+ /bin/codex\n30 1 S+ /bin/codex"), nil
		},
		files: func(_ context.Context, pids []int) (map[int][]string, error) {
			if !reflect.DeepEqual(pids, []int{10, 20}) {
				t.Fatalf("wrong PIDs: %v", pids)
			}
			return map[int][]string{10: {a}, 20: {b}, 30: {a}}, nil
		},
	}
	got := probe.resolve(context.Background(), []Target{{Ref: "A", RootPID: 10, Command: "codex"}, {Ref: "B", RootPID: 20, Command: "codex"}})
	if !reflect.DeepEqual(got, map[string]string{"A": "home A", "B": "home B"}) {
		t.Fatal(got)
	}
}

func TestResolveNoTargetsAndFailuresDoNotInventNames(t *testing.T) {
	calls := 0
	probe := probe{
		processes: func(context.Context) ([]byte, error) { calls++; return nil, errors.New("unavailable") },
		files: func(context.Context, []int) (map[int][]string, error) {
			t.Fatal("must not inspect descriptors")
			return nil, nil
		},
	}
	if got := probe.resolve(context.Background(), []Target{{Ref: "A", RootPID: 0}}); len(got) != 0 || calls != 0 {
		t.Fatal(got, calls)
	}
	if got := probe.resolve(context.Background(), []Target{{Ref: "A", RootPID: 10}}); len(got) != 0 || calls != 1 {
		t.Fatal(got, calls)
	}
}

func TestDescriptorReaderWithOwnSyntheticFile(t *testing.T) {
	if runtime.GOOS != "linux" && runtime.GOOS != "darwin" {
		t.Skip("descriptor metadata supports Linux and Darwin")
	}
	path := rollout(t, t.TempDir(), idA, `"cli"`)
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	files, err := processFiles(context.Background(), []int{os.Getpid()})
	if err != nil {
		t.Fatal(err)
	}
	canonical, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, got := range files[os.Getpid()] {
		if got == canonical {
			return
		}
	}
	t.Fatal("own synthetic rollout descriptor not found")
}
