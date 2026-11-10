package api

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"github.com/agentmirror/agentmirror/internal/nodeprobe"
)

// Contract pid-churn.backoff.v1 is independent of a caller's refresh clock.
func TestPortReportBackoffExactScheduleAndSaturation(t *testing.T) {
	want := []time.Duration{2, 4, 8, 16, 30, 30, 30, 30}
	for i, seconds := range want {
		if got := reportBackoff(i + 1); got != seconds*time.Second {
			t.Fatalf("failure=%d delay=%s want=%s", i+1, got, seconds*time.Second)
		}
	}
	if got := reportBackoff(int(^uint(0) >> 1)); got != 30*time.Second {
		t.Fatalf("saturated delay=%s want=30s", got)
	}
}

type portReportSampler struct {
	calls atomic.Int64
	fn    func(context.Context, int64, string) (nodeprobe.Report, error)
}

func (p *portReportSampler) Sample(ctx context.Context, socket string) (nodeprobe.Report, error) {
	return p.fn(ctx, p.calls.Add(1), socket)
}

func portReport(socket, provider string) nodeprobe.Report {
	return nodeprobe.Report{Socket: socket, Nodes: []nodeprobe.Node{{Session: "agent", PaneID: "%0", Provider: provider, State: "idle", Activity: "idle", Health: "normal"}}}
}

// Developer-owned regressions from the public scenarios; independent tester
// implementations are not copied into this worktree.
func TestPortPendingFailureUsesCompletionClockBeforeCleanup(t *testing.T) {
	for _, budget := range []time.Duration{0, time.Second} {
		t.Run(budget.String(), func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				ctx, cancel := context.WithCancel(context.Background())
				defer cancel()
				gate := make(chan struct{})
				var once sync.Once
				release := func() { once.Do(func() { close(gate) }) }
				defer release()
				probe := &portReportSampler{fn: func(ctx context.Context, call int64, socket string) (nodeprobe.Report, error) {
					if call == 1 {
						if budget == 0 {
							<-gate
						} else {
							time.Sleep(budget)
						}
						return nodeprobe.Report{}, context.DeadlineExceeded
					}
					return portReport(socket, "claude_code"), nil
				}}
				broker := newReportBroker(ctx, probe, nil)
				model := workspaceFixtureModel("/A", "/fixture/pending", "%0")
				results := make(chan error, 2)
				go func() { _, _, err := broker.sampleModel(ctx, model, time.Now()); results <- err }()
				synctest.Wait()
				time.Sleep(100 * time.Millisecond)
				go func() { _, _, err := broker.sampleModel(ctx, model, time.Now()); results <- err }()
				synctest.Wait()
				if budget == 0 {
					release()
				} else {
					time.Sleep(budget - 100*time.Millisecond)
				}
				synctest.Wait()
				if probe.calls.Load() != 1 {
					t.Fatalf("pending bypassed failure backoff: calls=%d want=1", probe.calls.Load())
				}
				for range 2 {
					if err := <-results; err == nil {
						t.Fatal("failure/deferral became success")
					}
				}
				time.Sleep(2*time.Second - time.Nanosecond)
				if _, _, err := broker.sampleModel(ctx, model, time.Now()); err == nil || probe.calls.Load() != 1 {
					t.Fatalf("retry before completion+2s: err=%v calls=%d", err, probe.calls.Load())
				}
				time.Sleep(time.Nanosecond)
				for range 2 {
					go func() { _, _, err := broker.sampleModel(ctx, model, time.Now()); results <- err }()
				}
				synctest.Wait()
				for range 2 {
					if err := <-results; err != nil {
						t.Fatal(err)
					}
				}
				if probe.calls.Load() != 2 {
					t.Fatalf("eligible retries did not coalesce: calls=%d want=2", probe.calls.Load())
				}
			})
		})
	}
}

func TestPortActiveSocketReplacementRejectsLateOldReport(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		socket := filepath.Join(t.TempDir(), "socket")
		if err := os.WriteFile(socket, []byte("old"), 0600); err != nil {
			t.Fatal(err)
		}
		gate := make(chan struct{})
		var once sync.Once
		release := func() { once.Do(func() { close(gate) }) }
		defer release()
		probe := &portReportSampler{fn: func(ctx context.Context, call int64, socket string) (nodeprobe.Report, error) {
			if call == 1 {
				<-gate // deliberately ignore cancellation and return late success
				return portReport(socket, "claude_code"), nil
			}
			return portReport(socket, "pi"), nil
		}}
		broker := newReportBroker(ctx, probe, nil)
		model := workspaceFixtureModel("/A", socket, "%0")
		fence := time.Now()
		old := make(chan error, 1)
		go func() { _, _, err := broker.sampleModel(ctx, model, fence); old <- err }()
		synctest.Wait()
		time.Sleep(100 * time.Millisecond)
		if err := os.WriteFile(socket+".new", []byte("new"), 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.Rename(socket+".new", socket); err != nil {
			t.Fatal(err)
		}
		rows, _, err := broker.sampleModel(ctx, model, fence)
		if err != nil || probe.calls.Load() != 2 || rows[socket+"\x1f%0"].Provider != "pi" {
			t.Fatalf("new file inherited old run: rows=%+v err=%v calls=%d", rows, err, probe.calls.Load())
		}
		release()
		synctest.Wait()
		if err := <-old; !errors.Is(err, context.Canceled) {
			t.Fatalf("obsolete run returned usable success: err=%v", err)
		}
		rows, _, err = broker.sampleModel(ctx, model, fence)
		if err != nil || probe.calls.Load() != 2 || rows[socket+"\x1f%0"].Provider != "pi" {
			t.Fatalf("late old result overwrote new file: rows=%+v err=%v calls=%d", rows, err, probe.calls.Load())
		}
	})
}
