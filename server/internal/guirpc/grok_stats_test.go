package guirpc

import "testing"

func TestGrokStatsNativeMarkdown(t *testing.T) {
	data := grokStatsMetrics("**Model:** grok-4.6\n**Turn:** 0\n**Context:** 1359 / 500000 tokens (0%)\n**Auth:** not-for-projection")
	context, ok := data["contextUsage"].(map[string]any)
	if !ok || context["tokens"] != int64(1359) || context["contextWindow"] != int64(500000) {
		t.Fatalf("context=%v", data)
	}
	if data["turnCount"] != int64(0) {
		t.Fatalf("native zero lost: %v", data)
	}
	if _, ok := data["tokens"]; ok {
		t.Fatal("context is not cumulative usage")
	}
	if _, ok := data["cost"]; ok {
		t.Fatal("unreported cost fabricated")
	}
	if len(data) != 2 {
		t.Fatalf("unexpected private projection: %v", data)
	}
}
func TestGrokStatsMissingAndInvalidRemainUnknown(t *testing.T) {
	for _, text := range []string{"", "Context: unknown", "Context: 10 / 0 tokens", "Context: 99999999999999999999999 / 10 tokens"} {
		if _, ok := grokStatsMetrics(text)["contextUsage"]; ok {
			t.Fatalf("invented metric: %q", text)
		}
	}
}
func TestGrokStatsPlainAndCommaSeparated(t *testing.T) {
	data := grokStatsMetrics("Context: 51,000 / 500,000 tokens (10%)\nTurn: 12\n")
	if data["turnCount"] != int64(12) || data["contextUsage"].(map[string]any)["tokens"] != int64(51000) {
		t.Fatal(data)
	}
}
