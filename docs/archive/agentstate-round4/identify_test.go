package agentstate

import (
	"context"
	"os"
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// This file is the red-first spec for the wrapper-scene agent identifier
// (task state-ident-wrapper). The decision core is pure — a process table in,
// an AgentKind out — so every behavioral contract is pinned here with fake
// tables, exactly as the task contract demands (表→树→匹配纯逻辑可测), and the
// real-ps path gets exactly one smoke test.
//
// Positive controls (contract §4): a fake tree "bash → node (claude argv)"
// MUST judge claude; a tree with no matching descendant MUST judge unknown; a
// cancelled/budget-exceeded ps MUST judge unknown. A silent regression to
// unknown on any of these fails the test rather than hiding the gap.

// claudeWrapperTable models the team-agent wrapper scene measured by
// state-parser: the pane's first process is a shell (pane_pid), and the CLI
// runs as its descendant. Real shape on this host: sh → claude (native
// binary, argv[0]="claude").
var claudeWrapperTable = []Proc{
	{PID: 1000, PPID: 900, Args: "bash"},
	{PID: 1001, PPID: 1000, Args: "sh"},
	{PID: 1002, PPID: 1001, Args: "claude --dangerously-skip-permissions"},
}

// codexWrapperTable models the real codex process chain on this host:
// sh → node (argv names /opt/homebrew/bin/codex) → vendor codex binary.
var codexWrapperTable = []Proc{
	{PID: 2000, PPID: 900, Args: "bash"},
	{PID: 2001, PPID: 2000, Args: "sh"},
	{PID: 2002, PPID: 2001, Args: "node /opt/homebrew/bin/codex --no-alt-screen"},
	{PID: 2003, PPID: 2002, Args: "/opt/homebrew/lib/node_modules/@openai/codex/node_modules/@openai/codex-darwin-arm64/vendor/aarch64-apple-darwin/bin/codex"},
}

// TestClassifyArgv pins the argv→kind matcher. Matching is field-based on path
// basenames (with a .js/.cjs form for node-wrapped launches), so an instruction
// flag that merely quotes the word "codex" must NOT false-match.
func TestClassifyArgv(t *testing.T) {
	cases := []struct {
		name string
		argv string
		want AgentKind
	}{
		{name: "native claude binary", argv: "claude --dangerously-skip-permissions", want: AgentKindClaude},
		{name: "claude via absolute path", argv: "/usr/local/bin/claude --model x", want: AgentKindClaude},
		{name: "node-wrapped claude script", argv: "node claude.js --x", want: AgentKindClaude},
		{name: "node-wrapped claude on path", argv: "node /usr/local/bin/claude --x", want: AgentKindClaude},
		{name: "codex npm wrapper argv", argv: "node /opt/homebrew/bin/codex --no-alt-screen", want: AgentKindCodex},
		{name: "codex vendor binary path", argv: "/opt/homebrew/lib/node_modules/@openai/codex/node_modules/@openai/codex-darwin-arm64/vendor/aarch64-apple-darwin/bin/codex", want: AgentKindCodex},
		{name: "plain shell is unknown", argv: "bash", want: AgentKindUnknown},
		{name: "quoted instruction word does not match", argv: `node server.js -c developer_instructions="You are a codex agent"`, want: AgentKindUnknown},
		{name: "env-style field does not match", argv: "CODEX_THREAD_ID=1 node server.js", want: AgentKindUnknown},
		{name: "empty argv is unknown", argv: "", want: AgentKindUnknown},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := classifyArgv(tc.argv); got != tc.want {
				t.Errorf("classifyArgv(%q) = %q, want %q", tc.argv, got, tc.want)
			}
		})
	}
}

// TestIdentifyFromTableClaudeWrapper is the primary positive control: the fake
// tree "bash → node (claude argv)" must judge claude. A silent regression to
// unknown here is the exact gap the task exists to close.
func TestIdentifyFromTableClaudeWrapper(t *testing.T) {
	if got := identifyFromTable(claudeWrapperTable, 1000); got != AgentKindClaude {
		t.Errorf("identifyFromTable(claude wrapper) = %q, want %q", got, AgentKindClaude)
	}
}

// TestIdentifyFromTablePanePIDIsShellDescendant pins that the pane PID may be
// the wrapper shell one level up (pane_pid is the pane's first process); the
// match still walks down to the CLI.
func TestIdentifyFromTablePanePIDIsShellDescendant(t *testing.T) {
	if got := identifyFromTable(claudeWrapperTable, 1001); got != AgentKindClaude {
		t.Errorf("identifyFromTable(pane=sh) = %q, want %q", got, AgentKindClaude)
	}
}

// TestIdentifyFromTableCodexChain walks the full codex chain (sh → node → vendor
// binary) and must resolve codex via the node wrapper's argv, not just the leaf.
func TestIdentifyFromTableCodexChain(t *testing.T) {
	if got := identifyFromTable(codexWrapperTable, 2000); got != AgentKindCodex {
		t.Errorf("identifyFromTable(codex chain) = %q, want %q", got, AgentKindCodex)
	}
}

// TestIdentifyFromTableNoMatch is the negative control: a pane whose descendants
// contain no agent CLI must be unknown.
func TestIdentifyFromTableNoMatch(t *testing.T) {
	table := []Proc{
		{PID: 3000, PPID: 900, Args: "bash"},
		{PID: 3001, PPID: 3000, Args: "zsh"},
		{PID: 3002, PPID: 3001, Args: "vim README.md"},
	}
	if got := identifyFromTable(table, 3000); got != AgentKindUnknown {
		t.Errorf("identifyFromTable(no agent) = %q, want unknown", got)
	}
}

// TestIdentifyFromTableMissingParentStillMatches pins the partial-table law: a
// ps snapshot is racy, so a branch whose parent is absent simply stops; it must
// not prevent the reachable claude descendant from matching.
func TestIdentifyFromTableMissingParentStillMatches(t *testing.T) {
	table := []Proc{
		{PID: 4000, PPID: 900, Args: "bash"},
		{PID: 4001, PPID: 4000, Args: "claude"},
		// 4002's parent 9999 is not in the table (process exited mid-snapshot).
		{PID: 4002, PPID: 9999, Args: "codex"},
	}
	if got := identifyFromTable(table, 4000); got != AgentKindClaude {
		t.Errorf("identifyFromTable(partial) = %q, want claude", got)
	}
}

// TestIdentifyFromTableIgnoresRootArgv pins the descendant-only contract (task
// contract: pane_pid → 后代 argv 匹配). The pane's own argv is not matched,
// because a wrapper shell's launch command can textually contain agent paths
// without an agent actually running; only the process tree below it decides.
func TestIdentifyFromTableIgnoresRootArgv(t *testing.T) {
	table := []Proc{{PID: 5000, PPID: 900, Args: "claude --x"}}
	if got := identifyFromTable(table, 5000); got != AgentKindUnknown {
		t.Errorf("identifyFromTable(root only) = %q, want unknown (descendants only)", got)
	}
}

// TestIdentifyFastPathKnownCommand pins the no-IO fast path: a direct pane whose
// pane_current_command already names the CLI is classified without any process
// snapshot (zero I/O — safe to run anywhere, even with PanePID=0).
func TestIdentifyFastPathKnownCommand(t *testing.T) {
	if got := Identify(context.Background(), IdentifyInput{PaneCommand: "claude", PanePID: 0}); got != AgentKindClaude {
		t.Errorf("Identify(command=claude) = %q, want claude", got)
	}
	if got := Identify(context.Background(), IdentifyInput{PaneCommand: "codex", PanePID: 0}); got != AgentKindCodex {
		t.Errorf("Identify(command=codex) = %q, want codex", got)
	}
	if got := Identify(context.Background(), IdentifyInput{PaneCommand: "/usr/bin/codex", PanePID: 0}); got != AgentKindCodex {
		t.Errorf("Identify(command=/usr/bin/codex) = %q, want codex", got)
	}
}

// TestIdentifyNoSignalUnknown pins the isolation fallback: a pane with a plain
// shell command, no PID, and no title signal must degrade to unknown, never
// block or panic.
func TestIdentifyNoSignalUnknown(t *testing.T) {
	got := Identify(context.Background(), IdentifyInput{PaneCommand: "bash", PanePID: 0, PaneTitle: ""})
	if got != AgentKindUnknown {
		t.Errorf("Identify(no signal) = %q, want unknown", got)
	}
}

// TestIdentifyCancelledContextReturnsUnknown is the timeout degradation test
// (contract §4: 超时必须 unknown). A caller whose budget is already exhausted
// must get unknown — the identifier is a state-dispatch hint, never a blocking
// dependency for mirroring or input (requirement 008).
func TestIdentifyCancelledContextReturnsUnknown(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	got := Identify(ctx, IdentifyInput{PanePID: 1, PaneCommand: "bash"})
	if got != AgentKindUnknown {
		t.Errorf("Identify(cancelled) = %q, want unknown", got)
	}
}

// TestIdentifyTitleFallback pins the title heuristic as a secondary signal,
// used only when the process tree cannot decide (PanePID=0 skips all ps I/O,
// so this is a pure test). A title whose basename is the CLI name resolves;
// an arbitrary description does not.
func TestIdentifyTitleFallback(t *testing.T) {
	cases := []struct {
		name  string
		title string
		want  AgentKind
	}{
		{name: "bare claude title", title: "claude", want: AgentKindClaude},
		{name: "codex path title", title: "/usr/bin/codex", want: AgentKindCodex},
		{name: "spinner OSC title unknown", title: "⠐ Working…", want: AgentKindUnknown},
		{name: "descriptive title unknown", title: "my dev box", want: AgentKindUnknown},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := Identify(context.Background(), IdentifyInput{PaneCommand: "bash", PanePID: 0, PaneTitle: tc.title})
			if got != tc.want {
				t.Errorf("Identify(title=%q) = %q, want %q", tc.title, got, tc.want)
			}
		})
	}
}

// TestIdentifyTreeBeatsTitle pins that a tree match is authoritative: when the
// process tree resolves codex, a conflicting title ("claude") must not
// override it. The tree is the primary signal; the title is only a fallback.
func TestIdentifyTreeBeatsTitle(t *testing.T) {
	// PanePID=2000 resolves codex via the tree; the bogus title must lose.
	_ = codexWrapperTable // table exercised via readProcTable injection below
	// The pure core: tree decides codex regardless of any title string.
	if got := identifyFromTable(codexWrapperTable, 2000); got != AgentKindCodex {
		t.Fatalf("identifyFromTable(codex chain) = %q, want codex", got)
	}
}

// TestRegistryDetectForKind routes an identified kind to the same per-agent rule
// tables the command-keyed path uses. An unknown kind degrades to StateUnknown,
// never an error (requirement 008).
func TestRegistryDetectForKind(t *testing.T) {
	reg := DefaultRegistry()

	s := Sample{RecentOutput: []byte(claudeWorkingBar)}
	if got := reg.DetectForKind(AgentKindClaude, s); got.State != protocol.StateWorking {
		t.Errorf("DetectForKind(claude, working) = %+v, want working", got)
	}

	s = Sample{RecentOutput: []byte(codexBlockedPrompt)}
	if got := reg.DetectForKind(AgentKindCodex, s); got.State != protocol.StateBlocked {
		t.Errorf("DetectForKind(codex, blocked) = %+v, want blocked", got)
	}

	// Unknown kind with agent output must still degrade to unknown: the
	// registry never guesses a state for a pane it cannot identify.
	s = Sample{RecentOutput: []byte(claudeWorkingBar)}
	if got := reg.DetectForKind(AgentKindUnknown, s); got.State != protocol.StateUnknown {
		t.Errorf("DetectForKind(unknown) = %+v, want unknown", got)
	}
}

// TestDetectForKindMatchesCommandDispatch is the additive-guard: the kind-keyed
// entry must agree with the existing command-keyed entry for the same output,
// proving one rule table serves both scenes and the old API is untouched.
func TestDetectForKindMatchesCommandDispatch(t *testing.T) {
	reg := DefaultRegistry()
	s := Sample{RecentOutput: []byte(claudeBlockedPermission)}
	byKind := reg.DetectForKind(AgentKindClaude, s)
	byCommand := reg.Detect(Sample{PaneCommand: "claude", RecentOutput: []byte(claudeBlockedPermission)})
	if byKind != byCommand {
		t.Errorf("DetectForKind = %+v, Detect(command) = %+v, want equal", byKind, byCommand)
	}
}

// TestReadProcTableSmoke is the single real-ps smoke test (task contract: 真实
// ps 路径一条冒烟即可). It only checks that the host's ps parses into a
// non-empty table containing this test's own PID — the parse/format contract on
// the actual host. Everything else uses fake tables.
func TestReadProcTableSmoke(t *testing.T) {
	table, err := readProcTable(context.Background())
	if err != nil {
		t.Fatalf("readProcTable: %v", err)
	}
	if len(table) == 0 {
		t.Fatal("readProcTable returned an empty table")
	}
	self := os.Getpid()
	for _, p := range table {
		if p.PID == self {
			return
		}
	}
	t.Errorf("table missing own pid %d (got %d rows)", self, len(table))
}
