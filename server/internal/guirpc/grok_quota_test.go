package guirpc

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestGrokQuotaPeriodAndSpendingNeverBecomeQuotaWindows(t *testing.T) {
	q, err := grokQuotaProjection(json.RawMessage(`{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-10-04T16:40:34Z","end":"2026-10-11T16:40:34Z"},"creditUsagePercent":23.5,"onDemandUsed":{"val":50},"onDemandCap":{"val":100},"includedUsed":{"val":11},"totalUsed":{"val":61},"monthlyLimit":{"val":999},"prepaidBalance":{"val":54321},"email":"private-sentinel"},"auth":"private-sentinel"}`))
	if err != nil {
		t.Fatal(err)
	}
	p := q["currentPeriod"].(map[string]any)
	if p["type"] != "USAGE_PERIOD_TYPE_WEEKLY" || p["end"] != "2026-10-11T16:40:34Z" || p["resetsAt"] != nil {
		t.Fatal("period metadata was lost or falsely became quota reset")
	}
	spending := q["onDemand"].(map[string]any)
	if spending["usedPercent"] != 50.0 || spending["basis"] != "onDemandUsed.val/onDemandCap.val" {
		t.Fatal("on-demand cap ratio lost")
	}
	b, _ := json.Marshal(q)
	for _, forbidden := range []string{"prepaidBalance", "private-sentinel", "creditUsagePercent", "includedUsed", "monthlyLimit"} {
		if strings.Contains(string(b), forbidden) {
			t.Fatal("unconfirmed/private field projected")
		}
	}
	if q["fiveHour"] != nil || q["weekly"] != nil || q["windowsStatus"] != "unreported" {
		t.Fatal("spending/period fabricated rolling quotas")
	}
}

func TestGrokQuotaOnDemandZeroMissingAndOverCapStayDistinct(t *testing.T) {
	for _, tc := range []struct {
		used, cap string
		known     bool
		want      float64
	}{
		{"0", "100", true, 0}, {"25", "100", true, 25}, {"125", "100", true, 125}, {"25", "0", false, 0}, {"25", "-1", false, 0}, {"-1", "100", false, 0},
	} {
		q, err := grokQuotaProjection(json.RawMessage(`{"config":{"onDemandUsed":{"val":` + tc.used + `},"onDemandCap":{"val":` + tc.cap + `}}}`))
		if err != nil {
			t.Fatal(err)
		}
		spending, ok := q["onDemand"].(map[string]any)
		if ok != tc.known || ok && spending["usedPercent"] != tc.want {
			t.Fatal("missing/invalid spending fabricated percent, zero lost, or wrong denominator")
		}
		if q["weekly"] != nil || q["fiveHour"] != nil {
			t.Fatal("spending was relabeled quota")
		}
	}
}

func TestGrokQuotaMissingAndInvalidMetadataStayUnknown(t *testing.T) {
	for _, raw := range []string{`{"config":{}}`, `{"config":{"creditUsagePercent":40}}`, `{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_MONTHLY"},"creditUsagePercent":40}}`} {
		q, err := grokQuotaProjection(json.RawMessage(raw))
		if err != nil {
			t.Fatal(err)
		}
		if q["weekly"] != nil || q["fiveHour"] != nil || q["onDemand"] != nil {
			t.Fatal("unknown/candidate fields fabricated quota")
		}
	}
	for _, raw := range []string{`{}`, `{"config":null}`, `{"config":{"onDemandUsed":{"val":"25"}}}`, strings.Repeat("x", (1<<20)+1)} {
		if _, err := grokQuotaProjection(json.RawMessage(raw)); err == nil {
			t.Fatal("invalid/missing native billing accepted")
		}
	}
	q, err := grokQuotaProjection(json.RawMessage(`{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","end":"not-a-date"}}}`))
	if err != nil {
		t.Fatal(err)
	}
	if q["currentPeriod"].(map[string]any)["end"] != nil {
		t.Fatal("invalid period date fabricated")
	}
}

func TestGrokStatsBillingReadStaysInNativeActorAndPreservesSessionUsage(t *testing.T) {
	for _, failure := range []bool{false, true} {
		t.Run(map[bool]string{false: "account-period", true: "account-error"}[failure], func(t *testing.T) {
			g, wires, events := acpTestBridge(t)
			n := int64(42)
			g.usage = func(_ context.Context, id string) (*grokUsage, error) {
				return &grokUsage{ID: id, Session: grokUsageCounters{Input: &n, Output: &n, Total: &n}}, nil
			}
			if err := g.readStats("stats-reading"); err != nil {
				t.Fatal(err)
			}
			wire := func(index int) map[string]json.RawMessage {
				until := time.Now().Add(time.Second)
				for time.Now().Before(until) {
					if w := wires(); len(w) > index {
						return w[index]
					}
					time.Sleep(time.Millisecond)
				}
				t.Fatal("native read did not produce bounded request")
				return nil
			}
			billing := wire(0)
			if string(billing["method"]) != `"_x.ai/billing"` || string(billing["params"]) != `{}` {
				t.Fatal("account getter used prompt/new session or wrong parameters")
			}
			reply := map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(billing["id"]), "result": map[string]any{"config": map[string]any{"currentPeriod": map[string]any{"type": "USAGE_PERIOD_TYPE_WEEKLY", "end": "2026-10-11T16:40:34Z"}}}}
			if failure {
				delete(reply, "result")
				reply["error"] = map[string]any{"code": -32000, "message": "private-account-sentinel"}
			}
			raw, _ := json.Marshal(reply)
			g.ingest(raw)
			for index, text := range []string{"Context: 100 / 1000 tokens", "Turn: 0"} {
				r := wire(index + 1)
				acpUpdate(g, map[string]any{"sessionUpdate": "agent_message_chunk", "content": map[string]any{"type": "text", "text": text}})
				g.ingest(append(append([]byte(`{"jsonrpc":"2.0","id":`), r["id"]...), []byte(`,"result":{"stopReason":"end_turn"}}`)...))
			}
			var data map[string]json.RawMessage
			for _, e := range events() {
				if string(e["command"]) == `"get_session_stats"` {
					_ = json.Unmarshal(e["data"], &data)
				}
			}
			if data["tokens"] == nil || data["contextUsage"] == nil || string(data["sessionId"]) != `"native-session"` {
				t.Fatal("optional account read erased/mixed valid session usage")
			}
			if failure && (data["quotaError"] == nil || strings.Contains(string(data["quotaError"]), "private-account-sentinel")) {
				t.Fatal("account error leaked original text or was hidden")
			}
			if !failure && (data["grokQuota"] == nil || strings.Contains(string(data["grokQuota"]), "usedPercent")) {
				t.Fatal("period did not project or fabricated percent")
			}
		})
	}
}

func TestGrokOptionalBillingTimeoutDoesNotDisconnectOrChangeSession(t *testing.T) {
	g, _, _ := acpTestBridge(t)
	done := make(chan error, 1)
	if err := g.call("_x.ai/billing", map[string]any{}, time.Millisecond, func(_ json.RawMessage, err error) { done <- err }); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if !errors.Is(err, errACPTimeout) {
			t.Fatal("wrong timeout result")
		}
	case <-time.After(time.Second):
		t.Fatal("billing timeout not finite")
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.session != "native-session" || g.ctx.Err() != nil || len(g.pending) != 0 {
		t.Fatal("optional account metadata changed native session")
	}
}
