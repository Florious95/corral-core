# Native Pi conversations

The daemon bridges official Pi processes already running in tmux. There is no
private pane launcher, agent supervisor, filesystem RPC socket or required
Corral-specific Pi argument.

## Launch and resume

Use literal official commands in tmux's native launch argument:

```sh
tmux new-window -t my-session -c /my/workspace 'pi --mode rpc'
tmux new-window -t my-session -c /my/workspace 'pi --mode rpc --session <uuid-or-file>'
tmux new-window -t my-session -c /my/workspace 'pi --session <uuid-or-file>'
```

RPC panes are discovered passively and advertised with `conversation=true`.
A GUI subscription joins a daemon-local bridge. The existing `conversation_v1`
WebSocket protocol, projections, history limits and resume cursors are preserved.
Native `get_messages` rehydrates a bridge after daemon restart; terminal text is
never parsed into a conversation. Attaching a terminal shows official Pi's JSONL
stream, rather than a private human-readable surrogate.

Pi can overwrite its OS argv through `process.title`. In that case the daemon
uses tmux's `pane_start_command`, bound to the actual foreground Pi process.
Only literal native commands are accepted; shell scripts, substitutions and
pipelines are not evaluated. Pi manually started inside a preexisting interactive
shell with erased argv is **unknown**, not probed by sending JSON into a possible
TUI. This fail-closed boundary is deliberate.

## Transparent I/O and shutdown

The output stream shares the existing tmux `pipe-pane` fan-out with terminal
subscribers. Input uses native tmux buffer injection, without bracketed-paste
control sequences. Canonical POSIX tty input silently truncates long JSON, so an
attached, confirmed RPC tty temporarily disables only canonical input and echo.
Signal handling and output processing remain intact. Detaching the bridge restores
the original tty state only if the same native process still owns it; a replacement
TUI's terminal setup is not overwritten. Daemon shutdown does not kill Pi.

## Switching modes

`switch_mode` is a daemon operation, never a command sent to Pi. The daemon:

1. Checks the exact foreground process and native run state. Missing state fields
   are unknown, not idle. Work in flight requires explicit `force`, followed by
   confirmed queue clearing/abort.
2. Reads the current session identity through native Pi metadata.
3. Gracefully stops that exact Pi process and launches the official native command
   in the same pane/workspace. Other panes are untouched.
4. Uses the exact durable session file with official `--session`. For a pristine
   session whose file has not yet been created, official `--session-id` preserves
   the UUID without fabricating a session file.
5. Rehydrates native history and confirms the UUID when returning to RPC.

While the native TUI owns the tty, structured prompts fail visibly. The daemon
cannot query an interactive Pi's in-memory `/new` or `/resume` selection through
RPC. A newly written sibling session makes the current session identity ambiguous;
it refuses the switch rather than guessing the newest file or silently resuming
stale context. Other panes writing in that same session directory can therefore
also produce a conservative refusal.

## Bounds and compatibility

- One bridge per pane; at most 64 bridges per daemon.
- Existing 32 MiB record, 8 MiB / 4,000-record replay and 256-record client queue
  limits remain; loss disconnects explicitly instead of returning corrupt history.
- No bridge-specific polling loop. Discovery uses the existing listing cadence.
- Slow-client loss and native process loss remain visible through the existing
  conversation closed/reset protocol.
- Android/desktop clients need no private launcher parameter or new protocol.

Live development coverage uses a project-local isolated tmux socket and official
Pi, without making a cloud prompt:

```sh
ISSUE56_NATIVE_ROOT=/short/project-local/path \
  go test -count=1 -race -run '^TestNativeBridgeRealPiLifecycle$' ./internal/guirpc
```

The lifecycle test covers long JSON, durable history, both native modes, command
rejection in TUI, an untouched empty session's UUID, bridge shutdown and reattach.
Independent acceptance additionally owns WebSocket listing/subscribe and client
rendering checks; the lifecycle test alone is not a UI acceptance claim.
