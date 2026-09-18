package api

import (
	"context"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// Model a second geometry owner overriding the phone resize before capture.
// A frame measured at the requested grid is the contract, not an assumption
// based on the earlier successful resize command.
func TestInitialSubscribeRefusesDifferentCapturedGrid(t *testing.T) {
	te := astraRound4Pane(t, `import os,signal,subprocess,time
changed=False
def redraw(_s,_f):
    global changed
    if changed: return
    changed=True
    subprocess.run(["tmux","-S",os.environ["TMUX"].split(",")[0],"resize-window","-t",os.environ["TMUX_PANE"],"-x","90","-y","25"],check=True)
    os.write(1,b"\x1b[?2026h\x1b[H\x1b[2JOTHER_OWNER_90_COLUMNS\r\n\x1b[?2026l")
signal.signal(signal.SIGWINCH,redraw)
os.write(1,b"\x1b]0;ASTRA_R4_READY\x07INITIAL\r\n")
while True: time.sleep(0.01)
`)
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	for {
		typ, data, err := te.wsEnv.conn.Read(ctx)
		if err != nil {
			t.Fatalf("expected a decidable geometry error, not a hang/close: %v", err)
		}
		if typ == websocket.MessageBinary {
			t.Fatalf("published data from a different grid: %q", data)
		}
		frame, err := protocol.UnmarshalFrame(data)
		if err != nil {
			t.Fatal(err)
		}
		if frame.FrameType() == protocol.TypeError {
			return
		}
	}
}
