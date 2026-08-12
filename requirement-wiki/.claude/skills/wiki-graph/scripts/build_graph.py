#!/usr/bin/env python3
"""扫描 wiki/，产出 wiki/_graph.html（D3 力导向图，节点点击经 localhost:<port> 在 PyCharm 内打开）。

幂等：重复跑结果一致。读 .wiki-runtime/port 决定 fetch 的端口（不存在则用 7777 默认）。
"""
import json
import os
import re
from pathlib import Path

# 项目根 = 本脚本上溯 4 层
ROOT = Path(__file__).resolve().parents[4]
WIKI = ROOT / "wiki"
OUT = WIKI / "_graph.html"
PORT_FILE = ROOT / ".wiki-runtime" / "port"


def discover_port() -> int:
    if v := os.environ.get("WIKI_OPEN_PORT"):
        try:
            return int(v)
        except ValueError:
            pass
    if PORT_FILE.exists():
        try:
            return int(PORT_FILE.read_text(encoding="utf-8").strip())
        except (ValueError, OSError):
            pass
    return 7777

# 系统文件不入图
SKIP_NAMES = {"_graph.html", "_graph.md", "hot.md", "index.md", "log.md"}

FRONT_RE = re.compile(r"^---\n(.*?)\n---\n", re.DOTALL)
WIKILINK_RE = re.compile(r"\[\[([^\]\|#]+?)(?:#[^\]\|]*)?(?:\|[^\]]*)?\]\]")
# 标准 markdown link：[label](path.md)，只取 path，过滤 http(s)/锚点单独
MDLINK_RE = re.compile(r"\[[^\]]+\]\(([^)]+?\.md)(?:#[^)]*)?\)")
TLDR_RE = re.compile(r"##\s*TL;DR\s*\n+([^\n#]+)")

# 类型 → 颜色
COLOR = {
    "concept": "#4a90e2",
    "technique": "#27ae60",
    "architecture": "#8e44ad",
    "project": "#e67e22",
    "pattern": "#16a085",
    "question": "#f1c40f",
    "idea": "#e74c3c",
    "claim": "#7f8c8d",
    "unknown": "#bdc3c7",
}


def parse_frontmatter(text: str) -> tuple[dict, str]:
    m = FRONT_RE.match(text)
    if not m:
        return {}, text
    body = text[m.end():]
    fm: dict = {}
    for line in m.group(1).splitlines():
        if ":" not in line or line.startswith(" "):
            continue
        k, _, v = line.partition(":")
        v = v.strip()
        if v.startswith("[") and v.endswith("]"):
            items = [x.strip().strip("\"'") for x in v[1:-1].split(",") if x.strip()]
            fm[k.strip()] = items
        else:
            fm[k.strip()] = v.strip("\"'")
    return fm, body


def safe_int(x, default=3) -> int:
    try:
        return int(str(x).strip())
    except (TypeError, ValueError):
        return default


def collect():
    nodes: dict[str, dict] = {}
    alias_map: dict[str, str] = {}

    for path in sorted(WIKI.rglob("*.md")):
        if path.name in SKIP_NAMES or path.name.startswith("_"):
            continue
        text = path.read_text(encoding="utf-8")
        fm, body = parse_frontmatter(text)
        slug = (fm.get("slug") or path.stem).strip()
        rel = path.relative_to(ROOT).as_posix()

        # TL;DR：找 ## TL;DR 块，否则取第一段非标题文字
        tldr = ""
        m = TLDR_RE.search(body)
        if m:
            tldr = m.group(1).strip()
        else:
            for line in body.splitlines():
                s = line.strip()
                if s and not s.startswith("#") and not s.startswith("---"):
                    tldr = s
                    break
        if len(tldr) > 200:
            tldr = tldr[:200] + "…"

        nodes[slug] = {
            "id": slug,
            "type": (fm.get("type") or "unknown").strip(),
            "importance": safe_int(fm.get("importance", 3)),
            "status": (fm.get("status") or "").strip(),
            "lifecycle": (fm.get("lifecycle") or "").strip(),
            "tldr": tldr,
            "path": rel,
        }
        alias_map[slug.lower()] = slug
        for a in fm.get("aliases", []) or []:
            alias_map[a.lower()] = slug

    # 边：扫每个文件正文里的 [[wikilink]] 和 [label](path.md)
    edges = []
    seen_pairs: set[tuple[str, str]] = set()

    def add_edge(src: str, tgt: str | None):
        if not tgt or tgt == src or tgt not in nodes:
            return
        key = (src, tgt)
        if key in seen_pairs:
            return
        seen_pairs.add(key)
        edges.append({"source": src, "target": tgt})

    for slug, node in nodes.items():
        text = (ROOT / node["path"]).read_text(encoding="utf-8")

        # 形式 1：[[xxx]] / [[xxx|label]] / [[xxx#anchor]]
        for m in WIKILINK_RE.finditer(text):
            target = alias_map.get(m.group(1).strip().lower())
            add_edge(slug, target)

        # 形式 2：[label](relative/path.md) —— 取 basename 当 slug 候选
        for m in MDLINK_RE.finditer(text):
            href = m.group(1).strip()
            if href.startswith(("http://", "https://", "mailto:")):
                continue
            stem = href.rsplit("/", 1)[-1].rsplit(".", 1)[0]
            add_edge(slug, alias_map.get(stem.lower()))

    return list(nodes.values()), edges


HTML_TEMPLATE = r"""<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>Wiki Graph</title>
<script src="https://d3js.org/d3.v7.min.js"></script>
<style>
:root { --bg:#1e1f22; --fg:#dfe1e5; --muted:#7a7f87; }
* { box-sizing: border-box; }
html, body { margin:0; height:100%; background:var(--bg); color:var(--fg);
  font-family: -apple-system, "Segoe UI", "PingFang SC", sans-serif; }
#hud { position:fixed; top:8px; left:8px; z-index:10; display:flex; gap:8px; align-items:center; }
#search { background:#2b2d31; border:1px solid #3a3d42; color:var(--fg);
  padding:6px 10px; border-radius:4px; outline:none; font-size:13px; width:240px; }
#search::placeholder { color:var(--muted); }
#legend { position:fixed; top:8px; right:8px; z-index:10; background:#2b2d31cc; padding:8px 10px;
  border:1px solid #3a3d42; border-radius:4px; font-size:11px; line-height:1.6; }
#legend .dot { display:inline-block; width:9px; height:9px; border-radius:50%; margin-right:5px; vertical-align:middle; }
#stats { position:fixed; bottom:8px; left:8px; color:var(--muted); font-size:11px; z-index:10; }
#tip { position:fixed; pointer-events:none; background:#2b2d31ee; border:1px solid #3a3d42;
  padding:6px 8px; border-radius:4px; font-size:12px; max-width:340px; line-height:1.5;
  z-index:20; display:none; }
#tip .t { color:#fff; font-weight:600; margin-bottom:2px; }
#tip .meta { color:var(--muted); font-size:10px; margin-bottom:4px; }
#tip .tldr { color:#cfd2d6; font-size:12px; }
svg { width:100%; height:100%; cursor:grab; }
svg:active { cursor:grabbing; }
.node { stroke:#1e1f22; stroke-width:1.5px; cursor:pointer; }
.node.dim { opacity:0.18; }
.node.hit { stroke:#fff; stroke-width:2px; }
.label { fill:var(--fg); font-size:10px; pointer-events:none; user-select:none;
  paint-order: stroke; stroke: var(--bg); stroke-width: 3px; stroke-linejoin: round; }
.label.dim { opacity:0.18; }
.label.minor { opacity:0; }
.node:hover + .label, g.nodes:hover ~ .labels .label { opacity: 1; }
.link { stroke:#5a5d63; stroke-opacity:0.4; }
.link.dim { stroke-opacity:0.05; }
.failed { stroke:#e74c3c; stroke-dasharray: 3 2; }
</style>
</head>
<body>
<div id="hud">
  <input id="search" placeholder="搜节点 (slug / alias)…" />
  <span id="stats"></span>
</div>
<div id="legend"></div>
<div id="tip"></div>
<svg></svg>
<script>
const DATA = __DATA__;
const COLOR = __COLOR__;
const OPEN_ENDPOINT = "http://127.0.0.1:__PORT__/open";

// Legend
const legend = d3.select("#legend");
Object.entries(COLOR).forEach(([k,v]) => {
  legend.append("div").html(`<span class="dot" style="background:${v}"></span>${k}`);
});

const svg = d3.select("svg");
const W = () => window.innerWidth, H = () => window.innerHeight;
const g = svg.append("g");

const zoom = d3.zoom().scaleExtent([0.2, 4]).on("zoom", e => g.attr("transform", e.transform));
svg.call(zoom);

const sim = d3.forceSimulation(DATA.nodes)
  .force("link", d3.forceLink(DATA.edges).id(d => d.id).distance(110).strength(0.5))
  .force("charge", d3.forceManyBody().strength(-360))
  .force("center", d3.forceCenter(W()/2, H()/2))
  .force("collide", d3.forceCollide(d => 28 + d.importance * 3))
  .force("x", d3.forceX(W()/2).strength(0.04))
  .force("y", d3.forceY(H()/2).strength(0.04));

const link = g.append("g").attr("class","links")
  .selectAll("line").data(DATA.edges).enter().append("line")
    .attr("class","link");

const node = g.append("g").attr("class","nodes")
  .selectAll("circle").data(DATA.nodes).enter().append("circle")
    .attr("class", d => "node" + (d.lifecycle === "failed" ? " failed" : ""))
    .attr("r", d => 4 + d.importance * 1.6)
    .attr("fill", d => COLOR[d.type] || COLOR.unknown)
    .call(drag(sim))
    .on("click", (e, d) => openInPycharm(d.path))
    .on("mouseenter", showTip)
    .on("mouseleave", hideTip);

const label = g.append("g").attr("class","labels")
  .selectAll("text").data(DATA.nodes).enter().append("text")
    .attr("class","label")
    .attr("dx", 7).attr("dy", 3)
    .text(d => d.id);

sim.on("tick", () => {
  link.attr("x1", d => d.source.x).attr("y1", d => d.source.y)
      .attr("x2", d => d.target.x).attr("y2", d => d.target.y);
  node.attr("cx", d => d.x).attr("cy", d => d.y);
  label.attr("x", d => d.x).attr("y", d => d.y);
});

document.getElementById("stats").textContent =
  `${DATA.nodes.length} nodes · ${DATA.edges.length} edges`;

const tip = document.getElementById("tip");
function showTip(e, d) {
  tip.innerHTML = `<div class="t">${d.id}</div>` +
    `<div class="meta">${d.type}${d.status?" · "+d.status:""}${d.lifecycle?" · "+d.lifecycle:""} · imp ${d.importance}</div>` +
    `<div class="tldr">${d.tldr || "(无 TL;DR)"}</div>`;
  tip.style.display = "block";
  moveTip(e);
}
function moveTip(e){ tip.style.left=(e.pageX+12)+"px"; tip.style.top=(e.pageY+12)+"px"; }
function hideTip(){ tip.style.display="none"; }
document.addEventListener("mousemove", e => { if (tip.style.display==="block") moveTip(e); });

function openInPycharm(relpath) {
  // 用 <img> 触发 GET，避开 file:// → localhost 的 CORS / 混合内容限制
  // 服务端返回 204/任意非图片，img 加载失败但请求一定到达
  const img = new Image();
  img.src = `${OPEN_ENDPOINT}?path=${encodeURIComponent(relpath)}&_=${Date.now()}`;
}

function drag(sim) {
  return d3.drag()
    .on("start", (e,d) => { if(!e.active) sim.alphaTarget(0.3).restart(); d.fx=d.x; d.fy=d.y; })
    .on("drag",  (e,d) => { d.fx=e.x; d.fy=e.y; })
    .on("end",   (e,d) => { if(!e.active) sim.alphaTarget(0); d.fx=null; d.fy=null; });
}

// Search → highlight
const inp = document.getElementById("search");
inp.addEventListener("input", () => {
  const q = inp.value.trim().toLowerCase();
  const hit = new Set();
  if (q) {
    DATA.nodes.forEach(n => {
      if (n.id.toLowerCase().includes(q) || (n.tldr||"").toLowerCase().includes(q)) hit.add(n.id);
    });
  }
  node.classed("dim", d => q && !hit.has(d.id)).classed("hit", d => q && hit.has(d.id));
  label.classed("dim", d => q && !hit.has(d.id));
  link.classed("dim", d => q && !hit.has(d.source.id) && !hit.has(d.target.id));
});

window.addEventListener("resize", () =>
  sim.force("center", d3.forceCenter(W()/2, H()/2)).alpha(0.3).restart());
</script>
</body>
</html>
"""


def render(nodes, edges, port: int):
    data = json.dumps({"nodes": nodes, "edges": edges}, ensure_ascii=False)
    color = json.dumps(COLOR, ensure_ascii=False)
    html = (HTML_TEMPLATE
            .replace("__DATA__", data)
            .replace("__COLOR__", color)
            .replace("__PORT__", str(port)))
    OUT.write_text(html, encoding="utf-8")
    print(f"✅ {OUT}  —  {len(nodes)} nodes · {len(edges)} edges · port {port}")


if __name__ == "__main__":
    nodes, edges = collect()
    if not nodes:
        print("⚠️  wiki/ 下没有可入图的页面，已跳过", flush=True)
    else:
        render(nodes, edges, discover_port())
