package bridge

// NativeAgent observes an actual foreground CLI, never terminal text. Argv stays on the host, in memory, and is never a diagnostic.
// @contract
// @pre the caller supplies one discovered, scoped tmux pane
// @post only a unique supported foreground agent returns an identity and mode
// @err unknown/ambiguous identity is not a capability
// @inv inspection does not alter the pane or launch an agent

import (
	"bytes"
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"golang.org/x/sys/unix"
)

// NativeProcess binds native launch metadata to a foreground process and pane.
// Args are host-private input to a deliberate switch, never protocol payload.
type NativeProcess struct {
	PID, RootPID, Group int
	TTY, Mode, Session  string
	Provider, Started   string
	Args                []string
}

// PiProcess preserves the existing Pi call boundary while native I/O is shared.
type PiProcess = NativeProcess

func piArguments(args []string) (mode, session string, ok bool) {
	if len(args) == 0 {
		return
	}
	i := 1
	switch filepath.Base(args[0]) {
	case "pi":
	case "node", "bun":
		if len(args) < 2 {
			return
		}
		entry := args[1]
		if resolved, err := filepath.EvalSymlinks(entry); err == nil {
			entry = resolved
		}
		if !strings.Contains(filepath.ToSlash(entry), "pi-coding-agent/") || (filepath.Base(entry) != "cli.js" && filepath.Base(entry) != "rpc-entry.js") {
			return
		}
		i = 2
	default:
		return
	}
	mode, ok = "tui", true
	for ; i < len(args); i++ {
		switch args[i] {
		case "--mode":
			if i+1 >= len(args) {
				return "", "", false
			}
			i++
			mode = args[i]
		case "--session", "--session-id":
			if i+1 >= len(args) {
				return "", "", false
			}
			i++
			session = args[i]
		default:
			if strings.HasPrefix(args[i], "--mode=") {
				mode = strings.TrimPrefix(args[i], "--mode=")
			}
			if strings.HasPrefix(args[i], "--session=") {
				session = strings.TrimPrefix(args[i], "--session=")
			}
			if strings.HasPrefix(args[i], "--session-id=") {
				session = strings.TrimPrefix(args[i], "--session-id=")
			}
		}
	}
	return mode, session, mode == "rpc" || mode == "tui" || mode == "interactive"
}

// nativeStartArguments accepts only a literal native launch, not shell
// programs, substitutions or pipelines. It parses quotes without evaluation.
func nativeStartArguments(command string) ([]string, error) {
	args, err := literalArguments(command)
	if err != nil {
		return nil, err
	}
	// tmux serializes a single shell-command argument as one quoted string.
	// Decode that argv layer, then parse its literal native command once.
	if len(args) == 1 {
		args, err = literalArguments(args[0])
		if err != nil {
			return nil, err
		}
	}
	if len(args) > 0 && args[0] == "exec" {
		args = args[1:]
	}
	if _, _, _, ok := agentArguments(args); !ok {
		return nil, errors.New("not a direct native agent launch")
	}
	return args, nil
}

func literalArguments(command string) ([]string, error) {
	var args []string
	var token strings.Builder
	var quote rune
	active, escape := false, false
	for _, r := range command {
		if escape {
			token.WriteRune(r)
			active = true
			escape = false
			continue
		}
		if r == '\\' && quote != '\'' {
			escape = true
			active = true
			continue
		}
		if quote != 0 {
			if r == quote {
				quote = 0
			} else {
				if quote == '"' && (r == '$' || r == '`') {
					return nil, errors.New("nonliteral native launch")
				}
				token.WriteRune(r)
			}
			continue
		}
		if r == '\'' || r == '"' {
			quote = r
			active = true
			continue
		}
		if r == ' ' || r == '\t' {
			if active {
				args = append(args, token.String())
				token.Reset()
				active = false
			}
			continue
		}
		if strings.ContainsRune("$`;&|()<>\n\r", r) {
			return nil, errors.New("nonliteral native launch")
		}
		token.WriteRune(r)
		active = true
	}
	if quote != 0 || escape {
		return nil, errors.New("incomplete native launch")
	}
	if active {
		args = append(args, token.String())
	}
	return args, nil
}

func (p *Pane) NativePi(ctx context.Context) (PiProcess, error) {
	process, err := p.NativeAgent(ctx)
	if err == nil && process.Provider != "pi" {
		return PiProcess{}, errors.New("native foreground is not Pi")
	}
	return process, err
}

// NativeAgent verifies one supported foreground native CLI without probing it.
func (p *Pane) NativeAgent(ctx context.Context) (NativeProcess, error) {
	var found NativeProcess
	out, err := runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{pane_pid}|#{pane_tty}|#{pane_dead}")
	if err != nil {
		return found, err
	}
	parts := strings.Split(strings.TrimSpace(string(out)), "|")
	if len(parts) != 3 || parts[2] != "0" {
		return found, errors.New("native agent pane is not live")
	}
	root, err := strconv.Atoi(parts[0])
	if err != nil || root <= 1 {
		return found, errors.New("native agent root unavailable")
	}
	tty := parts[1]
	probeCtx, cancel := context.WithTimeout(ctx, p.timeout)
	defer cancel()
	// Only structural metadata is collected for the tty; argv is subsequently
	// read for the unique foreground process, not for unrelated host processes.
	out, err = exec.CommandContext(probeCtx, "ps", "-t", strings.TrimPrefix(tty, "/dev/"), "-o", "pid=,ppid=,pgid=,tpgid=,comm=").Output()
	if err != nil {
		return found, errors.New("native agent process inspection failed")
	}
	type row struct{ pid, parent, group, foreground int }
	parents := map[int]int{}
	var candidates []row
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 5 {
			continue
		}
		var r row
		r.pid, _ = strconv.Atoi(fields[0])
		r.parent, _ = strconv.Atoi(fields[1])
		r.group, _ = strconv.Atoi(fields[2])
		r.foreground, _ = strconv.Atoi(fields[3])
		parents[r.pid] = r.parent
		if r.group > 1 && r.group == r.foreground {
			candidates = append(candidates, r)
		}
	}
	for _, r := range candidates {
		ancestor := r.pid
		for steps := 0; ancestor > 1 && ancestor != root && steps < len(parents); steps++ {
			ancestor = parents[ancestor]
		}
		if ancestor != root {
			continue
		}
		args, err := processArguments(r.pid)
		if err != nil {
			continue
		}
		// Node's process.title='pi' overwrites argv, including native mode. For
		// tmux direct launches the immutable start command is the authoritative
		// native launch metadata. A shell-entered command has no such proof.
		if len(args) > 0 && (args[0] == "pi" || args[0] == "pi-rpc") {
			erased := true
			for _, arg := range args[1:] {
				if arg != "" {
					erased = false
					break
				}
			}
			if erased {
				start, startErr := runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{pane_start_command}")
				if startErr != nil {
					continue
				}
				args, startErr = nativeStartArguments(strings.TrimSpace(string(start)))
				if startErr != nil {
					continue
				}
				if provider, _, _, _ := agentArguments(args); provider != "pi" {
					continue // erased Pi argv cannot prove another provider
				}
			}
		}
		provider, mode, session, ok := agentArguments(args)
		if !ok {
			continue
		}
		if found.PID != 0 {
			return NativeProcess{}, errors.New("native agent foreground is ambiguous")
		}
		if mode == "interactive" {
			mode = "tui"
		}
		started, stampErr := processStamp(r.pid)
		if stampErr != nil {
			continue
		}
		found = NativeProcess{PID: r.pid, RootPID: root, Group: r.group, TTY: tty, Mode: mode, Session: session, Args: args, Provider: provider, Started: started}
	}
	if found.PID == 0 {
		return found, errors.New("no supported native foreground agent")
	}
	return found, nil
}

// PiCommand preserves native configuration, changing only mode and the exact
// session selected by Pi itself. It never adds a daemon/private executable.
func PiCommand(process PiProcess, mode, session string) []string {
	args := make([]string, 0, len(process.Args)+4)
	for i := 0; i < len(process.Args); i++ {
		arg := process.Args[i]
		if arg == "--mode" || arg == "--session" || arg == "--session-id" {
			i++
			continue
		}
		if strings.HasPrefix(arg, "--mode=") || strings.HasPrefix(arg, "--session=") || strings.HasPrefix(arg, "--session-id=") || arg == "--no-session" || arg == "--continue" || arg == "-c" || arg == "--resume" || arg == "-r" {
			continue
		}
		args = append(args, arg)
	}
	if mode == "rpc" {
		args = append(args, "--mode", "rpc")
	}
	return append(args, "--session", session)
}

// RPCInput temporarily makes the confirmed RPC tty a byte stream. Canonical
// tty input silently truncates long JSON (including ordinary pasted prompts).
// Restore never overwrites a replacement TUI's own terminal setup.
func (p *Pane) RPCInput(ctx context.Context, process NativeProcess) (func([]byte) error, func(), error) {
	current, err := p.NativeAgent(ctx)
	if err != nil || current.PID != process.PID || current.Started != process.Started || current.TTY != process.TTY || current.Provider != process.Provider || current.Mode != "rpc" {
		return nil, nil, errors.New("RPC process changed before attach")
	}
	f, err := os.OpenFile(process.TTY, os.O_RDWR|unix.O_NOCTTY|unix.O_NONBLOCK, 0)
	if err != nil {
		return nil, nil, errors.New("RPC tty open failed")
	}
	restore, err := p.claimRPCTTY(ctx, process, f)
	if err != nil {
		f.Close()
		return nil, nil, err
	}
	write := func(raw []byte) error {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		now, err := p.NativeAgent(ctx)
		if err != nil || now.PID != process.PID || now.Started != process.Started || now.TTY != process.TTY || now.Provider != process.Provider || now.Mode != "rpc" {
			return errors.New("RPC process changed before input")
		}
		return p.pasteBytes(ctx, raw)
	}
	return write, restore, nil
}

func (p *Pane) pasteBytes(ctx context.Context, raw []byte) error {
	name := newBufferName()
	cmd, stderr, derived, cancel := newTmuxCommand(ctx, p.socket, p.timeout, "load-buffer", "-b", name, "-")
	cmd.Stdin = bytes.NewReader(raw)
	err := cmd.Run()
	if err != nil {
		defer cancel()
		if derived.Err() != nil {
			return ErrTmuxTimeout
		}
		return classifyTmuxError(stderr.String())
	}
	cancel()
	defer runTmux(context.Background(), p.socket, p.timeout, "delete-buffer", "-b", name)
	_, err = runTmux(ctx, p.socket, p.timeout, "paste-buffer", "-r", "-d", "-b", name, "-t", p.target)
	return err
}

// ReplaceNativeAgent stops only the verified foreground native agent. A surviving shell
// receives a standard native command; a dead root is respawned in the same pane.
func (p *Pane) ReplaceNativeAgent(ctx context.Context, expected NativeProcess, cwd string, args []string) error {
	current, err := p.NativeAgent(ctx)
	if err != nil || current.PID != expected.PID || current.Started != expected.Started || current.Provider != expected.Provider {
		return errors.New("native agent identity changed before replacement")
	}
	out, err := runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{remain-on-exit}")
	if err != nil {
		return err
	}
	policy := strings.TrimSpace(string(out))
	if _, err = runTmux(ctx, p.socket, p.timeout, "set-option", "-p", "-t", p.target, "remain-on-exit", "on"); err != nil {
		return err
	}
	defer runTmux(context.Background(), p.socket, p.timeout, "set-option", "-p", "-t", p.target, "remain-on-exit", policy)
	if err := RememberManagedCWD(ctx, p.socket, p.target, cwd); err != nil {
		return err
	}
	if err = unix.Kill(expected.PID, unix.SIGTERM); err != nil {
		return errors.New("native agent stop failed")
	}
	deadline := time.NewTimer(8 * time.Second)
	defer deadline.Stop()
	for !errors.Is(unix.Kill(expected.PID, 0), unix.ESRCH) {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return errors.New("native agent did not exit; pane preserved")
		case <-time.After(50 * time.Millisecond):
		}
	}
	quoted := make([]string, len(args))
	for i, arg := range args {
		quoted[i] = shellQuote(arg)
	}
	command := strings.Join(quoted, " ")
	out, err = runTmux(ctx, p.socket, p.timeout, "display-message", "-p", "-t", p.target, "#{pane_pid}|#{pane_dead}|#{pane_current_command}")
	if err != nil {
		return err
	}
	identity := strings.Split(strings.TrimSpace(string(out)), "|")
	if len(identity) != 3 || identity[0] != strconv.Itoa(expected.RootPID) {
		return errors.New("pane changed during replacement")
	}
	if identity[1] == "1" {
		_, err = runTmux(ctx, p.socket, p.timeout, "respawn-pane", "-t", p.target, "-c", cwd, command)
		return err
	}
	// Never inject a command into another live application or a background job.
	switch identity[2] {
	case "bash", "zsh", "sh", "fish", "dash", "ksh":
	default:
		return errors.New("pane foreground is not a shell after native agent exit")
	}
	return p.pasteBytes(ctx, []byte(command+"\n"))
}
