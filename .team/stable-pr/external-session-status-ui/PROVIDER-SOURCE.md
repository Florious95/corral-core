# Provider visual source (HTML extract)

JPEG pointer (not an icon sheet):

- `/Users/alauda/Downloads/agentmirror-uploads/upload-20260903T082618-1000023292.jpg`
- bytes `38346`, SHA-256 `a0eca60af65f802e93958ace7e936e7bcb9be5d80d5def01784a1525cbed3fe1`
- pixels `1260 x 170`
- content is the path to the unique design source below

## Unique design source

- `/Volumes/nvme/Projects/tmux桌面端/design-handoff/cross-platform-desktop-ui-mockups/project/Agent App Prototype.dc.html`
- bytes `46424`, SHA-256 `9c81f24bfdb57fb54fbcfe0a6abf825d6299e0d19482f790ec3f4e057d4bb0fc`
- import: `./support.js` only (runtime; SHA-256 `8fe7df74405f3c55f49b7249c74ea1397e65d07dea2b1bd3b4a489bec2e28cbe`; no Provider glyphs)
- `uploads/` is not referenced by the HTML
- LobeHub URL `https://unpkg.com/@lobehub/icons-static-svg@latest/icons/{slug}.svg` is network-only and was not fetched

Local Provider geometry is `icon()` L278–315: homemade inline SVG fallbacks (`blobPath` + per-provider inner mark). Letter-circle is a loading placeholder, not a glyph. Default `blob + '?'` was not extracted.

Source symbol map (`icon()` switch / `Component.BRAND` / `iconGallery` / `provCycle`):

| Canonical id | Source symbol | Extract | Raw resource | bytes | SHA-256 |
|---|---|---|---|---|---|
| `claude_code` | `case 'claude-code': blob(mtext('❯_'))` tint `#D97757` | yes | `raw/provider_icon_claude_code.svg` | 927 | `5d2a03146e55387d8d58cfc44cc1c0fb90c47b559648dbe52f0420f0d9757626` |
| `codex` | `case 'codex': blob(circle r=3.6 dasharray 3.4 1.5)` tint `#6d6a63` | yes | `raw/provider_icon_codex.svg` | 879 | `9cb405c0c50125d8562fd7a2c9fa4220376d2fc8ecae5ac99f4032e40cf85f7c` |
| `grok` | `case 'grok': blob(path M9 15.5…)` tint `#3a3835` | yes | `raw/provider_icon_grok.svg` | 901 | `2425946f7e10d26d978e9fd3cd642cfb6d6db3033d2b4a0819b2b989503540b2` |
| `cursor` | `case 'cursor': blob(path M12 8l3.4…)` tint `#6d6a63` | yes | `raw/provider_icon_cursor.svg` | 921 | `66d07e0c2cc8f227d55fedafbdfcb98825905cbb8b1d071b9a8b68abeb901684` |
| `copilot` | none | no | — | — | missing, see search evidence |
| `pi` | none | no | — | — | missing, see search evidence |

`blobPath` (shared): `M12 2.8c1.7-.8 3.6-.3 4.6 1.1 1.7.2 2.9 1.7 2.7 3.4 1.1 1.2 1.2 3 .2 4.3.5 1.6-.2 3.3-1.7 4-.4 1.6-1.9 2.7-3.6 2.5-1.1 1.2-3 1.4-4.3.5-1.7.3-3.3-.8-3.7-2.4-1.5-.6-2.4-2.2-2-3.8-1.1-1.3-1-3.1.1-4.3-.2-1.7 1-3.2 2.6-3.5C8.1 3.2 9.9 2.5 11.5 3l.5-.2Z`

Runtime draws those primitives with `androidx.core.graphics.PathParser` + Canvas. No AndroidSVG. No LobeHub download. Raw SVG is not parsed at runtime.

## Missing-provider search evidence (no guess)

Commands against the HTML, `support.js`, sibling Desktop Mockups, `_ds/**/styles.css`, and `uploads/` filenames:

- `copilot` / `Copilot`: 0 hits in the HTML, 0 in `support.js`, 0 in Desktop Mockups, 0 in styles.css. Not in `BRAND`, `provCycle`, `iconGallery`, or `icon()` switch.
- `pi` as a word: 0 hits in the HTML (`re ( ^|[^A-Za-z0-9_])pi([^A-Za-z0-9_]|$) `), 0 in `support.js`, 0 in Desktop Mockups, 0 in styles.css. Not in `BRAND` / gallery / switch.
- Gallery names only: Claude Code, Codex, Grok, OpenCode, Cursor, Z Code, Kimi Code.

Those two canonical ids stay blank (no `?`, no letter-circle, no homemade substitute).
