package api

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

func TestWireRoundTripInvalidFieldDistinctFromBadFrame(t *testing.T) {
	e := startWS(t, Options{Token: "test-token", Discoverer: scriptedDiscoverer{model: testModel()}})
	e.auth()

	emptySub := []byte(`{"v":1,"type":"overlay_subscribe","payload":{"socket":""}}`)
	if err := e.conn.Write(context.Background(), websocket.MessageText, emptySub); err != nil {
		t.Fatalf("write empty overlay_subscribe: %v", err)
	}
	got := e.readControl()
	ef, ok := got.(protocol.ErrorFrame)
	if !ok {
		t.Fatalf("empty socket: got %T %v, want ErrorFrame", got, got)
	}
	if ef.Code != protocol.ErrCodeInvalidField {
		t.Fatalf("empty socket code=%q want invalid_field (must not share bad_frame)", ef.Code)
	}
	if !strings.Contains(ef.Reason, "socket") {
		t.Fatalf("invalid_field reason must name the field, got %q", ef.Reason)
	}

	if err := e.conn.Write(context.Background(), websocket.MessageText, []byte(`{"v":1,"type"`)); err != nil {
		t.Fatalf("write malformed: %v", err)
	}
	got2 := e.readControl()
	ef2, ok := got2.(protocol.ErrorFrame)
	if !ok {
		t.Fatalf("malformed: got %T %v, want ErrorFrame", got2, got2)
	}
	if ef2.Code != protocol.ErrCodeBadFrame {
		t.Fatalf("true decode failure code=%q want bad_frame", ef2.Code)
	}
}

func TestWireRoundTripOverlaySubscribePathAccepted(t *testing.T) {
	e := startWS(t, overlayTestOpts(&countingOverlay{}))
	e.auth()
	e.sendFrame(protocol.OverlaySubscribe{Socket: "/tmp/tmux-1000/default"})
	// 合法订阅不得回 invalid_field / bad_frame
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	_, data, err := e.conn.Read(ctx)
	if err != nil {
		return // 无立刻错误帧即可
	}
	typed, err := protocol.UnmarshalFrame(data)
	if err != nil {
		return
	}
	if ef, ok := typed.(protocol.ErrorFrame); ok {
		t.Fatalf("valid overlay_subscribe must not error: %+v", ef)
	}
}
