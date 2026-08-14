// 归档（2026-08-15，058 补刀）：原 server/internal/api/connected_idle_economy_test.go 中
// 被否定的测试。此测试的「公平性/60s 可见性机制」部分本身有效（采样节流、FIFO 公平、60s
// 可见性约束），但它的 blocked/working 检测断言依赖被归档的决策层（屏文本规则），占位 stub
// 诚实报 unknown 故断言落空。机制留待 t.impl 重建后，用脚本化 fake provider 重写
// （scriptedStates 模式见 aggregate_test.go，不依赖决策层）。
// 同目录两个保留的 discovery 测试（ScopedDiscoverySeam / ProductionDefault / ConsumesOnly）
// 与采样不扇出测试（SamplingDoesNotFanOutFleet）不依赖决策层，留在 live。
// 逐字节拷贝自 commit 7117f57cb 时的原文件，未改一字。
package api

// ==== TestConnectedIdleEconomySamplingRateFairnessAndVisibility：检测断言被否定的化石 ====
// TestConnectedIdleEconomySamplingRateFairnessAndVisibility advances one
// fake clock in 2s listing rounds. Each round may dispatch the same fixed
// burst for 3, 27, and 200 panes; the FIFO must reach every pane and later
// carry a worst-position blocked change into the listing/delta source within
// the frozen 60s bound.
func TestConnectedIdleEconomySamplingRateFairnessAndVisibility(t *testing.T) {
	for _, panes := range []int{3, 27, 200} {
		t.Run(fmt.Sprintf("panes_%d", panes), func(t *testing.T) {
			base := time.Unix(1_700_000_000, 0)
			var fakeNanos atomic.Int64
			fakeNanos.Store(base.UnixNano())
			now := func() time.Time { return time.Unix(0, fakeNanos.Load()) }

			p := NewStateProvider(discardLogger())
			defer p.Close()
			p.now = now
			p.mu.Lock()
			p.lastRefill = now()
			p.tokens = 1
			p.mu.Unlock()

			model := connectedIdleModel(panes)
			catalog := newSessionCatalog()
			catalog.rebuild(model)
			targetPane := model.Workspaces[0].Panes[panes-1]
			targetRef := sessionRef(targetPane)

			var (
				blocked   atomic.Bool
				samplesMu sync.Mutex
				total     int
				firstSeen = make(map[string]time.Duration, panes)
			)
			p.sample = func(_ context.Context, pn discovery.Pane) ([]byte, time.Duration, error) {
				samplesMu.Lock()
				total++
				if _, ok := firstSeen[pn.PaneID]; !ok {
					firstSeen[pn.PaneID] = now().Sub(base)
				}
				samplesMu.Unlock()
				if blocked.Load() && pn.PaneID == targetPane.PaneID {
					return []byte("Do you want to proceed?\n(esc to cancel)\n"), 0, nil
				}
				return []byte("bypass permissions on · esc to interrupt · 1 shell\n"), 0, nil
			}

			var snap *modelSnapshot
			for round := 0; round <= 30; round++ {
				if round > 0 {
					fakeNanos.Add(int64(defaultListInterval))
				}
				samplesMu.Lock()
				before := total
				samplesMu.Unlock()
				// buildSnapshot's value is irrelevant in this round: the call is
				// what drives the sampler's dispatch (counted via p.sample). The
				// FIFO result is only read after the loop settles, below.
				_ = buildSnapshot(catalog, p, context.Background())
				waitConnectedIdleProvider(t, p)
				samplesMu.Lock()
				dispatched := total - before
				seen := len(firstSeen)
				samplesMu.Unlock()
				limit := defaultStateDispatchBurst
				if round == 0 {
					limit = 1
				}
				if dispatched > limit {
					t.Fatalf("round %d dispatched %d samples for %d panes, fixed limit %d", round, dispatched, panes, limit)
				}
				if seen == panes {
					break
				}
			}

			samplesMu.Lock()
			if len(firstSeen) != panes {
				t.Fatalf("first-pass fairness reached %d/%d panes in %v", len(firstSeen), panes, now().Sub(base))
			}
			for id, elapsed := range firstSeen {
				if elapsed > 60*time.Second {
					t.Fatalf("pane %s first sampled after %v, want <= 60s", id, elapsed)
				}
			}
			samplesMu.Unlock()

			// The dispatch arithmetic itself must retain room for one full
			// sample budget and the next default listing publication.
			roundsAfterFirst := (panes - 1 + defaultStateDispatchBurst - 1) / defaultStateDispatchBurst
			worstVisible := time.Duration(roundsAfterFirst)*defaultListInterval + defaultStateSamplingBudget + defaultListInterval
			if worstVisible > 60*time.Second {
				t.Fatalf("%d-pane configured worst visibility = %v, want <= 60s", panes, worstVisible)
			}

			// Refresh completion happens after State returned its cached value;
			// rebuild once at the same fake instant to publish the sampled state.
			snap = buildSnapshot(catalog, p, context.Background())
			if got := snap.byRef[targetRef].State; got != protocol.StateWorking {
				t.Fatalf("target initial state = %q, want working", got)
			}

			blocked.Store(true)
			changedAt := now()
			for round := 1; round <= 30; round++ {
				fakeNanos.Add(int64(defaultListInterval))
				samplesMu.Lock()
				before := total
				samplesMu.Unlock()

				_ = buildSnapshot(catalog, p, context.Background())
				waitConnectedIdleProvider(t, p)
				afterRefresh := buildSnapshot(catalog, p, context.Background())

				samplesMu.Lock()
				dispatched := total - before
				samplesMu.Unlock()
				if dispatched > defaultStateDispatchBurst {
					t.Fatalf("change round %d dispatched %d samples, fixed limit %d", round, dispatched, defaultStateDispatchBurst)
				}
				if afterRefresh.byRef[targetRef].State != protocol.StateBlocked {
					snap = afterRefresh
					continue
				}

				if elapsed := now().Sub(changedAt); elapsed > 60*time.Second {
					t.Fatalf("blocked target became visible after %v, want <= 60s", elapsed)
				}
				delta := afterRefresh.diff(snap)
				var changedSession, changedWorkspace bool
				for _, s := range delta.ChangedSessions {
					changedSession = changedSession || s.Ref == targetRef && s.State == protocol.StateBlocked
				}
				for _, ws := range delta.ChangedWorkspaces {
					changedWorkspace = changedWorkspace || ws.Cwd == "/isolated" && ws.AggregateState == protocol.StateBlocked
				}
				if !changedSession || !changedWorkspace {
					t.Fatalf("blocked change did not reach listing delta: %+v", delta)
				}
				return
			}
			t.Fatalf("blocked target %q did not reach listing within 60s", targetRef)
		})
	}
}

// ==== connectedIdleModel / waitConnectedIdleProvider：仅此测试使用的 helper（随测试归档） ====
func connectedIdleModel(panes int) *discovery.Model {
	ps := make([]discovery.Pane, panes)
	for i := range ps {
		ps[i] = discovery.Pane{
			Socket:  "/isolated/socket",
			Session: fmt.Sprintf("s-%03d", i),
			PaneID:  fmt.Sprintf("%%%d", i),
			CWD:     "/isolated",
			Command: "claude",
			Width:   80,
			Height:  24,
		}
	}
	return &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/isolated", Panes: ps}}}
}

func waitConnectedIdleProvider(t *testing.T, p *wiredStateProvider) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		p.mu.Lock()
		busy := false
		for _, e := range p.cache {
			busy = busy || e.inFlight
		}
		p.mu.Unlock()
		if !busy {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("state refreshes did not drain")
}
