package guirpc

import (
	"encoding/json"
	"testing"
)

func TestGrokUsageProjectsNativeCountersWithoutCacheDoubleCountOrFakeCost(t *testing.T) {
	var usage grokUsage
	if err := json.Unmarshal([]byte(`{"sessionId":"s","session":{"inputTokens":42,"outputTokens":20,"cachedReadTokens":15,"cacheCreationTokens":5,"reasoningTokens":3,"totalTokens":62,"modelCalls":2,"turnCount":1,"modelUsage":{"grok-4.7":{"inputTokens":42,"outputTokens":20,"totalTokens":62}}},"turns":[{"turnNumber":1,"inputTokens":42,"outputTokens":20,"totalTokens":62}]}`), &usage); err != nil {
		t.Fatal(err)
	}
	metrics := map[string]any{"contextUsage": map[string]any{"tokens": 1359, "contextWindow": 256000}, "modelName": "live-model"}
	projectGrokUsage(metrics, &usage)
	raw, err := json.Marshal(metrics)
	if err != nil {
		t.Fatal(err)
	}
	var projected struct {
		Tokens struct{ Input, Output, CacheRead, CacheWrite, Reasoning, Total int64 }
		Turns  int64  `json:"turnCount"`
		Calls  int64  `json:"modelCalls"`
		Model  string `json:"modelName"`
	}
	if err := json.Unmarshal(raw, &projected); err != nil {
		t.Fatal(err)
	}
	if projected.Tokens.Input != 42 || projected.Tokens.Total != 62 || projected.Tokens.CacheRead != 15 || projected.Tokens.Reasoning != 3 || projected.Calls != 2 || projected.Turns != 1 || projected.Model != "live-model" {
		t.Fatal("native counters or live model were rewritten")
	}
	if _, exists := metrics["cost"]; exists {
		t.Fatal("missing native cost became a fabricated billing estimate")
	}
	if metrics["grokUsage"] != &usage || len(usage.Turns) != 1 || *usage.Turns[0].Number != 1 {
		t.Fatal("native model/turn ledger missing")
	}
}

func TestGrokUsageOutputIsBounded(t *testing.T) {
	var out usageOutput
	if _, err := out.Write(make([]byte, 4<<20)); err != nil {
		t.Fatal(err)
	}
	if _, err := out.Write([]byte("x")); err == nil || out.Len() != 4<<20 {
		t.Fatal("usage stdout overflow was accepted")
	}
}
