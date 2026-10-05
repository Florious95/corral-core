package api

import (
	"context"
	"os"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/guirpc"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// agentLauncher is an explicit provider adapter. command intentionally stays
// a bare executable name: tmux resolves it at launch through the server's PATH,
// allowing user-provided wrappers to participate in normal lookup order.
type agentLauncher struct {
	protocol.AgentLauncher
	command    string
	nameArgs   func(string) []string
	bypassArgs []string
}

func availableAgentLaunchers() []agentLauncher {
	candidates := []agentLauncher{
		{
			AgentLauncher: protocol.AgentLauncher{Provider: "pi", DisplayName: "Pi Coding Agent", SupportsBypass: false, Naming: "tmux"},
			command:       "pi",
		},
		{
			AgentLauncher: protocol.AgentLauncher{Provider: "codex", DisplayName: "Codex CLI", SupportsBypass: true, Naming: "tmux"},
			command:       "codex",
			bypassArgs:    []string{"--dangerously-bypass-approvals-and-sandbox"},
		},
		{
			AgentLauncher: protocol.AgentLauncher{Provider: "cursor", DisplayName: "Cursor Agent", SupportsBypass: true, Naming: "tmux"},
			command:       "agent",
			bypassArgs:    []string{"--force"},
		},
		{
			AgentLauncher: protocol.AgentLauncher{Provider: "grok", DisplayName: "Grok", SupportsBypass: true, Naming: "tmux"},
			command:       "grok",
			bypassArgs:    []string{"--always-approve"},
		},
	}
	// Do not resolve these names eagerly. Keeping the bare command means each
	// tmux-created pane follows the user's PATH and wrapper scripts exactly when
	// it starts.
	return candidates
}

func (l agentLauncher) protocolValue() protocol.AgentLauncher { return l.AgentLauncher }

func (s *Server) launcher(provider string) (agentLauncher, bool) {
	for _, launcher := range s.agentLaunchers {
		if launcher.Provider == provider {
			return launcher, true
		}
	}
	return agentLauncher{}, false
}

func validateAgentName(name string) bool {
	if strings.TrimSpace(name) == "" || !utf8.ValidString(name) || utf8.RuneCountInString(name) > 64 {
		return false
	}
	for _, r := range name {
		if unicode.IsControl(r) {
			return false
		}
	}
	return true
}

func (s *Server) agentLauncherValues() []protocol.AgentLauncher {
	out := make([]protocol.AgentLauncher, len(s.agentLaunchers))
	for i, launcher := range s.agentLaunchers {
		out[i] = launcher.protocolValue()
	}
	return out
}

func agentCommand(launcher agentLauncher, name string, bypass bool) []string {
	args := []string{launcher.command}
	if launcher.Naming == "cli" && launcher.nameArgs != nil {
		args = append(args, launcher.nameArgs(name)...)
	}
	if bypass {
		args = append(args, launcher.bypassArgs...)
	}
	return args
}

func createAgentResult(reqID uint32, ok bool, ref, name, naming string, reason protocol.CreateAgentReason) protocol.CreateAgentResult {
	return protocol.CreateAgentResult{ReqID: reqID, OK: ok, Ref: ref, Name: name, Naming: naming, Reason: string(reason)}
}

// handleCreateAgent resolves the exact anchor pane, applies the selected
// provider adapter, and creates one child window in that pane's tmux session.
// All semantic failures use a typed create_agent_result and expose no command
// output or environment details.
func (c *wsConn) handleCreateAgent(req protocol.CreateAgent) {
	result := c.s.createAgent(c.ctx, req, false)
	c.send(&result)
}

// createAgent is shared by create_agent and conversation_create. A structured
// launch runs this daemon's guirpc worker in the new pane instead of the
// provider TUI and waits until the worker serves its private socket.
func (s *Server) createAgent(ctx context.Context, req protocol.CreateAgent, structured bool) protocol.CreateAgentResult {
	fail := func(reason protocol.CreateAgentReason) protocol.CreateAgentResult {
		return createAgentResult(req.ReqID, false, "", "", "", reason)
	}
	if req.Workspace == "" || !validateAgentName(req.Name) || req.Provider == "" || req.AnchorRef == "" {
		return fail(protocol.CreateAgentInvalidField)
	}
	launcher, ok := s.launcher(req.Provider)
	if !ok || (structured && (req.Provider != "pi" || s.guiDir == "")) {
		return fail(protocol.CreateAgentProviderUnavailable)
	}
	if req.Bypass && !launcher.SupportsBypass {
		return fail(protocol.CreateAgentUnsupportedBypass)
	}

	entry := s.catalogEntry(req.AnchorRef)
	if entry == nil || entry.ref != req.AnchorRef || entry.pane.CWD != req.Workspace || entry.pane.Socket == "" || entry.pane.Session == "" {
		return fail(protocol.CreateAgentTargetNotFound)
	}

	args := agentCommand(launcher, req.Name, req.Bypass)
	if structured {
		executable, err := os.Executable()
		if err != nil {
			return fail(protocol.CreateAgentLaunchFailed)
		}
		args = []string{executable, "gui-worker", s.guiDir, req.Name}
	}
	paneID, err := bridge.CreateWindow(ctx, entry.pane.Socket, entry.pane.Session, entry.pane.CWD, req.Name, args)
	if err != nil {
		s.log.Debug("ws: create agent", "structured", structured, "err", err)
		return fail(protocol.CreateAgentLaunchFailed)
	}
	ref := entry.pane.Socket + "\x1f" + paneID
	if structured {
		if err := bridge.RememberManagedCWD(ctx, entry.pane.Socket, paneID, entry.pane.CWD); err != nil {
			_ = bridge.KillPane(entry.pane.Socket, paneID)
			return fail(protocol.CreateAgentLaunchFailed)
		}
		startup, cancel := context.WithTimeout(ctx, structuredStartupTimeout)
		err := guirpc.WaitReady(startup, s.guiDir, ref)
		cancel()
		if err != nil {
			s.log.Warn("ws: structured agent not ready", "timeout_ms", structuredStartupTimeout.Milliseconds(), "err", err)
			_ = bridge.KillPane(entry.pane.Socket, paneID)
			return fail(protocol.CreateAgentLaunchFailed)
		}
	}
	// The scan coordinator is already the sole owner of catalog publication and
	// fan-out. Wake it instead of scanning inline or maintaining a second path.
	s.scans.cadence()
	return createAgentResult(req.ReqID, true, ref, req.Name, launcher.Naming, "")
}

// structuredStartupTimeout bounds how long create waits for the worker socket.
const structuredStartupTimeout = 5 * time.Second
