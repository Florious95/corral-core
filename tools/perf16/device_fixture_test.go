//go:build perf16fixture

package api

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

type p16DeviceDiscovery struct{ model *discovery.Model }

func (d p16DeviceDiscovery) Discover(context.Context) (*discovery.Model, error) { return d.model, nil }

type p16DeviceRound struct {
	conn       *wsConn
	sub        chan *subscription
	writer     chan struct{}
	release    chan struct{}
	once       sync.Once
	writerDone chan struct{}
}

func (r *p16DeviceRound) unblock() { r.once.Do(func() { close(r.release) }) }

// Same product server and healthy connection persist through all losses in this stage.
// Hooks are installed before publication and affect only the selected slow peer.
// This file is copied into internal/api only by the pinned hosted build gate.
func TestPerf16A6FixtureProcess(t *testing.T) {
	root, stage := os.Getenv("PERF16_A6_FIXTURE_DIR"), os.Getenv("PERF16_A6_STAGE")
	socket, session, pane := os.Getenv("PERF16_A6_TMUX_SOCKET"), os.Getenv("PERF16_A6_TMUX_SESSION"), os.Getenv("PERF16_A6_TMUX_PANE")
	if root == "" || socket == "" || session == "" || pane == "" || (stage != "bridge" && stage != "ws") {
		t.Fatal("missing device environment")
	}
	rounds, err := strconv.Atoi(os.Getenv("PERF16_A6_ROUNDS"))
	if err != nil || (rounds != 1 && rounds != 5) {
		t.Fatal("PERF16_A6_ROUNDS must be 5 (stage) or 1 (historylock)")
	}
	ref := socket + "\x1f" + pane
	write := func(dir, name string, value any) {
		data, err := json.Marshal(value)
		if err != nil {
			t.Error(err)
			return
		}
		p := filepath.Join(dir, name)
		if err = os.WriteFile(p+".next", data, 0600); err == nil {
			err = os.Rename(p+".next", p)
		}
		if err != nil {
			t.Error(err)
		}
	}
	model := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/perf16", Panes: []discovery.Pane{{Socket: socket, Session: session, PaneID: pane, CWD: "/perf16", Command: "python3", Width: 120, Height: 40}}}}}
	srv := NewServer(Options{Token: "perf16-a6-token", Discoverer: p16DeviceDiscovery{model}, ListInterval: 100 * time.Millisecond, Log: slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug}))})
	var mu sync.Mutex
	var owned []*wsConn
	var subscriptions []*subscription
	writers := make(map[*wsConn]chan struct{})
	var accepted atomic.Int32
	var healthyFrames atomic.Int64
	var healthy *wsConn
	selected := make(chan *p16DeviceRound, rounds)
	allRounds := make([]*p16DeviceRound, rounds)
	for i := range allRounds {
		allRounds[i] = &p16DeviceRound{sub: make(chan *subscription, 1), writer: make(chan struct{}, 1), release: make(chan struct{})}
	}
	srv.connInit = func(c *wsConn) {
		number := int(accepted.Add(1))
		writerDone := make(chan struct{})
		c.afterWriter = func() { close(writerDone) }
		mu.Lock()
		writers[c] = writerDone
		owned = append(owned, c)
		if number == 1 {
			healthy = c
		}
		mu.Unlock()
		var r *p16DeviceRound
		if number >= 2 && number <= rounds+1 {
			r = allRounds[number-2]
			r.conn = c
			r.writerDone = writerDone
		}
		c.beforeRelay = func(sub *subscription) {
			mu.Lock()
			subscriptions = append(subscriptions, sub)
			mu.Unlock()
			if r != nil {
				select {
				case r.sub <- sub:
				default:
					t.Error("selected connection subscribed twice")
				}
				if stage == "bridge" {
					<-r.release
				}
			}
		}
		if number == 1 {
			c.beforeWriterFrame = func(m wsMsg) {
				if m.typ != wsBinary {
					return
				}
				f, err := protocol.DecodeBinary(m.data)
				if err != nil {
					t.Error(err)
					return
				}
				if f.Kind == protocol.KindDelta {
					healthyFrames.Add(1)
				}
			}
		}
		if r == nil {
			return
		}
		if stage == "ws" {
			var first sync.Once
			c.beforeWriterFrame = func(m wsMsg) {
				if m.typ != wsBinary {
					return
				}
				f, err := protocol.DecodeBinary(m.data)
				if err != nil {
					t.Error(err)
					return
				}
				if f.Kind == protocol.KindDelta {
					first.Do(func() { r.writer <- struct{}{}; <-r.release })
				}
			}
		}
		selected <- r
	}
	httpServer := httptest.NewServer(srv.Handler())
	stop := make(chan struct{})
	var workers sync.WaitGroup
	defer func() {
		close(stop)
		for _, r := range allRounds {
			r.unblock()
		}
		workers.Wait()
		httpServer.Close()
		mu.Lock()
		conns := append([]*wsConn(nil), owned...)
		writerEnds := make([]chan struct{}, len(conns))
		for i, c := range conns {
			writerEnds[i] = writers[c]
		}
		mu.Unlock()
		for _, c := range conns {
			c.cancel()
			_ = c.conn.CloseNow()
		}
		deadline := time.NewTimer(3 * time.Second)
		defer deadline.Stop()
		for _, writerEnd := range writerEnds {
			select {
			case <-writerEnd:
			case <-deadline.C:
				t.Error("writer cleanup deadline")
				srv.Close()
				return
			}
		}
		ticker := time.NewTicker(5 * time.Millisecond)
		defer ticker.Stop()
		for {
			srv.trackersMu.Lock()
			remaining := len(srv.trackers)
			srv.trackersMu.Unlock()
			if remaining == 0 {
				break
			}
			select {
			case <-ticker.C:
			case <-deadline.C:
				t.Error("tracker cleanup deadline")
				srv.Close()
				return
			}
		}
		mu.Lock()
		subs := append([]*subscription(nil), subscriptions...)
		mu.Unlock()
		for _, sub := range subs {
			if sub.relayDone == nil {
				t.Error("missing relay done")
				continue
			}
			select {
			case <-sub.relayDone:
			case <-deadline.C:
				t.Error("relay cleanup deadline")
				srv.Close()
				return
			}
		}
		srv.Close()
		write(root, "server-cleanup.json", map[string]any{"connections": len(conns), "subscriptions": len(subs), "all_writers_and_relays_joined": true})
	}()
	if err := os.WriteFile(filepath.Join(root, "endpoint"), []byte(httpServer.URL+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	await := func(path string, limit time.Duration) bool {
		timer := time.NewTimer(limit)
		defer timer.Stop()
		tick := time.NewTicker(10 * time.Millisecond)
		defer tick.Stop()
		for {
			if _, err := os.Stat(path); err == nil {
				return true
			}
			select {
			case <-stop:
				return false
			case <-timer.C:
				t.Errorf("file boundary deadline: %s", filepath.Base(path))
				return false
			case <-tick.C:
			}
		}
	}
	workers.Add(1)
	go func() {
		defer workers.Done()
		for i := 0; i < rounds; i++ {
			dir := filepath.Join(root, fmt.Sprintf("round-%02d", i+1))
			if err := os.MkdirAll(dir, 0700); err != nil {
				t.Error(err)
				return
			}
			// Device setup does not consume the unchanged 30s loss budget.
			if !await(filepath.Join(dir, "app-start"), 90*time.Second) {
				return
			}
			timer := time.NewTimer(30 * time.Second)
			var r *p16DeviceRound
			select {
			case r = <-selected:
			case <-stop:
				timer.Stop()
				return
			case <-timer.C:
				t.Error("target absent")
				return
			}
			var sub *subscription
			select {
			case sub = <-r.sub:
			case <-stop:
				timer.Stop()
				return
			case <-timer.C:
				t.Error("relay absent")
				return
			}
			timer.Stop()
			if sub.ref != ref {
				t.Error("wrong ref")
				return
			}
			baseline := healthyFrames.Load()
			write(dir, "bridge-ready", map[string]any{"round": i + 1, "stage": stage, "ref": ref, "conn": r.conn.id, "healthy_frame_baseline": baseline})
			done := make(chan struct{})
			var progress sync.WaitGroup
			progress.Add(1)
			go func() {
				defer progress.Done()
				tick := time.NewTicker(2 * time.Millisecond)
				defer tick.Stop()
				for {
					write(dir, "progress.json", map[string]any{"healthy_frames": healthyFrames.Load() - baseline, "target_frames": r.conn.connMetrics.snapshot().FramesSent, "aborted": r.conn.catalogAborted.Load()})
					select {
					case <-done:
						return
					case <-stop:
						return
					case <-tick.C:
					}
				}
			}()
			run := func() bool {
				if !await(filepath.Join(dir, "source-go"), 30*time.Second) {
					return false
				}
				lossTimer := time.NewTimer(30 * time.Second)
				defer lossTimer.Stop()
				tick := time.NewTicker(2 * time.Millisecond)
				defer tick.Stop()
				if stage == "bridge" {
					for len(sub.loss) != 1 {
						select {
						case <-stop:
							return false
						case <-lossTimer.C:
							t.Error("real bridge loss absent")
							return false
						case <-tick.C:
						}
					}
					write(dir, "queue-boundary", map[string]any{"buffered_loss": len(sub.loss), "conn": r.conn.id})
					r.unblock()
				} else {
					select {
					case <-r.writer:
					case <-stop:
						return false
					case <-lossTimer.C:
						t.Error("writer boundary absent")
						return false
					}
				}
				select {
				case <-r.conn.ctx.Done():
				case <-stop:
					return false
				case <-lossTimer.C:
					t.Error("abort boundary absent")
					return false
				}
				// The existing once barrier completes only after queue drain and CloseNow.
				// Cancellation is not sufficient evidence of either transport or writer exit.
				r.conn.catalogAbortOnce.Do(func() { t.Error("connection cancelled without production abort") })
				want := "mirror_loss: ws_send_queue_overflow"
				if stage == "bridge" {
					want = "mirror_loss: bridge: subscriber queue overflow"
				}
				got := fmt.Sprint(r.conn.catalogCloseReason.Load())
				if got != want || len(r.conn.sendCh) != 0 {
					t.Errorf("loss cause/queue got %q/%d", got, len(r.conn.sendCh))
					return false
				}
				write(dir, "loss.json", map[string]any{"round": i + 1, "stage": stage, "conn": r.conn.id, "ref": ref, "cause": got, "abort_complete": r.conn.catalogAborted.Load(), "close_now_completed": true, "queue_remaining": len(r.conn.sendCh), "queue_capacity": cap(r.conn.sendCh), "process_queue_peak": srv.sendQueue.Snapshot().QueuePeak})
				r.unblock()
				select {
				case <-r.writerDone:
				case <-stop:
					return false
				case <-lossTimer.C:
					t.Error("old writer survived overflow")
					return false
				}
				select {
				case <-sub.relayDone:
				case <-stop:
					return false
				case <-lossTimer.C:
					t.Error("old relay survived recovery")
					return false
				}
				srv.trackersMu.Lock()
				tracked := len(srv.trackers)
				srv.trackersMu.Unlock()
				mu.Lock()
				h := healthy
				mu.Unlock()
				if h == nil || h.catalogAborted.Load() {
					t.Error("healthy aborted")
					return false
				}
				write(dir, "resources.json", map[string]any{"round": i + 1, "tracked_connections": tracked, "accepted": accepted.Load(), "goroutines": runtime.NumGoroutine(), "slow_queue": len(r.conn.sendCh), "healthy_queue": len(h.sendCh), "old_relay_joined": true, "old_writer_joined": true})
				if !await(filepath.Join(dir, "device-complete"), 90*time.Second) {
					return false
				}
				return true
			}
			ok := run()
			close(done)
			progress.Wait()
			if !ok {
				return
			}
			write(dir, "round-complete", map[string]any{"round": i + 1})
		}
		write(root, "stage-rounds-complete", map[string]any{"rounds": rounds})
	}()
	// Finite owner completion; no success before all planned rounds and final teardown.
	if !await(filepath.Join(root, "fixture-stop"), 15*time.Minute) {
		return
	}
	if _, err := os.Stat(filepath.Join(root, "stage-rounds-complete")); err != nil {
		t.Error("planned rounds incomplete")
	}
	if got := accepted.Load(); int(got) != rounds+2 {
		t.Errorf("connections got %d want %d", got, rounds+2)
	}
}
