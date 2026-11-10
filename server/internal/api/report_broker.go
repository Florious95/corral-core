package api

// report_broker.go is the single nodeprobe report producer per socket, shared
// by L1 inventory and L2 workspace scans (pid-churn contract package C).
// @contract
// @pre sampler is the server's nodeprobe.Sampler; ctx is the server loop context
// @post per socket at most one executing and one coalesced pending report; at most reportBrokerConcurrency executions host-wide
// @err a dependency failure reaches every joined waiter; cancellation is never a socket failure
// @inv a waiter never receives a report that started before its fence, nor one that may predate a pane identity it joins; hits never move startedAt; reports are read-only

import (
	"context"
	"fmt"
	"hash/fnv"
	"log/slog"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
)

const (
	reportBrokerConcurrency = 4
	// A periodic consumer may adopt a report that started at most this long
	// ago. With the 2s cadence the worst status age stays ≈3s, inside the 5s
	// end-to-end budget; a full 2s window would stack into ≈4s (contract E).
	reportShareWindow = time.Second
	reportIdleTTL     = time.Minute
	// pid-churn.backoff.v1: D(n)=min(30s, 2s×2^(n−1)), measured from the
	// failed attempt's completion.
	reportBackoffBase = 2 * time.Second
	reportBackoffMax  = 30 * time.Second
	// Bound on remembered pane identities per socket; overflow forgets them,
	// which only forces fresh reports.
	reportPaneLimit = 4096
)

type reportRun struct {
	st      *socketReports
	ctx     context.Context
	cancel  context.CancelFunc
	started time.Time // zero until execution begins
	waiters int
	// keys are the pane identities that requested this run; true means the
	// request came before execution began, so the run postdates that identity.
	keys   map[uint64]bool
	done   chan struct{}
	report nodeprobe.Report // read only after done
	err    error
}

// socketReports is one socket's producer state. accepted is the last
// successful report (never refreshed by hits); failures/nextEligible are the
// independent failure clock, invalidated by socket replacement or a pane
// identity that has not failed in this streak. panes records when each pane
// identity (socket/session/window/pane/pid/command/cwd) was first requested.
type socketReports struct {
	file            os.FileInfo
	accepted        *reportRun
	active, pending *reportRun
	failures        int
	nextEligible    time.Time
	lastErr         error
	failedKeys      map[uint64]struct{}
	panes           map[uint64]paneSeen
	touched         time.Time
}

type paneSeen struct{ first, last time.Time }

type reportBroker struct {
	ctx     context.Context
	sampler nodeprobe.Sampler
	log     *slog.Logger
	now     func() time.Time
	slots   chan struct{}
	mu      sync.Mutex
	sockets map[string]*socketReports
}

func newReportBroker(ctx context.Context, sampler nodeprobe.Sampler, log *slog.Logger) *reportBroker {
	return &reportBroker{ctx: ctx, sampler: sampler, log: log, now: time.Now,
		slots: make(chan struct{}, reportBrokerConcurrency), sockets: make(map[string]*socketReports)}
}

func reportBackoff(failures int) time.Duration {
	d := reportBackoffBase
	for i := 1; i < failures && d < reportBackoffMax; i++ {
		d *= 2
	}
	return min(d, reportBackoffMax)
}

// acquire registers interest in a report for socket that started no earlier
// than fence. keys are the caller's pane identities on that socket: the join
// key carries no pane PID, so a report may serve a pane only if it postdates
// that identity (requested for it before starting, or started after the
// identity was first seen). A replaced pane process never inherits the old
// process's provider, even when both happen in one clock tick.
func (b *reportBroker) acquire(socket string, fence time.Time, keys []uint64) (*reportRun, error) {
	file, statErr := os.Stat(socket) // outside the lock; replacement invalidates cache and failures
	b.mu.Lock()
	defer b.mu.Unlock()
	now := b.now()
	for path, st := range b.sockets {
		if st.active == nil && st.pending == nil && now.Sub(st.touched) > reportIdleTTL {
			delete(b.sockets, path)
		}
	}
	st := b.sockets[socket]
	if st == nil {
		st = &socketReports{}
		b.sockets[socket] = st
	}
	if statErr == nil {
		if st.file != nil && !os.SameFile(st.file, file) {
			// A path replacement is a new producer generation, not merely a
			// cache miss. Old work cannot serve or overwrite the new state.
			if st.active != nil {
				st.active.cancel()
			}
			if next := st.pending; next != nil {
				st.pending = nil
				next.err = fmt.Errorf("nodeprobe socket=%s file generation replaced: %w", socket, context.Canceled)
				close(next.done)
				next.cancel()
			}
			st = &socketReports{}
			b.sockets[socket] = st
		}
		st.file = file
	}
	st.touched = now
	if st.panes == nil || len(st.panes)+len(keys) > reportPaneLimit {
		st.panes = make(map[uint64]paneSeen)
	}
	for k, seen := range st.panes {
		if now.Sub(seen.last) > reportIdleTTL {
			delete(st.panes, k)
		}
	}
	for _, k := range keys {
		seen, ok := st.panes[k]
		if !ok {
			seen.first = now
		}
		seen.last = now
		st.panes[k] = seen
	}
	fresh := func(run *reportRun) bool {
		if run.started.Before(fence) {
			return false
		}
		for _, k := range keys {
			if !run.keys[k] && !run.started.After(st.panes[k].first) {
				return false
			}
		}
		return true
	}
	join := func(run *reportRun) *reportRun {
		run.waiters++
		for _, k := range keys {
			run.keys[k] = run.keys[k] || run.started.IsZero()
		}
		return run
	}
	if run := st.accepted; run != nil && fresh(run) {
		run.waiters++
		return run, nil
	}
	if run := st.active; run != nil && run.ctx.Err() == nil && (run.started.IsZero() || fresh(run)) {
		return join(run), nil
	}
	if err := b.backoffLocked(socket, st, now, keys); err != nil {
		return nil, err
	}
	if st.active == nil {
		st.active = b.newRunLocked(st)
		go b.execute(socket, st.active)
		return join(st.active), nil
	}
	if st.pending == nil {
		st.pending = b.newRunLocked(st)
	}
	return join(st.pending), nil
}

// backoffLocked refuses a new execution before nextEligible when every pane
// identity already failed in this streak. It returns the existing error,
// never an empty success.
func (b *reportBroker) backoffLocked(socket string, st *socketReports, now time.Time, keys []uint64) error {
	if st.failures == 0 || !now.Before(st.nextEligible) {
		return nil
	}
	for _, k := range keys {
		if _, failed := st.failedKeys[k]; !failed {
			return nil
		}
	}
	if b.log != nil {
		b.log.Debug("nodeprobe: retry deferred", "socket", socket, "failures", st.failures,
			"now", now, "next_eligible_at", st.nextEligible,
			"now_until_eligible_ms", st.nextEligible.Sub(now).Milliseconds())
	}
	return fmt.Errorf("nodeprobe socket=%s backoff failures=%d retry_in_ms=%d: %w",
		socket, st.failures, st.nextEligible.Sub(now).Milliseconds(), st.lastErr)
}

func (b *reportBroker) newRunLocked(st *socketReports) *reportRun {
	ctx, cancel := context.WithCancel(b.ctx)
	return &reportRun{st: st, ctx: ctx, cancel: cancel, keys: make(map[uint64]bool), done: make(chan struct{})}
}

func (b *reportBroker) execute(socket string, run *reportRun) {
	select {
	case b.slots <- struct{}{}:
	case <-run.ctx.Done():
		b.complete(socket, run, nodeprobe.Report{}, run.ctx.Err())
		return
	}
	b.mu.Lock()
	run.started = b.now()
	b.mu.Unlock()
	report, err := b.sampler.Sample(run.ctx, socket)
	<-b.slots
	b.complete(socket, run, report, err)
}

func (b *reportBroker) complete(socket string, run *reportRun, report nodeprobe.Report, err error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	st, now := run.st, b.now()
	if b.sockets[socket] != st {
		// A dependency may ignore cancellation and return late success.
		// Never publish it as a result for the replacement file generation.
		err = fmt.Errorf("nodeprobe socket=%s file generation replaced: %w", socket, context.Canceled)
	}
	// Capture execution failure before our own cleanup cancels run.ctx.
	// Runner's own timeout leaves this context live and is a real failure;
	// no-interest/shutdown cancellation is not.
	failed := err != nil && run.ctx.Err() == nil
	run.report, run.err = report, err
	switch {
	case err == nil:
		st.accepted = run
		st.failures, st.nextEligible, st.lastErr, st.failedKeys = 0, time.Time{}, nil, nil
	case failed:
		// The broker cancels a run only when every waiter left or the server
		// stops; any other error (including Runner's own timeout) is the
		// dependency failing while interest remained.
		st.failures++
		st.nextEligible = now.Add(reportBackoff(st.failures))
		st.lastErr = err
		if st.failedKeys == nil || len(st.failedKeys)+len(run.keys) > reportPaneLimit {
			st.failedKeys = make(map[uint64]struct{})
		}
		for k := range run.keys {
			st.failedKeys[k] = struct{}{}
		}
		if b.log != nil {
			b.log.Warn("nodeprobe: sample failed", "socket", socket, "failures", st.failures,
				"backoff_ms", reportBackoff(st.failures).Milliseconds(), "err", err)
		}
	}
	st.active = nil
	close(run.done)
	run.cancel()
	next := st.pending
	if next == nil {
		return
	}
	st.pending = nil
	if failed {
		// Waiters queued behind the failed attempt start only if they carry a
		// pane identity that has not failed in this streak.
		keys := make([]uint64, 0, len(next.keys))
		for k := range next.keys {
			keys = append(keys, k)
		}
		if next.err = b.backoffLocked(socket, st, now, keys); next.err != nil {
			close(next.done)
			next.cancel()
			return
		}
	}
	st.active = next
	go b.execute(socket, next)
}

// release drops one waiter. The last waiter of an unfinished run removes a
// pending run or cancels the active one; other interest keeps it alive.
func (b *reportBroker) release(run *reportRun) {
	b.mu.Lock()
	defer b.mu.Unlock()
	run.waiters--
	if run.waiters > 0 {
		return
	}
	select {
	case <-run.done:
		return
	default:
	}
	if run.st.pending == run {
		run.st.pending = nil
		run.cancel()
		return
	}
	run.cancel()
}

func (b *reportBroker) wait(ctx context.Context, run *reportRun) (nodeprobe.Report, error) {
	defer b.release(run)
	select {
	case <-run.done:
		return run.report, run.err
	case <-ctx.Done():
		select {
		case <-run.done:
			return run.report, run.err
		default:
			return nodeprobe.Report{}, ctx.Err()
		}
	}
}

// paneIdentity is one pane's structural identity; a changed root PID,
// command, window or cwd is a new identity.
func paneIdentity(cwd string, pane discovery.Pane) string {
	return fmt.Sprintf("%q/%q/%d/%q/%d/%q/%q;", pane.Socket, pane.Session, pane.WindowIndex, pane.PaneID, pane.PanePID, pane.Command, cwd)
}

// socketModels splits model per socket and returns each socket's structural
// signature (the L1 inventory key).
func socketModels(model *discovery.Model) (map[string]*discovery.Model, map[string]string) {
	models := map[string]*discovery.Model{}
	builders := map[string]*strings.Builder{}
	for _, ws := range model.Workspaces {
		for _, pane := range ws.Panes {
			if models[pane.Socket] == nil {
				models[pane.Socket] = &discovery.Model{}
				builders[pane.Socket] = &strings.Builder{}
			}
			models[pane.Socket].Workspaces = append(models[pane.Socket].Workspaces, discovery.Workspace{CWD: ws.CWD, Panes: []discovery.Pane{pane}})
			builders[pane.Socket].WriteString(paneIdentity(ws.CWD, pane))
		}
	}
	signatures := make(map[string]string, len(builders))
	for socket, b := range builders {
		signatures[socket] = b.String()
	}
	return models, signatures
}

// sampleModel joins one shared report per socket onto model. Every socket's
// interest is registered before waiting, so independent sockets execute
// concurrently without per-request goroutines. sampled is the oldest
// startedAt among the joined reports.
func (b *reportBroker) sampleModel(ctx context.Context, model *discovery.Model, fence time.Time) (out map[string]nodeprobe.Observation, sampled time.Time, err error) {
	models, _ := socketModels(model)
	sockets := make([]string, 0, len(models))
	for socket := range models {
		sockets = append(sockets, socket)
	}
	sort.Strings(sockets)
	runs := make([]*reportRun, 0, len(sockets))
	for _, socket := range sockets {
		var keys []uint64
		for _, ws := range models[socket].Workspaces {
			for _, pane := range ws.Panes {
				h := fnv.New64a()
				h.Write([]byte(paneIdentity(ws.CWD, pane)))
				keys = append(keys, h.Sum64())
			}
		}
		run, acquireErr := b.acquire(socket, fence, keys)
		if acquireErr != nil {
			err = acquireErr
			break
		}
		runs = append(runs, run)
	}
	out = map[string]nodeprobe.Observation{}
	for i, run := range runs {
		if err != nil {
			b.release(run)
			continue
		}
		report, waitErr := b.wait(ctx, run)
		if waitErr != nil {
			err = waitErr
			continue
		}
		if sampled.IsZero() || run.started.Before(sampled) {
			sampled = run.started
		}
		var panes []discovery.Pane
		for _, ws := range models[sockets[i]].Workspaces {
			panes = append(panes, ws.Panes...)
		}
		nodeprobe.JoinReport(report, panes, out)
	}
	if err != nil {
		return nil, time.Time{}, err
	}
	return out, sampled, nil
}
