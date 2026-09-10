package discovery

import "testing"

// This is an independent consumer red for the real session46/%169 metadata
// shape: the Codex OSC title contains a pipe, while the discovery wire format
// also uses pipe separators. A valid pane must survive parsing with its full
// title and structural identity intact.
func TestPR29CodexPipeTitleSurvivesDiscoveryParse(t *testing.T) {
	line := "46|0|%169|/Users/alauda/Documents/code/agent前沿探索/多agent协作|node|92501|⠙ 多 agent leader | 多agent协作|235x50|node"
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
