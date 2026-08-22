package api

import (
	"bufio"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// TestL2FixtureCorpusParity 用与 Rust nodeprobe 同一份语料约束 Go 判定。
// 路径必须是仓根 tools/nodeprobe/fixtures/titles.tsv，禁止复制到 server/。
func TestL2FixtureCorpusParity(t *testing.T) {
	path := fixtureCorpusPath(t)
	if !strings.Contains(filepath.ToSlash(path), "tools/nodeprobe/fixtures/titles.tsv") {
		t.Fatalf("must read the shared corpus, got %s", path)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open shared corpus %s: %v", path, err)
	}
	defer f.Close()

	sc := bufio.NewScanner(f)
	// Titles can be long; default 64k is enough, keep a higher cap anyway.
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)

	n := 0
	for lineNo := 1; sc.Scan(); lineNo++ {
		line := sc.Text()
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.Split(line, "\t")
		if len(parts) != 4 {
			t.Fatalf("%s:%d: need payload<TAB>want<TAB>provider<TAB>kind (4 fields), got %d in %q", path, lineNo, len(parts), line)
		}
		payload, want, provider, kind := parts[0], parts[1], parts[2], parts[3]
		switch kind {
		case "title":
			var got string
			if provider != "" && provider != "unknown" {
				got, _, _ = classifyForProvider(provider, payload)
			} else {
				got, _, _ = classifyFallback(payload)
			}
			if got != want {
				t.Errorf("%s:%d title=%q: go=%q want=%q", path, lineNo, payload, got, want)
			}
		case "footer":
			got := backgroundTasksFor(provider, payload)
			if !got.equalWant(want) {
				t.Errorf("%s:%d footer=%q provider=%s: go=%s want=%s", path, lineNo, payload, provider, got.String(), want)
			}
		default:
			t.Fatalf("%s:%d: unknown kind %q (want title|footer); refusing to ignore a new column", path, lineNo, kind)
		}
		n++
	}
	if err := sc.Err(); err != nil {
		t.Fatalf("scan %s: %v", path, err)
	}
	if n < 6 {
		t.Fatalf("corpus too small: %d rows in %s (need the shared titles.tsv)", n, path)
	}
	t.Logf("parity %d rows from %s", n, path)
}

func fixtureCorpusPath(t *testing.T) string {
	t.Helper()
	var starts []string
	if wd, err := os.Getwd(); err == nil {
		starts = append(starts, wd)
	}
	if _, file, _, ok := runtime.Caller(0); ok {
		starts = append(starts, filepath.Dir(file))
	}
	for _, start := range starts {
		dir := start
		for i := 0; i < 10; i++ {
			cand := filepath.Join(dir, "tools", "nodeprobe", "fixtures", "titles.tsv")
			if st, err := os.Stat(cand); err == nil && !st.IsDir() {
				return cand
			}
			parent := filepath.Dir(dir)
			if parent == dir {
				break
			}
			dir = parent
		}
	}
	t.Fatal("cannot find tools/nodeprobe/fixtures/titles.tsv; do not copy it under server/")
	return ""
}
