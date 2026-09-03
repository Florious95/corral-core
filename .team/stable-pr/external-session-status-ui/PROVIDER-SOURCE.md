# Provider visual source (frozen JPEG)

- Absolute path: `/Users/alauda/Downloads/agentmirror-uploads/upload-20260903T082618-1000023292.jpg`
- Bytes: `38346`
- SHA-256: `a0eca60af65f802e93958ace7e936e7bcb9be5d80d5def01784a1525cbed3fe1`
- Pixel size: `1260 x 170` (JPEG SOF baseline, 8-bit, 3 components)

## What the file contains

The JPEG is a dark strip of monospace path text only:

```
/Volumes/nvme/Projects/tmux桌面端
/design-handoff/cross-platform-desktop-ui-mockups/project/Agent App Prototype.dc.html
```

There are **no** Provider glyph tiles, no cropable icon bounds, and no question-mark/status overlay that belongs in a mark.

## Followed path (not used as icons)

The pointed HTML exists. Its icon gallery fetches `@lobehub/icons-static-svg@latest` at runtime and falls back to homemade blob/robot SVGs. That is not this JPEG. LobeHub substitutes and self-drawn fallbacks are forbidden.

Prototype gallery names: Claude Code, Codex, Grok, OpenCode, Cursor, Z Code, Kimi Code. **Copilot** and **Pi** are absent there as well.

## Per-provider glyph inventory (in the JPEG)

| Canonical id | Unique glyph in JPEG | Action |
|---|---|---|
| `claude_code` | none | blocked |
| `codex` | none | blocked |
| `copilot` | none | blocked |
| `grok` | none | blocked |
| `cursor` | none | blocked |
| `pi` | none | blocked |

No crop coordinates. No output raster. APP does not invent marks.

## Removed from PR 71

- `third_party/lobehub-icons-static-svg-1.94.0/**`
- `app/app/src/main/assets/provider-icons/*.svg`
- `app/app/libs/androidsvg-1.4.jar` and NOTICE
- Gradle AndroidSVG dependency and runtime parser
