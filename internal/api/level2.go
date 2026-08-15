package api

import (
	"context"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// level2.go implements the second-level live stream (requirement 060: 二级菜单
// 改为实时流并取代状态判定). The level-2 menu is "Ctrl-B w 的重绘": the server
// pushes each pane's verbatim pane-title plus structural identity to the client
// in real time while the menu is open.
//
// Three invariants (from the design at .team/nodes/level2-livestream/实现方案.md):
//
//  1. Title verbatim — the pane title is transmitted byte-for-byte (including
//     ◐/✳ prefixes), zero parsing/matching/mapping. It is display-only; never
//     used for identity.
//  2. Identity structural — Ref/Name come from tmux structural fields
//     (session_name / window_index / window_name → sessionRef socket+paneid).
//     Never derived from the title string.
//  3. Pull only while open — the loop scans tmux only while ≥1 level2 subscriber
//     exists; at zero subscribers it parks (idle CPU ≈ 0). Never attach tmux.

// defaultLevel2Interval is how often the level2 loop scans while subscribers
// exist. The live stream should feel fresher than the level-1 menu cadence.
const defaultLevel2Interval = 500 * time.Millisecond

// level2Entry is one row the server pushes to a level-2 subscriber: the pane's
// verbatim title plus structural identity. Name comes from window_name
// (fallback session_name); Ref is the stable session ref (socket + pane id).
type level2Entry struct {
	ref      string
	name     string
	cwd      string
	title    string
	rows     uint16
	cols     uint16
}

// level2Loop is the idle-gated scan heartbeat for the second-level live stream.
// While ≥1 subscriber exists it scans tmux on the level2 cadence and pushes a
// full-replace Level2Frame to each subscriber's workspace; at zero subscribers
// it parks and spawns no scan subprocesses (idle CPU ≈ 0, engineering red line
// 1). The 0→1 wake (markLevel2) breaks the park immediately.
func (s *Server) level2Loop(ctx context.Context) {
	s.log.Debug("level2 loop started", "interval", s.level2Interval)
	for {
		if s.countLevel2() == 0 {
			// No second-level subscribers: park and spawn no scan subprocesses.
			select {
			case <-ctx.Done():
				s.log.Debug("level2 loop stopped")
				return
			case <-s.level2WakeCh:
			}
		} else {
			select {
			case <-ctx.Done():
				s.log.Debug("level2 loop stopped")
				return
			case <-s.level2WakeCh:
			case <-time.After(s.level2Interval):
			}
		}
		s.publishLevel2(ctx)
	}
}

// markLevel2 is called when a connection subscribes to the level-2 stream: it
// bumps the subscriber count and wakes the loop (0→1) so the first subscriber's
// stream is fresh immediately.
func (s *Server) markLevel2() {
	if s.level2Subscribers.Add(1) == 1 {
		select {
		case s.level2WakeCh <- struct{}{}:
		default:
		}
	}
}

// unmarkLevel2 is called when a connection unsubscribes or tears down: it drops
// the subscriber count. At zero the loop parks after the in-flight scan, so an
// idle daemon spawns no further level2 scan subprocesses.
func (s *Server) unmarkLevel2() {
	if s.level2Subscribers.Add(-1) <= 0 {
		s.level2Subscribers.Store(0) // never negative: teardown is idempotent-guarded
	}
}

// countLevel2 returns the number of connections currently viewing the second
// level.
func (s *Server) countLevel2() int64 {
	return s.level2Subscribers.Load()
}

// publishLevel2 performs one scan-and-push cycle: one Discover (list-panes -a,
// zero attach), then for each subscribed connection push a Level2Frame covering
// its workspace. It is the level2Loop's only scan point, so with zero
// subscribers it never runs (zero tmux calls).
func (s *Server) publishLevel2(ctx context.Context) {
	model, err := s.discoverer.Discover(ctx)
	if err != nil {
		s.log.Warn("level2: discover failed", "err", err)
		return
	}
	// Build a per-workspace index of panes.
	byCWD := make(map[string][]level2Entry)
	for _, ws := range model.Workspaces {
		for _, p := range ws.Panes {
			name := p.WindowName
			if name == "" {
				name = p.Session
			}
			byCWD[ws.CWD] = append(byCWD[ws.CWD], level2Entry{
				ref:   sessionRef(p),
				name:  name,
				cwd:   ws.CWD,
				title: p.PaneTitle, // verbatim, zero parsing (requirement 060)
				rows:  uint16(p.Height),
				cols:  uint16(p.Width),
			})
		}
	}

	// Push to each subscribed connection, scoped to its workspace.
	s.trackersMu.Lock()
	conns := make([]*wsConn, 0, len(s.trackers))
	for c := range s.trackers {
		if c.level2Active() {
			conns = append(conns, c)
		}
	}
	s.trackersMu.Unlock()

	seq := s.nextSeq()
	for _, c := range conns {
		ws := c.level2Workspace()
		entries := byCWD[ws] // empty if the workspace has no panes
		sessions := make([]protocol.Session, 0, len(entries))
		for _, e := range entries {
			sessions = append(sessions, protocol.Session{
				Ref:   e.ref,
				Name:  e.name,
				Cwd:   e.cwd,
				Title: e.title, // verbatim, zero parsing
				Rows:  e.rows,
				Cols:  e.cols,
			})
		}
		c.send(protocol.Level2Frame{
			Workspace: ws,
			Seq:       seq,
			Sessions:  sessions,
		})
	}
}

// handleLevel2Subscribe starts this connection's second-level live stream. The
// workspace scopes the push; empty means all workspaces.
//
// @contract
// @pre 连接已认证
// @post 连接计入 level2 订阅者并绑定 workspace；服务端开始按 cadence 推 Level2Frame
// @err none
func (c *wsConn) handleLevel2Subscribe(f protocol.Level2Subscribe) {
	c.setLevel2(true, f.Workspace)
	c.s.markLevel2()
}

// handleLevel2Unsubscribe stops this connection's second-level live stream.
//
// @contract
// @pre 连接已认证
// @post 连接移出 level2 订阅者；最后一名订阅者退出后 level2Loop park
// @err none
func (c *wsConn) handleLevel2Unsubscribe(protocol.Level2Unsubscribe) {
	if c.level2Active() {
		c.setLevel2(false, "")
		c.s.unmarkLevel2()
	}
}

// level2Active reports whether this connection is subscribed to the level-2
// live stream.
func (c *wsConn) level2Active() bool {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	return c.level2On
}

// level2Workspace returns the workspace scope this connection subscribes to.
func (c *wsConn) level2Workspace() string {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	return c.level2WS
}

// setLevel2 records this connection's level-2 subscription state.
func (c *wsConn) setLevel2(on bool, workspace string) {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	c.level2On = on
	c.level2WS = workspace
}
