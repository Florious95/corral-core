# nodeprobe (external module)

Read-only tmux inventory and fail-closed activity probe for Agent CLI panes.
This crate has no dependency on a product workspace. Provider identity is a
separate comm-only process-tree lookup; Pi activity is read from its optional
official lifecycle extension channel, never inferred from the static `π` title.

## Usage

```sh
cargo build --release
NODEPROBE_FIXTURES=/path/to/titles.tsv \
NODEPROBE_PROVIDERS=/path/to/providers.tsv \
  target/release/nodeprobe -S /path/to/tmux.sock
```

The development `fixtures` symlink points at the existing canonical corpus in
`远程Agent安卓/tools/nodeprobe/fixtures`; it is intentionally not a second
copy. Deployments must provide both TSV files or set the environment variables.
Missing/unreadable tmux inventory returns a versioned JSON error and exit 1,
never an empty successful report.

## Library API

- `classify_provider(provider, title)` — activity only after identity.
- `detect_provider(comms)` — whitelist identity from process `comm` values only.
- `probe(SocketSpec)` — read-only `list-panes` and narrow `ps` process snapshot;
  it never captures pane bodies and returns `schema_version: 1`.
- `run_fixtures(path)` — deterministic corpus assertion.
- `pi/nodeprobe-pi-activity.mjs` — Pi 0.84.4 extension using official
  `agent_start`, `agent_end`, `agent_settled`, tool, and session events. Load it
  in interactive TUI with `pi --extension pi/nodeprobe-pi-activity.mjs`.

Each node exposes independent `activity` (`working|idle|unknown`),
`session_name` (nullable), `provider`, and `health` (`normal|abnormal|unknown`);
`state` remains a compatibility alias for activity. Set
`NODEPROBE_PI_ACTIVITY_DIR` for Pi and nodeprobe to share atomic `<pid>.json`
schema-v2 records plus a live per-process Unix-socket challenge; v1 and
unknown channel schemas are unsupported. Optionally set
`NODEPROBE_PI_SEAT`. Missing, stale, malformed, crashed, disconnected,
PID-reused, or multi-process channel observations never become idle.
The Pi title parser accepts only `π - <cwd>` and `π - <session> - <cwd>` and
returns no name for spaced-hyphen ambiguity. Channel/title names must agree;
conflicts produce null name and unknown health/activity. No process argv is read
or serialized.
