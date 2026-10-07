package guirpc

// Account metadata is read through the existing verified native ACP actor.
// @contract
// @pre result is the native read-only _x.ai/billing response for this worker
// @post period and on-demand cap ratio are separate from quota/reset windows
// @err missing/malformed config stays unknown, never zero or invented quota
// @inv no auth/cookie/email/raw response/balance projection, polling or model calls

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

func grokQuotaReadError(err error) error {
	switch {
	case errors.Is(err, context.DeadlineExceeded), errors.Is(err, errACPTimeout):
		return errors.New("Grok 原生账号读取超时；未生成配额")
	case strings.HasPrefix(err.Error(), "Grok ACP -32601:"):
		return errors.New("当前原生 Grok 未提供账号读取接口")
	case strings.Contains(strings.ToLower(err.Error()), "auth"):
		return errors.New("原生 Grok 账号授权不可用；未生成配额")
	default:
		return errors.New("Grok 原生账号读取失败；未生成配额")
	}
}

func grokQuotaProjection(raw json.RawMessage) (map[string]any, error) {
	var result struct {
		Config *struct {
			CurrentPeriod *struct {
				Type  string `json:"type"`
				Start string `json:"start"`
				End   string `json:"end"`
			} `json:"currentPeriod"`
			OnDemandUsed *struct {
				Val *int64 `json:"val"`
			} `json:"onDemandUsed"`
			OnDemandCap *struct {
				Val *int64 `json:"val"`
			} `json:"onDemandCap"`
		} `json:"config"`
	}
	if len(raw) > 1<<20 || json.Unmarshal(raw, &result) != nil || result.Config == nil {
		return nil, errors.New("Grok 原生账号配额格式未确认")
	}
	quota := map[string]any{"source": "native_acp_billing", "windowsStatus": "unreported"}
	if p := result.Config.CurrentPeriod; p != nil {
		period := map[string]any{}
		if len(p.Type) <= 64 && strings.HasPrefix(p.Type, "USAGE_PERIOD_TYPE_") {
			period["type"] = p.Type
		}
		for name, stamp := range map[string]string{"start": p.Start, "end": p.End} {
			if at, err := time.Parse(time.RFC3339Nano, stamp); err == nil {
				period[name] = at.UTC().Format(time.RFC3339)
			}
		}
		if len(period) > 0 {
			quota["currentPeriod"] = period
		}
	}
	c := result.Config
	if c.OnDemandUsed != nil && c.OnDemandUsed.Val != nil && *c.OnDemandUsed.Val >= 0 && c.OnDemandCap != nil && c.OnDemandCap.Val != nil && *c.OnDemandCap.Val > 0 {
		quota["onDemand"] = map[string]any{"usedPercent": float64(*c.OnDemandUsed.Val) * 100 / float64(*c.OnDemandCap.Val), "basis": "onDemandUsed.val/onDemandCap.val"}
	}
	// No confirmed native fiveHour/weekly window fields exist for the observed
	// actor. Neither billing period nor onDemand cap ratio populates them.
	return quota, nil
}
