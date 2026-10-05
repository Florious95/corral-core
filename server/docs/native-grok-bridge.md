# Native Grok Build conversations

Grok Build 1.0.46 is bridged through its official ACP / JSON-RPC 2.0 stdio
interface. The pane contains only native Grok; there is no launcher wrapper,
supervisor or private RPC socket. Pi's existing native protocol is unchanged.

## Launch

Specify a literal native command in tmux's launch argument:

```sh
tmux new-window -t my-session -c /my/workspace 'grok agent stdio'
tmux new-window -t my-session -c /my/workspace 'grok'
tmux new-window -t my-session -c /my/workspace 'grok --resume <session-id>'
```

Discovery verifies the actual foreground process, tty and process birth. Both
native modes advertise `provider=grok, conversation=true`. A TUI subscription
reports mode `tui` without writing any JSON into it. Unknown commands and
ambiguous shell programs fail closed, rather than being tested by injection.

Grok has distinct pager and agent argument namespaces. Shared configuration is
preserved where supported; the RPC command places it **after `agent`**, before
`stdio`. For example, isolated tests use `grok agent --no-leader stdio`;
`grok --no-leader agent stdio` is not a valid agent command. Resume in ACP uses
`session/load`, not the pager's CLI resume argument. Unsupported mode-specific
launch options refuse switching before the current process is stopped.

## Protocol projection

The daemon sends official `initialize`, then `session/new` or `session/load`.
Native model/configuration responses supply model choices and reasoning levels;
configuration commands succeed only after the native response confirms the
selected value. No model/effort list is hard-coded into the client.

`session/update` text, thought and tool records become the existing
`conversation_v1` message and tool events. Prompts use native `session/prompt`;
client acceptance requires native stream/queue evidence or a successful native
turn response, not merely a successful tmux write. Busy state comes from native
`x.ai/runningPromptId` and `_x.ai/queue/changed`, not filesystem heuristics.
A response timeout makes work **unknown** and closes the bridge visibly; it
never manufactures a settled/idle event.

Supported commands include state/model/effort/command queries, configuration,
prompt, abort (`session/cancel`), new session, and compact (native `/compact`
prompt). Overlapping prompts and session creation are refused explicitly.
Unsupported Pi queue/steering commands and image prompts fail visibly. Native
permission requests are explicitly cancelled, never silently approved; the GUI
shows a notice. User-selected native approval flags are preserved, not added.

The optional `get_state.data.agentProvider` identifies the CLI independently
of the model API vendor, allowing Android provider-specific shortcuts. Legacy
Pi responses retain their previous behavior. Model summaries omit private native
metadata, authentication information and opaque reasoning signatures.

## Modes and session identity

RPC → TUI launches the official `grok --resume <last-known-id>` in the same pane.
TUI → RPC requires explicit interruption consent: the TUI exposes no reliable
current-work/current-session query. ACP restores the **last known** ID, or creates
a new session if no ID is known. An in-TUI `/new` or `/resume` is not observable
by this bridge and is not claimed to be the last known session.

A pane-local `@corral_native_session` option journals only the official session
ID, provider and native PID/birth. It contains no command, transcript or auth.
It allows daemon reattach to load that identity without guessing the newest
session or reading Grok's unreliable `active_sessions.json`. New-session success
is exposed only after its native identity is journaled. Unknown identity closes
the bridge instead of permitting input into an unverified context.

## Ownership and bounds

The native bridge reuses tmux `pipe-pane` output fan-out and guarded stdin
injection. Confirmed RPC tty canonical input/echo are temporarily disabled for
long JSON; the phase-one exact termios journal and lease fencing are retained.
Graceful detach restores the original tty and **does not kill Grok**. Explicit
mode switching replaces only the verified foreground native process.

The existing 64-pane bridge limit, catalog retirement, 32 MiB wire-record,
8 MiB / 4,000-record replay, and bounded subscriber queues remain. ACP pending
requests additionally have a 64-request limit and finite deadlines. Text
aggregation uses bounded builders; no new idle polling loop is introduced.

## Development checks

A real opt-in lifecycle test uses official Grok, ambient authentication and only
project-owned tmux/leader sockets and read-file proof data:

```sh
GROK_NATIVE_ROOT=/short/project-local/path \
  go test -count=1 -race -timeout 6m -run '^TestNativeGrokBridgeLifecycle$' ./internal/guirpc
```

It covers model/effort changes, a 16 KiB prompt, read-file tool/text output,
shutdown/tty restoration/native PID survival, reattach/history, both modes,
known-session resume, new session and pristine TUI consent. Protocol units also
cover thought chunks, correlation, permission refusal, request bounds and unknown
work on timeout. These checks are not Android rendering or production deployment
acceptance; independent WebSocket and Android checks own those claims.
