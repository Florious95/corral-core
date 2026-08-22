package api

import (
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"sync"
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
// pane-pid set changes, or when the snapshot is older than procTTL.
type procFinder struct {
	mu      sync.Mutex
	snap    *procSnap
	paneKey string
}

const procTTL = 10 * time.Second

type procSnap struct {
	parent map[int]int
	comm   map[int]string
	kids   map[int][]int
	taken  time.Time
}

func newProcFinder() *procFinder { return &procFinder{} }

func (f *procFinder) Identify(panePID int) string {
	if panePID <= 0 {
		return ""
	}
	snap := f.snapshot([]int{panePID})
	if snap == nil {
		return ""
	}
	comms := walkComms(snap, panePID)
	e, ok := provider.MatchComms(comms)
	if !ok {
		return ""
	}
	return e.ID
}

// IdentifySet refreshes at most once for the given pane pid set, then
// identifies each pid. Used by the level-2 scan so one ps covers all panes.
func (f *procFinder) IdentifySet(panePIDs []int) map[int]string {
	out := make(map[int]string, len(panePIDs))
	snap := f.snapshot(panePIDs)
	if snap == nil {
		return out
	}
	for _, pid := range panePIDs {
		if pid <= 0 {
			continue
		}
		comms := walkComms(snap, pid)
		if e, ok := provider.MatchComms(comms); ok {
			out[pid] = e.ID
		}
	}
	return out
}

func (f *procFinder) snapshot(panePIDs []int) *procSnap {
	key := paneSetKey(panePIDs)
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.snap != nil && f.paneKey == key && time.Since(f.snap.taken) < procTTL {
		return f.snap
	}
	snap, err := readProcTable()
	if err != nil {
		return f.snap
	}
	f.snap = snap
	f.paneKey = key
	return snap
}

func paneSetKey(pids []int) string {
	cp := append([]int(nil), pids...)
	sort.Ints(cp)
	var b strings.Builder
	for i, p := range cp {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(strconv.Itoa(p))
	}
	return b.String()
}

func readProcTable() (*procSnap, error) {
	// Narrow fields only. Do not add further -o tokens.
	cmd := exec.Command("ps", "-axo", "pid=,ppid=,comm=")
	out, err := cmd.Output()
	if err != nil {
		return nil, err
	}
	s := &procSnap{
		parent: make(map[int]int),
		comm:   make(map[int]string),
		kids:   make(map[int][]int),
		taken:  time.Now(),
	}
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		parts := strings.SplitN(line, " ", 3)
		// ps pads columns; collapse.
		fields := strings.Fields(line)
		if len(fields) < 3 {
			_ = parts
			continue
		}
		pid, err1 := strconv.Atoi(fields[0])
		ppid, err2 := strconv.Atoi(fields[1])
		if err1 != nil || err2 != nil {
			continue
		}
		comm := strings.Join(fields[2:], " ")
		s.parent[pid] = ppid
		s.comm[pid] = comm
		s.kids[ppid] = append(s.kids[ppid], pid)
	}
	return s, nil
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
