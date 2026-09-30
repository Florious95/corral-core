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
			s.overlayLastHash = make(map[string]string)
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
	s.trackersMu.Lock()
	bySock := make(map[string][]*wsConn)
	for c := range s.trackers {
		if !c.overlayActive() {
			continue
		}
		sock := c.overlaySocket()
		bySock[sock] = append(bySock[sock], c)
	}
	s.trackersMu.Unlock()
	if len(bySock) == 0 {
		return
	}
	for sock, conns := range bySock {
		s.publishOverlaySocket(ctx, sock, conns)
	}
}

func (s *Server) publishOverlaySocket(ctx context.Context, sock string, conns []*wsConn) {
	if sock == "" {
		s.log.Warn("overlay: skip empty socket (refuse first-found)",
			"conns", len(conns),
			"subscribers", s.countOverlay(),
		)
		return
	}
	viewCols, viewRows := overlayViewFor(conns)
	if sized, ok := s.overlay.(interface{ WantSize(cols, rows uint16) }); ok {
		sized.WantSize(viewCols, viewRows)
	}
	if err := s.overlay.Start(ctx, sock); err != nil {
		s.log.Warn("overlay: start failed", "err", err,
			"requested", sock,
			"subscribers", s.countOverlay(),
			"clients", overlayClients(s.overlay),
		)
		return
	}
	raw, err := s.overlay.Snapshot(ctx)
	if err != nil {
		s.log.Warn("overlay: snapshot failed", "err", err, "requested", sock)
		return
	}
	if len(raw) == 0 {
		s.log.Debug("overlay: empty snapshot skipped",
			"requested", sock,
			"captures", overlayCaptures(s.overlay),
			"bytes", 0,
		)
		return
	}
	sum := sha256.Sum256(raw)
	cur := hex.EncodeToString(sum[:])
	prev := s.overlayLastHash[sock]
	changed := prev != cur
	s.log.Debug("overlay: frame hash",
		"requested", sock,
		"prev", prev,
		"cur", cur,
		"bytes", len(raw),
		"changed", changed,
	)
	if !changed {
		return
	}
	s.overlayLastHash[sock] = cur
	seq := s.nextSeq()
	frame := protocol.OverlayFrame{
		Seq:  seq,
		Text: string(raw),
		Rows: viewRows,
		Cols: viewCols,
	}
	if prev == "" {
		s.log.Info("overlay: first frame",
			"requested", sock,
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
			"requested", sock,
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

func overlayViewFor(conns []*wsConn) (cols, rows uint16) {
	for _, c := range conns {
		cc, rr := c.overlayView()
		if cc > cols {
			cols = cc
		}
		if rr > rows {
			rows = rr
		}
	}
	if cols < 20 {
		cols = overlay.ScratchCols
	}
	if rows < 8 {
		rows = overlay.ScratchRows
	}
	return cols, rows
}

func (c *wsConn) handleOverlaySubscribe(req protocol.OverlaySubscribe) {
	// 已归档，2026-08-19 用户令暂不介入；展示不完全问题未修。
	// 接受合法 overlay_subscribe 以免客户端收到 invalid_field，但不 mark、
	// 不 attach、不起 scratch、不推 overlay_frame。
	c.s.log.Info("overlay: subscribe archived no-op",
		"requested", req.Socket,
		"cols", req.Cols,
		"rows", req.Rows,
	)
}

func (c *wsConn) handleOverlayUnsubscribe(protocol.OverlayUnsubscribe) {
	if c.overlayActive() {
		c.setOverlay(false, "", 0, 0)
		c.s.unmarkOverlay()
	}
}

func (c *wsConn) overlayActive() bool {
	c.overlayMu.Lock()
	defer c.overlayMu.Unlock()
	return c.overlayOn
}

func (c *wsConn) overlaySocket() string {
	c.overlayMu.Lock()
	defer c.overlayMu.Unlock()
	return c.overlaySock
}

func (c *wsConn) overlayView() (cols, rows uint16) {
	c.overlayMu.Lock()
	defer c.overlayMu.Unlock()
	return c.overlayCols, c.overlayRows
}

func (c *wsConn) setOverlay(on bool, sock string, cols, rows uint16) {
	c.overlayMu.Lock()
	defer c.overlayMu.Unlock()
	c.overlayOn = on
	if on {
		c.overlaySock = sock
		c.overlayCols = cols
		c.overlayRows = rows
	} else {
		c.overlaySock = ""
		c.overlayCols = 0
		c.overlayRows = 0
	}
}
