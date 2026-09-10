# Codex /rename display metadata

## Why this belongs in corral-serve

The current App already renders the server's `Session.name` for Codex and
reconciles live favorite rows from that value. `displayName` previously returned
`PaneTitle` unconditionally. A title configured to contain only the project/CWD
is not the name changed by Codex `/rename`.

The server now resolves the saved name before the shared listing/Level2
projection. `DisplayName` is internal display-only data, not another provider,
activity or health source. `title`, `session_name`, window/session structure,
CWD grouping, socket/pane refs, protocol schemas and favorite identities do not
change. No App/core duplication, CLI configuration edits, command interception,
production deployment or process restart is needed in this PR.

## Identity and freshness

1. Complete the existing allowed-socket discovery and unique nodeprobe join.
   Only positively identified Codex panes with a valid `pane_pid` are eligible.
2. Take one narrow `ps pid,ppid,stat,comm` snapshot, select the pane's live native
   Codex process family, and obtain its numeric open-file descriptors. Linux
   uses `/proc/PID/fd` links; Darwin uses one batched `lsof -F0pfn` invocation.
   A child local app-server can own the file. No argv or process environment is
   read. Other panes and background Codex leftovers are not identity evidence.
3. From already-open `sessions/YYYY/MM/DD/rollout-*.jsonl` files, inspect only
   the **first `session_meta` record**, extracting its stable `payload.id` and
   `source`. Do not parse conversation records. Exclude exec/subagent sources;
   multiple interactive roots are ambiguous rather than resolved by CWD,
   modification time or descriptor order. A RolloutId filename override does
   not replace the header's ThreadId.
4. Read the corresponding Codex home's `session_index.jsonl` backwards, matching
   the exact ThreadId. The last valid appended record wins, not `updated_at`.
   Each home is read once per scan for the eligible IDs. Unicode and literal
   pipes in names are preserved. Custom Codex homes are derived from the open
   rollout location rather than assuming the daemon's HOME/CODEX_HOME.
5. Repeat on each **existing** metadata scan, including while Codex is idle.
   There is no sticky pane/PID/name cache and no additional polling loop. A
   rename-only change already changes `list_delta` and `level2SnapKey`; normal
   App pushes therefore carry the latest resolved name without reopening or
   reconnecting. The existing Level2 cadence is 2 seconds, not a claim of a
   zero-latency native Codex event subscription.

Upstream sources checked during implementation:

- https://github.com/openai/codex/blob/main/codex-rs/rollout/src/session_index.rs
  (`SessionIndexEntry`, `append_thread_name`, newest-first lookup).
- https://github.com/openai/codex/blob/main/codex-rs/rollout/src/recorder.rs
  (`SessionMeta`, stable conversation ID, rollout ID override, writer ownership).
- https://github.com/openai/codex/blob/main/codex-rs/tui/src/terminal_title.rs
  (OSC title is a separately composed/sanitized display surface).

## Bounds and fallback

No new dependency or accepted-nodeprobe binary/manifest change is introduced.
Name enrichment has a 750 ms context budget, 4 MiB command-output bound, 4096-node
process-walk bound, 1 MiB metadata/record bound and 32 MiB reverse-index scan
bound. Ordinary local-file reads check cancellation between records/chunks;
these are not hard cancellation guarantees for a stalled filesystem syscall.
The implementation does not persist or log metadata records or raw process/file
listings. Header reads deserialize only metadata, but the first record itself
can include other Codex metadata fields; it is not claimed to be an OS-level
field-selective read. Index/header symlinks and nonregular files are rejected.

Missing/unpersisted rollout files, permissions, unsupported layouts/platforms,
ambiguous interactive roots, unavailable lsof, invalid metadata or budget
exhaustion produce **no override**. The existing complete OSC-title/unknown
fallback remains; no other session's name is guessed. Names older than the
bounded index search window may likewise be unavailable. This is intentional
fail-closed compatibility, not a promise that metadata can always be obtained.

## Validation

`internal/codexname/names_test.go` uses temporary metadata and synthetic process
inventories; the native descriptor test opens only its own synthetic rollout.
Coverage includes repeated idle renames, resume/new identity refresh, same-home
IDs, separate custom homes, a local app-server child, subagents, duplicate FDs,
ambiguous roots, stopped/background processes, partial appends, same-size index
rewrites, replacement/truncation/removal, multi-chunk records, Unicode, and
symlink rejection.

`internal/nodeprobe/codex_names_test.go` covers the positive unique-provider join,
extra-key rejection and independent status axes. `internal/api/codex_rename_test.go`
checks the shared projection, name-only listing delta, Level2 push decision,
unchanged-name suppression, stable refs/geometry/status and legacy providers.

The PR workflow runs these on Linux and Darwin using the repository's Go
version. A workflow definition is not a claim that it passed. Real Codex
`/rename` plus Android sessions/favorites/drawer validation and on-host timing
remain separate acceptance checks; synthetic tests are not phone E2E evidence.
