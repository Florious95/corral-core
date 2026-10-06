package guirpc

// Grok exposes context through native read-only slash output, not Pi usage.
// Only known labelled metrics are projected; credentials/cwd/auth/native text
// never leave this collector. Missing cumulative/turn token metrics stay absent.
// @contract
// @pre idle verified ACP session; no concurrent native context mutation
// @post context uses one native used/limit pair, never invented token/cost zero
// @err bounded query failure is visible; timeout does not fabricate idle
// @inv no polling, model call, transcript injection or text-screen scraping

import (
	"encoding/json"
	"errors"
	"math"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type grokStatsRead struct {
	id, session string
	text        strings.Builder
	context     string
	overflow    bool
}

func (g *grokACP) readStats(id string) error {
	g.mu.Lock()
	if id == "" || g.session == "" || g.running || g.queued > 0 || g.promptID != "" || g.sessionChanging {
		g.mu.Unlock()
		return errors.New("Grok 当前任务未停止，统计读取未提交")
	}
	read := &grokStatsRead{id: id, session: g.session}
	g.stats, g.sessionChanging = read, true
	g.mu.Unlock()
	g.statsQuery(read, "/context", false)
	return nil
}

func (g *grokACP) statsQuery(read *grokStatsRead, command string, last bool) {
	params := map[string]any{"sessionId": read.session, "prompt": []any{map[string]any{"type": "text", "text": command}}}
	err := g.call("session/prompt", params, 5*time.Second, func(raw json.RawMessage, err error) {
		g.mu.Lock()
		if g.stats != read {
			g.mu.Unlock()
			return
		}
		text, overflow := read.text.String(), read.overflow
		read.text.Reset()
		if !last && err == nil && !overflow {
			read.context = text
			g.mu.Unlock()
			g.statsQuery(read, "/session-info", true)
			return
		}
		// A missing /context implementation does not erase /session-info's
		// native context pair. A deadline is different: work may still exist.
		if !last && err != nil && !errors.Is(err, errACPTimeout) {
			g.mu.Unlock()
			g.statsQuery(read, "/session-info", true)
			return
		}
		g.stats, g.sessionChanging = nil, false
		same := g.session == read.session
		model := g.modelLocked()
		g.mu.Unlock()
		if errors.Is(err, errACPTimeout) {
			g.lost()
		}
		if err != nil || overflow || !same {
			g.response(read.id, "get_session_stats", nil, errors.New("Grok 未确认统计读取，请核对原生会话"))
			return
		}
		metrics := grokStatsMetrics(read.context + "\n" + text)
		metrics["sessionId"], metrics["agentProvider"], metrics["modelName"] = read.session, "grok", model.Name
		if model.Name == "" {
			metrics["modelName"] = model.ID
		}
		metrics["source"] = "native_slash"
		g.response(read.id, "get_session_stats", metrics, nil)
	})
	if err != nil {
		g.mu.Lock()
		if g.stats == read {
			g.stats, g.sessionChanging = nil, false
		}
		g.mu.Unlock()
		g.response(read.id, "get_session_stats", nil, errors.New("Grok 统计查询未提交"))
	}
}

var grokContextMetric = regexp.MustCompile(`(?im)^\s*(?:\*\*)?Context(?:\*\*)?:\s*(?:\*\*)?\s*([0-9][0-9,]*)\s*/\s*([0-9][0-9,]*)\s+tokens\b`)
var grokTurnMetric = regexp.MustCompile(`(?im)^\s*(?:\*\*)?Turns?(?:\*\*)?:\s*(?:\*\*)?\s*([0-9][0-9,]*)\s*$`)

func grokStatsMetrics(text string) map[string]any {
	result := make(map[string]any)
	number := func(raw string) (int64, bool) {
		n, err := strconv.ParseInt(strings.ReplaceAll(raw, ",", ""), 10, 64)
		return n, err == nil && n >= 0
	}
	if matches := grokContextMetric.FindStringSubmatch(text); len(matches) == 3 {
		used, ok1 := number(matches[1])
		limit, ok2 := number(matches[2])
		if ok1 && ok2 && limit > 0 {
			percent := float64(used) * 100 / float64(limit)
			if !math.IsInf(percent, 0) {
				result["contextUsage"] = map[string]any{"tokens": used, "contextWindow": limit, "percent": percent}
			}
		}
	}
	if matches := grokTurnMetric.FindStringSubmatch(text); len(matches) == 2 {
		if count, ok := number(matches[1]); ok {
			result["turnCount"] = count
		}
	}
	return result
}
