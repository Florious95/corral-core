package api

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// level2.go implements the second-level menu stream (requirement 061/062).
// Identity comes from tmux structural fields (session_name / window_name /
// socket / pane_id / cwd). A pane is listed only after comm-basename
// whitelist identity (068). Status is then dispatched to that family's
// detector; unclaimed titles are unknown and the log records provider,
// codepoint, and the full original title.
//
// The loop scans only while ≥1 subscriber exists (zero subscribers ⇒ zero
// tmux calls). It pushes a Level2Frame only when that connection's snapshot
// changed, and a Level2Heartbeat when the snapshot is unchanged past the
// heartbeat interval.

const (
	// defaultLevel2Interval is the 061 poll cadence: 2s so end-to-end status
	// stays under the 5s budget after transport and render.
	defaultLevel2Interval = 2 * time.Second

	// defaultLevel2Heartbeat is the 061 keep-alive: without it the client
	// cannot tell "no change" from "connection dead".
	defaultLevel2Heartbeat = 8 * time.Second
)

// level2Entry is one row the server pushes to a level-2 subscriber.
type level2Entry struct {
	ref      string
	name     string
	cwd      string
	title    string
	status   string
	provider string
	rows     uint16
	cols     uint16
}

// level2Loop is the idle-gated scan for the second-level stream. While ≥1
// subscriber exists it scans on the 2s cadence; at zero it parks (idle CPU ≈ 0).
// The 0→1 wake (markLevel2) breaks the park immediately.
func (s *Server) level2Loop(ctx context.Context) {
	s.log.Debug("level2 loop started",
		"interval", s.level2Interval,
		"heartbeat", s.level2Heartbeat,
	)
	for {
		if s.countLevel2() == 0 {
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
		// Re-check after the wait: unsubscribe during the interval must not
		// produce a tmux call (requirement 061: zero subscribers ⇒ zero poll).
		if s.countLevel2() == 0 {
			continue
		}
		s.publishLevel2(ctx)
	}
}

// markLevel2 is called when a connection subscribes to the level-2 stream: it
// bumps the subscriber count and wakes the loop (0→1) so the first subscriber's
// stream is fresh immediately.
func (s *Server) markLevel2() {
	if s.level2Subscribers.Add(1) == 1 {
		s.wakeLevel2()
	}
}

// wakeLevel2 is the 069 enter-menu signal. It must fire on every subscribe
// (including re-subscribe on an already-active connection), not only 0→1.
func (s *Server) wakeLevel2() {
	select {
	case s.level2WakeCh <- struct{}{}:
	default:
	}
}

// unmarkLevel2 is called when a connection unsubscribes or tears down: it drops
// the subscriber count. At zero the loop parks after the in-flight scan.
func (s *Server) unmarkLevel2() {
	if s.level2Subscribers.Add(-1) <= 0 {
		s.level2Subscribers.Store(0)
	}
}

func (s *Server) countLevel2() int64 {
	return s.level2Subscribers.Load()
}

func level2SnapKey(sessions []protocol.Session) string {
	var b strings.Builder
	for _, sess := range sessions {
		fmt.Fprintf(&b, "%s\x1e%s\x1e%s\x1e%s\x1e%s\x1e%s\x1e%d\x1e%d\x1f",
			sess.Ref, sess.Name, sess.Cwd, sess.Title, sess.Status, sess.Provider, sess.Rows, sess.Cols)
	}
	return b.String()
}

// publishLevel2 performs one scan-and-push cycle. It is the loop's only scan
// point. Each subscribed connection gets a Level2Frame on snapshot change, or
// a Level2Heartbeat when the snapshot is unchanged past the heartbeat interval.
func (s *Server) publishLevel2(ctx context.Context) {
	if s.countLevel2() == 0 {
		return
	}
	model, err := s.discoverer.Discover(ctx)
	if err != nil {
		s.log.Warn("level2: discover failed", "err", err)
		return
	}
	byPID := identifyModel(s, model)

	byCWD := make(map[string][]level2Entry)
	for _, ws := range model.Workspaces {
		for _, p := range ws.Panes {
			prov := byPID[p.PanePID]
			if prov == "" {
				continue
			}
			name := p.WindowName
			if name == "" {
				name = p.Session
			}
			status, first, known := classifyForProvider(prov, p.PaneTitle)
			if !known {
				s.logUnknownForProvider(prov, p.PaneTitle, first)
			}
			byCWD[ws.CWD] = append(byCWD[ws.CWD], level2Entry{
				ref:      sessionRef(p),
				name:     name,
				cwd:      p.CWD,
				title:    p.PaneTitle, // verbatim; status is a separate field
				status:   status,
				provider: prov,
				rows:     uint16(p.Height),
				cols:     uint16(p.Width),
			})
		}
	}

	s.trackersMu.Lock()
	conns := make([]*wsConn, 0, len(s.trackers))
	for c := range s.trackers {
		if c.level2Active() {
			conns = append(conns, c)
		}
	}
	s.trackersMu.Unlock()

	now := time.Now()
	for _, c := range conns {
		ws := c.level2Workspace()
		entries := byCWD[ws] // missing cwd ⇒ honest empty list
		sessions := make([]protocol.Session, 0, len(entries))
		for _, e := range entries {
			sessions = append(sessions, protocol.Session{
				Ref:      e.ref,
				Name:     e.name,
				Cwd:      e.cwd,
				Title:    e.title,
				Status:   e.status,
				Provider: e.provider,
				Rows:     e.rows,
				Cols:     e.cols,
			})
		}
		key := level2SnapKey(sessions)
		kind := c.noteLevel2Push(key, now, s.level2Heartbeat)
		if kind == "" {
			continue
		}
		seq := s.nextLevel2Seq()
		if kind == "heartbeat" {
			c.send(protocol.Level2Heartbeat{Workspace: ws, Seq: seq})
			continue
		}
		c.send(protocol.Level2Frame{
			Workspace: ws,
			Seq:       seq,
			Sessions:  sessions,
		})
	}
}

// handleLevel2Subscribe starts this connection's second-level stream.
//
// @contract
// @pre 连接已认证
// @post 连接计入 level2 订阅者并绑定 workspace；服务端开始按 cadence 推 Level2Frame
// @err none
func (c *wsConn) handleLevel2Subscribe(f protocol.Level2Subscribe) {
	already := c.level2Active()
	c.setLevel2(true, f.Workspace)
	c.resetLevel2PushState()
	if already {
		c.s.wakeLevel2()
	} else {
		c.s.markLevel2()
	}
	// Event-driven one-shot (069): do not wait for the 2s cadence.
	// publishLevel2 no-ops on discover failure and does not send an empty wipe.
	c.s.publishLevel2(c.ctx)
}

// handleLevel2Unsubscribe stops this connection's second-level stream.
//
// @contract
// @pre 连接已认证
// @post 连接移出 level2 订阅者；最后一名订阅者退出后 level2Loop park
// @err none
func (c *wsConn) handleLevel2Unsubscribe(protocol.Level2Unsubscribe) {
	if c.level2Active() {
		c.setLevel2(false, "")
		c.resetLevel2PushState()
		c.s.unmarkLevel2()
	}
}

func (c *wsConn) level2Active() bool {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	return c.level2On
}

func (c *wsConn) level2Workspace() string {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	return c.level2WS
}

func (c *wsConn) setLevel2(on bool, workspace string) {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	c.level2On = on
	c.level2WS = workspace
}

func (c *wsConn) resetLevel2PushState() {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	c.level2Snap = ""
	c.level2PushedAt = time.Time{}
}

// noteLevel2Push records the latest snapshot and returns "frame", "heartbeat",
// or "" (skip). First snapshot after subscribe always yields "frame".
func (c *wsConn) noteLevel2Push(key string, now time.Time, hb time.Duration) string {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	if !c.level2On {
		return ""
	}
	if c.level2PushedAt.IsZero() || c.level2Snap != key {
		c.level2Snap = key
		c.level2PushedAt = now
		return "frame"
	}
	if hb > 0 && now.Sub(c.level2PushedAt) >= hb {
		c.level2PushedAt = now
		return "heartbeat"
	}
	return ""
}
