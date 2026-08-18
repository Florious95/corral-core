// Package provider loads the shared Agent CLI whitelist
// (tools/nodeprobe/fixtures/providers.tsv). Identity is comm-basename only.
package provider

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

// Entry is one whitelist row. Comm is the basename; ID is the protocol
// provider token; Display is the human label.
type Entry struct {
	Comm    string
	ID      string
	Display string
}

var (
	loadOnce sync.Once
	table    []Entry
	byComm   map[string]Entry
	loadErr  error
)

// Load reads the shared TSV once. Tests and the API call Lookup after this.
func Load() ([]Entry, error) {
	loadOnce.Do(func() {
		path, err := locateTSV()
		if err != nil {
			loadErr = err
			return
		}
		f, err := os.Open(path)
		if err != nil {
			loadErr = err
			return
		}
		defer f.Close()
		sc := bufio.NewScanner(f)
		byComm = make(map[string]Entry)
		for sc.Scan() {
			line := strings.TrimSpace(sc.Text())
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			parts := strings.Split(line, "\t")
			if len(parts) < 3 {
				loadErr = fmt.Errorf("provider tsv: want comm\\tid\\tdisplay, got %q", line)
				return
			}
			e := Entry{Comm: parts[0], ID: parts[1], Display: parts[2]}
			table = append(table, e)
			byComm[e.Comm] = e
		}
		loadErr = sc.Err()
	})
	return table, loadErr
}

// Lookup matches one comm string by basename (never whole-string equality).
func Lookup(comm string) (Entry, bool) {
	if _, err := Load(); err != nil {
		return Entry{}, false
	}
	base := filepath.Base(strings.TrimSpace(comm))
	if base == "" || base == "." {
		return Entry{}, false
	}
	e, ok := byComm[base]
	return e, ok
}

// MatchComms returns the first whitelist hit in comms (root-to-descendant
// order is the caller's job). Empty means not a node.
func MatchComms(comms []string) (Entry, bool) {
	for _, c := range comms {
		if e, ok := Lookup(c); ok {
			return e, true
		}
	}
	return Entry{}, false
}

func locateTSV() (string, error) {
	var starts []string
	if _, file, _, ok := runtime.Caller(0); ok {
		starts = append(starts, filepath.Dir(file))
	}
	if wd, err := os.Getwd(); err == nil {
		starts = append(starts, wd)
	}
	for _, start := range starts {
		dir := start
		for i := 0; i < 10; i++ {
			cand := filepath.Join(dir, "tools", "nodeprobe", "fixtures", "providers.tsv")
			if st, err := os.Stat(cand); err == nil && !st.IsDir() {
				return cand, nil
			}
			parent := filepath.Dir(dir)
			if parent == dir {
				break
			}
			dir = parent
		}
	}
	return "", fmt.Errorf("provider: cannot find tools/nodeprobe/fixtures/providers.tsv")
}
