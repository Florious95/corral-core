# Provider icon SOURCE-AUDIT (PR #71)

Trace start: local PR #67 owning commit **`1b12e92d8efb1c0eec41e14a264f9d80ee833ad9`**
(`pr/provider-status-icons-favorite-menu`). GitHub PR #67 head `96a075f766dd972edbee0d661461c82d4bf2fef3`
and PR #68 accepted head `013083c8119fcd9411fd92a2e852d1f91cb41de5` carry the same Pi and Copilot blobs.

## Runtime mapping at 1b12e92 (actual displayed resource)

`app/app/src/main/java/dev/agentmirror/app/ui/components/ProviderMark.kt`:

```
"pi" -> R.drawable.provider_pi
"copilot" -> R.drawable.provider_copilot_color
```

Painter: `painterResource(resources.getValue(key))` at 18.dp. The `?` / `!` Text overlays in that file are **not** copied.

## Blob identity (old == new)

| id | old commit | old path | git blob | bytes | SHA-256 | new path |
|---|---|---|---|---|---|---|
| `pi` | `1b12e92d8efb1c0eec41e14a264f9d80ee833ad9` | `app/app/src/main/res/drawable-nodpi/provider_pi.png` | `d8fcf42cdaa5d18e6063637270365a20481ece86` | 287 | `9d59066fac0cb0361fb7cf663e87d0f29beb654e49780baa55aab74aa4757b2f` | same path |
| `copilot` | same | `app/app/src/main/res/drawable-nodpi/provider_copilot_color.png` | `90c8b385064a918ab2b01d93f19ab95ef64dc2e3` | 4669 | `49faef29cb14fa7aaa73672ef126acee65ff504c2463a6672d9a9364fa75c54a` | same path |

`git hash-object` of the new files equals the old git blobs. PR #67 GitHub head and PR #68 head resolve to the same blob ids.

PNG: 72×72 RGBA, signature `89504e47`. Not a question-mark overlay. Copied with `git cat-file blob`; not regenerated; not downloaded; no LobeHub package; no AndroidSVG.

## HTML source (unchanged for the other four)

`claude_code` / `codex` / `grok` / `cursor` remain Agent App Prototype.dc.html `icon()` extracts. HTML has no Pi/Copilot glyphs; Pi/Copilot come from the prior APP, not from that HTML.
