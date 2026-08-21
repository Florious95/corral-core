package api

import (
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/agentmirror/agentmirror/internal/provider"
)

// ProviderFinder maps a pane root pid to a whitelist provider id.
// Empty string means the pane is not a node (068: not listed).
type ProviderFinder interface {
	Identify(panePID int) string
}

// procFinder walks pane_pid plus descendants using one cached `ps`
// snapshot (narrow fields only). Full-table refresh happens when the
// snapshot is older than procTTL, or a requested pid is absent from it.
// Identity results are cached by (pid, starttime) for procTTL so a later
// Identify(single pid) cannot evict the table (095).
type procFinder struct {
	mu    sync.Mutex
	snap  *procSnap
	ident map[int]identCache
}

const procTTL = 10 * time.Second

type procSnap struct {
	parent map[int]int
	comm   map[int]string
	start  map[int]string
	kids   map[int][]int
	taken  time.Time
}

type identCache struct {
	id    string
	start string
	until time.Time
}

func newProcFinder() *procFinder { return &procFinder{ident: make(map[int]identCache)} }

func (f *procFinder) Identify(panePID int) string {
	return f.IdentifySet([]int{panePID})[panePID]
}

// IdentifySet refreshes at most once for the given pane pid set, then
// identifies each pid. Used by the listing tick and the level-2 scan so
// one ps covers all panes (095).
func (f *procFinder) IdentifySet(panePIDs []int) map[int]string {
	out := make(map[int]string, len(panePIDs))
	snap := f.snapshot(panePIDs)
	if snap == nil {
		return out
	}
	now := time.Now()
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, pid := range panePIDs {
		if pid <= 0 {
			continue
		}
		if id := f.cachedOrWalkLocked(snap, pid, now); id != "" {
			out[pid] = id
		}
	}
	return out
}

func (f *procFinder) cachedOrWalkLocked(snap *procSnap, pid int, now time.Time) string {
	start := snap.start[pid]
	if e, ok := f.ident[pid]; ok && start != "" && e.start == start && now.Before(e.until) {
		return e.id
	}
	comms := walkComms(snap, pid)
	id := ""
	if e, ok := provider.MatchComms(comms); ok {
		id = e.ID
	}
	if start != "" {
		f.ident[pid] = identCache{id: id, start: start, until: now.Add(procTTL)}
	}
	return id
}

func (f *procFinder) snapshot(panePIDs []int) *procSnap {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.snap != nil && time.Since(f.snap.taken) < procTTL && snapCovers(f.snap, panePIDs) {
		return f.snap
	}
	snap, err := readProcTable()
	if err != nil {
		return f.snap
	}
	f.snap = snap
	return snap
}

func snapCovers(s *procSnap, pids []int) bool {
	for _, pid := range pids {
		if pid <= 0 {
			continue
		}
		if _, ok := s.comm[pid]; !ok {
			return false
		}
	}
	return true
}

// procTableReads counts full-table `ps` forks. Tests assert a listing tick
// issues at most one (095). Production code does not read this.
var procTableReads atomic.Uint64

func readProcTable() (*procSnap, error) {
	procTableReads.Add(1)
	// Narrow fields only. lstart is process starttime, not argv (068: comm
	// only). LANG=C keeps lstart tokens stable across host locale.
	cmd := exec.Command("ps", "-axo", "pid=,ppid=,lstart=,comm=")
	cmd.Env = append(os.Environ(), "LANG=C", "LC_ALL=C")
	out, err := cmd.Output()
	if err != nil {
		return nil, err
	}
	s := &procSnap{
		parent: make(map[int]int),
		comm:   make(map[int]string),
		start:  make(map[int]string),
		kids:   make(map[int][]int),
		taken:  time.Now(),
	}
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		pid, err1 := strconv.Atoi(fields[0])
		ppid, err2 := strconv.Atoi(fields[1])
		if err1 != nil || err2 != nil {
			continue
		}
		yearIdx := -1
		for i := 2; i < len(fields); i++ {
			if len(fields[i]) == 4 && isDigits(fields[i]) {
				yearIdx = i
				break
			}
		}
		comm := ""
		if yearIdx >= 2 {
			s.start[pid] = strings.Join(fields[2:yearIdx+1], " ")
			if yearIdx+1 < len(fields) {
				comm = strings.Join(fields[yearIdx+1:], " ")
			}
		} else if len(fields) >= 3 {
			comm = strings.Join(fields[2:], " ")
		}
		s.parent[pid] = ppid
		s.comm[pid] = comm
		s.kids[ppid] = append(s.kids[ppid], pid)
	}
	return s, nil
}

func isDigits(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}

func walkComms(s *procSnap, root int) []string {
	var out []string
	var walk func(int)
	walk = func(pid int) {
		if c, ok := s.comm[pid]; ok {
			out = append(out, c)
		}
		for _, kid := range s.kids[pid] {
			walk(kid)
		}
	}
	walk(root)
	return out
}
