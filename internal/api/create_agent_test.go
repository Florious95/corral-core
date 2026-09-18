package api

import (
	"reflect"
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestPiAgentLauncherUsesTmuxNamingWithoutGuessedFlags(t *testing.T) {
	pi := agentLauncher{
		AgentLauncher: protocol.AgentLauncher{Provider: "pi", Naming: "tmux", SupportsBypass: false},
		command:       "pi",
	}
	if got, want := agentCommand(pi, "修复员", true), []string{"pi"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("pi args=%#v, want %#v", got, want)
	}
	if got, want := agentCommand(pi, "plain", false), []string{"pi"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("pi no-bypass args=%#v, want %#v", got, want)
	}
}

func TestAgentLauncherTmuxFallbackOmitsCliNameFlag(t *testing.T) {
	codex := agentLauncher{
		AgentLauncher: protocol.AgentLauncher{Provider: "codex", Naming: "tmux", SupportsBypass: true},
		command:       "codex",
		bypassArgs:    []string{"--dangerously-bypass-approvals-and-sandbox"},
	}
	if got, want := agentCommand(codex, "named-window", true), []string{"codex", "--dangerously-bypass-approvals-and-sandbox"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("codex args=%#v, want %#v", got, want)
	}
}

func TestValidateAgentName(t *testing.T) {
	for _, tc := range []struct {
		name string
		want bool
	}{
		{"修复测试员", true},
		{"name with spaces!", true},
		{"", false},
		{"   ", false},
		{"line\nfeed", false},
	} {
		if got := validateAgentName(tc.name); got != tc.want {
			t.Errorf("validateAgentName(%q)=%v, want %v", tc.name, got, tc.want)
		}
	}
}
