package api

// lifecycle.go implements close_session (contract 088 E12): kill the CLI and
// its tmux pane as one atomic action. It does not add a Kill method to
// bridge.Pane (that type stays mirror-only).
//
// @consumes internal/bridge
//
// @consumes internal/protocol

import (
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

const closeWait = 2 * time.Second

// handleCloseSession tears down one catalog pane: kill-pane, wait until the
// pane id is gone and pane_pid is dead, escalate to the process group if the
// pane vanished but the pid lingered.
//
// @contract
// @pre 连接已认证；f.Validate 已通过
// @post 回 CloseSessionAck；pane 已不在时 ok=true（幂等）；空 socket 不回退默认 tmux socket
// @err 超时 / kill 失败 → ok=false reason=close_failed；内部错误 → internal
// @inv 从不对默认 tmux socket 发命令；不读进程 argv
func (c *wsConn) handleCloseSession(f protocol.CloseSession) {
	ack := func(ok bool, reason protocol.CloseFailReason) {
		c.send(&protocol.CloseSessionAck{ReqID: f.ReqID, OK: ok, Reason: reason})
	}

	e := c.s.catalog.entry(f.Ref)
	if e == nil {
		c.s.log.Info("close_session missing ref (idempotent ok)",
			"conn", c.id, "ref", f.Ref, "req_id", f.ReqID)
		ack(true, "")
		return
	}
	socket := e.pane.Socket
	paneID := e.pane.PaneID
	if strings.TrimSpace(socket) == "" {
		c.s.log.Error("close_session empty socket, refusing default tmux",
			"conn", c.id, "ref", f.Ref, "pane_id", paneID, "req_id", f.ReqID)
		ack(false, protocol.CloseFailCloseFailed)
		return
	}

	pid, pidErr := tmuxPanePID(socket, paneID)
	paneGoneNow := !tmuxPaneExists(socket, paneID)
	c.s.log.Info("close_session start",
		"conn", c.id, "ref", f.Ref, "req_id", f.ReqID,
		"socket", socket, "pane_id", paneID,
		"pane_pid", pid, "pid_err", errString(pidErr),
		"pane_gone", paneGoneNow)
	if paneGoneNow {
		if !processAlive(pid) {
			ack(true, "")
			return
		}
	}

	if _, err := runTmuxOnSocket(socket, "kill-pane", "-t", paneID); err != nil {
		// kill-pane can fail if the pane raced away; treat gone+dead as success.
		paneGone := !tmuxPaneExists(socket, paneID)
		pidAlive := processAlive(pid)
		c.s.log.Error("close_session kill-pane",
			"conn", c.id, "err", err,
			"pane_gone", paneGone, "pid_alive", pidAlive,
			"pane_pid", pid, "socket", socket, "pane_id", paneID)
		if paneGone && !pidAlive {
			ack(true, "")
			return
		}
		if !paneGone {
			ack(false, protocol.CloseFailCloseFailed)
			return
		}
	}

	deadline := time.Now().Add(closeWait)
	escalated := false
	for {
		paneGone := !tmuxPaneExists(socket, paneID)
		pidAlive := processAlive(pid)
		c.s.log.Info("close_session poll",
			"conn", c.id, "req_id", f.ReqID,
			"pane_gone", paneGone, "pid_alive", pidAlive,
			"pane_pid", pid, "socket", socket, "pane_id", paneID,
			"escalated", escalated)
		if paneGone && !pidAlive {
			ack(true, "")
			return
		}
		if paneGone && pidAlive && !escalated {
			c.s.log.Info("close_session escalate",
				"conn", c.id, "pane_pid", pid, "comm", processComm(pid),
				"socket", socket, "pane_id", paneID)
			escalateKill(pid)
			escalated = true
			time.Sleep(200 * time.Millisecond)
			continue
		}
		if time.Now().After(deadline) {
			c.s.log.Error("close_session timeout",
				"conn", c.id, "req_id", f.ReqID,
				"pane_gone", paneGone, "pid_alive", pidAlive,
				"pane_pid", pid, "socket", socket, "pane_id", paneID)
			ack(false, protocol.CloseFailCloseFailed)
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func tmuxPanePID(socket, paneID string) (int, error) {
	out, err := runTmuxOnSocket(socket, "display-message", "-p", "-t", paneID, "#{pane_pid}")
	if err != nil {
		return 0, err
	}
	pid, convErr := strconv.Atoi(strings.TrimSpace(out))
	if convErr != nil || pid <= 0 {
		return 0, fmt.Errorf("pane_pid %q", strings.TrimSpace(out))
	}
	return pid, nil
}

func tmuxPaneExists(socket, paneID string) bool {
	out, err := runTmuxOnSocket(socket, "list-panes", "-a", "-F", "#{pane_id}")
	if err != nil {
		// list failed: do not invent "still there".
		return false
	}
	for _, line := range strings.Split(out, "\n") {
		if strings.TrimSpace(line) == paneID {
			return true
		}
	}
	return false
}

func processAlive(pid int) bool {
	if pid <= 0 {
		return false
	}
	err := syscall.Kill(pid, 0)
	return err == nil
}

func escalateKill(pid int) {
	if pid <= 0 {
		return
	}
	pgid, err := syscall.Getpgid(pid)
	if err != nil || pgid <= 0 {
		_ = syscall.Kill(pid, syscall.SIGTERM)
		time.Sleep(200 * time.Millisecond)
		if processAlive(pid) {
			_ = syscall.Kill(pid, syscall.SIGKILL)
		}
		return
	}
	_ = syscall.Kill(-pgid, syscall.SIGTERM)
	time.Sleep(200 * time.Millisecond)
	if processAlive(pid) {
		_ = syscall.Kill(-pgid, syscall.SIGKILL)
	}
}

func processComm(pid int) string {
	if pid <= 0 {
		return ""
	}
	// Linux: /proc/<pid>/comm. macOS has no /proc — ps -o comm= (not argv).
	if b, err := exec.Command("ps", "-o", "comm=", "-p", strconv.Itoa(pid)).Output(); err == nil {
		return strings.TrimSpace(string(b))
	}
	return ""
}

func runTmuxOnSocket(socket string, args ...string) (string, error) {
	if strings.TrimSpace(socket) == "" {
		return "", fmt.Errorf("empty tmux socket")
	}
	cmd := exec.Command("tmux", append([]string{"-S", socket}, args...)...)
	cmd.Env = scrubbedEnv()
	out, err := cmd.CombinedOutput()
	return string(out), err
}
