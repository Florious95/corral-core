#!/usr/bin/env python3
"""把 wiki/ 下所有正文里的 [[wikilink]] 转成 [label](relative/path.md)。

- frontmatter（YAML 块 --- ... ---）里的 [[]] 不动
- [[xxx]]              → [xxx](relpath/xxx.md)
- [[xxx#anchor]]       → [xxx](relpath/xxx.md#anchor)
- [[xxx|自定义 label]] → [自定义 label](relpath/xxx.md)
- 找不到对应文件的 [[]] 报告但不动

用法：
  python3 migrate_links.py /path/to/wiki [--dry-run]
"""
import re
import sys
from pathlib import Path

FRONT_RE = re.compile(r"^(---\n.*?\n---\n)", re.DOTALL)
WIKILINK_RE = re.compile(r"\[\[([^\]\|#]+?)(?:#([^\]\|]*))?(?:\|([^\]]*))?\]\]")


def parse_frontmatter(text: str) -> tuple[dict, str, str]:
    m = FRONT_RE.match(text)
    if not m:
        return {}, "", text
    fm_text = m.group(0)
    body = text[m.end():]
    fm: dict = {}
    cur_key = None
    for line in m.group(0).split("\n"):
        if line in ("---",) or not line.strip():
            continue
        if ":" in line and not line.startswith(" ") and not line.startswith("\t"):
            k, _, v = line.partition(":")
            v = v.strip()
            if v.startswith("[") and v.endswith("]"):
                fm[k.strip()] = [x.strip().strip("\"'") for x in v[1:-1].split(",") if x.strip()]
            else:
                fm[k.strip()] = v.strip("\"'")
    return fm, fm_text, body


def build_index(wiki_root: Path) -> dict[str, Path]:
    """slug.lower() → 文件绝对路径。同时收 alias。"""
    idx: dict[str, Path] = {}
    for p in wiki_root.rglob("*.md"):
        # build_index 跳过这些（它们不是概念页，不该被链接到）
        if p.name.startswith("_") or p.name in {"index.md", "log.md", "hot.md"}:
            continue
        text = p.read_text(encoding="utf-8")
        fm, _, _ = parse_frontmatter(text)
        slug = fm.get("slug") or p.stem
        idx[slug.strip().lower()] = p
        idx[p.stem.lower()] = p  # 文件名也作为 fallback
        for a in fm.get("aliases", []) or []:
            idx[a.strip().lower()] = p
    return idx


def relpath(from_file: Path, to_file: Path) -> str:
    """from from_file 跳到 to_file 的相对路径，POSIX 风格。"""
    rel = Path(*to_file.parts[len(from_file.parent.parts):]) if to_file.parts[:len(from_file.parent.parts)] == from_file.parent.parts else None
    # 用 os.path.relpath 兜底，确保跨目录正确
    import os
    rel_str = os.path.relpath(to_file, from_file.parent).replace(os.sep, "/")
    return rel_str


def migrate_body(body: str, current_file: Path, idx: dict[str, Path]) -> tuple[str, int, list[str]]:
    """转换正文里的 [[]]。返回 (新正文, 替换数, 未命中列表)"""
    converted = 0
    misses: list[str] = []

    def repl(m: re.Match) -> str:
        nonlocal converted
        target_raw = m.group(1).strip()
        anchor = m.group(2)
        label_override = m.group(3)
        target_file = idx.get(target_raw.lower())
        if not target_file:
            misses.append(f"[[{m.group(0)[2:-2]}]]")
            return m.group(0)  # 留着不动
        rel = relpath(current_file, target_file)
        anchor_part = f"#{anchor}" if anchor else ""
        label = (label_override or target_raw).strip()
        converted += 1
        return f"[{label}]({rel}{anchor_part})"

    new_body = WIKILINK_RE.sub(repl, body)
    return new_body, converted, misses


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: migrate_links.py <wiki-root> [--dry-run]")
    wiki = Path(sys.argv[1]).resolve()
    dry = "--dry-run" in sys.argv
    if not wiki.is_dir():
        sys.exit(f"not a dir: {wiki}")

    idx = build_index(wiki)
    print(f"📚 索引建立：{len(idx)} 个 slug/alias → 文件")

    total_converted = 0
    total_misses: list[tuple[Path, list[str]]] = []
    files_changed = 0

    for p in sorted(wiki.rglob("*.md")):
        if p.name.startswith("_"):
            continue
        # index.md / log.md / hot.md 仍然处理（它们也含正文链接需 Ctrl+Click）
        # 但 build_graph 不入图（不影响图谱）
        text = p.read_text(encoding="utf-8")
        _, fm_block, body = parse_frontmatter(text)
        new_body, n, misses = migrate_body(body, p, idx)
        if misses:
            total_misses.append((p, misses))
        if n > 0:
            total_converted += n
            files_changed += 1
            new_text = fm_block + new_body
            if not dry:
                p.write_text(new_text, encoding="utf-8")
            rel = p.relative_to(wiki)
            print(f"  {'[dry]' if dry else '[w]':6} {rel} : {n} 处转换")

    print(f"\n{'═' * 60}")
    print(f"{'DRY RUN' if dry else '已写入'}：{total_converted} 处转换，{files_changed} 个文件")

    if total_misses:
        print(f"\n⚠️  未命中（找不到对应 slug 的 [[xxx]]，已保留原样）：")
        for p, misses in total_misses[:10]:
            print(f"  {p.relative_to(wiki)}: {len(misses)} 处")
            for m in misses[:3]:
                print(f"      {m}")
        if len(total_misses) > 10:
            print(f"  ...（共 {len(total_misses)} 个文件有未命中）")

    if dry:
        print("\n💡 跑去 --dry-run 真正写入。")


if __name__ == "__main__":
    main()
