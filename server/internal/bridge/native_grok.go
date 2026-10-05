package bridge

// Grok uses its official ACP stdio command; recognition never trial-writes to
// a TUI or evaluates a shell program. Unknown options/subcommands fail closed.
// @contract
// @pre argv belongs to a verified foreground native process
// @post only native grok TUI or agent stdio is identified
// @err ambiguous/unsupported launch syntax is not a capability
// @inv argv remains host-private; no wrapper or private executable is added

import (
	"errors"
	"path/filepath"
	"strings"
)

func agentArguments(args []string) (provider, mode, session string, ok bool) {
	if mode, session, ok = piArguments(args); ok {
		return "pi", mode, session, true
	}
	if mode, session, ok = grokArguments(args); ok {
		return "grok", mode, session, true
	}
	return "", "", "", false
}

func grokArguments(args []string) (mode, session string, ok bool) {
	if len(args) == 0 || filepath.Base(args[0]) != "grok" {
		return
	}
	var positional []string
	for i := 1; i < len(args); i++ {
		arg, value, hasValue := strings.Cut(args[i], "=")
		switch arg {
		case "--resume", "-r":
			if !hasValue && i+1 < len(args) && !strings.HasPrefix(args[i+1], "-") && args[i+1] != "agent" {
				i++
				value = args[i]
			}
			session = value
		case "--agent", "--agents", "--allow", "--allowedTools", "--cwd", "--debug-file", "--deny", "--disallowed-tools", "--json-schema", "--leader-socket", "--model", "-m", "--max-turns", "--permission-mode", "--prompt-file", "--prompt-json", "--reasoning-effort", "--effort", "--rules", "--session-id", "-s", "--sandbox", "--system-prompt-override", "--tools", "--worktree-ref", "--ref", "--agent-profile", "--plugin-dir", "--grok-ws-origin", "--grok-ws-url", "--cli-chat-proxy-base-url", "--xai-api-base-url":
			if !hasValue {
				if i+1 >= len(args) {
					return "", "", false
				}
				i++
			}
		case "--always-approve", "--continue", "-c", "--debug", "--disable-web-search", "--fork-session", "--fullscreen", "--include-partial-messages", "--minimal", "--no-alt-screen", "--no-plan", "--no-subagents", "--oauth", "--restore-code", "--verbatim", "--reauth", "--leader", "--no-leader":
		default:
			if strings.HasPrefix(arg, "-") {
				return "", "", false
			}
			positional = append(positional, args[i])
		}
	}
	if len(positional) == 2 && positional[0] == "agent" && positional[1] == "stdio" {
		return "rpc", session, true
	}
	if len(positional) > 1 {
		return "", "", false
	}
	if len(positional) == 1 {
		switch positional[0] {
		case "agent", "clone", "completions", "cursor-worker", "dashboard", "doctor", "du", "disk-usage", "export", "help", "inspect", "leader", "login", "logout", "mcp", "memory", "models", "plugin", "sessions", "setup", "trace", "update", "usage", "version", "v", "worktree", "wrap":
			return "", "", false
		}
	}
	return "tui", session, true
}

// GrokCommand carries shared native configuration into the target mode. It
// refuses unsupported mode-specific options before stopping the current agent.
func GrokCommand(process NativeProcess, mode, session string) ([]string, error) {
	if len(process.Args) == 0 || (mode == "tui" && session == "") {
		return nil, errors.New("Grok session identity unavailable")
	}
	args := []string{process.Args[0]}
	for i := 1; i < len(process.Args); i++ {
		arg, _, equal := strings.Cut(process.Args[i], "=")
		switch arg {
		case "agent", "stdio", "--continue", "-c":
		case "--resume", "-r":
			if !equal && i+1 < len(process.Args) && !strings.HasPrefix(process.Args[i+1], "-") && process.Args[i+1] != "agent" {
				i++
			}
		case "--model", "-m", "--reasoning-effort", "--effort", "--leader-socket":
			args = append(args, process.Args[i])
			if !equal {
				if i+1 >= len(process.Args) {
					return nil, errors.New("incomplete Grok launch configuration")
				}
				i++
				args = append(args, process.Args[i])
			}
		case "--always-approve", "--leader", "--no-leader":
			args = append(args, process.Args[i])
		default:
			return nil, errors.New("Grok mode-specific launch configuration cannot be preserved safely")
		}
	}
	if mode == "rpc" {
		// Pager top-level flags cannot precede the agent subcommand. ACP
		// resume uses session/load, not the pager-only resume option.
		return append(append([]string{args[0], "agent"}, args[1:]...), "stdio"), nil
	}
	return append(args, "--resume", session), nil
}
