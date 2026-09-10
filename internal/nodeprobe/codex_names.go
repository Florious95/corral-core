package nodeprobe

import (
	"context"

	"github.com/agentmirror/agentmirror/internal/codexname"
	"github.com/agentmirror/agentmirror/internal/discovery"
)

// codexNameResolver is separate from the accepted status report schema. A
// synthetic sampler need not inspect the host. The production Runner supplies
// this metadata capability without replacing/rebuilding the pinned binary.
type codexNameResolver interface {
	CodexNames(context.Context, []codexname.Target) map[string]string
}

func (r *Runner) CodexNames(ctx context.Context, targets []codexname.Target) map[string]string {
	return codexname.Resolve(ctx, targets)
}

func enrichCodexNames(ctx context.Context, model *discovery.Model, sampler Sampler, observations map[string]Observation) {
	resolver, ok := sampler.(codexNameResolver)
	if !ok || ctx.Err() != nil {
		return
	}
	var targets []codexname.Target
	for _, ws := range model.Workspaces {
		for _, pane := range ws.Panes {
			ref := pane.Socket + "\x1f" + pane.PaneID
			// Only allowlisted discovery panes with a unique positive Codex
			// join may trigger process/metadata inspection. Never identify a
			// provider from a title, working directory, or a renamed label.
			if observations[ref].Provider == "codex" && pane.PanePID > 0 {
				targets = append(targets, codexname.Target{Ref: ref, RootPID: pane.PanePID, Command: pane.Command})
			}
		}
	}
	if len(targets) == 0 {
		return
	}
	names := resolver.CodexNames(ctx, targets)
	// Do not trust extra resolver keys to widen the discovery allowlist.
	for _, target := range targets {
		obs := observations[target.Ref]
		obs.DisplayName = names[target.Ref]
		observations[target.Ref] = obs
	}
}
