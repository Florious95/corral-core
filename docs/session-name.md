# Unified session naming

Serve computes one `Session.name` for every Agent pane. Clients display that
field and do not re-select a name by Provider.

The algorithm lives only in `internal/sessionname`. `listing.go` `displayName`
delegates to it; listing, `list_delta`, and Level2 share `sessionFromPane`.

**Effective non-project window name > effective non-project title segment > project basename > `未命名会话`.**

- Left: `#{window_name}` as one candidate. Never split on `-` or `|`.
- Right: clean `#{pane_title}`, then split on `|` (always) and on `-` only when both sides are whitespace.
- Filter complete matches of the shared noise table (`tmux`, `node`, `zsh`, `π`, `claude_code`, …), the current foreground command basename, empty/separator-only strings, and collector decorations (`[tmux]`, `node!`).
- Demote (do not delete) candidates that exactly match the cwd basename or the trimmed cwd; they lose to a real task name.
- Inputs are the already-scanned tmux fields. No Provider argument, no `/rename`, no process walk, no Provider-private index.

Native `session_name` remains on the wire for compatibility and never overrides `name`.
