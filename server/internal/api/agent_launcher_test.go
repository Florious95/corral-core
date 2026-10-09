package api

import (
	"reflect"
	"testing"
)

func TestAgentLaunchersKeepBareCommandsAndCapabilities(t *testing.T) {
	launchers := availableAgentLaunchers()
	if len(launchers) != 4 {
		t.Fatalf("launchers=%d, want four", len(launchers))
	}
	want := map[string]struct {
		command string
		bypass  bool
		args    []string
		naming  string
	}{
		"pi":     {command: "pi", bypass: false, naming: "tmux"},
		"codex":  {command: "codex", bypass: true, args: []string{"--dangerously-bypass-approvals-and-sandbox"}, naming: "tmux"},
		"cursor": {command: "agent", bypass: true, args: []string{"--force"}, naming: "tmux"},
		"grok":   {command: "grok", bypass: true, args: []string{"--always-approve"}, naming: "tmux"},
	}
	for _, launcher := range launchers {
		w, ok := want[launcher.Provider]
		if !ok {
			t.Fatalf("unexpected launcher provider %q", launcher.Provider)
		}
		if launcher.command != w.command {
			t.Errorf("%s command=%q, want bare %q", launcher.Provider, launcher.command, w.command)
		}
		if launcher.SupportsBypass != w.bypass || launcher.Naming != w.naming || !reflect.DeepEqual(launcher.bypassArgs, w.args) {
			t.Errorf("%s capabilities=(bypass=%v naming=%q args=%#v), want=(%v %q %#v)", launcher.Provider, launcher.SupportsBypass, launcher.Naming, launcher.bypassArgs, w.bypass, w.naming, w.args)
		}
		if got := agentCommand(launcher, "ignored", true); !reflect.DeepEqual(got, append([]string{w.command}, w.args...)) {
			t.Errorf("%s bypass command=%#v, want %#v", launcher.Provider, got, append([]string{w.command}, w.args...))
		}
	}
}
