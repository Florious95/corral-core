package discovery

import (
	"strings"
	"testing"
)

// This is an independent consumer red for the real session46/%169 metadata
// shape: the Codex OSC title contains a pipe, while the discovery wire format
// also uses pipe separators. A valid pane must survive parsing with its full
// title and structural identity intact.
func TestPR29CodexPipeTitleSurvivesDiscoveryParse(t *testing.T) {
	line := strings.NewReplacer(
		"#{session_name}", "46",
		"#{window_index}", "0",
		"#{pane_id}", "%169",
		"#{pane_current_path}", "/Users/alauda/Documents/code/agent前沿探索/多agent协作",
		"#{pane_current_command}", "node",
		"#{pane_pid}", "92501",
		"#{pane_title}", "⠙ 多 agent leader | 多agent协作",
		"#{pane_width}", "235",
		"#{pane_height}", "50",
		"#{window_name}", "node",
	).Replace(paneFormat)
	pane, ok := parsePaneLine(line)
	if !ok {
		t.Fatalf("real Codex pane line with pipe title was discarded: %q", line)
	}
	if pane.Session != "46" || pane.PaneID != "%169" || pane.PanePID != 92501 {
		t.Fatalf("structural identity changed: %+v", pane)
	}
	if pane.PaneTitle != "⠙ 多 agent leader | 多agent协作" {
		t.Fatalf("title=%q, want full Codex title", pane.PaneTitle)
	}
}
