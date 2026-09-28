package main

// metrics.go aggregates the measured numbers the e2e report consumes:
// first-frame latency distribution (layer 1) and aging loop round counts
// (layer 3). The collector writes one JSON object to the file named by the
// E2E_METRICS env var (set by run.sh), so report.md can render real numbers
// rather than hand-written claims.

import (
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"sync"
)

// firstFrameSample is one measured subscribe→snapshot latency.
type firstFrameSample struct {
	// Ms is the latency in milliseconds.
	Ms float64
	// Scenario labels where the sample came from (e.g. "shell" or "claude").
	Scenario string
}

// Metrics accumulates layer-1 and layer-3 numbers.
type Metrics struct {
	mu sync.Mutex

	// FirstFrames holds every subscribe→snapshot latency sample.
	FirstFrames []firstFrameSample
	// AgeRestartsOK counts completed daemon-restart aging rounds.
	AgeRestartsOK int
	// AgeRestartsFail counts failed daemon-restart rounds.
	AgeRestartsFail int
	// AgeReconnectsOK counts completed connection-drop aging rounds.
	AgeReconnectsOK int
	// AgeReconnectsFail counts failed connection-drop rounds.
	AgeReconnectsFail int
	// Layer1Pass reports whether the whole layer-1 suite passed.
	Layer1Pass bool
	// Layer3Pass reports whether the whole layer-3 suite passed.
	Layer3Pass bool
}

// AddFirstFrame records one first-frame sample.
func (m *Metrics) AddFirstFrame(ms float64, scenario string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.FirstFrames = append(m.FirstFrames, firstFrameSample{Ms: ms, Scenario: scenario})
}

// AddAging records one aging round verdict.
func (m *Metrics) AddAging(kind string, ok bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	switch kind {
	case "restart":
		if ok {
			m.AgeRestartsOK++
		} else {
			m.AgeRestartsFail++
		}
	case "reconnect":
		if ok {
			m.AgeReconnectsOK++
		} else {
			m.AgeReconnectsFail++
		}
	}
}

// Percentile returns the p-th percentile of first-frame latencies (ms).
func percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	idx := int(float64(len(sorted)-1) * p)
	return sorted[idx]
}

// Report is the JSON shape written to the metrics file.
type Report struct {
	FirstFrame struct {
		Count    int        `json:"count"`
		MinMs    float64    `json:"min_ms"`
		MaxMs    float64    `json:"max_ms"`
		AvgMs    float64    `json:"avg_ms"`
		P50Ms    float64    `json:"p50_ms"`
		P90Ms    float64    `json:"p90_ms"`
		Samples  []float64  `json:"samples"`
		Scenario []string   `json:"scenario"`
	} `json:"first_frame"`
	Aging struct {
		RestartOK   int `json:"restart_rounds_ok"`
		RestartFail int `json:"restart_rounds_fail"`
		ReconnectOK int `json:"reconnect_rounds_ok"`
		ReconnectFail int `json:"reconnect_rounds_fail"`
	} `json:"aging"`
	Layer1Pass bool `json:"layer1_pass"`
	Layer3Pass bool `json:"layer3_pass"`
}

// Write dumps the report JSON to the E2E_METRICS path, or stderr if unset.
func (m *Metrics) Write() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	r := Report{}
	times := make([]float64, 0, len(m.FirstFrames))
	for _, s := range m.FirstFrames {
		times = append(times, s.Ms)
	}
	r.FirstFrame.Count = len(times)
	r.FirstFrame.Samples = times
	for _, s := range m.FirstFrames {
		r.FirstFrame.Scenario = append(r.FirstFrame.Scenario, s.Scenario)
	}
	if len(times) > 0 {
		sorted := append([]float64(nil), times...)
		sort.Float64s(sorted)
		sum := 0.0
		for _, v := range sorted {
			sum += v
		}
		r.FirstFrame.MinMs = sorted[0]
		r.FirstFrame.MaxMs = sorted[len(sorted)-1]
		r.FirstFrame.AvgMs = sum / float64(len(sorted))
		r.FirstFrame.P50Ms = percentile(sorted, 0.5)
		r.FirstFrame.P90Ms = percentile(sorted, 0.9)
	}
	r.Aging.RestartOK = m.AgeRestartsOK
	r.Aging.RestartFail = m.AgeRestartsFail
	r.Aging.ReconnectOK = m.AgeReconnectsOK
	r.Aging.ReconnectFail = m.AgeReconnectsFail
	r.Layer1Pass = m.Layer1Pass
	r.Layer3Pass = m.Layer3Pass

	data, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return err
	}
	path := os.Getenv("E2E_METRICS")
	if path == "" {
		fmt.Fprintln(os.Stderr, "E2E_METRICS unset; metrics:", string(data))
		return nil
	}
	return os.WriteFile(path, data, 0o644)
}
