package api

// close_drain_test.go — 优雅关闭 drain 的红测（taskbook#fix-bridge-restart-pipe
// 可选加固：daemon 优雅关闭时 drain 全部 subscription，detach+关 FIFO）。
// 根因链第 2 步的优雅路径映射：Server.Close 目前只停 discovery loop，活跃
// 订阅的 pipe-pane cat 不 detach → daemon 退出后残留 cat attach 在 pane。
// SIGKILL 靠 bridge 的 detach-first 自愈（主修）；优雅路径在此补 drain。

import (
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// TestCloseDrainsSubscriptions 验证 Server.Close 会把每个活跃订阅的 pipe
// detach 掉（pane_pipe 归 0），使优雅关闭不残留 pipe-pane cat。
// 修前：Close 不 drain → pane_pipe 仍为 1 → 红。修后：Close drain → 0 → 绿。
func TestCloseDrainsSubscriptions(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame() // snapshot

	// 正向控制：close 之前 pipe 确实已 attach。
	deadline := time.Now().Add(3 * time.Second)
	attached := false
	for time.Now().Before(deadline) {
		out, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{pane_pipe}")
		if err == nil && strings.TrimSpace(out) == "1" {
			attached = true
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !attached {
		t.Fatal("pipe never attached before Close (positive control failed)")
	}

	// 优雅关闭必须 drain：detach 全部订阅，pane_pipe 归 0。
	te.wsEnv.srv.Close()

	deadline = time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		out, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{pane_pipe}")
		if err == nil && strings.TrimSpace(out) == "0" {
			t.Logf("Server.Close drained the subscription: pane_pipe=0")
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatal("pipe still attached after Server.Close (graceful drain failed)")
}
