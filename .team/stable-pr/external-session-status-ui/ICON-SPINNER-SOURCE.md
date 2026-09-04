# PR #73 icon and spinner source receipt

## User-confirmed visual authority

The final visual reference is the two user-confirmed boards:

- dark: `/Users/alauda/Downloads/agentmirror-uploads/upload-20260904T152351-clipboard.png` (SHA-256 `97d3a067b96e77d04706d306f24cc101185c1aa9a773357204bdff8736f9b03a`)
- light: `/Users/alauda/Downloads/agentmirror-uploads/upload-20260904T152418-clipboard.png` (SHA-256 `dbf8a6a718876010867232b0d1ddb538bf5d9f0fbc881eb174cea50e0b15894d`)

The right-side marks have no plate, one monochrome color, a 28dp slot, about
22dp optical bound, and vertical centering. Dark uses near-white
`#F0F2F5`; light uses the explicitly accepted `#667085`. The left working
lamp owns a 20dp slot and paints all six dots of a 2-column by 3-row grid.
Working active dots are `#34C759`; inactive dots are a weak theme neutral.

## HTML authority and exact BRAND sources

HTML: `/Users/alauda/Downloads/cross-platform-desktop-ui-mockups/project/Agent App Prototype.dc.html`

HTML SHA-256: `9c81f24bfdb57fb54fbcfe0a6abf825d6299e0d19482f790ec3f4e057d4bb0fc`

- Lines 236-245 define `claude-code` as `run=claude-color,idle=claude`,
  `codex` as `run=codex,idle=codex`, and `cursor` as `run=cursor,idle=cursor`.
- Lines 255-261 fetch the named BRAND SVG slugs; lines 278-290 render a
  cache-hit BRAND as an image. The fallback switch is not the BRAND path.
- The exact local, already-installed desktop resource bytes used for the
  approved BRAND shapes are:

| provider | exact source | bytes | SHA-256 | structure |
|---|---|---:|---|---|
| Claude Code | `/Users/alauda/Downloads/herdrm.app/Contents/Resources/claude-color.svg` | 1986 | `3de36b2f9c3abbac9c93751b7f5ac04bb37d9af71db347d98675ac464dcabb2f` | one filled Claude path, `viewBox=0 0 24 24`, color `#D97757`; Android raw SHA `907175cc3a05492c941144633eedc8def950e345f41705a571ec087b5e6b9183` |
| Codex | `/Users/alauda/Downloads/herdrm.app/Contents/Resources/codex.svg` | 1578 | `e8ad73aeb418b7e6ebda96d600b0724be536d6f9b4d5775eb10797b60d09aa74` | one filled Codex path, `viewBox=0 0 24 24`, `currentColor`; Android raw SHA `6fe42dc2de268e438b2f3cc32eaa07a4b34f17eafc9320bc3381a5e475c3dfe0` |
| Cursor | `/Users/alauda/Downloads/herdrm.app/Contents/Resources/cursor.svg` | 634 | `3926c742b009cd7a70ff7be23c0957f3dffd918bba080341617c7c314107419f` | one filled Cursor path, `viewBox=0 0 24 24`, `currentColor`; Android raw SHA `64c552bfae500e7b1d80cb3a7186553817c188504537fea7faa096c161ca200e` |

The Android raw resources preserve these SVG structures and path data (with
only the required terminal text newline) and runtime
uses the same path data with a theme tint; there is no fallback glyph, X,
letter circle, network fetch, or screenshot redraw. Grok, Pi, and Copilot
remain their accepted existing sources and are tinted at the same optical
size.

## Spinner source and clipping root cause

The frozen Codex CLI 0.149.0 conversation-leading sequence remains:
`⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏`, 100ms per frame, 1000ms wrap. The Android
renderer derives these exact masks from the Unicode Braille code points:

`0x0B, 0x19, 0x39, 0x38, 0x3C, 0x34, 0x36, 0x27, 0x07, 0x0F`.

The prior renderer put a 12sp Unicode `Text` in an 8dp slot with equal
12sp line height. Font ascent/descent and the parent clip therefore cut the
top and bottom Braille rows. The fix removes Unicode Text from final drawing:
Canvas paints six circles at x=`5dp,15dp`, y=`4dp,10dp,16dp`, radius `2.2dp`.
Every circle remains inside the 20dp slot (bounds `2.8..17.2` horizontally
and `1.8..18.2` vertically). Idle, unknown, abnormal, and offline states
paint no dots while retaining the same transparent 20dp slot.
