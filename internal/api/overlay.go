package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"time"

	"github.com/agentmirror/agentmirror/internal/overlay"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

const defaultOverlayInterval = 100 * time.Millisecond

func (s *Server) overlayLoop(ctx context.Context) {
	s.log.Debug("overlay loop started", "interval", s.overlayInterval)
	defer func() {
		if s.overlay != nil {
			s.overlay.Stop()
		}
		s.log.Debug("overlay loop stopped")
	}()
	for {
		if s.countOverlay() == 0 {
			s.overlayLastHash = ""
			if s.overlay != nil {
				s.overlay.Stop()
			}
			s.log.Debug("overlay: park",
				"subscribers", s.countOverlay(),
				"captures", overlayCaptures(s.overlay),
				"clients", overlayClients(s.overlay),
			)
			select {
			case <-ctx.Done():
				return
			case <-s.overlayWakeCh:
			}
			continue
		}
		select {
		case <-ctx.Done():
			return
		case <-s.overlayWakeCh:
		case <-time.After(s.overlayInterval):
		}
		if s.countOverlay() == 0 {
			continue
		}
		s.publishOverlay(ctx)
	}
}

func (s *Server) markOverlay() {
	if s.overlaySubscribers.Add(1) == 1 {
		select {
		case s.overlayWakeCh <- struct{}{}:
		default:
		}
	}
}

func (s *Server) unmarkOverlay() {
	if s.overlaySubscribers.Add(-1) <= 0 {
		s.overlaySubscribers.Store(0)
		if s.overlay != nil {
			s.overlay.Stop()
		}
	}
}

func (s *Server) countOverlay() int64 {
	return s.overlaySubscribers.Load()
}

func (s *Server) publishOverlay(ctx context.Context) {
	if s.countOverlay() == 0 || s.overlay == nil {
		return
	}
	if err := s.overlay.Start(ctx); err != nil {
		s.log.Warn("overlay: start failed", "err", err,
			"subscribers", s.countOverlay(),
			"clients", overlayClients(s.overlay),
		)
		return
	}
	raw, err := s.overlay.Snapshot(ctx)
	if err != nil {
		s.log.Warn("overlay: snapshot failed", "err", err)
		return
	}
	if len(raw) == 0 {
		s.log.Debug("overlay: empty snapshot skipped",
			"captures", overlayCaptures(s.overlay),
			"bytes", 0,
		)
		return
	}
	sum := sha256.Sum256(raw)
	cur := hex.EncodeToString(sum[:])
	prev := s.overlayLastHash
	changed := prev != cur
	s.log.Debug("overlay: frame hash",
		"prev", prev,
		"cur", cur,
		"bytes", len(raw),
		"changed", changed,
	)
	if !changed {
		return
	}
	s.overlayLastHash = cur
	seq := s.nextSeq()
	frame := protocol.OverlayFrame{
		Seq:  seq,
		Text: string(raw),
		Rows: overlay.ScratchRows,
		Cols: overlay.ScratchCols,
	}
	s.trackersMu.Lock()
	conns := make([]*wsConn, 0, len(s.trackers))
	for c := range s.trackers {
		if c.overlayActive() {
			conns = append(conns, c)
		}
	}
	s.trackersMu.Unlock()
	if prev == "" {
		s.log.Info("overlay: first frame",
			"bytes", len(raw),
			"seq", seq,
			"subscribers", s.countOverlay(),
			"overlay_conns", len(conns),
			"trackers", trackerCount(s),
			"captures", overlayCaptures(s.overlay),
		)
	}
	if len(conns) == 0 {
		s.log.Info("overlay: frame ready but no overlay-active conn",
			"bytes", len(raw),
			"subscribers", s.countOverlay(),
			"trackers", trackerCount(s),
		)
		return
	}
	for _, c := range conns {
		c.send(frame)
	}
}

func trackerCount(s *Server) int {
	s.trackersMu.Lock()
	n := len(s.trackers)
	s.trackersMu.Unlock()
	return n
}

func overlayCaptures(c overlay.Capturer) int64 {
	if c == nil {
		return 0
	}
	return c.CaptureCount()
}

func overlayClients(c overlay.Capturer) int64 {
	if c == nil {
		return 0
	}
	return c.ClientCount()
}

func (c *wsConn) handleOverlaySubscribe(protocol.OverlaySubscribe) {
	if c.overlayActive() {
		select {
		case c.s.overlayWakeCh <- struct{}{}:
		default:
		}
		return
	}
	c.setOverlay(true)
	c.s.markOverlay()
}

func (c *wsConn) handleOverlayUnsubscribe(protocol.OverlayUnsubscribe) {
	if c.overlayActive() {
		c.setOverlay(false)
		c.s.unmarkOverlay()
	}
}

func (c *wsConn) overlayActive() bool {
	c.overlayMu.Lock()
	defer c.overlayMu.Unlock()
	return c.overlayOn
}

func (c *wsConn) setOverlay(on bool) {
	c.overlayMu.Lock()
	defer c.overlayMu.Unlock()
	c.overlayOn = on
}
