package bridge

import (
	"context"
	"fmt"
	"strings"
)

// CreateWindow creates one detached tmux window in session using commandArgs.
// The command is assembled with shell quoting because tmux accepts the shell
// command as one argument after new-window's options. The returned pane id is
// the exact new pane identity. Window rename protection is applied immediately
// after creation so providers without a native naming option cannot replace
// the requested name through automatic-rename or allow-rename.
//
// No cleanup is attempted after creation: once tmux accepted new-window, the
// new pane belongs to the user's fleet even if a later option update fails.
func CreateWindow(ctx context.Context, socket, session, cwd, name string, commandArgs []string) (string, error) {
	if strings.TrimSpace(session) == "" || strings.TrimSpace(cwd) == "" || name == "" || len(commandArgs) == 0 {
		return "", fmt.Errorf("tmux: invalid create-window arguments")
	}
	quoted := make([]string, len(commandArgs))
	for i, arg := range commandArgs {
		quoted[i] = shellQuote(arg)
	}
	command := strings.Join(quoted, " ")
	out, err := runTmux(ctx, socket, defaultTimeout,
		"new-window", "-d", "-P", "-F", "#{pane_id}", "-t", session,
		"-c", cwd, "-n", name, command)
	if err != nil {
		return "", err
	}
	paneID := strings.TrimSpace(string(out))
	if len(paneID) < 2 || paneID[0] != '%' {
		return "", fmt.Errorf("tmux: create-window returned invalid pane id")
	}
	if _, err := runTmux(ctx, socket, defaultTimeout, "set-option", "-w", "-t", paneID, "automatic-rename", "off"); err != nil {
		return "", err
	}
	if _, err := runTmux(ctx, socket, defaultTimeout, "set-option", "-w", "-t", paneID, "allow-rename", "off"); err != nil {
		return "", err
	}
	return paneID, nil
}
