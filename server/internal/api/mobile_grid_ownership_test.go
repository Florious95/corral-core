package api

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// Real private tmux and two real WebSockets: stale desktop commands cannot
// change a live phone's PTY, but the desktop may take over after the phone leaves.
func TestMobileOwnsPTYAcrossDesktopSubscribeResizeAndReconnect(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	phone := te.wsEnv
	phone.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 44, Cols: 46, ClientType: protocol.ClientTypeMobile})
	presence := func(e *wsEnv, mobile bool) {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		for {
			kind, data, err := e.conn.Read(ctx)
			if err != nil {
				t.Fatal(err)
			}
			if kind != websocket.MessageText {
				continue
			}
			frame, err := protocol.UnmarshalFrame(data)
			if err != nil {
				t.Fatal(err)
			}
			if update, ok := frame.(protocol.PresenceUpdate); ok && update.Ref == te.ref() && update.HasMobile == mobile {
				return
			}
		}
	}
	grid := func(cols, rows int) {
		t.Helper()
		out, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{pane_width}x#{pane_height}")
		if err != nil {
			t.Fatal(err)
		}
		if got, want := strings.TrimSpace(out), fmt.Sprintf("%dx%d", cols, rows); got != want {
			t.Fatalf("real PTY = %s, want %s", got, want)
		}
	}
	dial := func() *wsEnv {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(phone.hsrv.URL, "http")+"/ws", nil)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = conn.CloseNow() })
		e := &wsEnv{t: t, srv: phone.srv, hsrv: phone.hsrv, conn: conn}
		e.auth()
		return e
	}
	resize := func(e *wsEnv, cols, rows uint16) {
		t.Helper()
		e.sendFrame(&protocol.Resize{Ref: te.ref(), Cols: cols, Rows: rows})
		// A later input ACK is a reader-order receipt: the preceding resize has
		// completed (or been rejected), not merely sat in a client write queue.
		e.sendFrame(&protocol.Input{ReqID: 99, Ref: te.ref(), Text: "x"})
		for {
			if ack, ok := e.readControlDraining().(protocol.InputAck); ok && ack.ReqID == 99 {
				if !ack.OK {
					t.Fatalf("input stopped working: %+v", ack)
				}
				return
			}
		}
	}
	presence(phone, true)
	grid(46, 44)
	desktop := dial()
	desktop.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 51, Cols: 139, ClientType: protocol.ClientTypeDesktop})
	presence(desktop, true)
	grid(46, 44)
	resize(desktop, 112, 60)
	grid(46, 44)
	_ = desktop.conn.CloseNow()
	desktop = dial()
	desktop.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 61, Cols: 113, ClientType: protocol.ClientTypeDesktop})
	presence(desktop, true)
	grid(46, 44)
	resize(phone, 80, 24)
	grid(80, 24)
	phone.sendFrame(&protocol.Unsubscribe{Ref: te.ref()})
	presence(desktop, false)
	resize(desktop, 112, 60)
	grid(112, 60)
}
