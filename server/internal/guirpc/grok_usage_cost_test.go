package guirpc

import (
	"encoding/json"
	"testing"
)

func TestNativeGrokCostTicksUseExactDecimalUSD(t *testing.T) {
	var usage grokUsage
	if err := json.Unmarshal([]byte(`{"sessionId":"s","session":{"costUsdTicks":277705200}}`), &usage); err != nil {
		t.Fatal(err)
	}
	metrics := make(map[string]any)
	projectGrokUsage(metrics, &usage)
	if metrics["cost"] != json.Number("0.0277705200") {
		t.Fatalf("native ticks were not precisely scaled: %v", metrics["cost"])
	}
	usage.Session.CostTicks = nil
	unknown := make(map[string]any)
	projectGrokUsage(unknown, &usage)
	if _, exists := unknown["cost"]; exists {
		t.Fatal("absent native cost became zero")
	}
}
