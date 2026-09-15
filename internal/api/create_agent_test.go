package api

import (
	"reflect"
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestAgentLauncherCommandsUseVerifiedProviderFlags(t *testing.T) {
	pi := agentLauncher{
		AgentLauncher: protocol.AgentLauncher{Provider: "pi", Naming: "cli", SupportsBypass: true},
		command:       "/opt/homebrew/bin/pi",
		nameArgs:      func(name string) []string { return []string{"--name", name} },
		bypassArgs:    []string{"--approve"},
	}
	if got, want := agentCommand(pi, "修复员", true), []string{"/opt/homebrew/bin/pi", "--name", "修复员", "--approve"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("pi args=%#v, want %#v", got, want)
	}
	if got, want := agentCommand(pi, "plain", false), []string{"/opt/homebrew/bin/pi", "--name", "plain"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("pi no-bypass args=%#v, want %#v", got, want)
	}
}

func TestAgentLauncherTmuxFallbackOmitsCliNameFlag(t *testing.T) {
	codex := agentLauncher{
		AgentLauncher: protocol.AgentLauncher{Provider: "codex", Naming: "tmux", SupportsBypass: true},
		command:       "/opt/homebrew/bin/codex",
		bypassArgs:    []string{"--dangerously-bypass-approvals-and-sandbox"},
	}
	if got, want := agentCommand(codex, "named-window", true), []string{"/opt/homebrew/bin/codex", "--dangerously-bypass-approvals-and-sandbox"}; !reflect.DeepEqual(got, want) {
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
