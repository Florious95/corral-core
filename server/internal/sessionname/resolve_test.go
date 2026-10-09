package sessionname

import (
	"strings"
	"testing"
	"unicode/utf8"
)

func TestResolveSpecExamples(t *testing.T) {
	tests := []struct {
		name, window, title, cwd, command, want, source string
	}{
		{"fig1 tmux noise yields title", "[tmux]", "解决中转站的问题", "/work/多agent协作", "tmux", "解决中转站的问题", SourceTitle},
		{"fig2 zsh pi title", "zsh", "π - 多agent开发leader - 多agent协作", "/work/多agent协作", "zsh", "多agent开发leader", SourceTitle},
		{"fig3 node flag title", "node!", "多 agent leader | 多agent协作", "/work/多agent协作", "node", "多 agent leader", SourceTitle},
		{"fig4 meaningful window", "smoke-luna", "多agent协作", "/work/多agent协作", "node", "smoke-luna", SourceWindow},
		{"left project loses to title", "project", "reviewer | project", "/work/project", "node", "reviewer", SourceTitle},
		{"title beats left reviewer", "reviewer", "architect | project", "/work/project", "node", "architect", SourceTitle},
		{"hyphen fields keep internals", "node", "project - reviewer", "/work/project", "node", "reviewer", SourceTitle},
		{"first title segment wins", "node", "reviewer | architect | project", "/work/project", "node", "reviewer", SourceTitle},
		{"fix-login not split", "node", "fix-login | my-project", "/work/my-project", "node", "fix-login", SourceTitle},
		{"title beats node-proxy", "node-proxy", "other | project", "/work/project", "node", "other", SourceTitle},
		{"claude_code noise", "claude_code", "✳ 远控 leader", "/work/project", "node", "远控 leader", SourceTitle},
		{"future executable filtered", "future-cli", "审查任务 | project", "/work/project", "future-cli", "审查任务", SourceTitle},
		{"duplicate title fields", "node", "project|reviewer|reviewer", "/work/project", "", "reviewer", SourceTitle},
		{"substring is not project", "project-review", "", "/work/project", "node", "project-review", SourceWindow},
		{"project named node", "node", "", "/work/node", "node", "node", SourceProject},
		{"trailing slash cwd", "zsh", "π - project", "/work/project/", "zsh", "project", SourceProject},
		{"review bang kept", "review!", "", "/work/project", "node", "review!", SourceWindow},
		{"csharp kept", "C#", "", "/work/project", "node", "C#", SourceWindow},
		{"empty placeholder", "", "", "", "", Placeholder, SourcePlaceholder},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := Resolve(tt.window, tt.title, tt.cwd, tt.command)
			if got.Value != tt.want || got.Source != tt.source {
				t.Fatalf("Resolve(%q,%q,%q,%q)=%+v want value=%q source=%q",
					tt.window, tt.title, tt.cwd, tt.command, got, tt.want, tt.source)
			}
		})
	}
}

func TestResolveIgnoresProviderShapedInputsByUsingOnlyTmuxFields(t *testing.T) {
	// Same tmux fields must yield the same name regardless of which Agent
	// produced them. Resolve has no Provider parameter; this locks the
	// call shape and the four screenshot rows.
	rows := []struct{ window, title, cwd, command, want string }{
		{"[tmux]", "解决中转站的问题", "/work/多agent协作", "tmux", "解决中转站的问题"},
		{"zsh", "π - 多agent开发leader - 多agent协作", "/work/多agent协作", "zsh", "多agent开发leader"},
		{"node!", "多 agent leader | 多agent协作", "/work/多agent协作", "node", "多 agent leader"},
		{"smoke-luna", "多agent协作", "/work/多agent协作", "node", "smoke-luna"},
	}
	for _, row := range rows {
		if got := Resolve(row.window, row.title, row.cwd, row.command); got.Value != row.want {
			t.Fatalf("got %q want %q", got.Value, row.want)
		}
	}
}

func TestResolveControlCharactersAndQuotes(t *testing.T) {
	got := Resolve("node", "\x01\"审查任务\"\t|\tproject", "/work/project", "node")
	if got.Value != "审查任务" {
		t.Fatalf("got %q", got.Value)
	}
}

func TestResolveUnicodeWhitespaceHyphenAndFullCwd(t *testing.T) {
	title := "任务" + "\u00a0-\u00a0" + "/work/project"
	got := Resolve("node", title, "/work/project", "node")
	if got.Value != "任务" {
		t.Fatalf("got %q want 任务 from Unicode-spaced hyphen and full cwd demotion", got.Value)
	}
}

func TestResolveStatusDecorationChangeKeepsName(t *testing.T) {
	a := Resolve("node", "◐ 远控 leader", "/work/project", "node")
	b := Resolve("node", "✳ 远控 leader", "/work/project", "node")
	if a.Value != "远控 leader" || a.Value != b.Value {
		t.Fatalf("decoration changed the name: %q vs %q", a.Value, b.Value)
	}
}

func TestResolveWindowFlagsAreNotDisplayValues(t *testing.T) {
	got := Resolve("node!*", "任务 | project", "/work/project", "node")
	if got.Value != "任务" {
		t.Fatalf("got %q, window flags leaked", got.Value)
	}
}

func TestResolveCaseFoldNoiseAndDedup(t *testing.T) {
	got := Resolve("NODE", "Reviewer | REVIEWER | project", "/work/project", "node")
	if got.Value != "Reviewer" {
		t.Fatalf("got %q want first title casing Reviewer", got.Value)
	}
}

func TestResolveMissingSidesFallBackToProjectThenPlaceholder(t *testing.T) {
	if got := Resolve("", "", "/work/alpha", ""); got != (Resolved{Value: "alpha", Source: SourceProject}) {
		t.Fatalf("cwd-only: %+v", got)
	}
	if got := Resolve("", "", "/", "zsh"); got.Source != SourcePlaceholder || got.Value != Placeholder {
		t.Fatalf("root cwd: %+v", got)
	}
	if got := Resolve("", "", ".", ""); got.Value != Placeholder {
		t.Fatalf("dot cwd: %+v", got)
	}
	if got := Resolve("", "", "..", ""); got.Value != Placeholder {
		t.Fatalf("dotdot cwd: %+v", got)
	}
}

func TestResolveDoesNotTreatNodeSubstringAsNoise(t *testing.T) {
	got := Resolve("node-proxy", "", "/work/project", "node")
	if got.Value != "node-proxy" {
		t.Fatalf("got %q", got.Value)
	}
	got = Resolve("my-project-review", "", "/work/my-project", "node")
	if got.Value != "my-project-review" {
		t.Fatalf("substring project demotion: %q", got.Value)
	}
}

func TestResolveDoesNotUseTmuxSessionOrTruncate(t *testing.T) {
	got := Resolve("", "", "/work/project", "")
	if got.Value == "team-refactor-maintainability" {
		t.Fatal("must not invent a tmux session name")
	}
	name := "很长的中文任务名称仍应完整保留"
	got = Resolve(name, "", "/work/project", "node")
	if got.Value != name || utf8.RuneCountInString(got.Value) != utf8.RuneCountInString(name) {
		t.Fatalf("truncated or rewritten: %q", got.Value)
	}
}

func TestCleanStripsDecorationsAndKeepsPunctuation(t *testing.T) {
	if got := clean("✳ 远控 leader"); got != "远控 leader" {
		t.Fatalf("clean decoration: %q", got)
	}
	if got := clean("  'C++'  "); got != "C++" {
		t.Fatalf("clean quotes: %q", got)
	}
	if strings.Contains(clean("review!"), " ") {
		t.Fatal("unexpected space")
	}
}

func TestKeyMatchesFoldedNFC(t *testing.T) {
	if key("Π") != key("π") {
		t.Fatal("greek letter must fold for the noise table")
	}
	if key("NODE") != key("node") {
		t.Fatal("ascii case fold")
	}
}

func TestResolveDimensionOneTitleBeatsMeaningfulWindow(t *testing.T) {
	tests := []struct {
		name, window, title, cwd, command, want string
	}{
		{"meaningful title beats meaningful window", "smoke-luna", "多agent协作", "/work/other-project", "node", "多agent协作"},
		{"first meaningful title segment beats window", "reviewer", "architect | project", "/work/project", "node", "architect"},
		{"non-noise window still loses to title", "node-proxy", "other | project", "/work/project", "node", "other"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := Resolve(tt.window, tt.title, tt.cwd, tt.command)
			if got.Value != tt.want || got.Source != SourceTitle {
				t.Fatalf("Resolve(%q,%q,%q,%q)=%+v want value=%q source=%q",
					tt.window, tt.title, tt.cwd, tt.command, got, tt.want, SourceTitle)
			}
		})
	}
}
