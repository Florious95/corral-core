package api

import (
	"fmt"
	"strings"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// level2.go implements the second-level menu stream (requirement 061/062).
// Identity/routing comes from tmux structural fields. Status identity and the
// independent four axes come only from one accepted nodeprobe sample per
// allowed socket; production keeps only rows with a known provider while
// activity and health remain independent axes.
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

func (s *Server) unmarkLevel2() {
	if s.level2Subscribers.Add(-1) <= 0 {
		s.level2Subscribers.Store(0)
	}
	s.scans.wake()
}

func (s *Server) countLevel2() int64 { return s.level2Subscribers.Load() }

func level2SnapKey(sessions []protocol.Session) string {
	var b strings.Builder
	for _, sess := range sessions {
		sessionName := "<null>"
		if sess.SessionName != nil {
			sessionName = *sess.SessionName
		}
		fmt.Fprintf(&b, "%s\x1e%s\x1e%s\x1e%s\x1e%s\x1e%s\x1e%s\x1e%s\x1e%s\x1e%s\x1e%s\x1e%d\x1e%d\x1f",
			sess.Ref, sess.Name, sess.WindowName, sess.WindowIndex, sess.Cwd, sess.Title, sess.Provider, sess.Activity, sessionName, sess.Health, sess.Status, sess.Rows, sess.Cols)
	}
	return b.String()
}

// handleLevel2Subscribe changes the epoch before admission. A repeat subscribe
// is a new refresh intent even when its workspace string is unchanged.
func (c *wsConn) handleLevel2Subscribe(f protocol.Level2Subscribe) {
	c.level2Mu.Lock()
	if !c.level2On {
		c.s.level2Subscribers.Add(1)
	}
	c.level2On = true
	c.level2WS = f.Workspace
	c.level2Epoch++
	epoch := c.level2Epoch
	c.level2Snap = ""
	c.level2PushedAt = time.Time{}
	c.level2Mu.Unlock()
	c.s.scans.level2(c, epoch)
}

func (c *wsConn) handleLevel2Unsubscribe(protocol.Level2Unsubscribe) {
	c.level2Mu.Lock()
	wasOn := c.level2On
	c.level2On = false
	c.level2Epoch++
	c.level2WS = ""
	c.level2Snap = ""
	c.level2PushedAt = time.Time{}
	c.level2Mu.Unlock()
	if wasOn {
		c.s.unmarkLevel2()
	}
	c.s.scans.removeLevel2(c)
}

func (c *wsConn) level2Active() bool {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	return c.level2On
}

// level2Output checks workspace, epoch and last-push state in one critical
// section. No network or queue operation occurs while level2Mu is held.
func (c *wsConn) level2Output(epoch uint64, projection map[string][]protocol.Session, scanErr error) (protocol.Typed, uint64) {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	if !c.level2On || c.level2Epoch != epoch {
		return nil, 0
	}
	if scanErr != nil {
		return &protocol.ErrorFrame{Code: protocol.ErrCodeInternal, Reason: "catalog refresh failed"}, epoch
	}
	sessions := projection[c.level2WS]
	key := level2SnapKey(sessions)
	now := time.Now()
	if c.level2PushedAt.IsZero() || c.level2Snap != key {
		c.level2Snap, c.level2PushedAt = key, now
		return protocol.Level2Frame{Workspace: c.level2WS, Seq: c.s.nextLevel2Seq(), Sessions: sessions}, epoch
	}
	if now.Sub(c.level2PushedAt) >= c.s.level2Heartbeat {
		c.level2PushedAt = now
		return protocol.Level2Heartbeat{Workspace: c.level2WS, Seq: c.s.nextLevel2Seq()}, epoch
	}
	return nil, 0
}

func (c *wsConn) currentLevel2Epoch() uint64 {
	c.level2Mu.Lock()
	defer c.level2Mu.Unlock()
	if !c.level2On {
		return 0
	}
	return c.level2Epoch
}
