#!/usr/bin/env python3
"""一次性把钉死 SHA 的 iTerm2-Color-Schemes 转成 Kotlin 常量。不进 Gradle。

用法（cwd = 仓根）:
  python3 tools/import_iterm_schemes.py --sha 4cbae6273354e5e91a7641d72c69daa3de6a867f
  python3 tools/import_iterm_schemes.py --check
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.parse import quote

DEFAULT_SHA = "4cbae6273354e5e91a7641d72c69daa3de6a867f"
UPSTREAM_REPO = "mbadolato/iTerm2-Color-Schemes"
ROOT = Path(__file__).resolve().parents[1]
KT_PATH = ROOT / "app/app/src/main/java/dev/agentmirror/app/ui/theme/TermSchemes.kt"
NOTICE_PATH = ROOT / "app/app/src/main/assets/NOTICE-iterm2-color-schemes.txt"

# (id, title, lightSource, darkSource) — 方案 §1.2 写死，禁止扫 606。
FAMILIES: list[tuple[str, str, str, str]] = [
    ("follow-system", "默认（Alabaster / Afterglow）", "Alabaster.itermcolors", "Afterglow.itermcolors"),
    ("vesper", "Vesper", "Vesper.itermcolors", "Vesper.itermcolors"),
    ("apple-system-colors", "Apple System Colors", "Apple System Colors Light.itermcolors", "Apple System Colors.itermcolors"),
    ("dracula", "Dracula", "Dracula.itermcolors", "Dracula.itermcolors"),
    ("solarized", "Solarized", "iTerm2 Solarized Light.itermcolors", "iTerm2 Solarized Dark.itermcolors"),
    ("catppuccin", "Catppuccin", "Catppuccin Latte.itermcolors", "Catppuccin Mocha.itermcolors"),
    ("tokyo-night", "Tokyo Night", "TokyoNight Day.itermcolors", "TokyoNight Night.itermcolors"),
    ("gruvbox", "Gruvbox", "Gruvbox Light.itermcolors", "Gruvbox Dark.itermcolors"),
    ("nord", "Nord", "Nord Light.itermcolors", "Nord.itermcolors"),
    ("monokai-pro", "Monokai Pro", "Monokai Pro Light.itermcolors", "Monokai Pro.itermcolors"),
    ("rose-pine", "Rosé Pine", "Rose Pine Dawn.itermcolors", "Rose Pine.itermcolors"),
    ("ayu", "Ayu", "Ayu Light.itermcolors", "Ayu.itermcolors"),
    ("one-half", "One Half", "One Half Light.itermcolors", "One Half Dark.itermcolors"),
    ("kanagawa", "Kanagawa", "Kanagawa Lotus.itermcolors", "Kanagawa Wave.itermcolors"),
    ("everforest", "Everforest", "Everforest Light Med.itermcolors", "Everforest Dark Hard.itermcolors"),
    ("github", "GitHub", "GitHub Light Default.itermcolors", "GitHub Dark Default.itermcolors"),
    ("night-owl", "Night Owl", "Night Owlish Light.itermcolors", "Night Owl.itermcolors"),
    ("iceberg", "Iceberg", "Iceberg Light.itermcolors", "Iceberg Dark.itermcolors"),
    ("flexoki", "Flexoki", "Flexoki Light.itermcolors", "Flexoki Dark.itermcolors"),
    ("selenized", "Selenized", "Selenized Light.itermcolors", "Selenized Dark.itermcolors"),
    ("modus", "Modus", "Modus Operandi.itermcolors", "Modus Vivendi.itermcolors"),
    ("tomorrow", "Tomorrow", "Tomorrow.itermcolors", "Tomorrow Night.itermcolors"),
    ("melange", "Melange", "Melange Light.itermcolors", "Melange Dark.itermcolors"),
    ("zenbones", "Zenbones", "Zenbones Light.itermcolors", "Zenbones Dark.itermcolors"),
    ("atom-one-dark", "One Dark", "Atom One Dark.itermcolors", "Atom One Dark.itermcolors"),
    ("snazzy", "Snazzy", "Snazzy.itermcolors", "Snazzy.itermcolors"),
    ("oceanic-next", "Oceanic Next", "Oceanic Next.itermcolors", "Oceanic Next.itermcolors"),
    ("poimandres", "Poimandres", "Poimandres.itermcolors", "Poimandres.itermcolors"),
    ("horizon", "Horizon", "Horizon.itermcolors", "Horizon.itermcolors"),
    ("zenburn", "Zenburn", "Zenburn.itermcolors", "Zenburn.itermcolors"),
]

REQUIRED_COLOR_KEYS = (
    [f"Ansi {i} Color" for i in range(16)]
    + ["Background Color", "Foreground Color", "Cursor Color"]
)

LICENSE_CONFLICT_MARKERS = (
    "AGPL",
    "GNU Affero",
    "GPL-3",
    "GPLv3",
    "CC BY-NC",
    "Creative Commons Attribution-NonCommercial",
    "all rights reserved",
    "proprietary",
    "not licensed for redistribution",
)

MIT_BODY = """MIT License

Copyright (c) 2011-present Mark Badolato
Copyright (c) 2011 to Present Mark Badolato

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

This license covers the iTerm-Color-Schemes repository collection of themes.

The copyright/license for each individual theme belongs to the author of that theme.
"""


def unique_source_files() -> list[str]:
    seen: list[str] = []
    for _, _, light, dark in FAMILIES:
        for name in (light, dark):
            if name not in seen:
                seen.append(name)
    return seen


def kt_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def round_component(v: float) -> int:
    if v < -0.001 or v > 1.001:
        raise ValueError(f"component out of 0-1 range: {v}")
    v = min(1.0, max(0.0, v))
    return int(round(v * 255.0))


def pack_argb(r: float, g: float, b: float) -> int:
    return 0xFF000000 | (round_component(r) << 16) | (round_component(g) << 8) | round_component(b)


def srgb_decode(u: float) -> float:
    u = min(1.0, max(0.0, u))
    if u <= 0.04045:
        return u / 12.92
    return ((u + 0.055) / 1.055) ** 2.4


def srgb_encode(u: float) -> float:
    u = min(1.0, max(0.0, u))
    if u <= 0.0031308:
        return 12.92 * u
    return 1.055 * (u ** (1.0 / 2.4)) - 0.055


# Display P3 linear → sRGB linear (D65). 不是把 P3 分量当 sRGB 用。
_P3_TO_SRGB = (
    (1.224940176, -0.224940176, 0.000000000),
    (-0.042056955, 1.042056955, 0.000000000),
    (-0.019637555, -0.078636046, 1.098273601),
)


def p3_encoded_to_srgb_encoded(r: float, g: float, b: float) -> tuple[float, float, float]:
    lr, lg, lb = srgb_decode(r), srgb_decode(g), srgb_decode(b)
    m = _P3_TO_SRGB
    sr = m[0][0] * lr + m[0][1] * lg + m[0][2] * lb
    sg = m[1][0] * lr + m[1][1] * lg + m[1][2] * lb
    sb = m[2][0] * lr + m[2][1] * lg + m[2][2] * lb
    return srgb_encode(sr), srgb_encode(sg), srgb_encode(sb)


def plist_scalar(el: ET.Element):
    tag = el.tag
    if tag == "real":
        return float((el.text or "0").strip())
    if tag == "integer":
        return int((el.text or "0").strip())
    if tag == "string":
        return el.text or ""
    if tag == "true":
        return True
    if tag == "false":
        return False
    return None


def parse_dict(el: ET.Element) -> dict:
    out: dict = {}
    children = list(el)
    i = 0
    while i < len(children):
        child = children[i]
        if child.tag != "key":
            i += 1
            continue
        key = child.text or ""
        if i + 1 >= len(children):
            break
        val_el = children[i + 1]
        if val_el.tag == "dict":
            out[key] = parse_dict(val_el)
        elif val_el.tag == "array":
            out[key] = [parse_dict(x) if x.tag == "dict" else plist_scalar(x) for x in list(val_el)]
        else:
            out[key] = plist_scalar(val_el)
        i += 2
    return out


def parse_plist(xml_text: str) -> dict:
    root = ET.fromstring(xml_text)
    dict_el = root.find("dict")
    if dict_el is None:
        raise ValueError("plist has no top-level dict")
    return parse_dict(dict_el)


def color_from_dict(name: str, d: dict) -> int:
    if not isinstance(d, dict):
        raise ValueError(f"{name}: not a dict")
    try:
        r = float(d["Red Component"])
        g = float(d["Green Component"])
        b = float(d["Blue Component"])
    except (KeyError, TypeError, ValueError) as e:
        raise ValueError(f"{name}: missing RGB components ({e})") from e
    space = d.get("Color Space")
    if space is None:
        return pack_argb(r, g, b)
    norm = str(space).strip().lower().replace(" ", "")
    if norm == "srgb":
        return pack_argb(r, g, b)
    # iTerm 遗留 NSCalibratedRGBColorSpace：分量与 sRGB 同编码，不是 Display P3。
    if norm == "calibrated":
        return pack_argb(r, g, b)
    if norm in ("p3", "displayp3"):
        r, g, b = p3_encoded_to_srgb_encoded(r, g, b)
        return pack_argb(r, g, b)
    raise ValueError(f"{name}: Color Space={space!r} is not sRGB/Calibrated/P3")


def license_conflict(xml_text: str) -> str | None:
    lower = xml_text.lower()
    for marker in LICENSE_CONFLICT_MARKERS:
        if marker.lower() in lower:
            return marker
    return None


def fetch_scheme(sha: str, filename: str) -> str:
    path = f"schemes/{quote(filename, safe='')}"
    api = f"repos/{UPSTREAM_REPO}/contents/{path}?ref={sha}"
    last_err = None
    for attempt in range(6):
        try:
            r = subprocess.run(
                ["gh", "api", "-H", "Accept: application/vnd.github.raw", api],
                capture_output=True,
                timeout=60,
            )
            if r.returncode == 0 and r.stdout:
                return r.stdout.decode("utf-8")
            err = (r.stderr or r.stdout).decode("utf-8", "replace")
            last_err = f"exit={r.returncode} {err[:400]}"
        except (subprocess.TimeoutExpired, OSError) as e:
            last_err = str(e)
        time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"fetch failed {filename}: {last_err}")


def parse_scheme(filename: str, xml_text: str) -> dict:
    conflict = license_conflict(xml_text)
    if conflict:
        raise RuntimeError(f"{filename}: license conflict marker {conflict!r}; skipped (not MIT-redistributable)")
    top = parse_plist(xml_text)
    missing = [k for k in REQUIRED_COLOR_KEYS if k not in top]
    if missing:
        raise ValueError(f"{filename}: missing keys {missing}")
    ansi = [color_from_dict(f"Ansi {i} Color", top[f"Ansi {i} Color"]) for i in range(16)]
    selection = None
    if "Selection Color" in top:
        selection = color_from_dict("Selection Color", top["Selection Color"])
    return {
        "sourceFile": filename,
        "background": color_from_dict("Background Color", top["Background Color"]),
        "foreground": color_from_dict("Foreground Color", top["Foreground Color"]),
        "cursor": color_from_dict("Cursor Color", top["Cursor Color"]),
        "ansi": ansi,
        "selection": selection,
    }


def fetch_all(sha: str) -> dict[str, dict]:
    files = unique_source_files()
    out: dict[str, dict] = {}
    errors: list[str] = []
    with ThreadPoolExecutor(max_workers=6) as pool:
        futs = {pool.submit(fetch_scheme, sha, name): name for name in files}
        for fut in as_completed(futs):
            name = futs[fut]
            try:
                xml_text = fut.result()
                out[name] = parse_scheme(name, xml_text)
                print(f"ok {name} bg=0x{out[name]['background']:08X}", file=sys.stderr)
            except Exception as e:  # noqa: BLE001 — 生成器要集齐所有失败再 exit 1
                errors.append(f"{name}: {e}")
                print(f"FAIL {name}: {e}", file=sys.stderr)
    if errors:
        print("generation failed:", file=sys.stderr)
        for e in errors:
            print("  " + e, file=sys.stderr)
        sys.exit(1)
    if len(out) != len(files):
        sys.exit(1)
    return out


def hex_lit(v: int) -> str:
    return f"0x{v & 0xFFFFFFFF:08X}"


def emit_scheme(s: dict) -> str:
    ansi = ",\n            ".join(f"argb({hex_lit(x)})" for x in s["ansi"])
    sel = "null" if s["selection"] is None else f"argb({hex_lit(s['selection'])})"
    return (
        f'        "{kt_escape(s["sourceFile"])}" to TermSchemeColors(\n'
        f'            sourceFile = "{kt_escape(s["sourceFile"])}",\n'
        f"            background = argb({hex_lit(s['background'])}),\n"
        f"            foreground = argb({hex_lit(s['foreground'])}),\n"
        f"            cursor = argb({hex_lit(s['cursor'])}),\n"
        f"            selection = {sel},\n"
        f"            ansi = intArrayOf(\n"
        f"            {ansi}\n"
        f"            ),\n"
        f"        )"
    )


def emit_kotlin(sha: str, schemes: dict[str, dict]) -> str:
    family_lines = []
    for fid, title, light, dark in FAMILIES:
        family_lines.append(
            "        TermThemeFamilyDef(\n"
            f'            id = "{kt_escape(fid)}",\n'
            f'            title = "{kt_escape(title)}",\n'
            f'            lightSource = "{kt_escape(light)}",\n'
            f'            darkSource = "{kt_escape(dark)}",\n'
            "        ),"
        )
    scheme_entries = [emit_scheme(schemes[name]) for name in unique_source_files()]
    families_block = "\n".join(family_lines)
    schemes_block = ",\n".join(scheme_entries)
    return f"""// Generated by: python3 tools/import_iterm_schemes.py --sha {sha}
// Upstream: {UPSTREAM_REPO} @ {sha}
// Do not edit by hand.

package dev.agentmirror.app.ui.theme

/** 一份上游 .itermcolors 解开后的 16 ANSI + 纸色/字色/光标。sourceFile 必须等于上游文件名。 */
data class TermSchemeColors(
    val sourceFile: String,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val ansi: IntArray,
    val selection: Int? = null,
) {{
    override fun equals(other: Any?): Boolean {{
        if (this === other) return true
        if (other !is TermSchemeColors) return false
        return sourceFile == other.sourceFile &&
            background == other.background &&
            foreground == other.foreground &&
            cursor == other.cursor &&
            selection == other.selection &&
            ansi.contentEquals(other.ansi)
    }}

    override fun hashCode(): Int {{
        var result = sourceFile.hashCode()
        result = 31 * result + background
        result = 31 * result + foreground
        result = 31 * result + cursor
        result = 31 * result + (selection ?: 0)
        result = 31 * result + ansi.contentHashCode()
        return result
    }}
}}

/** 一个设置页族：浅槽/深槽各指向一份上游文件（可相同）。id 是持久化键。 */
data class TermThemeFamilyDef(
    val id: String,
    val title: String,
    val lightSource: String,
    val darkSource: String,
)

/**
 * 随包终端主题目录（契约 085）。色值来自 iTerm2-Color-Schemes 钉死 SHA，
 * 由 tools/import_iterm_schemes.py 生成，运行期零解析。
 */
object TermSchemeCatalog {{
    const val UPSTREAM_SHA = "{sha}"

    val families: List<TermThemeFamilyDef> = listOf(
{families_block}
    )

    val colorsBySourceFile: Map<String, TermSchemeColors> = mapOf(
{schemes_block}
    )

    fun colors(sourceFile: String): TermSchemeColors =
        colorsBySourceFile[sourceFile]
            ?: error("unknown terminal scheme sourceFile=$sourceFile")
}}

private fun argb(hex: Long): Int = hex.toInt()
"""


def emit_notice(sha: str) -> str:
    return (
        "NOTICE — color data from iTerm2-Color-Schemes\n"
        "\n"
        "Color values bundled in TermSchemes.kt come from\n"
        f"https://github.com/{UPSTREAM_REPO}\n"
        f"commit {sha}.\n"
        "\n"
        "Collection copyright: Mark Badolato. License: MIT.\n"
        "This Android app is Apache-2.0. It does not use source code or\n"
        "resources from Heeler or Ghostty.\n"
        "\n"
        "The copyright/license for each individual theme belongs to the author of that theme.\n"
        "\n"
        + MIT_BODY
    )


def parse_existing_kotlin(text: str) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for m in re.finditer(
        r'sourceFile = "(.*?)",\s*'
        r"background = argb\((0x[0-9A-Fa-f]+)\),\s*"
        r"foreground = argb\((0x[0-9A-Fa-f]+)\),\s*"
        r"cursor = argb\((0x[0-9A-Fa-f]+)\),\s*"
        r"selection = (null|argb\((0x[0-9A-Fa-f]+)\)),\s*"
        r"ansi = intArrayOf\(\s*((?:argb\(0x[0-9A-Fa-f]+\),?\s*){16})\)",
        text,
        re.S,
    ):
        ansi_hex = re.findall(r"argb\((0x[0-9A-Fa-f]+)\)", m.group(7))
        sel = None if m.group(5) == "null" else int(m.group(6), 16)
        out[m.group(1)] = {
            "sourceFile": m.group(1),
            "background": int(m.group(2), 16),
            "foreground": int(m.group(3), 16),
            "cursor": int(m.group(4), 16),
            "ansi": [int(x, 16) for x in ansi_hex],
            "selection": sel,
        }
    return out


def values_equal(a: dict, b: dict) -> list[str]:
    diffs = []
    for k in ("background", "foreground", "cursor", "selection"):
        if a.get(k) != b.get(k):
            diffs.append(f"{k}: gen={a.get(k)!r} file={b.get(k)!r}")
    if a.get("ansi") != b.get("ansi"):
        diffs.append("ansi mismatch")
    return diffs


def cmd_generate(sha: str) -> None:
    schemes = fetch_all(sha)
    KT_PATH.parent.mkdir(parents=True, exist_ok=True)
    NOTICE_PATH.parent.mkdir(parents=True, exist_ok=True)
    KT_PATH.write_text(emit_kotlin(sha, schemes), encoding="utf-8")
    NOTICE_PATH.write_text(emit_notice(sha), encoding="utf-8")
    print(f"wrote {KT_PATH} ({len(schemes)} schemes)")
    print(f"wrote {NOTICE_PATH}")


def cmd_check(sha: str) -> None:
    if not KT_PATH.is_file():
        print(f"missing {KT_PATH}", file=sys.stderr)
        sys.exit(1)
    text = KT_PATH.read_text(encoding="utf-8")
    if f'UPSTREAM_SHA = "{sha}"' not in text:
        print("UPSTREAM_SHA mismatch", file=sys.stderr)
        sys.exit(1)
    existing = parse_existing_kotlin(text)
    schemes = fetch_all(sha)
    diffs = []
    for name, gen in schemes.items():
        got = existing.get(name)
        if got is None:
            diffs.append(f"missing in TermSchemes.kt: {name}")
            continue
        d = values_equal(gen, got)
        if d:
            diffs.append(f"{name}: " + "; ".join(d))
    extra = sorted(set(existing) - set(schemes))
    for name in extra:
        diffs.append(f"extra in TermSchemes.kt: {name}")
    if diffs:
        print("check failed:", file=sys.stderr)
        for d in diffs:
            print("  " + d, file=sys.stderr)
        sys.exit(1)
    print(f"check ok: {len(schemes)} schemes match {KT_PATH}")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--sha", default=DEFAULT_SHA)
    p.add_argument("--check", action="store_true")
    args = p.parse_args()
    if args.check:
        cmd_check(args.sha)
    else:
        cmd_generate(args.sha)


if __name__ == "__main__":
    main()
