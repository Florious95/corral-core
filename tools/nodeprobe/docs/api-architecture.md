# API and architecture note

## Boundaries

`providers` performs identity from the pane root PID's process tree. The only
process fields requested are `pid`, `ppid`, and `comm`; argv/args/command are
never requested, parsed, or emitted. `classify` receives a provider id and
pane title and returns activity independently. A provider miss is represented
as `provider=unknown`, not by dropping the pane.

`lib::probe` performs one tmux `list-panes -a` inventory and one narrow `ps`
snapshot. It never captures pane bodies, attaches, sends keys, or mutates panes.
Inventory failure is a `tmux_inventory` JSON error with non-zero CLI exit.
Without a dedicated footer authority, `background_tasks` remains unknown; it
never silently becomes zero or alters activity.

## Versioned output

Successful output has `schema_version: 1`, socket, sample time, and node rows.
A node has structural tmux identity, independent `activity` (`working`,
`idle`, or `unknown`), nullable `session_name`, provider, independent `health`
(`normal`, `abnormal`, or `unknown`), background-task observation, and evidence.
`state` remains a compatibility alias for activity. Error output keeps the same
schema and includes `error: {kind, message}` with an empty node list. Consumers
must branch on `error` before interpreting nodes.

## Corpus arrangement

The external checkout contains one development symlink, `fixtures`, to the
canonical files at `/Volumes/nvme/Projects/远程Agent安卓/tools/nodeprobe/fixtures`.
Git therefore stores no duplicated `titles.tsv` or `providers.tsv`. Library and
CLI users outside that checkout provide a corpus directory/files explicitly via
`NODEPROBE_FIXTURES` and `NODEPROBE_PROVIDERS`. Moving the canonical files or
making the Go consumer fetch this external checkout is a follow-up migration,
not done here, because it would otherwise create two authorities or break the
existing Go lookup.

## Pi lifecycle channel

`pi/nodeprobe-pi-activity.mjs` is loaded explicitly with Pi's official
extension API. It writes one atomic `<pid>.json` record under
`NODEPROBE_PI_ACTIVITY_DIR`, refreshed every second and on
`agent_start`/`agent_end`/`agent_settled`/session events, plus a per-process
Unix socket. This channel wire format is schema version 2; version 1 and
unknown versions are explicitly unsupported. `agent_end` stays working because Pi documents it as non-settled;
only `agent_settled` writes idle. Nodeprobe performs a live challenge-response
against that socket and checks the per-process instance id, preventing a stale
PID-reused file from being accepted. The record is accepted only for one unique
Pi descendant, matching pid/provider, known activity, schema version, and a
fresh timestamp. Missing/ambiguous, stale, malformed, disconnected, or
mismatched channels fail closed. Health is never derived from activity.

Pi's official title is `π - <cwd>` or `π - <session> - <cwd>`; a spaced-hyphen
that creates multiple possible splits yields a null session name rather than a
guess. A valid channel name and a valid title name must agree; title-only or
channel-only names are retained only when the other authority is unavailable,
while conflicts yield null name, unknown activity, and unknown health with
conflict evidence. The static title itself never supplies activity.

## Determinism

Classifier and corpus runner are pure with respect to their inputs. Fixture
runs produce identical output across repeated runs. Live `sampled_at` is
expected to differ, while node ordering follows tmux output and is not used as
classifier input.
