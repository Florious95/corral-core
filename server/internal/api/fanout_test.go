package api

// fanout_test.go — Q5 红测（API 层）：被强制下线必须有明确帧，⛔ 不许静默 EOF。
// 修前：relay 见管道关闭只 teardownSubscription，不 sendError，客户端画面冻住。

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// dialSameServer connects a second authenticated client to e's httptest
// server (two subscribers, one daemon). Does not Close the shared Server.
func dialSameServer(t *testing.T, e *wsEnv) *wsEnv {
	t.Helper()
	url := "ws" + strings.TrimPrefix(e.hsrv.URL, "http") + "/ws"
	conn, _, err := websocket.Dial(context.Background(), url, nil)
	if err != nil {
		t.Fatalf("dial second client: %v", err)
	}
	t.Cleanup(func() { _ = conn.CloseNow() })
	peer := &wsEnv{t: t, srv: e.srv, hsrv: e.hsrv, conn: conn}
	peer.auth()
	return peer
}

func waitForMirrorOn(t *testing.T, e *wsEnv, want string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var got strings.Builder
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("mirror read: %v (got %q)", err, got.String())
		}
		if time.Now().After(deadline) {
			t.Fatalf("mirror never delivered %q; got %q", want, got.String())
		}
		if typ != websocket.MessageBinary {
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode mirror: %v", err)
		}
		got.WriteString(string(payload.Data))
		if strings.Contains(got.String(), want) {
			return
		}
	}
}

// TestFanoutTwoSubscribersStillReceiveAPI is assertion A at the wire:
// 第二个接入后第一个仍能收 DELTA（not affected）。
func TestFanoutTwoSubscribersStillReceiveAPI(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	ref := te.ref()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
	_ = te.readBinaryFrame()

	peer := dialSameServer(t, te.wsEnv)
	peer.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
	if snap := readBinaryOn(t, peer); snap.Kind != protocol.KindSnapshot {
		t.Fatalf("second client first frame kind=%d, want snapshot", snap.Kind)
	}

	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: ref, Text: "FANOUT_API_A"})
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 2, Ref: ref, Text: ""})
	waitForMirrorOn(t, te.wsEnv, "FANOUT_API_A")
	waitForMirrorOn(t, peer, "FANOUT_API_A")
}

func readBinaryOn(t *testing.T, e *wsEnv) protocol.BinaryPayload {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("read binary: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode binary: %v", err)
		}
		return payload
	}
	t.Fatal("timed out waiting for binary frame")
	return protocol.BinaryPayload{}
}

// TestFanoutFirstDisconnectDoesNotKickSecondAPI is assertion B at the wire:
// 第一个 close 后第二个仍能收 DELTA。
func TestFanoutFirstDisconnectDoesNotKickSecondAPI(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	ref := te.ref()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
	_ = te.readBinaryFrame()

	peer := dialSameServer(t, te.wsEnv)
	peer.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
	_ = readBinaryOn(t, peer)

	_ = te.wsEnv.conn.CloseNow() // 第一人断开

	peer.sendFrame(&protocol.Input{ReqID: 3, Ref: ref, Text: "FANOUT_API_B"})
	peer.sendFrame(&protocol.Input{ReqID: 4, Ref: ref, Text: ""})
	waitForMirrorOn(t, peer, "FANOUT_API_B")
}

// TestFanoutDisplacedSendsError is assertion C: 管道被强制拆掉时，订阅者
// 必须收到明确帧（error / sendError），⛔ 不是静默 EOF。
// kick 手段：对 pane 发无参 pipe-pane（模拟被外部抢走/拆除）。
func TestFanoutDisplacedSendsError(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	ref := te.ref()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
	_ = te.readBinaryFrame()

	if _, err := runTmuxCmd(te.env, te.sock, "pipe-pane", "-t", te.paneID); err != nil {
		t.Fatalf("external pipe-pane detach (kick): %v", err)
	}

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("C: connection died without a control frame: %v", err)
		}
		if typ != websocket.MessageText {
			continue
		}
		typed, err := protocol.UnmarshalFrame(data)
		if err != nil {
			t.Fatalf("decode control: %v", err)
		}
		ef, ok := typed.(protocol.ErrorFrame)
		if !ok {
			continue
		}
		t.Logf("got displaced notif error frame: code=%s reason=%q", ef.Code, ef.Reason)
		if ef.Reason == "" {
			t.Fatal("error frame must carry a reason (明确帧, not empty)")
		}
		return
	}
	t.Fatal("C: pipe kick produced no sendError / 明确帧 (silent EOF freeze)")
}
