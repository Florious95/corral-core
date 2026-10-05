package bridge

import (
	"reflect"
	"testing"
)

func TestNativeGrokArgumentsAreLiteralAndModeSpecific(t *testing.T) {
	for _, tc := range []struct {
		command, mode, id string
		ok                bool
	}{
		{`grok agent stdio`, "rpc", "", true},
		{`exec /opt/homebrew/bin/grok agent --no-leader stdio`, "rpc", "", true},
		{`grok --resume abc`, "tui", "abc", true},
		{`grok --model grok-4.7 --reasoning-effort high`, "tui", "", true},
		{`grok agent --agent-profile stdio headless`, "", "", false},
		{`grok --single "agent stdio"`, "", "", false},
		{`grok leader start`, "", "", false},
		{`grok sessions list`, "", "", false},
		{`grok agent stdio | tee out`, "", "", false},
		{`grok agent stdio --unknown`, "", "", false},
		{`grok agent stdio --model`, "", "", false},
		{`sh -c 'grok agent stdio'`, "", "", false},
	} {
		args, err := nativeStartArguments(tc.command)
		if err != nil {
			if tc.ok {
				t.Fatalf("literal native %q rejected", tc.command)
			}
			continue
		}
		provider, mode, id, ok := agentArguments(args)
		if ok != tc.ok || (ok && (provider != "grok" || mode != tc.mode || id != tc.id)) {
			t.Fatalf("native grammar %q: provider=%s mode=%s id=%s ok=%v", tc.command, provider, mode, id, ok)
		}
	}
}

func TestGrokModeCommandsUseOfficialNamespaces(t *testing.T) {
	p := NativeProcess{Provider: "grok", Args: []string{"/opt/bin/grok", "--leader-socket", "own.sock", "agent", "--no-leader", "--model", "grok-4.7", "stdio"}}
	want := []string{"/opt/bin/grok", "--leader-socket", "own.sock", "--no-leader", "--model", "grok-4.7", "--resume", "known-id"}
	got, err := GrokCommand(p, "tui", "known-id")
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("native pager command=%q err=%v", got, err)
	}
	p.Args = got
	want = []string{"/opt/bin/grok", "agent", "--leader-socket", "own.sock", "--no-leader", "--model", "grok-4.7", "stdio"}
	got, err = GrokCommand(p, "rpc", "known-id")
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("native ACP command=%q err=%v", got, err)
	}
	p.Args = []string{"grok", "--fullscreen"}
	if _, err := GrokCommand(p, "rpc", "known-id"); err == nil {
		t.Fatal("unpreservable launch configuration was silently dropped")
	}
}
