# Consumer migration map (frozen; no consumers changed)

| Consumer | Current source/API | Current corpus | Migration note |
|---|---|---|---|
| `server/internal/api/level2.go` | `classifyForProvider`, provider finder | indirect Go loader | Can consume the external JSON schema or Rust library adapter after an explicit protocol migration; currently unchanged. |
| `server/internal/api/l2detect_*.go` | Claude/Grok detectors; Codex/Copilot/Cursor unclaimed | `titles.tsv` parity tests | Keep Go implementation during staged extraction; compare corpus before cutover. |
| `server/internal/api/proctree.go` | narrow `ps -axo pid=,ppid=,comm=` and whitelist match | `providers.tsv` | External `proctree`/`providers` mirrors the contract; no argv migration required. |
| `server/internal/provider/table.go` | Go provider table loader | `providers.tsv` | Must continue reading canonical path until a coordinated source move. |
| `server/internal/api/l2detect_footer.go` | footer rules and count observation | `titles.tsv` footer rows | External classifier consumes same rows; no duplicate rule table. |
| `tools/nodeprobe.sh` | shell adapter to in-tree Cargo crate | both TSV files | Not replaced by this module. A future adapter can point at the external release binary and explicit corpus path. |

No team-agent product files or Go consumers were modified. The exact follow-up
for canonical-source migration is: choose one repository-owned corpus location,
update both Go and external deployment paths in one change, run both parity
suites, then remove the development symlink. Until then, the external module's
symlink/env arrangement is intentionally staged.
