// Package main implements the e2e harness: a Go test binary that drives the
// full agentmirror protocol chain against an isolated tmux + a real
// agentmirrord daemon (no emulator — layer 1) and the aging loops (layer 3).
//
// The client speaks the real wire protocol: JSON control frames (protocol
// package) and the binary mirror channel (protocol.EncodeBinary layout). It
// reuses the server's protocol package via the local `replace` in go.mod, so
// the wire shapes cannot drift from what the server produces.
package main

import (
	"bytes"
	"context"
	"fmt"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// wsMsg is one decoded inbound message. Exactly one of the two arms is
// meaningful: a control frame (Typed) or a binary stream frame (BinaryPayload).
type wsMsg struct {
	// control is the decoded control frame (nil when this is a binary frame).
	control protocol.Typed
	// binOK reports whether this is a binary frame (Bin valid).
	binOK bool
	// bin is the decoded binary stream frame (valid only when binOK).
	bin protocol.BinaryPayload
	// decodeErr carries a binary-decode failure (diagnostic only).
	decodeErr error
}

// Client is a minimal authenticated WebSocket client for the agentmirror
// protocol. It owns one connection and a reader goroutine that decodes every
// inbound message onto a buffered channel; tests consume via wait helpers.
type Client struct {
	conn *websocket.Conn
	recv chan wsMsg
	ctx  context.Context
}

// Connect dials the WS endpoint and authenticates with the given token. It
// returns the client only after auth_ack{ok:true} (or after the connection
// closes, which the server uses as the rejection signal). A rejected auth is
// an error carrying the reason.
func Connect(ctx context.Context, wsURL, token string) (*Client, error) {
	conn, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		return nil, fmt.Errorf("dial: %w", err)
	}
	c := &Client{
		conn: conn,
		recv: make(chan wsMsg, 256),
		ctx:  ctx,
	}
	go c.readLoop()

	// Auth handshake: send auth, expect auth_ack ok, or close-with-reason.
	if err := c.Send(ctx, protocol.Auth{Token: token}); err != nil {
		conn.Close(websocket.StatusNormalClosure, "auth send failed")
		return nil, err
	}
	deadline, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	m, err := c.waitFrame(deadline)
	if err != nil {
		conn.Close(websocket.StatusNormalClosure, "no auth reply")
		return nil, fmt.Errorf("auth: %w", err)
	}
	ack, ok := m.control.(protocol.AuthAck)
	if !ok {
		return nil, fmt.Errorf("auth: expected auth_ack, got %T (%v)", m.control, m.control)
	}
	if !ack.OK {
		return nil, fmt.Errorf("auth rejected: %s", ack.Reason)
	}
	return c, nil
}

// readLoop reads and decodes every inbound message until the connection ends.
// decodeErrs counts binary frames the decoder rejected (diagnostic for delta
// loss investigations).
func (c *Client) readLoop() {
	defer close(c.recv)
	for {
		typ, data, err := c.conn.Read(c.ctx)
		if err != nil {
			return
		}
		if typ == websocket.MessageBinary {
			bin, err := protocol.DecodeBinary(data)
			if err != nil {
				// A malformed mirror frame is a protocol violation; surface it
				// as an error frame-shaped message so tests fail loudly.
				select {
				case c.recv <- wsMsg{binOK: false, control: nil, decodeErr: err}:
				default:
				}
				continue
			}
			select {
			case c.recv <- wsMsg{binOK: true, bin: bin}:
			default: // full: drop; tests that need this frame read promptly
			}
			continue
		}
		f, err := protocol.UnmarshalFrame(data)
		if err != nil {
			continue // a bad control frame is a server bug; swallow to keep reading
		}
		select {
		case c.recv <- wsMsg{control: f}:
		default:
		}
	}
}

// Send marshals and writes one control frame.
func (c *Client) Send(ctx context.Context, p protocol.Typed) error {
	data, err := protocol.MarshalFrame(p)
	if err != nil {
		return fmt.Errorf("marshal %T: %w", p, err)
	}
	wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return c.conn.Write(wctx, websocket.MessageText, data)
}

// waitFrame reads the next inbound message, honoring ctx timeout.
func (c *Client) waitFrame(ctx context.Context) (wsMsg, error) {
	select {
	case m, ok := <-c.recv:
		if !ok {
			return wsMsg{}, fmt.Errorf("connection closed")
		}
		return m, nil
	case <-ctx.Done():
		return wsMsg{}, fmt.Errorf("timeout waiting for frame: %w", ctx.Err())
	}
}

// waitControl waits for the next control frame of the given type, discarding
// anything else (binary deltas may be in flight while we await an ack). It
// returns the typed payload.
func (c *Client) waitControl(ctx context.Context, want protocol.FrameType) (protocol.Typed, error) {
	for {
		m, err := c.waitFrame(ctx)
		if err != nil {
			return nil, err
		}
		if m.binOK || m.control == nil {
			continue
		}
		if m.control.FrameType() != want {
			continue
		}
		return m.control, nil
	}
}

// waitError waits for an error frame, returning its code/reason.
func (c *Client) waitError(ctx context.Context) (protocol.ErrorFrame, error) {
	f, err := c.waitControl(ctx, protocol.TypeError)
	if err != nil {
		return protocol.ErrorFrame{}, err
	}
	ef, ok := f.(protocol.ErrorFrame)
	if !ok {
		return protocol.ErrorFrame{}, fmt.Errorf("error frame decoded as %T", f)
	}
	return ef, nil
}

// waitBinary waits for the next binary frame of the given kind for the given
// ref, discarding anything else. It is the workhorse for snapshot/delta/
// scrollback assertions.
func (c *Client) waitBinary(ctx context.Context, kind protocol.BinaryKind, ref string) (protocol.BinaryPayload, error) {
	for {
		m, err := c.waitFrame(ctx)
		if err != nil {
			return protocol.BinaryPayload{}, err
		}
		if !m.binOK {
			continue
		}
		if m.bin.Kind != kind || m.bin.Ref != ref {
			continue
		}
		return m.bin, nil
	}
}

// Close shuts the connection down cleanly.
func (c *Client) Close() error {
	return c.conn.Close(websocket.StatusNormalClosure, "done")
}

// containsStr reports whether the byte slice contains the given substring.
func containsStr(b []byte, s string) bool {
	return bytes.Contains(b, []byte(s))
}

// waitAckAndDelta sends one Input and watches the connection from that moment
// for BOTH the decidable input_ack and a delta carrying substr in the given
// ref's stream (the echo can race the ack — inject → send-keys → shell echo →
// pipe → delta vs the ack reply). It returns (ackOK, deltaOK). Callers drain
// from inject time, never from after the ack, or a fast echo is missed.
func (c *Client) waitAckAndDelta(ctx context.Context, reqID uint32, ref, text, substr string, timeout time.Duration) (ackOK, deltaOK bool) {
	if err := c.Send(ctx, protocol.Input{ReqID: reqID, Ref: ref, Text: text}); err != nil {
		return false, false
	}
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		dctx, cancel := context.WithTimeout(ctx, time.Until(deadline))
		m, err := c.waitFrame(dctx)
		cancel()
		if err != nil {
			return ackOK, deltaOK
		}
		if m.binOK && m.bin.Ref == ref && containsStr(m.bin.Data, substr) {
			deltaOK = true
		}
		if !m.binOK && m.control != nil && m.control.FrameType() == protocol.TypeInputAck {
			if a, ok := m.control.(protocol.InputAck); ok {
				ackOK = a.OK && a.ReqID == reqID
			}
		}
		if ackOK && deltaOK {
			return ackOK, deltaOK
		}
	}
	return ackOK, deltaOK
}
