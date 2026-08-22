package pairing

// qr.go encodes the pairing handshake into a scannable QR. The QR content is a
// single JSON line (requirement 011 route (a)): the service ws URL, the
// pairing token, and the optional Tailscale auth key consumed by the embedded
// App tsnet node. Rendering is a self-contained ANSI half-block painter (▀▄█),
// so onboarding needs no image pipeline and prints on any terminal.

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/skip2/go-qrcode"
)

// PayloadVersion is the schema version of the QR JSON payload.
const PayloadVersion = 1

// Payload is the QR content: version, service ws URL, pairing token, the
// optional Tailscale auth key, and the optional full candidate ws URL set
// (task fix-pairing-candidates). Field order and JSON names are part of the
// wire contract with the Android app (docs/protocol.md §2.1) — do not rename.
type Payload struct {
	// Version is the payload schema version (PayloadVersion).
	Version int `json:"v"`
	// URL is the WebSocket endpoint, e.g. ws://192.168.1.5:9900/ws.
	URL string `json:"url"`
	// Token is the pairing token the app must present in its auth frame. It is
	// an intentional part of the payload: the QR is a legal token exit (§9).
	Token string `json:"token"`
	// TSAuthKey carries the credential for scan-to-join when configured. It is
	// legal only inside the QR payload and must never enter the plain-text guide.
	TSAuthKey string `json:"ts_authkey"`
	// Candidates is the OPTIONAL full candidate ws URL set for THIS host (its
	// other NICs, LAN + tailnet, never loopback). It is forward compatible:
	// omitempty keeps a no-candidate QR byte-identical to the pre-feature
	// contract, so no version bump. When non-empty it leads with the primary
	// URL (docs/protocol.md §2.1).
	Candidates []string `json:"candidates,omitempty"`
}

// NewPayload builds the QR payload for one service URL and token, with no
// candidate set (forward-compatible default: the candidates key is omitted).
func NewPayload(url, token string) Payload {
	return Payload{Version: PayloadVersion, URL: url, Token: token}
}

// NewPayloadWithCandidates builds the QR payload for one service URL, token,
// and the host's full candidate ws URL set (task fix-pairing-candidates). The
// app tries the primary URL first, then each candidate on failure.
func NewPayloadWithCandidates(url, token string, candidates []string) Payload {
	return Payload{Version: PayloadVersion, URL: url, Token: token, Candidates: candidates}
}

// Marshal encodes the payload as the compact JSON line that goes into the QR.
func (p Payload) Marshal() ([]byte, error) {
	return json.Marshal(p)
}

// RenderQR draws the half-block ANSI art for content: the full multi-line
// string (trailing newline) ready to print to a terminal.
// @contract
// @pre none
// @post 返回以换行结尾的多行 ANSI 半块艺术串（每两个模块一行）
// @err content 超过所选纠错级别（Medium）的 QR 容量时报错
// @inv none
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
