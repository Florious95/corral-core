package guirpc

// Account metadata is read through the existing verified native ACP actor.
// @contract
// @pre result is the native read-only _x.ai/billing response for this worker
// @post weekly percentage/default and period-end reset retain their distinct authorized bases
// @err malformed config/percentage stays unknown; nonzero on-demand spending bars a default
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
			CreditUsagePercent *float64 `json:"creditUsagePercent"`
			MonthlyLimit       *struct {
				Val *int64 `json:"val"`
			} `json:"monthlyLimit"`
			Used *struct {
				Val *int64 `json:"val"`
			} `json:"used"`
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
	if c.CurrentPeriod != nil && c.CurrentPeriod.Type == "USAGE_PERIOD_TYPE_WEEKLY" {
		weekly := map[string]any{}
		if c.CreditUsagePercent != nil && *c.CreditUsagePercent >= 0 && *c.CreditUsagePercent <= 100 {
			weekly["usedPercent"] = *c.CreditUsagePercent
			weekly["basis"] = "creditUsagePercent"
		} else if c.CreditUsagePercent == nil && c.MonthlyLimit != nil && c.MonthlyLimit.Val != nil && *c.MonthlyLimit.Val > 0 && c.Used != nil && c.Used.Val != nil && *c.Used.Val >= 0 {
			// Native CreditBalance adapter: used / monthlyLimit * 100, capped at 100.
			weekly["usedPercent"] = min(100.0, float64(*c.Used.Val)/float64(*c.MonthlyLimit.Val)*100)
			weekly["basis"] = "used.val/monthlyLimit.val"
		} else if c.CreditUsagePercent == nil && (c.Used == nil || c.Used.Val == nil || *c.Used.Val >= 0) && (c.OnDemandUsed == nil || c.OnDemandUsed.Val == nil || *c.OnDemandUsed.Val == 0) {
			// Authorized display policy, not a claim that missing native data is zero.
			weekly["usedPercent"] = float64(0)
			weekly["basis"] = "authorized_tui_default"
			weekly["usedPercentDefaulted"] = true
		}
		if len(weekly) > 0 {
			if at, err := time.Parse(time.RFC3339Nano, c.CurrentPeriod.End); err == nil {
				weekly["resetsAt"] = at.UTC().Format(time.RFC3339)
				weekly["resetBasis"] = "currentPeriod.end"
			}
			quota["weekly"] = weekly
			quota["windowsStatus"] = "reported"
		}
	}
	if c.OnDemandUsed != nil && c.OnDemandUsed.Val != nil && *c.OnDemandUsed.Val >= 0 && c.OnDemandCap != nil && c.OnDemandCap.Val != nil && *c.OnDemandCap.Val > 0 {
		quota["onDemand"] = map[string]any{"usedPercent": float64(*c.OnDemandUsed.Val) * 100 / float64(*c.OnDemandCap.Val), "basis": "onDemandUsed.val/onDemandCap.val"}
	}
	// No five-hour window is inferred. The authorized weekly default/reset policy
	// is identified separately from a measured native account percentage.
	return quota, nil
}
