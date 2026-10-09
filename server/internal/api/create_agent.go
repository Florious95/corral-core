package api

import (
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/agentmirror/agentmirror/internal/bridge"
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
	fail := func(reason protocol.CreateAgentReason) {
		result := createAgentResult(req.ReqID, false, "", "", "", reason)
		c.send(&result)
	}
	if req.Workspace == "" || !validateAgentName(req.Name) || req.Provider == "" || req.AnchorRef == "" {
		fail(protocol.CreateAgentInvalidField)
		return
	}
	launcher, ok := c.s.launcher(req.Provider)
	if !ok {
		fail(protocol.CreateAgentProviderUnavailable)
		return
	}
	if req.Bypass && !launcher.SupportsBypass {
		fail(protocol.CreateAgentUnsupportedBypass)
		return
	}

	entry := c.s.catalogEntry(req.AnchorRef)
	if entry == nil || entry.ref != req.AnchorRef || entry.pane.CWD != req.Workspace || entry.pane.Socket == "" || entry.pane.Session == "" {
		fail(protocol.CreateAgentTargetNotFound)
		return
	}

	args := agentCommand(launcher, req.Name, req.Bypass)
	paneID, err := bridge.CreateWindow(c.ctx, entry.pane.Socket, entry.pane.Session, entry.pane.CWD, req.Name, args)
	if err != nil {
		c.logErr("create agent", err)
		fail(protocol.CreateAgentLaunchFailed)
		return
	}
	// The scan coordinator is already the sole owner of catalog publication and
	// fan-out. Wake it instead of scanning inline or maintaining a second path.
	c.s.scans.cadence()
	ref := entry.pane.Socket + "\x1f" + paneID
	result := createAgentResult(req.ReqID, true, ref, req.Name, launcher.Naming, "")
	c.send(&result)
}
