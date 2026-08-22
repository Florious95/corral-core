// dump.go — adapted from e2e/harness Client (auth → list → subscribe → first KindSnapshot).
// measurement-only; writes dumps under -out.
package main

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

type wsMsg struct {
	control   protocol.Typed
	binOK     bool
	bin       protocol.BinaryPayload
	raw       []byte
	decodeErr error
}

type Client struct {
	conn *websocket.Conn
	recv chan wsMsg
	ctx  context.Context
}

func connect(ctx context.Context, wsURL, token string) (*Client, error) {
	conn, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		return nil, fmt.Errorf("dial: %w", err)
	}
	c := &Client{conn: conn, recv: make(chan wsMsg, 256), ctx: ctx}
	go c.readLoop()
	if err := c.send(ctx, protocol.Auth{Token: token}); err != nil {
		conn.Close(websocket.StatusNormalClosure, "auth send failed")
		return nil, err
	}
	deadline, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	m, err := c.waitFrame(deadline)
	if err != nil {
		conn.Close(websocket.StatusNormalClosure, "no auth reply")
		return nil, fmt.Errorf("auth: %w", err)
	}
	ack, ok := m.control.(protocol.AuthAck)
	if !ok {
		return nil, fmt.Errorf("auth: expected auth_ack, got %T", m.control)
	}
	if !ack.OK {
		return nil, fmt.Errorf("auth rejected: %s", ack.Reason)
	}
	return c, nil
}

func (c *Client) readLoop() {
	defer close(c.recv)
	for {
		typ, data, err := c.conn.Read(c.ctx)
		if err != nil {
			return
		}
		raw := append([]byte(nil), data...)
		if typ == websocket.MessageBinary {
			bin, err := protocol.DecodeBinary(data)
			if err != nil {
				select {
				case c.recv <- wsMsg{raw: raw, decodeErr: err}:
				default:
				}
				continue
			}
			select {
			case c.recv <- wsMsg{binOK: true, bin: bin, raw: raw}:
			default:
			}
			continue
		}
		f, err := protocol.UnmarshalFrame(data)
		if err != nil {
			continue
		}
		select {
		case c.recv <- wsMsg{control: f, raw: raw}:
		default:
		}
	}
}

func (c *Client) send(ctx context.Context, p protocol.Typed) error {
	data, err := protocol.MarshalFrame(p)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return c.conn.Write(wctx, websocket.MessageText, data)
}

func (c *Client) waitFrame(ctx context.Context) (wsMsg, error) {
	select {
	case m, ok := <-c.recv:
		if !ok {
			return wsMsg{}, fmt.Errorf("connection closed")
		}
		return m, nil
	case <-ctx.Done():
		return wsMsg{}, fmt.Errorf("timeout waiting for frame: %w", ctx.Err())
	}
}

func (c *Client) Close() error {
	return c.conn.Close(websocket.StatusNormalClosure, "done")
}

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

func hexHead(b []byte, n int) string {
	if len(b) < n {
		n = len(b)
	}
	return hex.Dump(b[:n])
}

func printablePreview(b []byte, n int) string {
	if len(b) < n {
		n = len(b)
	}
	var s strings.Builder
	for _, c := range b[:n] {
		if c == '\n' {
			s.WriteString("\\n")
		} else if c == '\r' {
			s.WriteString("\\r")
		} else if c == 0x1b {
			s.WriteString("\\e")
		} else if unicode.IsPrint(rune(c)) {
			s.WriteByte(c)
		} else {
			s.WriteString(fmt.Sprintf("\\x%02x", c))
		}
	}
	return s.String()
}

func main() {
	wsURL := flag.String("url", "", "ws url")
	token := flag.String("token", "", "auth token")
	cwdNeedle := flag.String("cwd", "", "session cwd substring")
	outDir := flag.String("out", ".", "output dir")
	rows := flag.Uint("rows", 96, "subscribe rows (phone geom)")
	cols := flag.Uint("cols", 108, "subscribe cols (phone geom)")
	timeout := flag.Duration("timeout", 8*time.Second, "wait snapshot")
	flag.Parse()
	if *wsURL == "" || *token == "" || *cwdNeedle == "" {
		fmt.Fprintln(os.Stderr, "need -url -token -cwd")
		os.Exit(2)
	}
	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	c, err := connect(ctx, *wsURL, *token)
	if err != nil {
		fmt.Fprintf(os.Stderr, "connect/auth: %v\n", err)
		os.Exit(1)
	}
	defer c.Close()

	if err := c.send(ctx, protocol.List{ReqID: 1}); err != nil {
		fmt.Fprintf(os.Stderr, "list: %v\n", err)
		os.Exit(1)
	}

	var listing protocol.Listing
	found := false
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) && !found {
		dctx, cancel := context.WithTimeout(ctx, time.Until(deadline))
		m, err := c.waitFrame(dctx)
		cancel()
		if err != nil {
			fmt.Fprintf(os.Stderr, "wait listing: %v\n", err)
			os.Exit(1)
		}
		if m.binOK || m.control == nil {
			continue
		}
		l, ok := m.control.(protocol.Listing)
		if !ok {
			continue
		}
		listing = l
		found = true
	}
	if !found {
		fmt.Fprintln(os.Stderr, "no listing")
		os.Exit(1)
	}
	rawList, _ := json.MarshalIndent(listing, "", "  ")
	_ = os.WriteFile(filepath.Join(*outDir, "listing.json"), rawList, 0o644)

	var ref, cwd, name string
	nMatch := 0
	for _, ws := range listing.Workspaces {
		for _, s := range ws.Sessions {
			if strings.Contains(s.Cwd, *cwdNeedle) {
				nMatch++
				ref, cwd, name = s.Ref, s.Cwd, s.Name
			}
		}
	}
	if nMatch != 1 {
		fmt.Fprintf(os.Stderr, "cwd needle %q matched %d sessions\n", *cwdNeedle, nMatch)
		os.Exit(1)
	}

	start := time.Now()
	if err := c.send(ctx, protocol.Subscribe{Ref: ref, Rows: uint16(*rows), Cols: uint16(*cols)}); err != nil {
		fmt.Fprintf(os.Stderr, "subscribe: %v\n", err)
		os.Exit(1)
	}

	snapDeadline := time.Now().Add(*timeout)
	var firstBin *wsMsg
	var snap *wsMsg
	for time.Now().Before(snapDeadline) {
		dctx, cancel := context.WithTimeout(ctx, time.Until(snapDeadline))
		m, err := c.waitFrame(dctx)
		cancel()
		if err != nil {
			break
		}
		if !m.binOK {
			if m.control != nil {
				_ = os.WriteFile(filepath.Join(*outDir, "control-after-sub.txt"),
					[]byte(fmt.Sprintf("%T %+v\n", m.control, m.control)), 0o644)
			}
			continue
		}
		if firstBin == nil {
			cp := m
			firstBin = &cp
		}
		if m.bin.Kind == protocol.KindSnapshot && m.bin.Ref == ref {
			cp := m
			snap = &cp
			break
		}
	}
	elapsed := time.Since(start)

	result := map[string]any{
		"ref":            ref,
		"cwd":            cwd,
		"name":           name,
		"subscribe_ms":   float64(elapsed) / float64(time.Millisecond),
		"rows":           *rows,
		"cols":           *cols,
		"got_snapshot":   snap != nil,
		"got_first_bin":  firstBin != nil,
		"marker":         "STATIC_ALT_MARKER_092",
		"snapshot_has_marker": false,
	}
	if firstBin != nil {
		result["first_bin_kind"] = int(firstBin.bin.Kind)
		result["first_bin_data_len"] = len(firstBin.bin.Data)
		result["first_bin_raw_len"] = len(firstBin.raw)
	}
	if snap == nil {
		result["world_hint"] = "no_snapshot"
		enc, _ := json.MarshalIndent(result, "", "  ")
		_ = os.WriteFile(filepath.Join(*outDir, "result.json"), enc, 0o644)
		fmt.Fprintf(os.Stderr, "no KindSnapshot within %s\n", *timeout)
		os.Exit(1)
	}

	_ = os.WriteFile(filepath.Join(*outDir, "snapshot.data.bin"), snap.bin.Data, 0o644)
	_ = os.WriteFile(filepath.Join(*outDir, "snapshot.wire.bin"), snap.raw, 0o644)
	_ = os.WriteFile(filepath.Join(*outDir, "snapshot.data.hex"), []byte(hexHead(snap.bin.Data, 200)), 0o644)
	_ = os.WriteFile(filepath.Join(*outDir, "snapshot.wire.hex"), []byte(hexHead(snap.raw, 200)), 0o644)

	has := bytes.Contains(snap.bin.Data, []byte("STATIC_ALT_MARKER_092"))
	glyphs := snapshotGlyphs(snap.bin.Data)
	result["snapshot_has_marker"] = has
	result["data_len"] = len(snap.bin.Data)
	result["wire_len"] = len(snap.raw)
	result["glyphs"] = glyphs
	result["data_preview"] = printablePreview(snap.bin.Data, 200)
	result["data_hex_head200"] = hex.EncodeToString(head(snap.bin.Data, 200))
	result["wire_hex_head200"] = hex.EncodeToString(head(snap.raw, 200))
	if has {
		result["world"] = "app渲染层"
	} else {
		result["world"] = "server仍空"
	}
	enc, _ := json.MarshalIndent(result, "", "  ")
	_ = os.WriteFile(filepath.Join(*outDir, "result.json"), enc, 0o644)
	fmt.Println(string(enc))
}

func head(b []byte, n int) []byte {
	if len(b) < n {
		return b
	}
	return b[:n]
}
