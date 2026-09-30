# Production Web verification — 2026-08-12

## Automated gates

- `npm test`: PASS, 46/46 (original protocol/client/binary suite plus config, theme, multi-key, resize debounce, scrollback pagination, dev server and app wiring regressions).
- `npm run build`: PASS, static client generated in `web/dist/`.
- clean-environment `npm audit --audit-level=high`: PASS, 0 vulnerabilities.
- production daemon API E2E (`WS_URL=ws://localhost:9900/ws`, token passed by environment only): PASS, 10/10 — auth/list/subscribe/snapshot/delta/input_ack/scrollback/state set.
- `npm run tauri dev -- --no-watch`: PASS — dev server started and `/Volumes/nvme/cargo-target/debug/agentmirror-desktop` ran; stopped after verification.
- `npm run tauri build -- --bundles dmg`: PASS — `/Volumes/nvme/cargo-target/release/bundle/dmg/AgentMirror_0.1.0_aarch64.dmg`, 3.2MB, SHA-256 `67198c0f09fde73b99220e4a9bbbfad4e5082b2dbedf07f70b185316907fcbce`.

## Chrome + production daemon

Chrome loaded the real web UI and connected directly to the production daemon (no origin proxy). The pairing token was read from the required file into the password input and was cleared from the page/virtual clipboard after the test; it was not printed by application code or stored in this evidence.

- refresh auto-recovery: PASS; workspace listing returned 1 workspace / 4 sessions.
- snapshot: PASS; first terminal xterm grid contained 1916 rendered characters.
- multi-session: PASS; two xterm instances stayed mounted, two tabs rendered, exactly one panel visible.
- input receipt: PASS; bare Enter returned visible `sent`.
- theme: PASS; system/dark/light selection applied; screenshots visually inspected for overflow and character overlap.

UI evidence:

- `web/evidence/ui-review/session-dark.png`
- `web/evidence/ui-review/session-light.png`

## Security/license

- token value and token file path scan under `web/`: no match.
- no application hard-coded daemon URL/token; URL and token come from pairing configuration.
- xterm.js 6.0.0 is MIT; Tauri v2 is MIT/Apache-2.0; project package/Rust crate are Apache-2.0.
- persisted token tradeoff is documented in `README.md` and `ARCHITECTURE.md`: refresh recovery uses localStorage; disconnect clears it; deployments must not load untrusted third-party script.
