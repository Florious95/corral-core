package api

import (
	"context"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// level2.go implements the second-level menu stream (requirement 061).
// Identity comes from tmux structural fields (session_name / window_name /
// socket / pane_id / cwd). Status is classified from the first Unicode scalar
// of pane_title against a closed glyph table. An unrecognized glyph is
// "unknown" — never idle — and the log records the codepoint plus the full
// original title.
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

	// pane_title prefix glyphs (requirement 061 symbol table). New CLI glyphs
	// are added here — callers must not guess idle.
	glyphWorking = '\u25D0' // ◐
	glyphIdle    = '\u2733' // ✳
)

// level2Entry is one row the server pushes to a level-2 subscriber.
type level2Entry struct {
	ref    string
	name   string
	cwd    string
	title  string
	status string
	rows   uint16
	cols   uint16
}

// classifyPaneTitle maps the first Unicode scalar of pane_title to a status.
// Empty title or a glyph not in the table is unknown (never idle).
// known is false when the glyph is not in the table (including empty title).
func classifyPaneTitle(title string) (status string, first rune, known bool) {
	if title == "" {
		return protocol.SessionStatusUnknown, 0, false
	}
	r, _ := utf8.DecodeRuneInString(title)
	switch r {
	case glyphWorking:
		return protocol.SessionStatusWorking, r, true
	case glyphIdle:
		return protocol.SessionStatusIdle, r, true
	default:
		return protocol.SessionStatusUnknown, r, false
	}
}

func formatCodepoint(r rune) string {
	return fmt.Sprintf("U+%04X", uint32(r))
}

func (s *Server) logUnknownGlyph(title string, first rune) {
	// Operands then verdict (diagnostic log discipline): the raw codepoint
	// and the full original title must be in the line, or the table cannot
	// be updated.
	s.log.Warn("level2: pane_title glyph unknown",
		"codepoint", formatCodepoint(first),
		"title", title,
		"status", protocol.SessionStatusUnknown,
	)
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
		select {
		case s.level2WakeCh <- struct{}{}:
		default:
		}
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
		fmt.Fprintf(&b, "%s\x1e%s\x1e%s\x1e%s\x1e%s\x1e%d\x1e%d\x1f",
			sess.Ref, sess.Name, sess.Cwd, sess.Title, sess.Status, sess.Rows, sess.Cols)
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
	byCWD := make(map[string][]level2Entry)
	for _, ws := range model.Workspaces {
		for _, p := range ws.Panes {
			name := p.WindowName
			if name == "" {
				name = p.Session
			}
			status, first, known := classifyPaneTitle(p.PaneTitle)
			if !known {
				s.logUnknownGlyph(p.PaneTitle, first)
			}
			byCWD[ws.CWD] = append(byCWD[ws.CWD], level2Entry{
				ref:    sessionRef(p),
				name:   name,
				cwd:    p.CWD,
				title:  p.PaneTitle, // verbatim; status is a separate field
				status: status,
				rows:   uint16(p.Height),
				cols:   uint16(p.Width),
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
				Ref:    e.ref,
				Name:   e.name,
				Cwd:    e.cwd,
				Title:  e.title,
				Status: e.status,
				Rows:   e.rows,
				Cols:   e.cols,
			})
		}
		key := level2SnapKey(sessions)
		kind := c.noteLevel2Push(key, now, s.level2Heartbeat)
		if kind == "" {
			continue
		}
		seq := s.nextSeq()
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
	if c.level2Active() {
		c.setLevel2(true, f.Workspace)
		c.resetLevel2PushState()
		select {
		case c.s.level2WakeCh <- struct{}{}:
		default:
		}
		return
	}
	c.setLevel2(true, f.Workspace)
	c.resetLevel2PushState()
	c.s.markLevel2()
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
