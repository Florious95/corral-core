package discovery

import (
	"bytes"
	"context"
	"fmt"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// TestDiscoverScanFilterCommandBoundary is the executable command-boundary
// contract: every candidate is a local UNIX listener, while all current-user
// sockets, including a Team-agent ta-* socket, may reach the PATH-fronted tmux
// spy and foreign/explicitly isolated paths do not.
func TestDiscoverScanFilterCommandBoundary(t *testing.T) {
	fixtureRoot := os.Getenv("SCAN_FILTER_FIXTURE_ROOT")
	if fixtureRoot == "" {
		// Keep ordinary package-wide runs self-contained; the ledger acceptance
		// injects this same path explicitly so its outer guard can inspect it.
		fixtureRoot = filepath.Join("..", "..", "..", ".team", "nodes", "scan-filter-impl", "tmp")
	}
	if err := os.MkdirAll(fixtureRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	var err error
	fixtureRoot, err = filepath.Abs(fixtureRoot)
	if err != nil {
		t.Fatal(err)
	}
	runRoot, err := os.MkdirTemp(fixtureRoot, "r-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(runRoot) })
	oldWD, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(runRoot); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chdir(oldWD) })

	uidDir := "tmux-" + strconv.Itoa(os.Getuid())
	otherUIDDir := "tmux-" + strconv.Itoa(os.Getuid()+1)
	for _, dir := range []string{uidDir, otherUIDDir} {
		if err := os.Mkdir(dir, 0o700); err != nil {
			t.Fatal(err)
		}
	}

	listen := func(path string) net.Listener {
		t.Helper()
		listener, err := net.Listen("unix", path)
		if err != nil {
			t.Fatalf("listen %s: %v", path, err)
		}
		t.Cleanup(func() {
			_ = listener.Close()
			_ = os.Remove(path)
		})
		return listener
	}

	defaultSocket := filepath.Join(uidDir, "default")
	tmuxSocket := filepath.Join(uidDir, "focus")
	taSocket := filepath.Join(uidDir, "ta-team")
	isolatedSocket := filepath.Join(uidDir, "e2e-isolated")
	namedSocket := filepath.Join(uidDir, "mystery")
	otherUIDSocket := filepath.Join(otherUIDDir, "default")
	for _, path := range []string{defaultSocket, tmuxSocket, taSocket, isolatedSocket, namedSocket, otherUIDSocket} {
		listen(path)
	}

	spyDir := filepath.Join(runRoot, "s")
	if err := os.Mkdir(spyDir, 0o700); err != nil {
		t.Fatal(err)
	}
	spyLog := filepath.Join(spyDir, "argv.log")
	spy := filepath.Join(spyDir, "tmux")
	spyScript := `#!/bin/sh
log=${SCAN_FILTER_SPY_LOG:?}
socket=
argv=tmux
while [ "$#" -gt 0 ]; do
  argv="$argv	$1"
  if [ "$1" = "-S" ] && [ "$#" -ge 2 ]; then socket=$2; fi
  shift
done
printf '%s\n' "$argv" >> "$log"
case "$socket" in
  tmux-*/default) printf '%s\n' 'agent-default|0|%0|/scan/default|claude|100|agent-default|80x24|default'; exit 0;;
  tmux-*/focus) printf '%s\n' 'agent-focus|0|%1|/scan/focus|claude|101|agent-focus|80x24|focus'; exit 0;;
  tmux-*/mystery) printf '%s\n' 'agent-mystery|0|%2|/scan/mystery|claude|102|agent-mystery|80x24|mystery'; exit 0;;
  tmux-*/ta-team) printf '%s\n' 'agent-ta|0|%3|/scan/ta-team|claude|103|agent-ta|80x24|ta-team'; exit 0;;
esac
exit 97
`
	if err := os.WriteFile(spy, []byte(spyScript), 0o700); err != nil {
		t.Fatal(err)
	}
	oldPath := os.Getenv("PATH")
	oldTMUX, hadTMUX := os.LookupEnv("TMUX")
	t.Setenv("PATH", spyDir+string(os.PathListSeparator)+oldPath)
	t.Setenv("SCAN_FILTER_SPY_LOG", spyLog)
	t.Setenv("TMUX", tmuxSocket+",123,0")
	if hadTMUX {
		t.Cleanup(func() { _ = os.Setenv("TMUX", oldTMUX) })
	} else {
		t.Cleanup(func() { _ = os.Unsetenv("TMUX") })
	}

	var logs bytes.Buffer
	logger := slog.New(slog.NewTextHandler(&logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	model, err := DiscoverWithDirs(context.Background(), logger, []string{uidDir, otherUIDDir})
	if err != nil {
		t.Fatalf("DiscoverWithDirs: %v", err)
	}
	if model == nil || len(model.Workspaces) != 4 {
		t.Fatalf("current-user sockets did not produce four workspaces: %+v", model)
	}

	spyBytes, err := os.ReadFile(spyLog)
	if err != nil {
		t.Fatal(err)
	}
	spyOutput := string(spyBytes)
	countCalls := func(socket string) int {
		needle := "\t-S\t" + socket + "\t"
		count := 0
		for _, line := range strings.Split(spyOutput, "\n") {
			if strings.Contains(line, needle) && strings.Contains(line, "\tlist-panes\t") {
				count++
			}
		}
		return count
	}
	if got := countCalls(defaultSocket); got != 1 {
		t.Fatalf("default list-panes calls = %d, want 1; argv=%q", got, spyOutput)
	}
	if got := countCalls(tmuxSocket); got != 1 {
		t.Fatalf("TMUX socket list-panes calls = %d, want 1; argv=%q", got, spyOutput)
	}
	if got := countCalls(taSocket); got != 1 {
		t.Fatalf("ta list-panes calls = %d, want 1; argv=%q", got, spyOutput)
	}
	for label, socket := range map[string]string{
		"isolated":  isolatedSocket,
		"other_uid": otherUIDDir,
	} {
		if got := countCalls(socket); got != 0 {
			t.Fatalf("%s list-panes calls = %d, want 0; argv=%q", label, got, spyOutput)
		}
	}
	if got := countCalls(namedSocket); got != 1 {
		t.Fatalf("named socket list-panes calls = %d, want 1; argv=%q", got, spyOutput)
	}
	if got := len(strings.Split(strings.TrimSpace(spyOutput), "\n")); got != 4 {
		t.Fatalf("spy argv records = %d, want 4; argv=%q", got, spyOutput)
	}

	for label, socket := range map[string]string{
		"isolated":  isolatedSocket,
		"other_uid": otherUIDDir,
	} {
		found := false
		for _, line := range strings.Split(logs.String(), "\n") {
			if strings.Contains(line, "path="+socket) && strings.Contains(line, "classification=") && strings.Contains(line, "action=skip") {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("%s skip log lacks path/classification/action operands: %s", label, logs.String())
		}
	}
	if !strings.Contains(logs.String(), "path="+namedSocket) || !strings.Contains(logs.String(), "classification=user_socket") || !strings.Contains(logs.String(), "action=allow") {
		t.Fatalf("named socket allow log lacks path/classification/action operands: %s", logs.String())
	}
	if !strings.Contains(logs.String(), "path="+taSocket) || !strings.Contains(logs.String(), "classification=user_socket") || !strings.Contains(logs.String(), "action=allow") {
		t.Fatalf("Team socket allow log lacks path/classification/action operands: %s", logs.String())
	}

	userAgents := 0
	seenSockets := make(map[string]bool)
	for _, ws := range model.Workspaces {
		for _, pane := range ws.Panes {
			if pane.Command == "claude" {
				userAgents++
			}
			seenSockets[pane.Socket] = true
		}
	}
	if userAgents != 4 {
		t.Fatalf("user agents found = %d, want 4; model=%+v", userAgents, model)
	}
	if !seenSockets[defaultSocket] || !seenSockets[tmuxSocket] || !seenSockets[taSocket] || !seenSockets[namedSocket] {
		t.Fatalf("allowed socket identity was not preserved: %v", seenSockets)
	}

	fmt.Println("SCAN_FILTER_EVIDENCE default_list_panes=1 tmux_env_list_panes=1 named_list_panes=1 ta_list_panes=1 isolated_list_panes=0 other_uid_list_panes=0 user_agents_found=4 spy_argv_recorded=true")
	fmt.Println("SCAN_FILTER_CLASSIFICATION_EVIDENCE ta=allow isolated=skip named=allow other_uid=skip path_operand=true classification_operand=true")
}
