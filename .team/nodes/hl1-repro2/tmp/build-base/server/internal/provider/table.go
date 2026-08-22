// Package provider loads the shared Agent CLI whitelist
// (tools/nodeprobe/fixtures/providers.tsv). Identity is comm-basename, plus
// an optional path-segment match when the TSV row says so.
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
// provider token; Display is the human label. PathSegment means the raw
// comm also hits when it contains /<Comm>/ as a directory (not via argv).
type Entry struct {
	Comm        string
	ID          string
	Display     string
	PathSegment bool
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
			if len(parts) >= 4 && parts[3] == "path-segment" {
				e.PathSegment = true
			}
			table = append(table, e)
			byComm[e.Comm] = e
		}
		loadErr = sc.Err()
	})
	return table, loadErr
}

// Lookup matches one comm string by basename (never whole-string equality).
// If basename misses, rows marked path-segment still hit when the raw comm
// contains /<comm-basename>/ as a directory (086: cursor-agent lives under
// .../cursor-agent/.../node; the basename is node and must not be whitelisted).
func Lookup(comm string) (Entry, bool) {
	if _, err := Load(); err != nil {
		return Entry{}, false
	}
	comm = strings.TrimSpace(comm)
	base := filepath.Base(comm)
	if base != "" && base != "." {
		if e, ok := byComm[base]; ok {
			return e, true
		}
	}
	slash := filepath.ToSlash(comm)
	for _, e := range table {
		if !e.PathSegment || e.Comm == "" {
			continue
		}
		if strings.Contains(slash, "/"+e.Comm+"/") {
			return e, true
		}
	}
	return Entry{}, false
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
