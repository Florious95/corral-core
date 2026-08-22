package provider

import (
	"path/filepath"
	"testing"
)

func TestProviderWhitelistFiveComms(t *testing.T) {
	rows, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 6 {
		t.Fatalf("whitelist rows=%d, want 6", len(rows))
	}
	want := map[string]string{
		"claude":       "claude_code",
		"codex":        "codex",
		"copilot":      "copilot",
		"grok":         "grok",
		"cursor-agent": "cursor",
		"pi":           "pi",
	}
	for comm, id := range want {
		e, ok := Lookup(comm)
		if !ok {
			t.Fatalf("comm %q absent from whitelist", comm)
		}
		if e.ID != id {
			t.Fatalf("comm %q id=%q want=%q", comm, e.ID, id)
		}
	}
}

func TestProviderWhitelistBasenameFullPath(t *testing.T) {
	full := "/opt/homebrew/Cellar/node/24.1.0/bin/codex"
	if full == "codex" {
		t.Fatal("fixture broken: whole-string equal to basename")
	}
	if filepath.Base(full) != "codex" {
		t.Fatalf("basename(%q)=%q, want codex", full, filepath.Base(full))
	}
	if _, ok := byExact(full); ok {
		t.Fatalf("whole-string lookup of %q must miss (A-wl-basename red on exact)", full)
	}
	e, ok := Lookup(full)
	if !ok || e.ID != "codex" {
		t.Fatalf("basename lookup of %q: ok=%v id=%q, want codex", full, ok, e.ID)
	}
}

func TestProviderWhitelistNoiseComms(t *testing.T) {
	for _, comm := range []string{"bash", "sleep", "vim", "make", "sshd", "node", ""} {
		if e, ok := Lookup(comm); ok {
			t.Fatalf("noise comm %q matched provider %q (must not be a node)", comm, e.ID)
		}
	}
}

func TestProviderWhitelistNoBareNodeRow(t *testing.T) {
	rows, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range rows {
		if e.Comm == "node" {
			t.Fatal("TSV must not whitelist basename node")
		}
	}
}

func TestProviderWhitelistCursorPathSegment(t *testing.T) {
	full := "/Users/alauda/.local/share/cursor-agent/versions/2026.08.11-e8db854/node"
	if filepath.Base(full) != "node" {
		t.Fatalf("fixture broken: basename(%q)=%q, want node", full, filepath.Base(full))
	}
	e, ok := Lookup(full)
	if !ok || e.ID != "cursor" {
		t.Fatalf("path-segment lookup of %q: ok=%v id=%q, want cursor", full, ok, e.ID)
	}
	if _, ok := Lookup("/Users/alauda/.nvm/versions/node/v20.0.0/bin/node"); ok {
		t.Fatal("generic node path must not match cursor")
	}
}

func byExact(comm string) (Entry, bool) {
	if _, err := Load(); err != nil {
		return Entry{}, false
	}
	e, ok := byComm[comm]
	return e, ok
}
