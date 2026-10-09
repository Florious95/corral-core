package bridge

import (
	"bytes"
	"context"
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestCaptureStateNativeWrapAndPendingRightMargin(t *testing.T) {
	for _, n := range []int{79, 80, 81} {
		t.Run(fmt.Sprint(n), func(t *testing.T) {
			tt := newTestTMUX(t)
			p := tt.newPane(t, fmt.Sprintf(`exec python3 -c 'import os,time;os.write(1,b"\x1b[?1002h\x1b[?1006h\x1b[31m"+b"x"*%d+b"\x1b]0;capture-ready\x07");time.sleep(30)'`, n))
			ready := false
			for deadline := time.Now().Add(3 * time.Second); time.Now().Before(deadline); {
				title, err := tt.run("display-message", "-p", "-t", p.target, "#{pane_title}")
				if err == nil && strings.TrimSpace(title) == "capture-ready" {
					ready = true
					break
				}
				time.Sleep(5 * time.Millisecond)
			}
			if !ready {
				t.Fatal("source did not acknowledge ready")
			}
			frame, err := p.CaptureState(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			if frame.Cols != 80 || frame.Rows != 24 || frame.WrappedRows != (n > 80) {
				t.Fatalf("n=%d metadata=%+v", n, frame)
			}
			if !frame.Mouse.Any || !frame.Mouse.Button || !frame.Mouse.SGR {
				t.Fatalf("mouse mode lost: %+v", frame.Mouse)
			}
			if !bytes.Contains(frame.Data, []byte("\x1b[31m")) || bytes.Count(frame.Data, []byte{'\n'}) != 24 {
				t.Fatalf("physical/color capture corrupted: %q", frame.Data)
			}
			if n <= 80 && (frame.CursorX != n || frame.CursorY != 0) {
				t.Fatalf("pending-margin cursor lost: (%d,%d), n=%d", frame.CursorX, frame.CursorY, n)
			}
		})
	}
}

func TestCaptureStateRejectsMalformedReply(t *testing.T) {
	for _, reply := range []string{
		"missing newline",
		"4|2|0\na\nb\na\nb\n",
		"0|2|0|0|0|0|0|0|0\na\nb\na\nb\n",
		"4|2|5|0|0|0|0|0|0\na\nb\na\nb\n",
		"4|2|0|0|2|0|0|0|0\na\nb\na\nb\n",
		"4|2|0|0|0|0|0|0|0\na\n",
		"4|2|0|0|0|0|0|0|0\na\nb\n",
		"4|2|0|0|0|0|0|0|0\na\nb\na\nb\nc\n",
	} {
		if _, err := parseCapturedPane([]byte(reply)); err == nil {
			t.Fatalf("accepted malformed capture %q", reply)
		}
	}
}
