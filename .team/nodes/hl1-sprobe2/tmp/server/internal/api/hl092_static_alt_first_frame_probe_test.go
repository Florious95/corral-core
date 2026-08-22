package api

// hl092_static_alt_first_frame_probe_test.go is the t.sprobe root-cause probe
// (ledger.hl1.v1): a real handleSubscribe path against an isolated tmux pane
// that already has alt-screen glyphs and then goes silent.
//
// handleSubscribe today does Resize → pipe-pane → capture. A frozen TUI that
// handles SIGWINCH by clearing (and not redrawing, because it is idle) leaves
// capture-pane with no glyphs. The snapshot is still sent (CUP suffix makes
// the payload non-empty), so len(data)==0 is a false green. This probe asserts
// the marker that was on the pane before subscribe.
//
// Two same-shaped worlds, distinguished in the failure text:
//   A 快照从没发 — 2s 内没有 KindSnapshot
//   B 快照发了但内容为空 — KindSnapshot 到了，但不含订阅前已有的字形

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

const staticAltMarker = "STATIC_ALT_MARKER_092"

const frozenAltScript = `#!/bin/bash
sleep 0.2
printf '\033[?1049h\033[H\033[2J` + staticAltMarker + `\n' > /dev/tty
trap 'printf "\033[2J\033[H" > /dev/tty' WINCH
while :; do read -r -t 3600 || true; done
`

// TestHandleSubscribeStaticAltScreenFirstFrameHasGlyphs is the 092 first-frame
// probe. Subscribe a pane that already shows glyphs and will emit no further
// output. The first snapshot on the real WS path must arrive within 2s and
// still contain those glyphs.
func TestHandleSubscribeStaticAltScreenFirstFrameHasGlyphs(t *testing.T) {
	script := filepath.Join(t.TempDir(), "frozen-alt.sh")
	if err := os.WriteFile(script, []byte(frozenAltScript), 0o755); err != nil {
		t.Fatalf("write frozen alt script: %v", err)
	}

	te := startTmuxEnv(t, script)
	waitFrozenAltReady(t, te)

	// Phone geometry ≠ host 80x24, so handleSubscribe must Resize (and the
	// frozen TUI therefore sees SIGWINCH) before it captures. Same-size
	// subscribe would skip the order bug this probe pins.
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 96, Cols: 108})

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("世界A 快照从没发: 2s 内未收到 KindSnapshot（read err=%v）", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode binary: %v", err)
		}
		if payload.Kind != protocol.KindSnapshot {
			t.Fatalf("世界A 快照从没发: 2s 内首个 binary 是 kind=%d（delta/其他），不是 KindSnapshot", payload.Kind)
		}
		glyphs := snapshotGlyphs(payload.Data)
		if !bytes.Contains(payload.Data, []byte(staticAltMarker)) {
			t.Fatalf("世界B 快照发了但内容为空: KindSnapshot 已到 bytes=%d glyphs=%q marker=%q 不在其中（CUP 后缀使 len>0 不能当有字）",
				len(payload.Data), glyphs, staticAltMarker)
		}
		return
	}
	t.Fatal("世界A 快照从没发: 2s 内未收到 KindSnapshot")
}

func waitFrozenAltReady(t *testing.T, te *tmuxEnv) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	var lastAlt, lastCap string
	for time.Now().Before(deadline) {
		alt, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{alternate_on}")
		if err != nil {
			t.Fatalf("precondition alternate_on: %v\n%s", err, alt)
		}
		lastAlt = strings.TrimSpace(alt)
		cap, err := runTmuxCmd(te.env, te.sock, "capture-pane", "-e", "-p", "-t", te.paneID)
		if err != nil {
			t.Fatalf("precondition capture-pane: %v\n%s", err, cap)
		}
		lastCap = cap
		if lastAlt == "1" && strings.Contains(cap, staticAltMarker) {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("precondition: frozen alt-screen never showed marker (alt=%q capture=%q)", lastAlt, lastCap)
}

// snapshotGlyphs strips CSI / ESC sequences and whitespace so a CUP-only
// snapshot (handleSubscribe always appends ESC[row;colH) reports as empty.
func snapshotGlyphs(data []byte) string {
	var b strings.Builder
	i := 0
	for i < len(data) {
		if data[i] == 0x1b {
			i++
			if i < len(data) && data[i] == '[' {
				i++
				for i < len(data) && (data[i] < '@' || data[i] > '~') {
					i++
				}
				if i < len(data) {
					i++
				}
			}
			continue
		}
		c := data[i]
		if c > ' ' && c < 0x7f {
			b.WriteByte(c)
		}
		i++
	}
	return b.String()
}
