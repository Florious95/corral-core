package api

import (
	"bytes"
	"context"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// This diagnostic uses only startTmuxEnv's private socket and httptest port.
func TestIncident13839ResubscribeMustDeliverNewPTYOutput(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	snapshot := func() {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		defer cancel()
		for {
			typ, data, err := te.wsEnv.conn.Read(ctx)
			if err != nil {
				t.Fatal(err)
			}
			if typ != websocket.MessageBinary {
				continue
			}
			frame, err := protocol.DecodeBinary(data)
			if err != nil {
				t.Fatal(err)
			}
			if frame.Ref == te.ref() && frame.Kind == protocol.KindSnapshot {
				return
			}
		}
	}
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	snapshot()
	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 24, Cols: 81})
	snapshot()
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 501, Ref: te.ref(), Text: "LIVE_BEFORE_RESUBSCRIBE"})
	if ack := te.waitForMirrorAndInputAck("LIVE_BEFORE_RESUBSCRIBE", 501); !ack.OK {
		t.Fatal(ack)
	}
	t.Log("positive control: real PTY delta delivered after resize, before resubscription")

	te.wsEnv.sendFrame(&protocol.Unsubscribe{Ref: te.ref()})
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 81})
	snapshot()
	t.Log("same WebSocket received the replacement subscription snapshot")
	const marker = "LIVE_AFTER_RESUBSCRIBE_13839"
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 502, Ref: te.ref(), Text: marker})
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 503, Ref: te.ref(), Text: ""})
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var wire bytes.Buffer
	ackOK := false
	for {
		typ, data, err := te.wsEnv.conn.Read(ctx)
		if err != nil {
			source, sourceErr := runTmuxCmd(te.env, te.sock, "capture-pane", "-p", "-t", te.paneID)
			t.Fatalf("post-resubscribe delta missing: ackOK=%v wire=%q sourceContainsMarker=%v sourceError=%v read=%v", ackOK, wire.String(), strings.Contains(source, marker), sourceErr, err)
		}
		if typ == websocket.MessageBinary {
			frame, err := protocol.DecodeBinary(data)
			if err != nil {
				t.Fatal(err)
			}
			if frame.Ref == te.ref() && frame.Kind == protocol.KindDelta {
				wire.Write(frame.Data)
			}
		} else {
			control, err := protocol.UnmarshalFrame(data)
			if err != nil {
				t.Fatal(err)
			}
			if ack, ok := control.(protocol.InputAck); ok && ack.ReqID == 502 {
				ackOK = ack.OK
			}
		}
		if ackOK && bytes.Contains(wire.Bytes(), []byte(marker)) {
			return
		}
	}
}

func TestIncident13839NewGateEpochMustSurviveOldStaleThreshold(t *testing.T) {
	c := &wsConn{}
	c.markStaleBefore("isolated-pane", 7)
	gate := newReflowGate()
	epoch, _ := gate.begin()
	c.markStaleBefore("isolated-pane", epoch)
	gate.end()
	if c.staleDelta(wsMsg{droppable: true, streamRef: "isolated-pane", epoch: epoch}) {
		t.Fatalf("fresh resubscription delta classified stale: newGateEpoch=%d retainedThreshold=%d", epoch, c.staleBefore["isolated-pane"])
	}
}
