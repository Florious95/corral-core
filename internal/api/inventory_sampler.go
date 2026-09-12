package api

import (
	"context"
	"fmt"
	"strings"
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
type inventorySample struct {
	signature    string
	model        *discovery.Model
	observations map[string]nodeprobe.Observation
	updated      time.Time
	refreshing   bool
}

type inventorySampler struct {
	ctx     context.Context
	sampler nodeprobe.Sampler
	mu      sync.Mutex
	entries map[string]*inventorySample
	jobs    chan *inventorySample
	workers sync.WaitGroup
}

func newInventorySampler(ctx context.Context, sampler nodeprobe.Sampler) *inventorySampler {
	p := &inventorySampler{ctx: ctx, sampler: sampler, entries: make(map[string]*inventorySample), jobs: make(chan *inventorySample, 64)}
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
			observations, err := nodeprobe.SampleModel(ctx, entry.model, p.sampler)
			cancel()
			p.mu.Lock()
			if err == nil {
				entry.observations, entry.updated = observations, time.Now()
			}
			entry.refreshing = false
			p.mu.Unlock()
		}
	}
}

func (p *inventorySampler) sample(ctx context.Context, model *discovery.Model) (map[string]nodeprobe.Observation, error) {
	models := map[string]*discovery.Model{}
	signatures := map[string]*strings.Builder{}
	for _, ws := range model.Workspaces {
		for _, pane := range ws.Panes {
			if models[pane.Socket] == nil {
				models[pane.Socket] = &discovery.Model{}
				signatures[pane.Socket] = &strings.Builder{}
			}
			models[pane.Socket].Workspaces = append(models[pane.Socket].Workspaces, discovery.Workspace{CWD: ws.CWD, Panes: []discovery.Pane{pane}})
			fmt.Fprintf(signatures[pane.Socket], "%q/%q/%d/%q/%d/%q/%q;", pane.Socket, pane.Session, pane.WindowIndex, pane.PaneID, pane.PanePID, pane.Command, ws.CWD)
		}
	}
	type task struct {
		socket string
		entry  *inventorySample
	}
	var missing []task
	out := map[string]nodeprobe.Observation{}
	p.mu.Lock()
	for socket, m := range models {
		sig := signatures[socket].String()
		entry := p.entries[socket]
		if entry == nil || entry.signature != sig {
			missing = append(missing, task{socket, &inventorySample{signature: sig, model: m}})
			continue
		}
		for ref, obs := range entry.observations {
			out[ref] = obs
		}
		if time.Since(entry.updated) >= 2*time.Second && !entry.refreshing {
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
	// identity calls run concurrently, irrespective of host or request count.
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
				obs, err := nodeprobe.SampleModel(ctx, task.entry.model, p.sampler)
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
				task.entry.observations, task.entry.updated = obs, time.Now()
				p.entries[task.socket] = task.entry
				p.mu.Unlock()
			}
		}()
	}
	wg.Wait()
	return out, firstErr
}
