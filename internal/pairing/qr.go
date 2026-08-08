package pairing

// qr.go encodes the pairing handshake into a scannable QR. The QR content is a
// single JSON line (requirement 011 route (a)): the service ws URL, the
// pairing token, and a reserved Tailscale auth-key field the app-tsnet task
// fills in later. Rendering is a self-contained ANSI half-block painter (▀▄█),
// so onboarding needs no image pipeline and prints on any terminal.

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/skip2/go-qrcode"
)

// PayloadVersion is the schema version of the QR JSON payload.
const PayloadVersion = 1

// Payload is the QR content: version, service ws URL, pairing token, and the
// reserved Tailscale auth key. Field order and JSON names are part of the wire
// contract with the Android app (pairing-ui task) — do not rename.
type Payload struct {
	// Version is the payload schema version (PayloadVersion).
	Version int `json:"v"`
	// URL is the WebSocket endpoint, e.g. ws://192.168.1.5:9900/ws.
	URL string `json:"url"`
	// Token is the pairing token the app must present in its auth frame. It is
	// an intentional part of the payload: the QR is a legal token exit (§9).
	Token string `json:"token"`
	// TSAuthKey is reserved for route (a)'s scan-to-join flow: when app-tsnet
	// lands it carries the tailnet auth key so a scan also groups the phone
	// onto the tailnet. Empty for now.
	TSAuthKey string `json:"ts_authkey"`
}

// NewPayload builds the QR payload for one service URL and token.
func NewPayload(url, token string) Payload {
	return Payload{Version: PayloadVersion, URL: url, Token: token}
}

// Marshal encodes the payload as the compact JSON line that goes into the QR.
func (p Payload) Marshal() ([]byte, error) {
	return json.Marshal(p)
}

// RenderQR draws the half-block ANSI art for content: the full multi-line
// string (trailing newline) ready to print to a terminal.
func RenderQR(content string) (string, error) {
	q, err := qrcode.New(content, qrcode.Medium)
	if err != nil {
		return "", fmt.Errorf("pairing: qr encode: %w", err)
	}
	return renderHalfBlock(q.Bitmap()), nil
}

// renderHalfBlock paints a QR bitmap as half-block cells: each terminal cell
// shows two vertical modules (top from row y, bottom from row y+1) via ▀▄█ and
// space, so the artifact is one cell tall per two modules and readable in any
// terminal theme (dark modules use the default foreground, so contrast always
// exists against the background).
func renderHalfBlock(bm [][]bool) string {
	h := len(bm)
	if h == 0 {
		return ""
	}
	w := len(bm[0])
	var sb strings.Builder
	for y := 0; y < h; y += 2 {
		for x := 0; x < w; x++ {
			top := bm[y][x]
			bottom := y+1 < h && bm[y+1][x]
			sb.WriteRune(halfBlockRune(top, bottom))
		}
		sb.WriteByte('\n')
	}
	return sb.String()
}

// halfBlockRune selects the glyph covering exactly the dark modules of one
// two-row cell: both dark = full block, top only = upper half, bottom only =
// lower half, neither = blank.
func halfBlockRune(top, bottom bool) rune {
	switch {
	case top && bottom:
		return '█'
	case top && !bottom:
		return '▀'
	case !top && bottom:
		return '▄'
	default:
		return ' '
	}
}
