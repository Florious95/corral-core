// Package codexname resolves Codex /rename metadata without using a workspace
// name, terminal text, process arguments, or credentials as session identity.
package codexname

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Target is an already-discovered, positively identified Codex pane. Ref is an
// opaque caller-owned identity; RootPID is tmux's pane_pid, not a thread id.
type Target struct {
	Ref     string
	RootPID int
	Command string
}

type probe struct {
	processes func(context.Context) ([]byte, error)
	files     func(context.Context, []int) (map[int][]string, error)
}

const (
	resolveBudget = 750 * time.Millisecond
	maxProcesses  = 4096
	maxOutput     = 4 << 20
)

// Resolve takes a fresh identity snapshot on every existing metadata scan. It
// has no goroutines, timers or process/name cache surviving the call. Thus an
// idle rename, /resume, or /new cannot reuse an old pane-to-thread association.
// Unsupported, inaccessible and ambiguous observations return no override;
// the caller retains its existing OSC-title/unknown display policy.
func Resolve(ctx context.Context, targets []Target) map[string]string {
	return (probe{
		processes: func(ctx context.Context) ([]byte, error) {
			return commandOutput(ctx, "ps", "-axo", "pid=,ppid=,stat=,comm=")
		},
		files: processFiles,
	}).resolve(ctx, targets)
}

func (p probe) resolve(ctx context.Context, targets []Target) map[string]string {
	result := make(map[string]string)
	valid := make([]Target, 0, len(targets))
	for _, t := range targets {
		if t.Ref != "" && t.RootPID > 0 {
			valid = append(valid, t)
		}
	}
	if len(valid) == 0 {
		return result
	}
	ctx, cancel := context.WithTimeout(ctx, resolveBudget)
	defer cancel()
	raw, err := p.processes(ctx)
	if err != nil {
		return result
	}
	table := parseProcesses(raw)
	byPID := make(map[int][]string)
	for _, t := range valid {
		if root := codexPID(table, t); root > 0 {
			for _, pid := range codexDescendants(table, root) {
				byPID[pid] = append(byPID[pid], t.Ref)
			}
		}
	}
	pids := make([]int, 0, len(byPID))
	for pid := range byPID {
		pids = append(pids, pid)
	}
	sort.Ints(pids)
	if len(pids) == 0 {
		return result
	}
	files, err := p.files(ctx, pids)
	if err != nil {
		return result
	}
	pathsByRef := make(map[string][]string)
	for pid, refs := range byPID {
		for _, ref := range refs {
			pathsByRef[ref] = append(pathsByRef[ref], files[pid]...)
		}
	}
	associations := make(map[string]thread)
	for ref, paths := range pathsByRef {
		if th := interactiveThread(ctx, paths); th.ID != "" {
			associations[ref] = th
		}
	}
	homes := make(map[string]map[string]bool)
	for _, th := range associations {
		if homes[th.Home] == nil {
			homes[th.Home] = make(map[string]bool)
		}
		homes[th.Home][th.ID] = true
	}
	for home, ids := range homes {
		names := indexNames(ctx, filepath.Join(home, "session_index.jsonl"), ids)
		for ref, th := range associations {
			if th.Home == home {
				if name, ok := names[th.ID]; ok && strings.TrimSpace(name) != "" {
					result[ref] = name
				}
			}
		}
	}
	return result
}

type process struct {
	parent     int
	stat, comm string
}

type processTable struct {
	rows     map[int]process
	children map[int][]int
}

func parseProcesses(data []byte) processTable {
	table := processTable{rows: make(map[int]process), children: make(map[int][]int)}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 4 {
			continue
		}
		pid, e1 := strconv.Atoi(fields[0])
		parent, e2 := strconv.Atoi(fields[1])
		if e1 != nil || e2 != nil || pid <= 0 || parent < 0 {
			continue
		}
		table.rows[pid] = process{parent, fields[2], strings.Join(fields[3:], " ")}
		table.children[parent] = append(table.children[parent], pid)
	}
	return table
}

// Choose an unambiguous native Codex process below this pane only. Do not
// descend into a Codex process's tool children (which can run another Codex).
// An actual foreground Codex wins over background leftovers. A foreground
// shell with a shell pane command must never resurrect an old background CLI.
func codexPID(table processTable, t Target) int {
	queue := []int{t.RootPID}
	seen := make(map[int]bool)
	candidates, foreground := []int{}, []int{}
	hasForeground := false
	for len(queue) > 0 {
		pid := queue[0]
		queue = queue[1:]
		if seen[pid] {
			continue
		}
		seen[pid] = true
		if len(seen) > maxProcesses {
			return 0
		}
		proc, ok := table.rows[pid]
		if !ok {
			continue
		}
		fg := strings.Contains(proc.stat, "+")
		hasForeground = hasForeground || fg
		if filepath.Base(proc.comm) == "codex" && !strings.ContainsAny(proc.stat, "ZT") {
			candidates = append(candidates, pid)
			if fg {
				foreground = append(foreground, pid)
			}
			continue
		}
		queue = append(queue, table.children[pid]...)
	}
	if len(foreground) == 1 {
		return foreground[0]
	}
	if len(foreground) > 1 || (hasForeground && isShell(t.Command)) {
		return 0
	}
	if len(candidates) == 1 {
		return candidates[0]
	}
	return 0
}

func isShell(command string) bool {
	switch filepath.Base(command) {
	case "sh", "bash", "zsh", "fish", "dash", "ksh", "csh", "tcsh":
		return true
	default:
		return false
	}
}

// Include native app-server/tool children below the chosen CLI root. The
// rollout's source metadata, not PID/FD order, distinguishes the interactive
// thread from exec/subagent sessions in the same process family.
func codexDescendants(table processTable, root int) []int {
	queue, ids := []int{root}, []int{}
	seen := make(map[int]bool)
	for len(queue) > 0 {
		pid := queue[0]
		queue = queue[1:]
		if seen[pid] {
			continue
		}
		seen[pid] = true
		if len(seen) > maxProcesses {
			return nil
		}
		proc, ok := table.rows[pid]
		if !ok {
			continue
		}
		if filepath.Base(proc.comm) == "codex" && !strings.ContainsAny(proc.stat, "ZT") {
			ids = append(ids, pid)
		}
		queue = append(queue, table.children[pid]...)
	}
	return ids
}

func processFiles(ctx context.Context, pids []int) (map[int][]string, error) {
	paths := make(map[int][]string)
	if len(pids) == 0 {
		return paths, nil
	}
	for _, pid := range pids {
		if pid <= 0 {
			return nil, errors.New("invalid process")
		}
	}
	switch runtime.GOOS {
	case "linux":
		for _, pid := range pids {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			dir := filepath.Join("/proc", strconv.Itoa(pid), "fd")
			entries, err := os.ReadDir(dir)
			if err != nil {
				return nil, err
			}
			if len(entries) > maxProcesses {
				return nil, errors.New("too many descriptors")
			}
			for _, entry := range entries {
				if ctx.Err() != nil {
					return nil, ctx.Err()
				}
				if _, err := strconv.Atoi(entry.Name()); err != nil {
					continue
				}
				path, err := os.Readlink(filepath.Join(dir, entry.Name()))
				if err == nil {
					paths[pid] = append(paths[pid], path)
				}
			}
		}
		return paths, nil
	case "darwin":
		ids := make([]string, 0, len(pids))
		for _, pid := range pids {
			ids = append(ids, strconv.Itoa(pid))
		}
		// One batched lsof per metadata scan, not a subprocess per pane.
		// Numeric FDs only; -a intersects PID selection. NUL-delimited fields
		// preserve spaces, pipes and Unicode in paths.
		data, err := commandOutput(ctx, "/usr/sbin/lsof", "-nP", "-a", "-p", strings.Join(ids, ","), "-F0pfn")
		if err != nil {
			return nil, err
		}
		return parseLsof(data), nil
	default:
		return nil, errors.New("unsupported Codex descriptor platform")
	}
}

func parseLsof(data []byte) map[int][]string {
	paths := make(map[int][]string)
	pid := 0
	numericFD := false
	for _, field := range bytes.Split(data, []byte{0}) {
		field = bytes.TrimLeft(field, "\n")
		if len(field) == 0 {
			continue
		}
		switch field[0] {
		case 'p':
			pid, _ = strconv.Atoi(string(field[1:]))
			numericFD = false
		case 'f':
			_, err := strconv.Atoi(string(field[1:]))
			numericFD = err == nil
		case 'n':
			if pid > 0 && numericFD {
				paths[pid] = append(paths[pid], string(field[1:]))
			}
		}
	}
	return paths
}

type boundedOutput struct{ bytes.Buffer }

func (b *boundedOutput) Write(p []byte) (int, error) {
	if len(p) > maxOutput-b.Len() {
		return 0, errors.New("metadata output limit exceeded")
	}
	return b.Buffer.Write(p)
}

func commandOutput(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Env = []string{"PATH=/usr/bin:/bin:/usr/sbin:/sbin", "LC_ALL=C"}
	cmd.WaitDelay = 100 * time.Millisecond
	var out boundedOutput
	cmd.Stdout = &out
	// Never include raw ps/lsof output, filenames, or stderr in errors/logs.
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("Codex metadata command: %w", err)
	}
	return out.Bytes(), nil
}
