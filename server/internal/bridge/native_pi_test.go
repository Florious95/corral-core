package bridge

import (
	"reflect"
	"testing"
)

func TestNativeStartArgumentsAreLiteralAndExact(t *testing.T) {
	for _, tc := range []struct {
		command, mode, session string
		ok                     bool
	}{
		{`pi --mode rpc --session "a b.jsonl"`, "rpc", "a b.jsonl", true},
		{`exec /opt/bin/pi --session 'a b.jsonl'`, "tui", "a b.jsonl", true},
		{`"'pi' '--mode' 'rpc' '--session' 'a b.jsonl'"`, "rpc", "a b.jsonl", true},
		{`node /opt/pi-coding-agent/dist/cli.js --mode=rpc --session=id`, "rpc", "id", true},
		{`pi --session-id id`, "tui", "id", true},
		{`bash -c 'pi --mode rpc'`, "", "", false},
		{`pi --mode rpc; other`, "", "", false},
		{"pi --session $(secret)", "", "", false},
		{`pi --session "$SECRET"`, "", "", false},
		{`zsh`, "", "", false},
		{`pi --mode print`, "", "", false},
	} {
		args, err := nativeStartArguments(tc.command)
		if (err == nil) != tc.ok {
			t.Fatalf("literal parser availability mismatch for %q", tc.command)
		}
		if tc.ok {
			mode, session, ok := piArguments(args)
			if !ok || mode != tc.mode || session != tc.session {
				t.Fatal("mode/session mismatch")
			}
		}
	}
}

func TestPiCommandPreservesNativeOptions(t *testing.T) {
	process := PiProcess{Args: []string{"pi", "--provider", "p", "--model", "m", "--name", "a b", "--mode", "rpc", "--session", "old", "--session-dir", "/sessions"}}
	want := []string{"pi", "--provider", "p", "--model", "m", "--name", "a b", "--session-dir", "/sessions", "--session", "/exact session.jsonl"}
	if got := PiCommand(process, "tui", "/exact session.jsonl"); !reflect.DeepEqual(got, want) {
		t.Fatalf("native TUI command=%q", got)
	}
	want = append(want[:len(want)-2], "--mode", "rpc", "--session", "new-id")
	if got := PiCommand(process, "rpc", "new-id"); !reflect.DeepEqual(got, want) {
		t.Fatalf("native RPC command=%q", got)
	}
}
