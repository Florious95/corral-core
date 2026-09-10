package api

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

func catalogEventually(t *testing.T, description string, f func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !f() {
		if time.Now().After(deadline) {
			t.Fatal(description)
		}
		time.Sleep(time.Millisecond)
	}
}
func catalogServer(t *testing.T) (*wsEnv, *wsConn, *catalogGate) {
	g := newCatalogGate(testModel())
	g.arm("Discover")
	e := startWS(t, Options{Token: "test-token", Discoverer: g, Nodeprobe: catalogGateSampler{g}, ListInterval: time.Hour, Level2Interval: time.Hour})
	e.auth()
	catalogGateEntered(t, g, "Discover")
	g.open()
	e.sendFrame(&protocol.List{ReqID: 1})
	mustListing(t, e, 1)
	var c *wsConn
	e.srv.trackersMu.Lock()
	for peer := range e.srv.trackers {
		c = peer
	}
	e.srv.trackersMu.Unlock()
	if c == nil {
		t.Fatal("real connection not tracked")
	}
	catalogEventually(t, "initial Listing write not settled", func() bool { q := e.srv.scans; q.mu.Lock(); defer q.mu.Unlock(); return q.clients[c].waiting == 0 })
	return e, c, g
}
func catalogPeer(t *testing.T, e *wsEnv, init func(*wsConn)) (*wsEnv, *wsConn) {
	t.Helper()
	captured := make(chan *wsConn, 1)
	e.srv.trackersMu.Lock()
	e.srv.connInit = func(c *wsConn) {
		if init != nil {
			init(c)
		}
		captured <- c
	}
	e.srv.trackersMu.Unlock()
	conn, _, err := websocket.Dial(context.Background(), "ws"+strings.TrimPrefix(e.hsrv.URL, "http")+"/ws", nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.CloseNow() })
	other := &wsEnv{t: t, srv: e.srv, hsrv: e.hsrv, conn: conn}
	other.auth()
	// auth_ack synchronizes startup, and the trackers lock restores the seam.
	e.srv.trackersMu.Lock()
	e.srv.connInit = nil
	e.srv.trackersMu.Unlock()
	select {
	case c := <-captured:
		return other, c
	case <-time.After(3 * time.Second):
		t.Fatal("connection init not reached")
	}
	return nil, nil
}
func catalogClosed(t *testing.T, e *wsEnv, c *wsConn, reason string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	for {
		_, _, err := e.conn.Read(ctx)
		if err != nil {
			if ctx.Err() != nil {
				t.Fatal("client deadline is not transport termination")
			}
			break
		}
	}
	if got := c.catalogCloseReason.Load(); got != reason {
		t.Fatalf("close reason=%v want=%s", got, reason)
	}
	catalogEventually(t, "waiters not removed", func() bool { q := e.srv.scans; q.mu.Lock(); defer q.mu.Unlock(); _, ok := q.clients[c]; return !ok })
}
func catalogIdle(t *testing.T, s *Server) {
	catalogEventually(t, "scan worker did not finish", func() bool {
		s.scans.mu.Lock()
		defer s.scans.mu.Unlock()
		return s.scans.active == nil && s.scans.pending == nil
	})
}

func TestScanSingleFlightAndOnePendingGeneration(t *testing.T) {
	e, c, g := catalogServer(t)
	q := e.srv.scans
	catalogIdle(t, e.srv)
	// A second arrival while bootstrap Discover was gated proves parallelism.
	select {
	case <-g.entered:
		t.Fatal("parallel scan worker: duplicate bootstrap gate arrival")
	default:
	}
	g.mu.Lock()
	before, beforeSample := g.calls["Discover"], g.calls["Sample"]
	g.mu.Unlock()
	g.arm("Discover")
	defer g.open()
	q.list(c, 10)
	catalogGateEntered(t, g, "Discover")
	q.mu.Lock()
	if q.active == nil {
		q.mu.Unlock()
		t.Fatal("parallel scan worker: gate arrived outside active generation")
	}
	active := q.active.id
	q.mu.Unlock()
	for i := 0; i < 5; i++ {
		q.list(c, 7)
	} // duplicate legal wire req_id are separate intents
	c.handleLevel2Subscribe(protocol.Level2Subscribe{Workspace: "/ws/a"})
	q.mu.Lock()
	if q.active == nil || q.active.id != active || q.pending == nil || q.pending.id != active+1 || len(q.pending.lists) != 5 {
		t.Errorf("not one active/one pending: active=%+v pending=%+v", q.active, q.pending)
	}
	ids := map[uint64]bool{}
	for _, w := range q.pending.lists {
		if ids[w.id] {
			t.Error("admission identity collapsed")
		}
		ids[w.id] = true
	}
	q.mu.Unlock()
	// No second Discover may reach the blocked worker boundary.
	select {
	case <-g.entered:
		t.Fatal("parallel scan worker")
	case <-time.After(50 * time.Millisecond):
	}
	g.open()
	counts := map[uint32]int{}
	for counts[10] < 1 || counts[7] < 5 {
		f := e.readControl()
		if l, ok := f.(protocol.Listing); ok {
			counts[l.ReqID]++
		}
	}
	if counts[7] != 5 || counts[10] != 1 {
		t.Fatal(counts)
	}
	catalogIdle(t, e.srv)
	catalogEventually(t, "writes not settled", func() bool { q.mu.Lock(); defer q.mu.Unlock(); return q.waiting == 0 })
	g.mu.Lock()
	after, afterSample := g.calls["Discover"], g.calls["Sample"]
	g.mu.Unlock()
	if afterSample-beforeSample != 2 {
		t.Fatalf("samples=%d want one per socket per generation", afterSample-beforeSample)
	}
	if after-before != 2 {
		t.Fatalf("scans=%d want 2 generations", after-before)
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.waiting != 0 {
		t.Fatalf("remaining waiters=%d", q.waiting)
	}
}

func TestCatalogSnapshotSequenceAtomicCommit(t *testing.T) {
	md := &mutableDiscoverer{model: testModel()}
	s := NewServer(Options{Discoverer: md, ListInterval: time.Hour})
	defer s.Close()
	if err := s.rebuildCatalog(context.Background()); err != nil {
		t.Fatal(err)
	}
	first, seq := s.currentSnapshot()
	if first == nil || seq != 1 {
		t.Fatal("initial publication missing")
	}
	if err := s.rebuildCatalog(context.Background()); err != nil {
		t.Fatal(err)
	}
	if s.currentSeq() != seq {
		t.Fatal("unchanged scan advanced listing seq")
	}
	stop := make(chan struct{})
	joined := make(chan struct{})
	bad := make(chan string, 1)
	go func() {
		defer close(joined)
		for {
			select {
			case <-stop:
				return
			default:
			}
			s.snapMu.RLock()
			snap, n, generation := s.snapshot, s.seq, s.scanGeneration
			entries := s.catalog.list()
			if snap == nil || n == 0 || generation == 0 {
				select {
				case bad <- "missing publication component":
				default:
				}
			}
			for _, entry := range entries {
				if got, ok := snap.byRef[entry.ref]; !ok || got != toSession(entry) {
					select {
					case bad <- "mixed catalog/snapshot generation":
					default:
					}
				}
			}
			for _, sessions := range s.level2Projection {
				for _, sess := range sessions {
					if got, ok := snap.byRef[sess.Ref]; !ok || got != sess {
						select {
						case bad <- "mixed L2 publication generation":
						default:
						}
					}
				}
			}
			if len(entries) != len(snap.byRef) {
				select {
				case bad <- "mixed ref set":
				default:
				}
			}
			s.snapMu.RUnlock()
		}
	}()
	for i := 0; i < 20; i++ {
		m := testModel()
		m.Workspaces[0].Panes[0].WindowName = fmt.Sprintf("generation-%d", i)
		md.set(m)
		if err := s.rebuildCatalog(context.Background()); err != nil {
			t.Error(err)
			break
		}
	}
	close(stop)
	<-joined
	select {
	case why := <-bad:
		t.Fatal(why)
	default:
	}
	// Natural errors retain both catalog and seq; no synthetic unknown escalation.
	catalogIdle(t, s)
	s.discoverer = scriptedDiscoverer{err: errors.New("natural scan failure")}
	prev, prevSeq := s.currentSnapshot()
	if err := s.rebuildCatalog(context.Background()); err == nil {
		t.Fatal("missing failure")
	}
	after, afterSeq := s.currentSnapshot()
	if after != prev || afterSeq != prevSeq {
		t.Fatal("failure changed publication")
	}
}

func TestListReplyDeltaWatermarkContinuity(t *testing.T) {
	e, c, g := catalogServer(t)
	other, peer := catalogPeer(t, e, nil)
	other.sendFrame(&protocol.List{ReqID: 2})
	base := mustListing(t, other, 2)
	catalogIdle(t, e.srv)
	// Gate the new generation, then change its immutable input before Discover returns.
	g.arm("Discover")
	defer g.open()
	e.srv.scans.list(c, 7)
	catalogGateEntered(t, g, "Discover")
	m := testModel()
	m.Workspaces[0].Panes[0].WindowName = "changed"
	g.mu.Lock()
	g.model = m
	g.mu.Unlock()
	g.open()
	listing := mustListing(t, e, 7)
	f := other.readControl()
	d, ok := f.(protocol.ListDelta)
	if !ok || d.Seq != base.Seq+1 || listing.Seq != d.Seq {
		t.Fatalf("baseline=%d listing=%d delta=%+v", base.Seq, listing.Seq, f)
	}
	// An unchanged followup must not be preceded by duplicated Delta(S).
	e.sendFrame(&protocol.List{ReqID: 7})
	f = e.readControl()
	l, ok := f.(protocol.Listing)
	if !ok || l.ReqID != 7 || l.Seq != listing.Seq {
		t.Fatalf("same-connection stale/duplicate delta: %+v", f)
	}
	e.srv.scans.mu.Lock()
	water := e.srv.scans.clients[peer].watermark
	e.srv.scans.mu.Unlock()
	if water != d.Seq {
		t.Fatal("delta watermark not advanced")
	}
	t.Run("slow-peer", func(t *testing.T) {
		entered, release := make(chan struct{}), make(chan struct{})
		var hold atomic.Bool
		var once sync.Once
		slow, sc := catalogPeer(t, e, func(c *wsConn) {
			c.beforeWriterFrame = func(m wsMsg) {
				if hold.Load() {
					once.Do(func() {
						close(entered)
						select {
						case <-release:
						case <-c.ctx.Done():
						}
					})
				}
			}
		})
		defer close(release)
		hold.Store(true)
		sc.sendCatalog(&protocol.Listing{ReqID: 1, Seq: 1}, 0, nil)
		select {
		case <-entered:
		case <-time.After(3 * time.Second):
			t.Fatal("writer gate not entered")
		}
		for i := 0; i < 257; i++ {
			sc.sendCatalog(&protocol.Listing{ReqID: uint32(i + 1), Seq: 1}, 0, nil)
		}
		catalogClosed(t, slow, sc, "catalog_backpressure")
		e.sendFrame(&protocol.List{ReqID: 9})
		mustListing(t, e, 9)
	})
}

func TestLevel2WorkspaceEpochRejectsOldCompletion(t *testing.T) {
	for _, order := range []string{"before-new-completion", "after-new-completion"} {
		t.Run(order, func(t *testing.T) {
			e, _, g := catalogServer(t)
			entered, release := make(chan struct{}), make(chan struct{})
			var once sync.Once
			writes := make(chan uint64, 8)
			other, c := catalogPeer(t, e, func(c *wsConn) {
				c.beforeWriterFrame = func(m wsMsg) {
					if m.level2Epoch != 0 {
						once.Do(func() {
							close(entered)
							select {
							case <-release:
							case <-c.ctx.Done():
							}
						})
					}
				}
				c.writeAttempt = func(m wsMsg) {
					if m.level2Epoch != 0 {
						writes <- m.level2Epoch
					}
				}
			})
			defer func() {
				select {
				case <-release:
				default:
					close(release)
				}
			}()
			// testModel has ONE workspace. Build a distinct W2 ref explicitly;
			// indexing Workspaces[1] would panic before either epoch oracle.
			epochModel := func(windowName string) *discovery.Model {
				model := testModel()
				second := testModel().Workspaces[0]
				second.CWD = "/ws/b"
				second.Panes = second.Panes[:1]
				second.Panes[0].CWD = "/ws/b"
				second.Panes[0].PaneID = "%2"
				second.Panes[0].Session = "epoch-b"
				second.Panes[0].WindowName = windowName
				model.Workspaces = append(model.Workspaces, second)
				return model
			}
			oldModel := epochModel("old-epoch")
			g.mu.Lock()
			g.model = oldModel
			g.mu.Unlock()
			other.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})
			select {
			case <-entered:
			case <-time.After(3 * time.Second):
				t.Fatal("L2 writer gate absent")
			}
			catalogIdle(t, e.srv)
			old := c.currentLevel2Epoch()
			e.srv.snapMu.RLock()
			projection := e.srv.level2Projection
			e.srv.snapMu.RUnlock()
			// A different immutable old projection defeats equal-key accidental rejection.
			if len(projection["/ws/b"]) == 0 {
				t.Fatal("old W2 projection absent")
			}
			m := epochModel("new-epoch-only")
			g.mu.Lock()
			g.model = m
			g.mu.Unlock()
			g.arm("Sample")
			defer g.open()
			// Reader ordering has its own real WS gates. Here synchronous handlers place
			// the exact off/on demand while the new scanner is held, without polling.
			c.handleLevel2Subscribe(protocol.Level2Subscribe{Workspace: "/ws/b"})
			catalogGateEntered(t, g, "Sample")
			c.handleLevel2Unsubscribe(protocol.Level2Unsubscribe{})
			c.handleLevel2Subscribe(protocol.Level2Subscribe{Workspace: "/ws/b"})
			q := e.srv.scans
			q.mu.Lock()
			final := q.pending
			q.mu.Unlock()
			if final == nil {
				t.Fatal("final generation not registered behind barrier")
			}
			checkOld := func() {
				c.level2Mu.Lock()
				key, at := c.level2Snap, c.level2PushedAt
				c.level2Mu.Unlock()
				frame, _ := c.level2Output(old, projection, nil)
				c.level2Mu.Lock()
				unchanged := c.level2Snap == key && c.level2PushedAt == at
				c.level2Mu.Unlock()
				if frame != nil || !unchanged {
					t.Fatal("stale completion admitted")
				}
			}
			if order == "before-new-completion" {
				checkOld()
			}
			g.open()
			select {
			case <-final.done:
			case <-time.After(3 * time.Second):
				t.Fatal("final generation completion barrier absent")
			}
			c.level2Mu.Lock()
			key, at := c.level2Snap, c.level2PushedAt
			c.level2Mu.Unlock()
			if at.IsZero() || key == level2SnapKey(projection["/ws/b"]) {
				t.Fatal("new projection is not different from old")
			}
			if order == "after-new-completion" {
				checkOld()
			}
			close(release)
			frame := waitLevel2Frame(t, other, 3*time.Second)
			if frame.Workspace != "/ws/b" || level2SnapKey(frame.Sessions) != key {
				t.Fatalf("queued old workspace leaked: %s", frame.Workspace)
			}
			select {
			case epoch := <-writes:
				if epoch != old+3 {
					t.Fatalf("queued old workspace leaked: epoch=%d current=%d", epoch, old+3)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("final epoch never wrote")
			}
		})
	}
}

func TestScanWaiterDeadlineCancelAndOverload(t *testing.T) {
	t.Run("zero-wire-rejected", func(t *testing.T) {
		e, _, _ := catalogServer(t)
		if _, err := protocol.MarshalFrame(&protocol.List{ReqID: 0}); !errors.Is(err, protocol.ErrInvalidField) {
			t.Fatalf("zero List codec verdict=%v", err)
		}
		if _, err := protocol.MarshalFrame(&protocol.Listing{ReqID: 0, Seq: 1}); !errors.Is(err, protocol.ErrInvalidField) {
			t.Fatalf("zero Listing codec verdict=%v", err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if err := e.conn.Write(ctx, websocket.MessageText, []byte(`{"v":1,"type":"list","payload":{"req_id":0}}`)); err != nil {
			t.Fatal(err)
		}
		if failure, ok := e.readControl().(protocol.ErrorFrame); !ok || failure.Code != protocol.ErrCodeInvalidField || !strings.Contains(failure.Reason, "req_id") {
			t.Fatalf("zero wire List rejection=%+v", failure)
		}
		e.sendFrame(&protocol.List{ReqID: 7})
		mustListing(t, e, 7)
	})
	t.Run("zero-internal-cancel", func(t *testing.T) {
		e, _, g := catalogServer(t)
		peer, c := catalogPeer(t, e, nil)
		q := e.srv.scans
		g.arm("Discover")
		defer g.open()
		q.list(c, 0)
		catalogGateEntered(t, g, "Discover")
		q.list(c, 0)
		q.mu.Lock()
		count := 0
		ids := map[uint64]bool{}
		for _, w := range q.outstanding {
			if w.c == c && w.req == 0 {
				count++
				ids[w.id] = true
			}
		}
		q.mu.Unlock()
		if count != 2 || len(ids) != 2 {
			t.Fatalf("internal zero admissions collapsed: count=%d ids=%d", count, len(ids))
		}
		_ = peer.conn.CloseNow()
		catalogEventually(t, "internal zero admissions survived cancel", func() bool {
			q.mu.Lock()
			defer q.mu.Unlock()
			for _, w := range q.outstanding {
				if w.c == c {
					return false
				}
			}
			return true
		})
		g.open()
		e.sendFrame(&protocol.List{ReqID: 7})
		mustListing(t, e, 7)
	})
	t.Run("queued-deadline", func(t *testing.T) {
		e, _, _ := catalogServer(t)
		q := e.srv.scans
		entered := make(chan *catalogWaiter, 1)
		var once sync.Once
		slow, c := catalogPeer(t, e, func(c *wsConn) {
			c.beforeWriterFrame = func(m wsMsg) {
				if m.catalog != nil {
					once.Do(func() { entered <- m.catalog; <-c.ctx.Done() })
				}
			}
		})
		for i := 0; i < 3; i++ {
			slow.sendFrame(&protocol.List{ReqID: 7})
		}
		var held *catalogWaiter
		select {
		case held = <-entered:
		case <-time.After(3 * time.Second):
			t.Fatal("real Listing writer barrier absent")
		}
		catalogEventually(t, "three Listings never queued", func() bool { return len(c.sendCh) == 2 })
		catalogIdle(t, e.srv)
		q.mu.Lock()
		n, total, live, remaining := q.clients[c].waiting, len(q.outstanding), q.outstanding[held.id] == held, time.Until(held.deadline)
		q.mu.Unlock()
		if n != 3 || total != 3 || !live || len(c.sendCh) >= cap(c.sendCh) {
			t.Fatalf("queued admissions lost: peer=%d total=%d live=%v", n, total, live)
		}
		if remaining < 34*time.Second || remaining > 35*time.Second {
			t.Fatalf("admission deadline=%s", remaining)
		}
		e.sendFrame(&protocol.List{ReqID: 88})
		mustListing(t, e, 88)
		q.mu.Lock()
		held.deadline = time.Now().Add(-time.Millisecond)
		q.mu.Unlock()
		q.wake()
		catalogClosed(t, slow, c, "catalog_timeout")
		q.mu.Lock()
		for _, w := range q.outstanding {
			if w.c == c {
				t.Error("queued admission survived terminal")
			}
		}
		q.mu.Unlock()
		e.sendFrame(&protocol.List{ReqID: 89})
		mustListing(t, e, 89)
	})
	t.Run("timely-duplicate-writes", func(t *testing.T) {
		e, _, _ := catalogServer(t)
		ids := make(chan uint64, 8)
		peer, c := catalogPeer(t, e, func(c *wsConn) {
			c.writeAttempt = func(m wsMsg) {
				if m.catalog != nil {
					ids <- m.catalog.id
				}
			}
		})
		for i := 0; i < 3; i++ {
			peer.sendFrame(&protocol.List{ReqID: 7})
		}
		peer.sendFrame(&protocol.List{ReqID: 99})
		count := 0
		for {
			f := peer.readControl()
			l, ok := f.(protocol.Listing)
			if !ok {
				t.Fatalf("unexpected frame: %+v", f)
			}
			if l.ReqID == 99 {
				break
			}
			if l.ReqID != 7 {
				t.Fatal(l.ReqID)
			}
			count++
		}
		if count != 3 {
			t.Fatalf("duplicate req_id replies=%d", count)
		}
		seen := map[uint64]bool{}
		for i := 0; i < 4; i++ {
			select {
			case id := <-ids:
				if seen[id] {
					t.Fatal("admission written twice")
				}
				seen[id] = true
			case <-time.After(time.Second):
				t.Fatal("write attempt absent")
			}
		}
		q := e.srv.scans
		catalogEventually(t, "successful writes did not settle", func() bool { q.mu.Lock(); defer q.mu.Unlock(); return q.clients[c].waiting == 0 })
		q.mu.Lock()
		for _, w := range q.outstanding {
			if w.c == c {
				t.Error("successful admission remains outstanding")
			}
		}
		q.mu.Unlock()
	})

	t.Run("per-connection", func(t *testing.T) {
		e, c, g := catalogServer(t)
		g.arm("Discover")
		defer g.open()
		e.srv.scans.list(c, 0)
		catalogGateEntered(t, g, "Discover")
		for i := 1; i < catalogPerConnLimit; i++ {
			e.srv.scans.list(c, 0)
		}
		if c.ctx.Err() != nil {
			t.Fatal("16 accepted requests closed prematurely")
		}
		e.srv.scans.list(c, 0)
		catalogClosed(t, e, c, "catalog_backpressure")
	})
	t.Run("global", func(t *testing.T) {
		e, c, g := catalogServer(t)
		g.arm("Discover")
		defer g.open()
		q := e.srv.scans
		q.list(c, 0)
		catalogGateEntered(t, g, "Discover")
		for i := 1; i < catalogPerConnLimit; i++ {
			q.list(c, 0)
		}
		for i := 1; i < catalogGlobalLimit/catalogPerConnLimit; i++ {
			_, peer := catalogPeer(t, e, nil)
			for j := 0; j < catalogPerConnLimit; j++ {
				q.list(peer, 0)
			}
		}
		q.mu.Lock()
		n := q.waiting
		q.mu.Unlock()
		if n != 1024 {
			t.Fatalf("global boundary not reached: %d", n)
		}
		overflow, peer := catalogPeer(t, e, nil)
		q.list(peer, 0)
		catalogClosed(t, overflow, peer, "catalog_backpressure")
		if c.ctx.Err() != nil {
			t.Fatal("global rejection canceled a different peer")
		}
	})
	t.Run("deadline", func(t *testing.T) {
		e, c, g := catalogServer(t)
		g.arm("Discover")
		defer g.open()
		q := e.srv.scans
		q.list(c, 0)
		catalogGateEntered(t, g, "Discover")
		q.mu.Lock()
		w := q.active.lists[0]
		remaining := time.Until(w.deadline)
		q.mu.Unlock()
		if remaining < 34*time.Second || remaining > 35*time.Second {
			t.Fatalf("admission deadline=%s", remaining)
		}
		if c.ctx.Err() != nil {
			t.Fatal("deadline closed before expiry")
		}
		// Deterministic clock boundary: retain the production one-shot timer and
		// expiration path, changing only the test waiter's absolute deadline.
		q.mu.Lock()
		w.deadline = time.Now().Add(-time.Millisecond)
		q.mu.Unlock()
		q.wake()
		catalogClosed(t, e, c, "catalog_timeout")
	})
	t.Run("scan-deadline", func(t *testing.T) {
		e, c, g := catalogServer(t)
		g.arm("Sample")
		defer g.open()
		e.srv.scans.list(c, 11)
		catalogGateEntered(t, g, "Sample")
		select {
		case <-c.ctx.Done():
		case <-time.After(32 * time.Second):
			t.Fatal("30s scan deadline did not terminate waiting List")
		}
		catalogClosed(t, e, c, "catalog_timeout")
	})
	t.Run("cancel-one-preserves-other", func(t *testing.T) {
		e, c, g := catalogServer(t)
		other, peer := catalogPeer(t, e, nil)
		g.arm("Discover")
		defer g.open()
		q := e.srv.scans
		q.list(c, 1)
		catalogGateEntered(t, g, "Discover")
		q.list(peer, 2)
		_ = e.conn.CloseNow()
		catalogEventually(t, "peer cancellation not observed", func() bool { return c.ctx.Err() != nil })
		g.open()
		mustListing(t, other, 2)
		if peer.ctx.Err() != nil {
			t.Fatal("one cancellation killed other peer")
		}
	})
}

func TestScanCoordinatorShutdownAndIdle(t *testing.T) {
	for _, stage := range []string{"Discover", "Sample"} {
		t.Run(stage, func(t *testing.T) {
			g := newCatalogGate(testModel())
			s := NewServer(Options{Discoverer: g, Nodeprobe: catalogGateSampler{g}, ListInterval: 10 * time.Millisecond, Level2Interval: 10 * time.Millisecond})
			defer s.Close()
			g.arm(stage)
			defer g.open()
			// Two cold demands, one actual scanner, cancellation during the chosen dependency.
			errs := make(chan error, 2)
			go func() { errs <- s.scans.wait(context.Background(), true) }()
			catalogGateEntered(t, g, stage)
			go func() { errs <- s.scans.wait(context.Background(), false) }()
			catalogEventually(t, "pending cold generation not registered", func() bool { s.scans.mu.Lock(); defer s.scans.mu.Unlock(); return s.scans.pending != nil })
			s.Close()
			for i := 0; i < 2; i++ {
				select {
				case err := <-errs:
					if err == nil {
						t.Error("closed cold wait succeeded")
					}
				case <-time.After(time.Second):
					t.Fatal("cold waiter survived Close")
				}
			}
			q := s.scans
			q.mu.Lock()
			if q.active != nil || q.pending != nil || q.waiting != 0 || len(q.clients) != 0 {
				t.Error("state survives Close")
			}
			q.mu.Unlock()
			select {
			case <-q.workerDone:
			default:
				t.Fatal("worker survives Close")
			}
			if snap, _ := s.currentSnapshot(); snap != nil {
				t.Fatal("published after cancellation")
			}
		})
	}
	t.Run("pending-List-gets-real-close", func(t *testing.T) {
		e, c, g := catalogServer(t)
		g.arm("Discover")
		defer g.open()
		e.srv.scans.list(c, 12)
		catalogGateEntered(t, g, "Discover")
		e.srv.Close()
		catalogClosed(t, e, c, "catalog_closed")
	})
	t.Run("zero-auth-zero-L2", func(t *testing.T) {
		g := newCatalogGate(testModel())
		s := NewServer(Options{Discoverer: g, ListInterval: 5 * time.Millisecond, Level2Interval: 5 * time.Millisecond})
		defer s.Close()
		time.Sleep(40 * time.Millisecond)
		g.mu.Lock()
		defer g.mu.Unlock()
		if len(g.calls) != 0 {
			t.Fatal("idle scan calls", g.calls)
		}
	})
}
