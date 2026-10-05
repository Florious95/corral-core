package guirpc

// Native Grok mode changes resume only the explicit last-known ACP session.
// The TUI has no current-session/state query; stopping it needs user consent.
// @consumes internal/bridge
// @contract
// @pre exact native Grok birth identity; switch input barrier is held
// @post official commands replace only this pane, with native ACP load/new
// @err unknown TUI work needs force; failed startup closes the bridge visibly
// @inv no newest-session guessing, TUI probing, private wrapper or UDS

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
)

func (s *session) startGrok(ctx context.Context, process bridge.NativeProcess, g *grokACP) error {
	id, err := s.bridge.NativeSession(ctx, process)
	if err != nil {
		return err
	}
	s.w.mu.Lock()
	s.w.resetHistory()
	if s.w.seq > 0 {
		s.w.publish([]byte(`{"type":"session_reset"}`), entry{kind: "session_reset"}, true)
	}
	s.w.mu.Unlock()
	id, err = g.start(ctx, s.pane.CWD, id)
	if err != nil {
		return err
	}
	if err = s.bridge.RememberNativeSession(ctx, process, id); err != nil {
		return err
	}
	s.w.mu.Lock()
	s.w.sessionID = id
	s.w.sessionFile = ""
	s.w.mu.Unlock()
	s.mu.Lock()
	s.grokSource = process
	s.mu.Unlock()
	return nil
}

func (s *session) switchGrok(ctx context.Context, current bridge.NativeProcess, target string, force bool) (map[string]any, error) {
	if target == current.Mode {
		return map[string]any{"mode": target}, nil
	}
	s.mu.Lock()
	g, source := s.grok, s.grokSource
	s.mu.Unlock()
	if current.Mode == ModeTUI && !force {
		return map[string]any{"mode": ModeTUI, "busy": true}, errors.New("终端当前任务不可查询；确认切换会中断当前任务并恢复最后已知会话（无已知身份则新建）")
	}
	if current.Mode == ModeRPC {
		if g == nil {
			return nil, errors.New("Grok native state unavailable")
		}
		g.mu.Lock()
		busy, sid := g.running || g.queued > 0 || g.sessionChanging || g.promptID != "", g.session
		g.mu.Unlock()
		if busy {
			if !force {
				return map[string]any{"mode": ModeRPC, "busy": true}, errors.New("当前任务正在运行")
			}
			if err := g.wire(map[string]any{"jsonrpc": "2.0", "method": "session/cancel", "params": map[string]any{"sessionId": sid}}); err != nil {
				return nil, err
			}
			for {
				g.mu.Lock()
				busy = g.running || g.queued > 0 || g.sessionChanging || g.promptID != ""
				g.mu.Unlock()
				if !busy {
					break
				}
				select {
				case <-ctx.Done():
					return nil, errors.New("Grok 当前任务未确认停止，未置换")
				case <-time.After(50 * time.Millisecond):
				}
			}
		}
	}
	s.w.mu.Lock()
	id := s.w.sessionID
	s.w.mu.Unlock()
	if id == "" {
		var err error
		id, err = s.bridge.NativeSession(ctx, current)
		if err != nil {
			return nil, err
		}
	}
	launch := current
	if target == ModeRPC && len(source.Args) > 0 {
		launch = source
	}
	args, err := bridge.GrokCommand(launch, target, id)
	if err != nil {
		return nil, err
	}
	s.detachRPC()
	if err = s.bridge.ReplaceNativeAgent(ctx, current, s.pane.CWD, args); err != nil {
		s.cancel()
		return nil, fmt.Errorf("replace native Grok: %w", err)
	}
	next, err := s.waitProcess(ctx, target, current.PID)
	if err != nil {
		s.cancel()
		return nil, errors.New("原生 Grok 启动未确认，面板已保留")
	}
	if next.Provider != "grok" {
		s.cancel()
		return nil, errors.New("Grok replacement provider changed")
	}
	if id != "" {
		if err = s.bridge.RememberNativeSession(ctx, next, id); err != nil {
			s.cancel()
			return nil, err
		}
	}
	s.mu.Lock()
	s.process = next
	s.mu.Unlock()
	if target == ModeRPC {
		if err = s.attachRPC(ctx); err != nil {
			s.cancel()
			return nil, fmt.Errorf("reattach native Grok ACP: %w", err)
		}
		s.w.mu.Lock()
		same := id == "" || s.w.sessionID == id
		s.w.mu.Unlock()
		if !same {
			s.cancel()
			return nil, errors.New("Grok 恢复后会话身份不一致")
		}
	}
	s.w.setMode(target)
	s.w.publishEvent(map[string]any{"type": "worker_mode", "mode": target}, false)
	return map[string]any{"mode": target}, nil
}
