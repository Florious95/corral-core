# External documentation sync (non-blocking)

Accepted 863c unit `pi_missing_channel_with_process_is_normal_health` and live install acceptance freeze:

- unique Pi process + missing/unset channel record → `activity=unknown`, `health=normal`
- default channel dir `$HOME/.local/state/nodeprobe/pi-activity` (0700/0600, `NODEPROBE_PI_ACTIVITY_DIR` override)
- auto-discovered observer suffix `.js`, not `.mjs`

The global skill `/Users/alauda/.agents/skills/tmux-node-activity/SKILL.md` still describes the older missing/unset → `health=unknown` wording. That is an external documentation sync item. It must not veto this product integration or the accepted source.

`.team/stable-pr/nodeprobe-accuracy/VERDICT.md` is superseded local-contract-conflict evidence only; it is not a status-core final verdict.
