package api

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

const (
	workspaceWorkers        = 2
	workspacePendingLimit   = 64
	workspaceRefreshTimeout = 12 * time.Second // admission, queue and execution together
)

type workspaceTarget struct {
	cwd   string
	epoch uint64
}

type workspaceWaiter struct {
	epoch    uint64
	deadline time.Time
}

type workspaceJob struct {
	cwd     string
	queued  time.Time
	started time.Time
	waiters map[*wsConn]workspaceWaiter // coordinator lock only; worker never reads it
	ctx     context.Context
	cancel  context.CancelFunc
}

type workspaceResult struct {
	job     *workspaceJob
	catalog *sessionCatalog
	rows    []protocol.Session
	err     error
}

// workspaceCoordinator separates current-workspace work from host catalog
// discovery/sampling. There are two fixed workers, at most 64 pending workspace
// jobs and one coalesced post-cutoff job per workspace. No request goroutines,
// per-workspace tickers, cached-row responses or additional global scans.
type workspaceCoordinator struct {
	s      *Server
	source WorkspaceDiscoverer
	mu     sync.Mutex

	targets map[*wsConn]workspaceTarget
	pending map[string]*workspaceJob
	active  map[string]*workspaceJob
	order   []string
	due     map[string]time.Time

	wakeCh  chan struct{}
	jobs    chan *workspaceJob
	results chan workspaceResult
	done    chan struct{}
	workers sync.WaitGroup
}

func newWorkspaceCoordinator(s *Server, source WorkspaceDiscoverer) *workspaceCoordinator {
	q := &workspaceCoordinator{
		s: s, source: source,
		targets: make(map[*wsConn]workspaceTarget), pending: make(map[string]*workspaceJob),
		active: make(map[string]*workspaceJob), due: make(map[string]time.Time),
		wakeCh: make(chan struct{}, 1), jobs: make(chan *workspaceJob, workspaceWorkers),
		results: make(chan workspaceResult, workspaceWorkers), done: make(chan struct{}),
	}
	for i := 0; i < workspaceWorkers; i++ {
		q.workers.Add(1)
		go q.worker()
	}
	go q.loop()
	return q
}

func (q *workspaceCoordinator) wake() {
	select {
	case q.wakeCh <- struct{}{}:
	default:
	}
}

func (c *wsConn) workspaceAtEpoch(epoch uint64) (string, bool) {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	return c.level2WS, c.level2On && c.level2Epoch == epoch
}

func (q *workspaceCoordinator) subscribe(c *wsConn, epoch uint64) {
	cwd, valid := c.workspaceAtEpoch(epoch)
	if !valid || c.ctx.Err() != nil || q.s.loopCtx.Err() != nil {
		return
	}
	q.mu.Lock()
	// Recheck after admission-lock acquisition: an obsolete page cannot
	// replace a newer owner while waiting for this lock.
	if current, ok := c.workspaceAtEpoch(epoch); !ok || current != cwd || q.s.loopCtx.Err() != nil || c.ctx.Err() != nil {
		q.mu.Unlock()
		return
	}
	q.removeLocked(c)
	accepted := len(q.targets) < catalogGlobalLimit
	if accepted {
		target := workspaceTarget{cwd: cwd, epoch: epoch}
		accepted = q.enqueueLocked(c, target, time.Now())
		if accepted {
			q.targets[c] = target
		}
	}
	q.mu.Unlock()
	if !accepted {
		q.sendError(c, epoch, errors.New("workspace refresh queue full"))
	}
	q.wake()
}

func (q *workspaceCoordinator) removeLocked(c *wsConn) {
	delete(q.targets, c)
	for cwd, job := range q.pending {
		delete(job.waiters, c)
		if len(job.waiters) == 0 {
			delete(q.pending, cwd)
		}
	}
	for _, job := range q.active {
		delete(job.waiters, c)
		if len(job.waiters) == 0 {
			job.cancel()
		}
	}
}

func (q *workspaceCoordinator) remove(c *wsConn) {
	q.mu.Lock()
	q.removeLocked(c)
	q.mu.Unlock()
	q.wake()
}

func (q *workspaceCoordinator) enqueueLocked(c *wsConn, target workspaceTarget, now time.Time) bool {
	job := q.pending[target.cwd]
	if job == nil {
		if len(q.pending) >= workspacePendingLimit {
			return false
		}
		// Rapid ownership changes can leave obsolete queue keys. Compact
		// before appending so even an unbounded request burst has bounded storage.
		if len(q.order) >= 2*workspacePendingLimit {
			q.compactOrderLocked()
		}
		job = &workspaceJob{cwd: target.cwd, queued: now, waiters: make(map[*wsConn]workspaceWaiter)}
		q.pending[target.cwd] = job
		q.order = append(q.order, target.cwd)
	}
	job.waiters[c] = workspaceWaiter{epoch: target.epoch, deadline: now.Add(workspaceRefreshTimeout)}
	return true
}

func (q *workspaceCoordinator) compactOrderLocked() {
	seen := make(map[string]bool)
	kept := q.order[:0]
	for _, cwd := range q.order {
		if q.pending[cwd] != nil && !seen[cwd] {
			seen[cwd] = true
			kept = append(kept, cwd)
		}
	}
	clear(q.order[len(kept):])
	q.order = kept
}

func (q *workspaceCoordinator) sendError(c *wsConn, epoch uint64, err error) {
	if frame, tag := c.level2Output(epoch, nil, err); frame != nil {
		c.sendCatalog(frame, tag, nil)
	}
}

func earlierWorkspaceTime(a, b time.Time) time.Time {
	if a.IsZero() || (!b.IsZero() && b.Before(a)) {
		return b
	}
	return a
}

// maintain admits due polling work, expires absolute request deadlines and
// dispatches independent workspaces. It performs no discovery or network I/O.
func (q *workspaceCoordinator) maintain(now time.Time) time.Time {
	type expiredWaiter struct {
		c *wsConn
		e uint64
	}
	var expired []expiredWaiter
	var next time.Time
	q.mu.Lock()
	for c := range q.targets {
		if c.ctx.Err() != nil {
			q.removeLocked(c)
		}
	}
	for _, jobs := range []map[string]*workspaceJob{q.pending, q.active} {
		for cwd, job := range jobs {
			for c, waiter := range job.waiters {
				if !now.Before(waiter.deadline) {
					delete(job.waiters, c)
					expired = append(expired, expiredWaiter{c, waiter.epoch})
					q.due[cwd] = now.Add(q.s.level2Interval)
				} else {
					next = earlierWorkspaceTime(next, waiter.deadline)
				}
			}
			if len(job.waiters) == 0 {
				if job.cancel != nil {
					job.cancel()
				} else {
					delete(q.pending, cwd)
				}
			}
		}
	}
	wanted := make(map[string]bool)
	for c, target := range q.targets {
		wanted[target.cwd] = true
		if job := q.pending[target.cwd]; job != nil {
			if waiter, ok := job.waiters[c]; ok && waiter.epoch == target.epoch {
				continue
			}
		}
		if job := q.active[target.cwd]; job != nil {
			if waiter, ok := job.waiters[c]; ok && waiter.epoch == target.epoch {
				continue
			}
		}
		if due := q.due[target.cwd]; due.After(now) {
			next = earlierWorkspaceTime(next, due)
			continue
		}
		if !q.enqueueLocked(c, target, now) {
			// No busy timer loop when the bounded queue is full. Completion
			// wakes us earlier; otherwise retry at the existing L2 cadence.
			next = earlierWorkspaceTime(next, now.Add(q.s.level2Interval))
		}
	}
	for cwd := range q.due {
		if !wanted[cwd] {
			delete(q.due, cwd)
		}
	}
	q.compactOrderLocked()
	for _, cwd := range q.order {
		if len(q.active) >= workspaceWorkers {
			break
		}
		job := q.pending[cwd]
		if job == nil || q.active[cwd] != nil {
			continue
		}
		var deadline time.Time
		for _, waiter := range job.waiters {
			deadline = earlierWorkspaceTime(deadline, waiter.deadline)
		}
		job.ctx, job.cancel = context.WithDeadline(q.s.loopCtx, deadline)
		job.started = time.Now() // freshness cutoff: later subscribes enter pending
		delete(q.pending, cwd)
		q.active[cwd] = job
		q.jobs <- job
	}
	// Include deadlines of jobs enqueued above, not just jobs present on entry.
	for _, jobs := range []map[string]*workspaceJob{q.pending, q.active} {
		for _, job := range jobs {
			for _, waiter := range job.waiters {
				next = earlierWorkspaceTime(next, waiter.deadline)
			}
		}
	}
	q.mu.Unlock()
	for _, waiter := range expired {
		q.sendError(waiter.c, waiter.e, context.DeadlineExceeded)
	}
	return next
}

func (q *workspaceCoordinator) worker() {
	defer q.workers.Done()
	for {
		select {
		case <-q.s.loopCtx.Done():
			return
		case job := <-q.jobs:
			result := q.scan(job)
			if job.ctx.Err() != nil {
				result.err = job.ctx.Err()
			}
			job.cancel()
			select {
			case q.results <- result:
			case <-q.s.loopCtx.Done():
				return
			}
		}
	}
}

func (q *workspaceCoordinator) scan(job *workspaceJob) (result workspaceResult) {
	result.job = job
	executing := time.Now()
	var discoverTime, sampleTime time.Duration
	var sockets, panes int
	defer func() {
		q.s.log.Info("workspace: refresh phases", "queue_ms", executing.Sub(job.queued).Milliseconds(),
			"discover_ms", discoverTime.Milliseconds(), "sample_ms", sampleTime.Milliseconds(),
			"total_ms", time.Since(job.queued).Milliseconds(), "sample_sockets", sockets, "sample_panes", panes,
			"failed", result.err != nil || job.ctx.Err() != nil)
	}()
	start := time.Now()
	model, err := q.source.DiscoverWorkspace(job.ctx, job.cwd)
	discoverTime = time.Since(start)
	if err != nil {
		result.err = err
		return
	}
	if model == nil {
		result.err = errors.New("discovery: nil workspace inventory")
		return
	}
	// Defensively constrain before sampling, not after building all workspaces.
	scoped := &discovery.Model{}
	if ws := model.Workspace(job.cwd); ws != nil {
		scoped.Workspaces = []discovery.Workspace{*ws}
		panes = len(ws.Panes)
		seen := make(map[string]struct{})
		for _, pane := range ws.Panes {
			seen[pane.Socket] = struct{}{}
		}
		sockets = len(seen)
	}
	start = time.Now()
	observations, err := nodeprobe.SampleModel(job.ctx, scoped, q.s.nodeprobe)
	sampleTime = time.Since(start)
	if err != nil {
		result.err = err
		return
	}
	if q.s.filterAgents {
		scoped = filterModelToIdentifiedAgents(scoped, observations)
	}
	result.catalog = newSessionCatalog()
	result.catalog.rebuild(scoped, observations)
	for _, ws := range scoped.Workspaces {
		for _, pane := range ws.Panes {
			obs, ok := observations[sessionRef(pane)]
			if !ok {
				obs = nodeprobe.Unknown()
			}
			result.rows = append(result.rows, sessionFromPane(pane, obs))
		}
	}
	return
}

func (q *workspaceCoordinator) finish(result workspaceResult) {
	job := result.job
	q.mu.Lock()
	delete(q.active, job.cwd)
	waiters := make(map[*wsConn]workspaceWaiter, len(job.waiters))
	for c, waiter := range job.waiters {
		if target, ok := q.targets[c]; ok && target.cwd == job.cwd && target.epoch == waiter.epoch && c.ctx.Err() == nil {
			waiters[c] = waiter
		}
	}
	q.due[job.cwd] = time.Now().Add(q.s.level2Interval)
	q.mu.Unlock()
	if result.err == nil && len(waiters) > 0 {
		// Newly discovered L2 refs must be immediately attachable, even if an
		// unrelated host scan is blocked. Empty catalogs are tombstones too.
		q.s.publishWorkspaceCatalog(job.cwd, job.started, result.catalog)
	}
	projection := map[string][]protocol.Session{job.cwd: result.rows}
	for c, waiter := range waiters {
		err := result.err
		if !time.Now().Before(waiter.deadline) {
			err = context.DeadlineExceeded
		}
		if frame, tag := c.level2Output(waiter.epoch, projection, err); frame != nil {
			c.sendCatalog(frame, tag, nil)
		}
	}
}

func (q *workspaceCoordinator) loop() {
	defer close(q.done)
	defer func() {
		q.workers.Wait() // shared loopCtx cancels active discovery/sampling first
		q.mu.Lock()
		clear(q.targets)
		clear(q.pending)
		clear(q.active)
		clear(q.due)
		q.order = nil
		q.mu.Unlock()
	}()
	timer := time.NewTimer(time.Hour)
	timer.Stop()
	defer timer.Stop()
	for {
		if q.s.loopCtx.Err() != nil {
			return
		}
		next := q.maintain(time.Now())
		if !timer.Stop() {
			select {
			case <-timer.C:
			default:
			}
		}
		var tick <-chan time.Time
		if !next.IsZero() {
			timer.Reset(time.Until(next))
			tick = timer.C
		}
		select {
		case <-q.s.loopCtx.Done():
			return
		case <-q.wakeCh:
		case <-tick:
		case result := <-q.results:
			q.finish(result)
		}
	}
}

// waitingConnections lets Server.Close terminate accepted scoped requests just
// like accepted List requests, instead of silently dropping them at shutdown.
func (q *workspaceCoordinator) waitingConnections() []*wsConn {
	q.mu.Lock()
	defer q.mu.Unlock()
	seen := make(map[*wsConn]bool)
	for _, jobs := range []map[string]*workspaceJob{q.pending, q.active} {
		for _, job := range jobs {
			for c := range job.waiters {
				seen[c] = true
			}
		}
	}
	out := make([]*wsConn, 0, len(seen))
	for c := range seen {
		out = append(out, c)
	}
	return out
}
