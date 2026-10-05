package api

// Legacy workers predate switch_mode. Never forward this supervisor command
// to Pi: upgrade only after explicit consent, preserving the pane and Pi ID.
// @contract
// @pre the ref is managed, attached and authenticated; force is user consent
// @post old workers resume the same Pi session under the current supervisor
// @err every metadata/stop/start failure has a bounded, stage-specific reply
// @inv no new pane/ref/socket, no implicit task cancellation, no idle polling

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/guirpc"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

const workerUpgradeTimeout = 40 * time.Second

type workerUpgrade struct{ done chan struct{} }

func (s *Server) upgradingConversation(ref string) *workerUpgrade {
	value, ok := s.conversationUpgrades.Load(ref)
	if !ok {
		return nil
	}
	return value.(*workerUpgrade)
}

func (s *Server) waitConversationUpgrade(ctx context.Context, ref string) error {
	if upgrade := s.upgradingConversation(ref); upgrade != nil {
		select {
		case <-upgrade.done:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	return nil
}

func (c *wsConn) switchLegacyConversation(cmd protocol.ConversationCommand) {
	var request struct {
		Mode  string `json:"mode"`
		Force bool   `json:"force"`
	}
	_ = json.Unmarshal(cmd.Command, &request)
	// A legacy worker already runs RPC. Viewing it natively requires no kill.
	if request.Mode == guirpc.ModeRPC {
		c.conversationSwitchResult(cmd, map[string]any{"mode": guirpc.ModeRPC}, nil)
		return
	}
	if !request.Force {
		c.conversationSwitchResult(cmd, map[string]any{"mode": guirpc.ModeRPC, "upgrade_required": true},
			errors.New("此会话的工作进程是旧版本，切换前需原地升级，请确认是否中断未完成任务"))
		return
	}
	upgrade := &workerUpgrade{done: make(chan struct{})}
	if _, loaded := c.s.conversationUpgrades.LoadOrStore(cmd.Ref, upgrade); loaded {
		c.conversationSwitchResult(cmd, nil, errors.New("此会话正在升级，请等待模式确认后重试"))
		return
	}
	go func() {
		defer c.s.conversationUpgrades.Delete(cmd.Ref)
		defer close(upgrade.done)
		// The transaction survives a client disconnect; the daemon owns it.
		ctx, cancel := context.WithTimeout(c.s.loopCtx, workerUpgradeTimeout)
		defer cancel()
		data, err := c.s.upgradeConversation(ctx, cmd.Ref, request.Mode)
		c.conversationSwitchResult(cmd, data, err)
	}()
}

func (c *wsConn) conversationSwitchResult(cmd protocol.ConversationCommand, data map[string]any, err error) {
	event := map[string]any{"type": "response", "id": cmd.ID, "command": "switch_mode", "success": err == nil}
	if data != nil {
		event["data"] = data
	}
	if err != nil {
		event["error"] = err.Error()
	}
	raw, _ := json.Marshal(event)
	c.send(&protocol.ConversationEvent{Ref: cmd.Ref, Event: raw})
}

func (s *Server) upgradeConversation(ctx context.Context, ref, target string) (map[string]any, error) {
	entry := s.catalogEntry(ref)
	if entry == nil || entry.ref != ref {
		return nil, errors.New("会话已不存在，未升级工作进程")
	}
	control, ready, err := openWorkerControl(ctx, s.guiDir, ref)
	if err != nil {
		return nil, fmt.Errorf("升级失败（连接旧进程）：%w", err)
	}
	defer control.conn.Close()
	if ready.Mode == guirpc.ModeRPC || ready.Mode == guirpc.ModeTUI {
		return control.request(ctx, map[string]any{"type": "switch_mode", "mode": target, "force": true})
	}
	if ready.Mode != "" {
		return nil, errors.New("工作进程模式不受支持，未转发切换命令")
	}
	// The old get_state projection omits sessionId. Stats retains the ID.
	if _, err = control.request(ctx, map[string]any{"type": "clear_queue"}); err != nil {
		return nil, fmt.Errorf("升级失败（停止队列）：%w", err)
	}
	if _, err = control.request(ctx, map[string]any{"type": "abort"}); err != nil {
		return nil, fmt.Errorf("升级失败（停止任务）：%w", err)
	}
	state, err := control.request(ctx, map[string]any{"type": "get_state"})
	if err != nil {
		return nil, fmt.Errorf("升级失败（确认空闲）：%w", err)
	}
	running, hasRunning := state["isStreaming"].(bool)
	compacting, hasCompacting := state["isCompacting"].(bool)
	pending, hasPending := state["pendingMessageCount"].(float64)
	if !hasRunning || !hasCompacting || !hasPending {
		return nil, errors.New("旧进程未报告完整状态，未置换")
	}
	if running || compacting || pending > 0 {
		return map[string]any{"busy": true}, errors.New("旧进程仍在运行任务，未置换")
	}
	stats, err := control.request(ctx, map[string]any{"type": "get_session_stats"})
	if err != nil {
		return nil, fmt.Errorf("升级失败（读取会话身份）：%w", err)
	}
	id, _ := stats["sessionId"].(string)
	file, _ := stats["sessionFile"].(string)
	if !validPiSessionID(id) || file == "" {
		return nil, errors.New("旧进程没有可恢复的持久会话身份，未置换")
	}
	if _, err := os.Stat(file); err != nil {
		messages, hasMessages := stats["totalMessages"].(float64)
		if !os.IsNotExist(err) || !hasMessages || messages != 0 {
			return nil, errors.New("旧会话记录尚未持久化，未置换")
		}
	}
	executable, err := os.Executable()
	if err != nil {
		return nil, fmt.Errorf("升级失败（当前程序）：%w", err)
	}
	restore, err := bridge.ReplacePaneProcess(ctx, entry.pane.Socket, entry.pane.PaneID, entry.pane.CWD,
		guirpc.SocketPath(s.guiDir, ref), []string{executable, "gui-worker", s.guiDir, entry.pane.WindowName, id})
	if err != nil {
		return nil, fmt.Errorf("升级失败（原地置换）：%w", err)
	}
	startup, stop := context.WithTimeout(ctx, 5*time.Second)
	err = guirpc.WaitReady(startup, s.guiDir, ref)
	stop()
	if err != nil {
		return nil, fmt.Errorf("升级失败（新进程就绪，原面板已保留）：%w", err)
	}
	next, _, err := openWorkerControl(ctx, s.guiDir, ref)
	if err != nil {
		return nil, fmt.Errorf("升级失败（连接新进程）：%w", err)
	}
	defer next.conn.Close()
	// Socket/process ready is not Pi ready: real startup can outlive a 3s
	// switch request. Wait for a native metadata reply within this transaction.
	resumed, err := next.request(ctx, map[string]any{"type": "get_session_stats"})
	if err != nil {
		return nil, fmt.Errorf("升级失败（确认新会话就绪）：%w", err)
	}
	if resumed["sessionId"] != id {
		return nil, errors.New("新进程会话身份未对齐，未切换模式")
	}
	data, err := next.request(ctx, map[string]any{"type": "switch_mode", "mode": target, "force": true})
	if err == nil {
		restore()
	}
	return data, err
}

func validPiSessionID(id string) bool {
	if id == "" || len(id) > 128 {
		return false
	}
	for _, ch := range id {
		if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-' || ch == '_' || ch == '.') {
			return false
		}
	}
	return strings.Trim(id, "-_.") == id
}

type workerControl struct {
	conn net.Conn
	scan *bufio.Scanner
	id   int
}

func openWorkerControl(ctx context.Context, dir, ref string) (*workerControl, guirpc.Ready, error) {
	conn, err := (&net.Dialer{Timeout: conversationDialTimeout}).DialContext(ctx, "unix", guirpc.SocketPath(dir, ref))
	if err != nil {
		return nil, guirpc.Ready{}, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	context.AfterFunc(ctx, func() { conn.Close() })
	control := &workerControl{conn: conn, scan: bufio.NewScanner(conn)}
	control.scan.Buffer(make([]byte, 4096), guirpc.MaxRecord+1024)
	_, err = conn.Write([]byte("{\"type\":\"hello\"}\n"))
	var ready guirpc.Ready
	if err == nil && control.scan.Scan() {
		err = json.Unmarshal(control.scan.Bytes(), &ready)
		if err == nil && ready.Type == "ready" {
			return control, ready, nil
		}
	}
	conn.Close()
	if err == nil {
		err = errors.New("工作进程没有回复就绪")
	}
	return nil, ready, err
}

func (c *workerControl) request(ctx context.Context, command map[string]any) (map[string]any, error) {
	c.id++
	id := fmt.Sprintf("upgrade-%d-%d", time.Now().UnixNano(), c.id)
	command["id"] = id
	data, _ := json.Marshal(command)
	if _, err := c.conn.Write(append(data, '\n')); err != nil {
		return nil, err
	}
	for c.scan.Scan() {
		var record struct {
			Event struct {
				Type    string         `json:"type"`
				ID      string         `json:"id"`
				Success bool           `json:"success"`
				Error   string         `json:"error"`
				Data    map[string]any `json:"data"`
			} `json:"event"`
		}
		if json.Unmarshal(c.scan.Bytes(), &record) != nil || record.Event.ID != id || record.Event.Type != "response" {
			continue
		}
		if !record.Event.Success {
			if record.Event.Error == "" {
				return record.Event.Data, errors.New("工作进程未确认命令")
			}
			return record.Event.Data, errors.New(record.Event.Error)
		}
		return record.Event.Data, nil
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err := c.scan.Err(); err != nil {
		return nil, err
	}
	return nil, errors.New("工作进程退出，结果未确认")
}
