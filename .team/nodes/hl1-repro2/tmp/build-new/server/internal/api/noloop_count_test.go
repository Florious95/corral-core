package api

import (
	"context"
	"fmt"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// tickingDiscoverer mimics a working agent: title changes every Discover.
// If jitterHeight is true, pane height also changes (listing-model change).
type tickingDiscoverer struct {
	n            atomic.Int64
	jitterHeight bool
}

func (d *tickingDiscoverer) Discover(context.Context) (*discovery.Model, error) {
	n := d.n.Add(1)
	h := 24
	if d.jitterHeight {
		h = 24 + int(n%3)
	}
	title := fmt.Sprintf("⠋ working %d", n)
	return &discovery.Model{
		Workspaces: []discovery.Workspace{
			{CWD: "/ws/a", Panes: []discovery.Pane{
				l2Pane(title, "alpha", "claude", "/ws/a", "/tmp/sock1", "%0", 80, h),
			}},
		},
	}, nil
}

// TestEnterLevel2TenSecondCounts records list requests vs level2_frame pushes
// for 10s after entering the L2 menu, using the App's seq-gap re-list rule.
// It logs both counts; the ≤2 list bound is asserted so a loop stays red.
func TestEnterLevel2TenSecondCounts(t *testing.T) {
	measureEnterLevel2(t, false)
}

func TestEnterLevel2TenSecondCountsHeightJitter(t *testing.T) {
	measureEnterLevel2(t, true)
}

func measureEnterLevel2(t *testing.T, jitterHeight bool) {
	t.Helper()
	d := &tickingDiscoverer{jitterHeight: jitterHeight}
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      d,
		ListInterval:    2 * time.Second,
		Level2Interval:  2 * time.Second,
		Level2Heartbeat: 8 * time.Second,
		ProviderFinder:  staticProvider("claude_code"),
	})
	e.auth()

	var listSent int
	var l2Frames int
	var lastSeen uint64
	var nextReq uint32 = 1

	sendList := func() {
		listSent++
		req := nextReq
		nextReq++
		e.sendFrame(&protocol.List{ReqID: req})
	}

	// READY: one list, wait for its listing so lastSeen is primed (App does this).
	sendList()
	ready := mustListing(t, e, 1)
	lastSeen = ready.Seq
	readyLists := listSent

	// Enter L2: App LaunchedEffect refresh() + DisposableEffect subscribe.
	sendList()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/ws/a"})

	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		remain := time.Until(deadline)
		if remain <= 0 {
			break
		}
		ctx, cancel := context.WithTimeout(context.Background(), remain)
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			continue
		}
		if typ != websocket.MessageText {
			continue
		}
		typed, err := protocol.UnmarshalFrame(data)
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		switch f := typed.(type) {
		case protocol.Listing:
			lastSeen = f.Seq
		case protocol.Level2Frame:
			l2Frames++
		case protocol.ListDelta:
			// ConnectionManager: discontinuous / delta-before-listing → sendList.
			if lastSeen == 0 || f.Seq != lastSeen+1 {
				sendList()
			} else {
				lastSeen = f.Seq
			}
		}
	}

	afterEnter := listSent - readyLists
	t.Logf("jitterHeight=%v after_enter list=%d level2_frame=%d lastSeen=%d total_list_including_ready=%d",
		jitterHeight, afterEnter, l2Frames, lastSeen, listSent)
	if afterEnter > 2 {
		t.Errorf("RED A-rf-noloop: after enter list=%d (>2) level2_frame=%d jitterHeight=%v",
			afterEnter, l2Frames, jitterHeight)
	}
}
