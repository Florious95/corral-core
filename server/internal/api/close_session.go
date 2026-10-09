package api

import (
	"errors"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func closeSessionResult(reqID uint32, ok bool, reason string) protocol.CloseSessionResult {
	return protocol.CloseSessionResult{ReqID: reqID, OK: ok, Reason: reason}
}

// handleCloseSession resolves the pane identity carried by ref and terminates
// only that pane. It deliberately does not resolve a window or session: a
// sibling pane in the same window must survive this request.
func (c *wsConn) handleCloseSession(req protocol.CloseSession) {
	socket, paneID, ok := parseSessionRef(req.Ref)
	if !ok {
		c.send(closeSessionResult(req.ReqID, false, "session_not_found"))
		return
	}
	if err := bridge.KillPane(socket, paneID); err != nil {
		reason := "close_failed"
		if errors.Is(err, bridge.ErrPaneNotFound) || errors.Is(err, bridge.ErrServerUnreachable) {
			reason = "session_not_found"
		}
		c.send(closeSessionResult(req.ReqID, false, reason))
		return
	}
	// Publish the removal immediately after tmux confirms the pane is gone.
	// The scan coordinator remains the sole owner of catalog publication and
	// list_delta fan-out.
	c.s.scans.cadence()
	c.send(closeSessionResult(req.ReqID, true, ""))
}
