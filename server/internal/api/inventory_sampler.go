package api

import (
	"context"
	"sync"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
)

// L1 retains the compatible session fields, but reuses the last accepted
// observation for an unchanged structural inventory. A bounded background pool
// revalidates even unknown providers: an Agent starting in an existing shell
// must become visible without needing a new pane or a title-based identity guess.
// New/changed socket inventories require a fresh accepted identity join.
// All reports come from the shared per-socket broker, so L1 and L2 scopes of
// the same socket do not each start nodeprobe.
type inventorySample struct {
	signature    string
	model        *discovery.Model
	observations map[string]nodeprobe.Observation
	sampled      time.Time // accepted report startedAt; cache hits never move it
	refreshing   bool
}

type inventorySampler struct {
	ctx     context.Context
	reports *reportBroker
	mu      sync.Mutex
	entries map[string]*inventorySample
	jobs    chan *inventorySample
	workers sync.WaitGroup
}

func newInventorySampler(ctx context.Context, reports *reportBroker) *inventorySampler {
	p := &inventorySampler{ctx: ctx, reports: reports, entries: make(map[string]*inventorySample), jobs: make(chan *inventorySample, 64)}
	for i := 0; i < 2; i++ {
		p.workers.Add(1)
		go p.worker()
	}
	return p
}

func (p *inventorySampler) worker() {
	defer p.workers.Done()
	for {
		select {
		case <-p.ctx.Done():
			return
		case entry := <-p.jobs:
			ctx, cancel := context.WithTimeout(p.ctx, 5*time.Second)
			observations, sampled, err := p.reports.sampleModel(ctx, entry.model, p.reports.now().Add(-reportShareWindow))
			cancel()
			p.mu.Lock()
			if err == nil {
				entry.observations, entry.sampled = observations, sampled
			}
			entry.refreshing = false
			p.mu.Unlock()
		}
	}
}

// sample retains unchanged inventory observations; new identities require a
// shared report no older than fresh, without a separate failure retry clock.
func (p *inventorySampler) sample(ctx context.Context, model *discovery.Model, fresh time.Time) (map[string]nodeprobe.Observation, error) {
	models, signatures := socketModels(model)
	type task struct {
		socket string
		entry  *inventorySample
	}
	var missing []task
	out := map[string]nodeprobe.Observation{}
	now := p.reports.now()
	p.mu.Lock()
	for socket, m := range models {
		sig := signatures[socket]
		entry := p.entries[socket]
		if entry == nil || entry.signature != sig {
			missing = append(missing, task{socket, &inventorySample{signature: sig, model: m}})
			continue
		}
		for ref, obs := range entry.observations {
			out[ref] = obs
		}
		if now.Sub(entry.sampled) >= 2*time.Second && !entry.refreshing {
			select {
			case p.jobs <- entry:
				entry.refreshing = true
			default:
			}
		}
	}
	for socket := range p.entries {
		if models[socket] == nil {
			delete(p.entries, socket)
		}
	}
	p.mu.Unlock()
	// The host coordinator admits only one scan at a time. At most four cold
	// identity calls wait concurrently, irrespective of host or request count.
	jobs := make(chan task, len(missing))
	for _, task := range missing {
		jobs <- task
	}
	close(jobs)
	var wg sync.WaitGroup
	var resultMu sync.Mutex
	var firstErr error
	for i := 0; i < min(4, len(missing)); i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for task := range jobs {
				obs, sampled, err := p.reports.sampleModel(ctx, task.entry.model, fresh)
				resultMu.Lock()
				if err != nil {
					if firstErr == nil {
						firstErr = err
					}
					resultMu.Unlock()
					continue
				}
				for ref, v := range obs {
					out[ref] = v
				}
				resultMu.Unlock()
				p.mu.Lock()
				task.entry.observations, task.entry.sampled = obs, sampled
				p.entries[task.socket] = task.entry
				p.mu.Unlock()
			}
		}()
	}
	wg.Wait()
	return out, firstErr
}
