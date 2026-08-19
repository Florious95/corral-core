package protocol_test

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// TestWireFixtureImport 070 ②③：读 App 序列化器落盘的同一批文件，
// 用服务端真正的 UnmarshalFrame（含 Validate）逐个解。
func TestWireFixtureImport(t *testing.T) {
	dir := os.Getenv("OV3_WIRE_FIXTURES")
	if dir == "" {
		t.Fatal("OV3_WIRE_FIXTURES unset")
	}
	files, err := filepath.Glob(filepath.Join(dir, "*.json"))
	if err != nil {
		t.Fatal(err)
	}
	if len(files) == 0 {
		t.Fatalf("no fixtures in %s", dir)
	}

	var (
		sawOverlay bool
		socketRaw  string
		decodeErr  error
		failed     []string
	)

	for _, f := range files {
		base := filepath.Base(f)
		body, err := os.ReadFile(f)
		if err != nil {
			t.Fatalf("read %s: %v", base, err)
		}
		typed, err := protocol.UnmarshalFrame(body)
		if base == "overlay_subscribe.json" || base == "overlay_subscribe_from_listing_token.json" {
			if base == "overlay_subscribe.json" {
				sawOverlay = true
			}
			decodeErr = err
			socketRaw = extractSocket(body, typed)
			fmt.Printf("%s socket raw=%q decode_err=%v\n", base, socketRaw, err)
			if err != nil {
				kind := classify(err)
				fmt.Printf("FAIL ③ %s Go UnmarshalFrame: %s err=%v\n", base, kind, err)
				failed = append(failed, base+":"+kind)
				continue
			}
			if strings.TrimSpace(socketRaw) == "" {
				fmt.Printf("FAIL ③ %s socket 为空 raw=%q\n", base, socketRaw)
				failed = append(failed, base+":socket_empty")
				continue
			}
			// listing 金样 ref=s1 无 U+001F：sessionSocketFromRef 回落整段 "s1"。
			// Go Validate 只查非空，所以同语言闭环永远绿。线上必须是 tmux socket 路径。
			if strings.Contains(socketRaw, `\u001f`) {
				fmt.Printf("FAIL ③ %s socket 含字面\\u001f raw=%q（listing JSON 的分隔符没解成 U+001F，sessionSocketFromRef 没切开）\n", base, socketRaw)
				failed = append(failed, base+":socket_literal_u001f")
				continue
			}
			if !strings.Contains(socketRaw, "/") {
				fmt.Printf("FAIL ③ %s socket 不是路径 raw=%q（listing 整段回落，不是空也不是 tmux socket）\n", base, socketRaw)
				failed = append(failed, base+":socket_not_path")
				continue
			}
			fmt.Printf("PASS ③ %s socket 非空且是路径 raw=%q\n", base, socketRaw)
			continue
		}
		if err != nil {
			fmt.Printf("FAIL ② %s UnmarshalFrame: %v\n", base, err)
			failed = append(failed, base+":"+err.Error())
			continue
		}
		fmt.Printf("PASS ② %s type=%s\n", base, typed.FrameType())
	}

	if !sawOverlay {
		t.Fatal("overlay_subscribe.json missing")
	}
	_ = decodeErr
	if len(failed) > 0 {
		t.Fatalf("wire import failed: %s", strings.Join(failed, " | "))
	}
}

func extractSocket(body []byte, typed protocol.Typed) string {
	if ov, ok := typed.(*protocol.OverlaySubscribe); ok {
		return ov.Socket
	}
	if ov, ok := typed.(protocol.OverlaySubscribe); ok {
		return ov.Socket
	}
	// 解码失败时仍从线上 JSON 抠 socket 原值，好区分「字段空」和「别的」。
	var env struct {
		Payload struct {
			Socket *string `json:"socket"`
		} `json:"payload"`
	}
	if json.Unmarshal(body, &env) != nil {
		return ""
	}
	if env.Payload.Socket == nil {
		return ""
	}
	return *env.Payload.Socket
}

func classify(err error) string {
	if err == nil {
		return "ok"
	}
	msg := err.Error()
	if errors.Is(err, protocol.ErrInvalidField) && strings.Contains(msg, "socket") {
		return "socket_empty"
	}
	if errors.Is(err, protocol.ErrInvalidField) {
		return "invalid_field_other"
	}
	if errors.Is(err, protocol.ErrBadPayload) {
		return "bad_payload"
	}
	return "other:" + msg
}
