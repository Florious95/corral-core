package guirpc

// Official Grok usage is a local read-only CLI, not an ACP billing estimate.
// @consumes internal/bridge
// @contract
// @pre verified current native birth and scoped session ID
// @post native session/turn/model counters only; absent cost stays absent
// @err output/identity/deadline failure is visible without stdout/stderr leakage
// @inv no shell, model call, polling or client-selected executable/path

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

type grokUsageCounters struct {
	Input         *int64                       `json:"inputTokens,omitempty"`
	Output        *int64                       `json:"outputTokens,omitempty"`
	CacheRead     *int64                       `json:"cachedReadTokens,omitempty"`
	CacheCreation *int64                       `json:"cacheCreationTokens,omitempty"`
	Reasoning     *int64                       `json:"reasoningTokens,omitempty"`
	Total         *int64                       `json:"totalTokens,omitempty"`
	CostTicks     *int64                       `json:"costUsdTicks,omitempty"`
	Calls         *int64                       `json:"modelCalls,omitempty"`
	Turns         *int64                       `json:"turnCount,omitempty"`
	Model         string                       `json:"primaryModelId,omitempty"`
	Models        map[string]grokUsageCounters `json:"modelUsage,omitempty"`
}

type grokUsageTurn struct {
	grokUsageCounters
	Number *int64 `json:"turnNumber,omitempty"`
	Ended  string `json:"endedAt,omitempty"`
}

type grokUsage struct {
	ID      string            `json:"sessionId"`
	Updated string            `json:"updatedAt,omitempty"`
	Session grokUsageCounters `json:"session"`
	Turns   []grokUsageTurn   `json:"turns"`
}

var usageSlots = make(chan struct{}, 4)

type usageOutput struct{ bytes.Buffer }

func (b *usageOutput) Write(p []byte) (int, error) {
	if b.Len()+len(p) > 4<<20 {
		return 0, errors.New("native usage output exceeds bound")
	}
	return b.Buffer.Write(p)
}

func (s *session) readGrokUsage(ctx context.Context, id string) (*grokUsage, error) {
	if id == "" || len(id) > 128 || strings.HasPrefix(id, "-") {
		return nil, errors.New("Grok 用量会话身份无效")
	}
	select {
	case usageSlots <- struct{}{}:
		defer func() { <-usageSlots }()
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
		return nil, errors.New("Grok 用量读取繁忙，请稍后重试")
	}
	_, process, err := s.verifiedGrok(ctx)
	if err != nil || len(process.Args) == 0 {
		return nil, errors.New("Grok 原生用量程序身份未确认")
	}
	binary := process.Args[0]
	if !filepath.IsAbs(binary) {
		binary, err = exec.LookPath(binary)
		if err != nil {
			return nil, errors.New("Grok 原生用量程序不可用")
		}
	}
	bounded, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	command := exec.CommandContext(bounded, binary, "usage", id)
	command.Dir = s.pane.CWD
	var out usageOutput
	command.Stdout, command.Stderr = &out, nil
	if err = command.Run(); err != nil {
		return nil, errors.New("Grok 原生用量 CLI 未返回有效记录（空账本或读取失败）")
	}
	var usage grokUsage
	if json.Unmarshal(out.Bytes(), &usage) != nil || usage.ID != id || usage.Session.Input == nil || usage.Session.Output == nil || usage.Session.Total == nil {
		return nil, errors.New("Grok 原生用量记录格式或会话身份未确认")
	}
	if _, _, err = s.verifiedGrok(ctx); err != nil {
		return nil, errors.New("Grok 用量读取期间原生实例已改变")
	}
	// Preserve all totals but send a bounded recent-turn window to the phone.
	if len(usage.Turns) > 50 {
		usage.Turns = usage.Turns[len(usage.Turns)-50:]
	}
	return &usage, nil
}

func projectGrokUsage(metrics map[string]any, usage *grokUsage) {
	metrics["source"] = "native_cli+native_slash"
	metrics["usageRecorded"] = usage != nil
	if usage == nil {
		return
	}
	c := usage.Session
	metrics["tokens"] = map[string]any{"input": c.Input, "output": c.Output, "cacheRead": c.CacheRead, "cacheWrite": c.CacheCreation, "reasoning": c.Reasoning, "total": c.Total}
	metrics["turnCount"], metrics["modelCalls"] = c.Turns, c.Calls
	metrics["grokUsage"] = usage
	if c.CostTicks != nil && *c.CostTicks >= 0 {
		// Native integer ledger unit: 10^10 ticks/USD, not float billing math.
		n := *c.CostTicks
		metrics["cost"] = json.Number(fmt.Sprintf("%d.%010d", n/10000000000, n%10000000000))
	}
	// modelName remains the live ACP model, not the ledger's historical primary.
}
