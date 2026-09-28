// 临时探针：验证从独立 harness module 可导入 server internal protocol 包。
package main

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestProbeImport(t *testing.T) {
	b, err := protocol.MarshalFrame(protocol.List{ReqID: 1})
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}
	if len(b) == 0 {
		t.Fatal("empty frame")
	}
}
