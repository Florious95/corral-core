package api

// raw_bytes_api_test.go — 输入透传第 1 步 API 红测：Input.Bytes 进 pty，too_large 仍拦。

import (
	"bytes"
	"context"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// waitAckAndMirror drains until input_ack(reqID, ok) and a mirror substring
// have both been seen. readControlDraining would drop the echo binary frames.
func waitAckAndMirror(t *testing.T, te *tmuxEnv, reqID uint32, want string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var got bytes.Buffer
	var ia protocol.InputAck
	seenAck, seenEcho := false, false
	for time.Now().Before(deadline) && (!seenAck || !seenEcho) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("read: %v (seenAck=%v seenEcho=%v got=%q)", err, seenAck, seenEcho, got.String())
		}
		if typ != websocket.MessageBinary {
			typed, err := protocol.UnmarshalFrame(data)
			if err != nil {
				t.Fatalf("decode control: %v", err)
			}
			if ack, ok := typed.(protocol.InputAck); ok {
				ia = ack
				seenAck = true
			}
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode mirror: %v", err)
		}
		got.Write(payload.Data)
		if bytes.Contains(got.Bytes(), []byte(want)) {
			seenEcho = true
		}
	}
	if !seenAck {
		t.Fatalf("input_ack never arrived; got %q", got.String())
	}
	if ia.ReqID != reqID || !ia.OK {
		t.Fatalf("input_ack = %#v, want req_id=%d ok", ia, reqID)
	}
	if !seenEcho {
		t.Fatalf("mirror never delivered %q; got %q", want, got.String())
	}
}

// TestRawBytesControlByteReachesPtyAPI is R1 on the wire: Input.Bytes carrying
// ESC (控制字节) must show up in the mirror, not be dropped as a bare Enter.
func TestRawBytesControlByteReachesPtyAPI(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame()

	raw := []byte{0x1b, '[', 'A'} // ESC CSI CUU；镜像里 ESC 渲染为 "^["
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 91, Ref: te.ref(), Bytes: raw})
	waitAckAndMirror(t, te, 91, "^[[A")
}

// TestMixedInputOrderOnWire is R2 at the API: printable+control mixed bytes
// arrive in order. 调用次数 is asserted in the bridge fake-tmux test.
func TestMixedInputOrderOnWire(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame()

	raw := []byte{'X', 0x1b, 'Y'}
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 92, Ref: te.ref(), Bytes: raw})
	waitAckAndMirror(t, te, 92, "X^[Y")
}

// TestPassthroughCompatTextStillAcks is R3 on the wire: 老 Text 路径兼容.
func TestPassthroughCompatTextStillAcks(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame()
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 93, Ref: te.ref(), Text: "COMPAT_TEXT"})
	te.waitForMirror("COMPAT_TEXT")
}

// TestRawBytesTooLargeRejected pins max-input-bytes on the Bytes path
// (no backdoor around too_large).
func TestRawBytesTooLargeRejected(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame()
	// Cap is the same comparison as production (len > maxInput); a tiny
	// cap avoids a 1 MiB websocket write that already broke the pipe on
	// the red run. Default 1 MiB is pinned in config_test.go.
	te.wsEnv.srv.maxInput = 16
	big := make([]byte, 17)
	for i := range big {
		big[i] = 'a'
	}
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 94, Ref: te.ref(), Bytes: big})
	ack := te.wsEnv.readControlDraining()
	ia, ok := ack.(protocol.InputAck)
	if !ok {
		t.Fatalf("want input_ack, got %#v", ack)
	}
	if ia.OK {
		t.Fatal("oversize Bytes must not be ok")
	}
	if ia.Reason != protocol.InputFailTooLarge {
		t.Fatalf("reason = %q, want too_large", ia.Reason)
	}
}
