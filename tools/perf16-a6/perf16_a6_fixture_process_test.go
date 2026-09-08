package api

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

type perf16A6Discoverer struct{ model *discovery.Model }

func (d perf16A6Discoverer) Discover(context.Context) (*discovery.Model, error) { return d.model, nil }

// Test-only orchestration: all hooks are published before Handler starts.
// The real server, bridge, tmux pipe and close path remain under test.
func TestPerf16A6FixtureProcess(t *testing.T) {
	root, stage := os.Getenv("PERF16_A6_FIXTURE_DIR"), os.Getenv("PERF16_A6_STAGE")
	socket, session, pane := os.Getenv("PERF16_A6_TMUX_SOCKET"), os.Getenv("PERF16_A6_TMUX_SESSION"), os.Getenv("PERF16_A6_TMUX_PANE")
	if root == "" || (stage != "bridge" && stage != "ws") || socket == "" || session == "" || pane == "" {
		t.Fatal("missing A6 fixture environment")
	}
	ref := socket + "\x1f" + pane
	noAbort := os.Getenv("PERF16_A6_CONTROL") == "no-abort"
	write := func(name string, value any) {
		data, err := json.Marshal(value)
		if err != nil {
			t.Error(err)
			return
		}
		path := filepath.Join(root, name)
		if err = os.WriteFile(path+".next", data, 0600); err == nil {
			err = os.Rename(path+".next", path)
		}
		if err != nil {
			t.Errorf("write %s: %v", name, err)
		}
	}
	model := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/perf16", Panes: []discovery.Pane{{Socket: socket, Session: session, PaneID: pane, CWD: "/perf16", Command: "python3", Width: 120, Height: 40}}}}}
	srv := NewServer(Options{Token: "perf16-a6-token", Discoverer: perf16A6Discoverer{model}, ListInterval: 100 * time.Millisecond, Log: slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug}))})
	var accepted atomic.Int32
	target := make(chan *wsConn, 1)
	healthy := make(chan *wsConn, 1)
	relay := make(chan *subscription, 1)
	writer := make(chan struct{}, 1)
	release := make(chan struct{})
	var releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	srv.connInit = func(c *wsConn) {
		number := accepted.Add(1)
		if number == 1 {
			healthy <- c
		}
		if number != 2 {
			return
		}
		// Local negative control of the loss action, not a product mutation.
		// The same real queues/transport/App oracle still run; suppress only
		// the once-owned abort body so the loss event cannot cause a redial.
		if noAbort {
			c.mirrorAbortOnce.Do(func() {})
		}
		c.beforeRelay = func(sub *subscription) {
			relay <- sub
			if stage == "bridge" {
				<-release
			}
		}
		if stage == "ws" {
			var first sync.Once
			c.beforeWriterFrame = func(m wsMsg) {
				if m.typ != wsBinary {
					return
				}
				p, err := protocol.DecodeBinary(m.data)
				if err != nil {
					t.Error(err)
					return
				}
				if p.Kind == protocol.KindDelta {
					first.Do(func() { writer <- struct{}{}; <-release })
				}
			}
		}
		target <- c
	}
	httpServer := httptest.NewServer(srv.Handler())
	stop := make(chan struct{})
	var workers sync.WaitGroup
	defer func() {
		close(stop)
		unblock()
		workers.Wait() // workers select stop at every wait; no unbounded fixture polling
		httpServer.CloseClientConnections()
		srv.Close()
		httpServer.Close()
	}()
	if err := os.WriteFile(filepath.Join(root, "endpoint"), []byte(httpServer.URL+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	workers.Add(1)
	go func() {
		defer workers.Done()
		// Compilation does not consume a network/behavior deadline. Android
		// publishes app-start immediately before its first real dial.
		awaitFile := func(name string) bool {
			tick := time.NewTicker(10 * time.Millisecond)
			defer tick.Stop()
			for {
				if _, err := os.Stat(filepath.Join(root, name)); err == nil {
					return true
				}
				select {
				case <-tick.C:
				case <-stop:
					return false
				}
			}
		}
		if !awaitFile("app-start") {
			return
		}
		// Setup has the same bounded budget as the Android initial snapshot.
		timer := time.NewTimer(30 * time.Second)
		defer timer.Stop()
		var c *wsConn
		select {
		case c = <-target:
		case <-stop:
			return
		case <-timer.C:
			t.Error("target connection absent")
			return
		}
		var sub *subscription
		select {
		case sub = <-relay:
		case <-stop:
			return
		case <-timer.C:
			t.Error("target relay absent")
			return
		}
		if sub.ref != ref {
			t.Errorf("wrong target ref %q", sub.ref)
			return
		}
		write("bridge-ready", map[string]any{"stage": stage, "ref": ref, "conn": c.id})
		// Do not charge compilation or initial App setup to the loss deadline.
		if !awaitFile("source-go") {
			return
		}
		lossTimer := time.NewTimer(30 * time.Second)
		defer lossTimer.Stop()
		if stage == "bridge" {
			ticker := time.NewTicker(2 * time.Millisecond)
			defer ticker.Stop()
			for len(sub.loss) != 1 {
				select {
				case <-ticker.C:
				case <-stop:
					return
				case <-lossTimer.C:
					t.Error("bridge queue loss not published")
					return
				}
			}
			// Read-only observation of the buffered loss: only the real relay
			// consumes the cause. Release cannot manufacture an abort.
			write("queue-boundary", map[string]any{"stage": stage, "ref": ref, "conn": c.id, "buffered_loss": len(sub.loss)})
			unblock()
		} else {
			select {
			case <-writer:
			case <-stop:
				return
			case <-lossTimer.C:
				t.Error("writer delta gate absent")
				return
			}
			write("writer-ready", map[string]any{"stage": stage, "ref": ref, "conn": c.id})
		}
		select {
		case <-c.mirrorAbortDone:
		case <-stop:
			return
		case <-lossTimer.C:
			if !noAbort {
				t.Error("stage abort did not complete")
			}
			return
		}
		want := "mirror_loss: cause=ws: mirror send queue overflow"
		if stage == "bridge" {
			want = "mirror_loss: ref=" + ref + ": bridge: subscriber queue overflow"
		}
		got := fmt.Sprint(c.mirrorLossReason.Load())
		if got != want {
			t.Errorf("stage cause got %q want %q", got, want)
			return
		}
		if !c.mirrorAborted.Load() || len(c.sendCh) != 0 {
			t.Error("abort did not discard target queue")
			return
		}
		write("loss.json", map[string]any{"stage": stage, "ref": ref, "conn": c.id, "accepted_number": 2, "cause": got, "abort_complete": true, "queue_remaining": len(c.sendCh)})
		unblock()
	}()
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	deadline := time.NewTimer(180 * time.Second)
	defer deadline.Stop()
	for {
		select {
		case <-deadline.C:
			t.Fatal("fixture stop deadline")
		case <-ticker.C:
			if _, err := os.Stat(filepath.Join(root, "fixture-stop")); err == nil {
				if !noAbort {
					if _, err := os.Stat(filepath.Join(root, "loss.json")); err != nil {
						t.Error("no stage loss receipt")
					}
				}
				select {
				case c := <-healthy:
					if c.mirrorAborted.Load() {
						t.Error("healthy connection lost mirror bytes")
					}
				default:
					t.Error("healthy connection absent")
				}
				wantConnections := int32(3)
				if noAbort {
					wantConnections = 2
				}
				if accepted.Load() != wantConnections {
					t.Errorf("accepted connections=%d, want %d", accepted.Load(), wantConnections)
				}
				return
			}
		}
	}
}
