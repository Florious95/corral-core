# Provider visual source

JPEG pointer (not an icon sheet):

- `/Users/alauda/Downloads/agentmirror-uploads/upload-20260903T082618-1000023292.jpg`
- bytes `38346`, SHA-256 `a0eca60af65f802e93958ace7e936e7bcb9be5d80d5def01784a1525cbed3fe1`

## HTML design source (four ids)

- `/Volumes/nvme/Projects/tmux桌面端/design-handoff/cross-platform-desktop-ui-mockups/project/Agent App Prototype.dc.html`
- SHA-256 `9c81f24bfdb57fb54fbcfe0a6abf825d6299e0d19482f790ec3f4e057d4bb0fc`
- import: `./support.js` only; LobeHub `@latest` URL was not fetched

| Canonical id | Source | Resource | bytes | SHA-256 |
|---|---|---|---|---|
| `claude_code` | HTML `icon()` `blob(mtext('❯_'))` | `raw/provider_icon_claude_code.svg` | 927 | `5d2a03146e55387d8d58cfc44cc1c0fb90c47b559648dbe52f0420f0d9757626` |
| `codex` | HTML `icon()` dashed circle | `raw/provider_icon_codex.svg` | 879 | `9cb405c0c50125d8562fd7a2c9fa4220376d2fc8ecae5ac99f4032e40cf85f7c` |
| `grok` | HTML `icon()` inner paths | `raw/provider_icon_grok.svg` | 901 | `2425946f7e10d26d978e9fd3cd642cfb6d6db3033d2b4a0819b2b989503540b2` |
| `cursor` | HTML `icon()` diamond path | `raw/provider_icon_cursor.svg` | 921 | `66d07e0c2cc8f227d55fedafbdfcb98825905cbb8b1d071b9a8b68abeb901684` |

## Prior APP (Pi + Copilot) — not missing

Owning commit `1b12e92d8efb1c0eec41e14a264f9d80ee833ad9`. Mapping `"pi" to R.drawable.provider_pi`, `"copilot" to R.drawable.provider_copilot_color`. Same git blobs on GitHub PR #67 head and PR #68 head. Exact bytes reused; no overlay.

| Canonical id | old/new path | git blob | bytes | SHA-256 |
|---|---|---|---|---|
| `pi` | `drawable-nodpi/provider_pi.png` | `d8fcf42cdaa5d18e6063637270365a20481ece86` | 287 | `9d59066fac0cb0361fb7cf663e87d0f29beb654e49780baa55aab74aa4757b2f` |
| `copilot` | `drawable-nodpi/provider_copilot_color.png` | `90c8b385064a918ab2b01d93f19ab95ef64dc2e3` | 4669 | `49faef29cb14fa7aaa73672ef126acee65ff504c2463a6672d9a9364fa75c54a` |

See `SOURCE-AUDIT.md`.

## Prior-app LICENSE / NOTICE (two blobs only)

Exact files from `1b12e92d8efb1c0eec41e14a264f9d80ee833ad9`, no icon pack/runtime:

- `third_party/provider-icons/lobehub-1.94.0/LICENSE` git blob `1dd53d2a9d99e5f91113831a850276ef530cb9d2` SHA-256 `add9d7531d1b21646317a8958e38fc727506fa39d24bdecb44154d943c82753a`
- `third_party/provider-icons/lobehub-1.94.0/NOTICE` git blob `28bb8f4ebf056e6233b015cf61a106c6335185a5` SHA-256 `92418dde8a6ea4eff4be9bf02a5a9e5581124fe935f0b41b42726d214dc09e14`
- `third_party/provider-icons/lobehub-1.94.0/PROVENANCE-pi-copilot.txt` (the two RESOURCE-MAP lines + current git blobs)

No `src/*.svg`, no AndroidSVG, no LobeHub runtime.
