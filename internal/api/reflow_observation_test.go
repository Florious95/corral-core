package api

import (
	"bytes"
	"context"
	"log/slog"
	"strings"
	"testing"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestReflowDecisionLogDistinguishesFallbackCauses(t *testing.T) {
	for _, tc := range []struct {
		name                  string
		resize, sync, wrapped bool
		captures, rejected    int64
	}{
		{"same_geometry", false, false, false, 1, 0},
		{"completed_frame", true, true, false, 1, 0},
		{"missing_completion", true, false, false, 1, 0},
		{"wrapped_completion", true, true, true, 2, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			width := "10"
			if tc.wrapped {
				width = "100"
			}
			te := astraRound4Pane(t, `import os,time
os.write(1,b"PRIVATE_SCREEN_CONTENT"+b"x"*`+width+`+b"\r\n\x1b]0;ASTRA_R4_READY\x07")
while True: time.sleep(0.01)
`)
			var logs bytes.Buffer
			c := &wsConn{
				ctx:        context.Background(),
				s:          &Server{log: slog.New(slog.NewTextHandler(&logs, nil))},
				priorityCh: make(chan wsMsg, 1),
			}
			gate := newReflowGate()
			epoch, _ := gate.begin()
			if tc.sync {
				gate.route([]byte("\x1b[?2026h\x1b[?2026l"), func(uint64) {}, func() {})
			}
			err := c.publishReflowSnapshot(c.ctx, bridge.NewPane(te.sock, te.paneID), gate,
				protocol.Resize{Ref: te.ref(), Cols: 80, Rows: 24}, epoch, tc.resize)
			if err != nil {
				t.Fatal(err)
			}
			line := logs.String()
			if strings.Count(line, "msg=perf_reflow") != 1 || strings.Contains(line, "PRIVATE_SCREEN_CONTENT") {
				t.Fatalf("must log one metadata-only decision: %q", line)
			}
			if slogInt(t, line, "captures") != tc.captures || slogInt(t, line, "wrapped_rejected") != tc.rejected {
				t.Fatalf("wrong decision counters: %q", line)
			}
			completed := int64(0)
			if tc.sync && !tc.wrapped {
				completed = 1
			}
			if slogInt(t, line, "completed_frame") != completed || slogInt(t, line, "actual_cols") != 80 || slogInt(t, line, "actual_rows") != 24 {
				t.Fatalf("wrong observed frame/grid: %q", line)
			}
			fallback := tc.resize && (!tc.sync || tc.wrapped)
			if strings.Contains(line, "deadline_reached=true") != fallback || !strings.Contains(line, "success=true") {
				t.Fatalf("wrong fallback/outcome: %q", line)
			}
		})
	}
}
