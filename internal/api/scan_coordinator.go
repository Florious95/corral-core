package api

// scan_coordinator.go owns catalog admission, freshness cutoffs and publication.
// @contract
// @pre NewServer initializes this coordinator before exposing the handler
// @post at most one Discover+Sample worker and one coalesced pending generation
// @err scan failures retain last-good; deadline/queue overload terminates the affected transport
// @inv no per-request goroutines; immutable catalog/snapshot/seq publish together; sends outside locks

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

const (
	catalogPerConnLimit = 16
	catalogGlobalLimit  = 1024
	catalogScanTimeout  = 30 * time.Second
	catalogListTimeout  = 35 * time.Second
)

type catalogWaiter struct {
	c        *wsConn
	id       uint64 // admission identity, never the reusable wire req_id
	req      uint32
	deadline time.Time
}

type catalogGeneration struct {
	id         uint64
	lists      []*catalogWaiter
	l2         map[*wsConn]uint64
	cold       int
	background bool
	done       chan struct{}
	err        error // read only after done closes
	cancel     context.CancelFunc
}

type catalogResult struct {
	generation *catalogGeneration
	catalog    *sessionCatalog
	snapshot   *modelSnapshot
	level2     map[string][]protocol.Session // original L2 model order, same observations
	err        error
}

type catalogClient struct {
	waiting   int
	watermark uint64
}
type catalogOutput struct {
	c      *wsConn
	frame  protocol.Typed
	epoch  uint64
	waiter *catalogWaiter
}

type scanCoordinator struct {
	s                     *Server
	mu                    sync.Mutex // admission only; never held over discovery, sampling or enqueue
	closed                bool
	generation, admission uint64
	active, pending       *catalogGeneration
	clients               map[*wsConn]*catalogClient
	waiting               int
	outstanding           map[uint64]*catalogWaiter // scanning, queued and writing; until actual completion
	wakeCh                chan struct{}
	jobs                  chan *catalogGeneration
	results               chan catalogResult
	done, workerDone      chan struct{}
}

func newScanCoordinator(s *Server) *scanCoordinator {
	q := &scanCoordinator{s: s, clients: make(map[*wsConn]*catalogClient), outstanding: make(map[uint64]*catalogWaiter), wakeCh: make(chan struct{}, 1), jobs: make(chan *catalogGeneration, 1), results: make(chan catalogResult, 1), done: make(chan struct{}), workerDone: make(chan struct{})}
	go q.worker()
	go q.loop()
	return q
}

func (q *scanCoordinator) wake() {
	select {
	case q.wakeCh <- struct{}{}:
	default:
	}
}
func (q *scanCoordinator) pendingLocked() *catalogGeneration {
	if q.pending == nil {
		q.generation++
		q.pending = &catalogGeneration{id: q.generation, l2: make(map[*wsConn]uint64), done: make(chan struct{})}
	}
	return q.pending
}

func (q *scanCoordinator) list(c *wsConn, req uint32) {
	q.mu.Lock()
	if q.closed {
		q.mu.Unlock()
		c.abortCatalog("catalog_closed")
		return
	}
	if c.ctx.Err() != nil {
		q.mu.Unlock()
		return
	}
	state := q.clients[c]
	if state == nil {
		state = &catalogClient{}
		q.clients[c] = state
	}
	if state.waiting >= catalogPerConnLimit || q.waiting >= catalogGlobalLimit {
		perConn, total := state.waiting, q.waiting
		q.mu.Unlock()
		q.s.log.Warn("catalog: admission rejected", "conn", c.id, "req_id", req, "conn_waiters", perConn, "conn_limit", catalogPerConnLimit, "total_waiters", total, "total_limit", catalogGlobalLimit)
		c.abortCatalog("catalog_backpressure")
		return
	}
	q.admission++
	g := q.pendingLocked()
	w := &catalogWaiter{c: c, id: q.admission, req: req, deadline: time.Now().Add(catalogListTimeout)}
	g.lists = append(g.lists, w)
	q.outstanding[w.id] = w
	state.waiting++
	q.waiting++
	q.mu.Unlock()
	q.wake()
}

func (q *scanCoordinator) level2(c *wsConn, epoch uint64) {
	q.mu.Lock()
	if !q.closed && c.ctx.Err() == nil {
		if q.active != nil {
			delete(q.active.l2, c)
		}
		if q.pending != nil {
			delete(q.pending.l2, c)
		}
		q.pendingLocked().l2[c] = epoch
	}
	q.mu.Unlock()
	q.wake()
}

func (q *scanCoordinator) removeLevel2(c *wsConn) {
	q.mu.Lock()
	if q.active != nil {
		delete(q.active.l2, c)
	}
	if q.pending != nil {
		delete(q.pending.l2, c)
	}
	q.mu.Unlock()
	q.wake()
}

func (q *scanCoordinator) removeLocked(c *wsConn) {
	for _, g := range []*catalogGeneration{q.active, q.pending} {
		if g == nil {
			continue
		}
		kept := g.lists[:0]
		for _, w := range g.lists {
			if w.c != c {
				kept = append(kept, w)
			}
		}
		// Clear removed pointers; backing arrays are bounded but must not retain peers.
		clear(g.lists[len(kept):])
		g.lists = kept
		delete(g.l2, c)
	}
	for id, w := range q.outstanding {
		if w.c == c {
			delete(q.outstanding, id)
			q.waiting--
		}
	}
	delete(q.clients, c)
}
func (q *scanCoordinator) remove(c *wsConn) {
	q.mu.Lock()
	q.removeLocked(c)
	q.mu.Unlock()
	q.wake()
}

func (q *scanCoordinator) cadence() {
	q.mu.Lock()
	if !q.closed && q.s.countAuthed() > 0 {
		q.pendingLocked().background = true
	}
	q.mu.Unlock()
	q.wake()
}

// wait is used only by cold Subscribe (reader-ordered) and the synchronous
// rebuild seam. Cold callers share active initialization; explicit refreshes
// enter pending and therefore never reuse a pre-admission sample.
func (q *scanCoordinator) wait(ctx context.Context, initial bool) error {
	ctx, cancel := context.WithTimeout(ctx, catalogScanTimeout)
	defer cancel()
	q.mu.Lock()
	if q.closed {
		q.mu.Unlock()
		return context.Canceled
	}
	if snap, _ := q.s.currentSnapshot(); initial && snap != nil {
		q.mu.Unlock()
		return nil
	}
	g := q.active
	if !initial || g == nil {
		g = q.pendingLocked()
	}
	g.cold++
	q.mu.Unlock()
	q.wake()
	defer func() { q.mu.Lock(); g.cold--; q.mu.Unlock(); q.wake() }()
	select {
	case <-g.done:
		return g.err
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (q *scanCoordinator) neededLocked(g *catalogGeneration) bool {
	return len(g.lists) > 0 || len(g.l2) > 0 || g.cold > 0 || (g.background && q.s.countAuthed() > 0)
}

func (q *scanCoordinator) worker() {
	defer close(q.workerDone)
	for {
		select {
		case <-q.s.loopCtx.Done():
			return
		case g := <-q.jobs:
			ctx, cancel := context.WithTimeout(q.s.loopCtx, catalogScanTimeout)
			q.mu.Lock()
			g.cancel = cancel
			if !q.neededLocked(g) {
				cancel()
			}
			q.mu.Unlock()
			result := q.scan(ctx, g)
			// A dependency returning a partial result after cancellation cannot publish.
			if ctx.Err() != nil {
				result.err = ctx.Err()
			}
			cancel()
			select {
			case q.results <- result:
			case <-q.s.loopCtx.Done():
				return
			}
		}
	}
}

func (q *scanCoordinator) scan(ctx context.Context, g *catalogGeneration) catalogResult {
	result := catalogResult{generation: g}
	model, err := q.s.discoverer.Discover(ctx)
	if err != nil {
		result.err = fmt.Errorf("api: discover: %w", err)
		return result
	}
	observations, err := nodeprobe.SampleModel(ctx, model, q.s.nodeprobe)
	if err != nil {
		result.err = fmt.Errorf("api: nodeprobe: %w", err)
		return result
	}
	if q.s.filterAgents {
		model = filterModelToIdentifiedAgents(model, observations)
	}
	result.catalog = newSessionCatalog()
	result.catalog.rebuild(model, observations)
	result.snapshot = buildSnapshot(result.catalog)
	result.level2 = make(map[string][]protocol.Session)
	for _, ws := range model.Workspaces {
		for _, pane := range ws.Panes {
			observation, ok := observations[sessionRef(pane)]
			if !ok {
				observation = nodeprobe.Unknown()
			}
			result.level2[ws.CWD] = append(result.level2[ws.CWD], sessionFromPane(pane, observation))
		}
	}
	return result
}

// finish is the single publication/output sequencer. Admission may continue
// during enqueue, but a later generation cannot overtake these outputs.
func (q *scanCoordinator) finish(result catalogResult) {
	s := q.s
	prev, prevSeq := s.currentSnapshot()
	var delta *protocol.ListDelta
	if result.err == nil && (prev != nil || prevSeq != 0) {
		previous := prev
		if previous == nil {
			previous = &modelSnapshot{}
		}
		delta = result.snapshot.diff(previous)
		if len(delta.AddedSessions)+len(delta.RemovedRefs)+len(delta.ChangedSessions)+len(delta.ChangedWorkspaces) == 0 {
			delta = nil
		}
	}
	q.mu.Lock()
	g := result.generation
	if q.closed {
		q.mu.Unlock()
		return
	}
	if !q.neededLocked(g) {
		result.err = context.Canceled
		delta = nil
	}
	s.snapMu.Lock()
	if result.err == nil {
		if s.seq == 0 {
			s.seq = 1
		} else if delta != nil {
			s.seq++
		}
		s.catalog, s.snapshot, s.scanGeneration = result.catalog, result.snapshot, g.id
		s.level2Projection = result.level2
	} else if s.seq == 0 && len(g.lists) > 0 {
		// Empty compatibility Listing(1) establishes a wire baseline only.
		// A subsequent successful catalog must delta from that empty baseline.
		s.seq = 1
	}
	snap, seq, projection := s.snapshot, s.seq, s.level2Projection
	if seq == 0 {
		seq = 1
	}
	s.snapMu.Unlock()
	outputs := []catalogOutput{}
	aborts := map[*wsConn]string{}
	now := time.Now()
	for _, w := range g.lists {
		state := q.clients[w.c]
		if state == nil {
			continue
		}
		if !now.Before(w.deadline) || result.err == context.DeadlineExceeded {
			aborts[w.c] = "catalog_timeout"
			continue
		}
		frame := &protocol.Listing{ReqID: w.req, Seq: seq}
		if snap != nil {
			frame.Workspaces = snap.listing()
		}
		outputs = append(outputs, catalogOutput{c: w.c, frame: frame, waiter: w})
		state.watermark = seq

	}
	// Listings(S) precede all deltas; a connection answered above skips Delta(S).
	if delta != nil {
		delta.Seq = seq
		for c, state := range q.clients {
			if state.watermark > 0 && state.watermark < seq {
				outputs = append(outputs, catalogOutput{c: c, frame: delta})
				state.watermark = seq
			}
		}
	}
	for c, epoch := range g.l2 {
		frame, tag := c.level2Output(epoch, projection, result.err)
		if frame != nil {
			outputs = append(outputs, catalogOutput{c: c, frame: frame, epoch: tag})
		}
	}
	completed := append([]*catalogWaiter(nil), g.lists...)
	clear(g.lists)
	g.lists = nil
	clear(g.l2)
	g.err = result.err
	q.active = nil
	close(g.done)
	q.mu.Unlock()
	if result.err != nil {
		s.log.Warn("catalog: scan failed", "generation", g.id, "err", result.err, "had_cache", prev != nil, "seq", seq)
	}
	for _, w := range completed {
		s.log.Info("listing: refresh on open", "conn", w.c.id, "admission", w.id, "req_id", w.req, "scan_generation", g.id, "had_cache", prev != nil, "prev_seq", prevSeq, "cur_seq", seq, "cur_sessions", snapshotSessionCount(snap), "refresh_err", errString(result.err))
	}
	for c, reason := range aborts {
		c.abortCatalog(reason)
	}
	for _, out := range outputs {
		if _, bad := aborts[out.c]; !bad {
			out.c.sendCatalog(out.frame, out.epoch, out.waiter)
		}
	}
}

// maintain expires bounded waiters, prunes vanished demand and hands exactly
// one generation to the worker. The handoff is the freshness cutoff.
func (q *scanCoordinator) maintain(now time.Time) time.Time {
	q.mu.Lock()
	expired := map[*wsConn]bool{}
	var deadline time.Time
	for _, w := range q.outstanding {
		if !now.Before(w.deadline) {
			expired[w.c] = true
		}
		if deadline.IsZero() || w.deadline.Before(deadline) {
			deadline = w.deadline
		}
	}
	for c := range expired {
		q.removeLocked(c)
	}
	if q.active != nil && !q.neededLocked(q.active) && q.active.cancel != nil {
		q.active.cancel()
	}
	if q.pending != nil && !q.neededLocked(q.pending) {
		q.pending.err = context.Canceled
		close(q.pending.done)
		q.pending = nil
	}
	if !q.closed && q.active == nil && q.pending != nil {
		g := q.pending
		q.pending = nil
		q.active = g
		// Capture current L2 epochs at this scan's cutoff, never at completion.
		q.s.trackersMu.Lock()
		for c := range q.s.trackers {
			if epoch := c.currentLevel2Epoch(); epoch != 0 {
				g.l2[c] = epoch
			}
		}
		q.s.trackersMu.Unlock()
		q.jobs <- g // capacity 1, only when the preceding worker result was consumed
	}
	q.mu.Unlock()
	for c := range expired {
		c.abortCatalog("catalog_timeout")
	}
	return deadline
}

func (q *scanCoordinator) loop() {
	defer close(q.done)
	defer func() {
		<-q.workerDone
		q.mu.Lock()
		for _, g := range []*catalogGeneration{q.active, q.pending} {
			if g != nil {
				g.err = context.Canceled
				close(g.done)
				clear(g.lists)
				g.lists = nil
				clear(g.l2)
			}
		}
		q.active, q.pending = nil, nil
		q.waiting = 0
		clear(q.clients)
		clear(q.outstanding)
		q.mu.Unlock()
	}()
	timer := time.NewTimer(time.Hour)
	timer.Stop()
	defer timer.Stop()
	var nextList, nextL2 time.Time
	for {
		if q.s.loopCtx.Err() != nil {
			return
		}
		now := time.Now()
		if q.s.countAuthed() == 0 {
			nextList = time.Time{}
		} else if nextList.IsZero() {
			nextList = now.Add(q.s.listInterval)
		}
		if q.s.countLevel2() == 0 {
			nextL2 = time.Time{}
		} else if nextL2.IsZero() {
			nextL2 = now.Add(q.s.level2Interval)
		}
		dueList := !nextList.IsZero() && !now.Before(nextList)
		dueL2 := !nextL2.IsZero() && !now.Before(nextL2)
		if dueList || dueL2 {
			q.mu.Lock()
			if !q.closed && q.active == nil && q.pending == nil {
				g := q.pendingLocked()
				g.background = dueList
				if dueL2 {
					q.s.trackersMu.Lock()
					for c := range q.s.trackers {
						if epoch := c.currentLevel2Epoch(); epoch != 0 {
							g.l2[c] = epoch
						}
					}
					q.s.trackersMu.Unlock()
				}
			}
			q.mu.Unlock()
			if dueList {
				nextList = now.Add(q.s.listInterval)
			}
			if dueL2 {
				nextL2 = now.Add(q.s.level2Interval)
			}
		}
		deadline := q.maintain(now)
		for _, d := range []time.Time{nextList, nextL2} {
			if !d.IsZero() && (deadline.IsZero() || d.Before(deadline)) {
				deadline = d
			}
		}
		var tick <-chan time.Time
		if !timer.Stop() {
			select {
			case <-timer.C:
			default:
			}
		}
		if !deadline.IsZero() {
			timer.Reset(time.Until(deadline))
			tick = timer.C
		}
		select {
		case <-q.s.loopCtx.Done():
			return
		case <-q.wakeCh:
		case <-tick:
		case result := <-q.results:
			q.finish(result)
			// Same cadence as the old scan-then-wait loops, with both projections
			// satisfied by this one scan instead of two independent subprocess chains.
			if !nextList.IsZero() {
				nextList = time.Now().Add(q.s.listInterval)
			}
			if !nextL2.IsZero() {
				nextL2 = time.Now().Add(q.s.level2Interval)
			}
		}
	}
}

func (q *scanCoordinator) close() {
	q.mu.Lock()
	q.closed = true
	affected := make(map[*wsConn]bool)
	for _, w := range q.outstanding {
		affected[w.c] = true
	}
	for _, g := range []*catalogGeneration{q.active, q.pending} {
		if g == nil {
			continue
		}
		for _, w := range g.lists {
			affected[w.c] = true
		}
		for c := range g.l2 {
			affected[c] = true
		}
	}
	q.s.loopStop()
	q.mu.Unlock()
	// Pending accepted requests get a real terminal result, not silent removal.
	for c := range affected {
		c.abortCatalog("catalog_closed")
	}
	<-q.done // dependencies must honor context; never start a replacement worker
}

// writeDeadline keeps admission identity and its absolute deadline under the
// same lock used by the one timer. Removed/canceled admissions cannot write.
func (q *scanCoordinator) writeDeadline(w *catalogWaiter) (time.Time, bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	return w.deadline, q.outstanding[w.id] == w
}

// written is called only after the real network Write succeeds. Queue admission
// is not completion, and reusable wire req_id is never the accounting key.
func (q *scanCoordinator) written(w *catalogWaiter, finished time.Time) {
	q.mu.Lock()
	if q.outstanding[w.id] != w {
		q.mu.Unlock()
		return
	}
	if !finished.Before(w.deadline) {
		q.mu.Unlock()
		w.c.abortCatalog("catalog_timeout")
		return
	}
	delete(q.outstanding, w.id)
	q.waiting--
	q.clients[w.c].waiting--
	q.mu.Unlock()
	q.wake()
}
