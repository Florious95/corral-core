package api

import (
	"testing"
)

func TestFooterGrokRunningIsCountNotFoldedIntoState(t *testing.T) {
	title := "修滚动摘要 - grok"
	st, _, known := classifyForProvider("grok", title)
	if !known || st != "idle" {
		t.Fatalf("title state=%q known=%v want idle", st, known)
	}
	footer := "◉ 1 command still running · 1 queued — Enter to send now"
	bg := backgroundTasksFor("grok", footer)
	if bg.unknown || bg.count < 1 {
		t.Fatalf("background_tasks=%s want >=1", bg.String())
	}
	if st == "working" {
		t.Fatal("must not fold background_tasks into state=working")
	}
}

func TestFooterGrokVacuumIsZeroNotConstantOne(t *testing.T) {
	bg := backgroundTasksFor("grok", "just a prompt · nothing in flight")
	if bg.unknown || bg.count != 0 {
		t.Fatalf("vacuum grok background_tasks=%s want 0", bg.String())
	}
}

func TestFooterProviderWithoutRuleIsUnknownNotZero(t *testing.T) {
	footer := "◉ 1 command still running · send a message to interrupt"
	for _, p := range []string{"claude_code", "codex", "cursor", "copilot"} {
		bg := backgroundTasksFor(p, footer)
		if !bg.unknown {
			t.Fatalf("provider %s background_tasks=%s want unknown (no footer rule)", p, bg.String())
		}
		if bg.String() == "0" {
			t.Fatalf("provider %s reported 0; missing rule must not look like vacuum", p)
		}
	}
}
