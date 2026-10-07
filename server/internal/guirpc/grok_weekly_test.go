package guirpc

import (
	"encoding/json"
	"testing"
)

// Authorized UI policy fixtures, not evidence of a live native account reading.
func TestGrokWeeklyPercentageAndAuthorizedDefault(t *testing.T) {
	for _, tc := range []struct {
		name, fields     string
		known, defaulted bool
		want             float64
	}{
		{"explicit zero", `,"creditUsagePercent":0`, true, false, 0},
		{"explicit percentage", `,"creditUsagePercent":42.5`, true, false, 42.5},
		{"full", `,"creditUsagePercent":100`, true, false, 100},
		{"missing", ``, true, true, 0},
		{"null", `,"creditUsagePercent":null`, true, true, 0},
		{"zero spending", `,"onDemandUsed":{"val":0}`, true, true, 0},
		{"nonzero spending", `,"onDemandUsed":{"val":1}`, false, false, 0},
		{"negative", `,"creditUsagePercent":-1`, false, false, 0},
		{"over limit", `,"creditUsagePercent":100.1`, false, false, 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			q, err := grokQuotaProjection(json.RawMessage(`{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","end":"2026-10-12T00:40:34+08:00"}` + tc.fields + `}}`))
			if err != nil {
				t.Fatal(err)
			}
			weekly, ok := q["weekly"].(map[string]any)
			if ok != tc.known {
				t.Fatalf("weekly presence=%v, want %v", ok, tc.known)
			}
			if !ok {
				if q["windowsStatus"] != "unreported" {
					t.Fatal("invalid quota reported")
				}
				return
			}
			if weekly["usedPercent"] != tc.want || q["windowsStatus"] != "reported" {
				t.Fatal("wrong percentage/status")
			}
			if weekly["resetsAt"] != "2026-10-11T16:40:34Z" || weekly["resetBasis"] != "currentPeriod.end" {
				t.Fatal("authorized reset provenance lost")
			}
			if tc.defaulted {
				if weekly["usedPercentDefaulted"] != true || weekly["basis"] != "authorized_tui_default" {
					t.Fatal("default falsely presented as measured")
				}
			} else if weekly["usedPercentDefaulted"] != nil || weekly["basis"] != "creditUsagePercent" {
				t.Fatal("native percentage mislabeled default")
			}
			if q["fiveHour"] != nil {
				t.Fatal("five-hour window invented")
			}
		})
	}
}

func TestGrokWeeklyInvalidPercentageNeverDefaults(t *testing.T) {
	for _, value := range []string{`"0"`, `true`, `{}`, `[]`, `1e999`} {
		if _, err := grokQuotaProjection(json.RawMessage(`{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY"},"creditUsagePercent":` + value + `}}`)); err == nil {
			t.Fatal("invalid percentage was accepted/defaulted")
		}
	}
	q, err := grokQuotaProjection(json.RawMessage(`{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","end":"10:40"},"creditUsagePercent":0}}`))
	if err != nil {
		t.Fatal(err)
	}
	weekly := q["weekly"].(map[string]any)
	if weekly["resetsAt"] != nil || weekly["resetBasis"] != nil {
		t.Fatal("invalid date invented a reset")
	}
}
