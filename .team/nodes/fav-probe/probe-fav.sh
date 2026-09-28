#!/usr/bin/env bash
# probe-fav.sh — 067 收藏 + 三栏 根因探针
#
# ① 收藏记录不含标题字符串（标题随任务摘要变，当键会错位/丢失）
# ② 写入收藏 → 重建 App 状态 → 仍在（持久化）
# ③ 收藏的会话消失后该行仍在且置灰（须先在「自动移除」写法上验红）
# ④ 冷启动落在中间页（会话），不是收藏页
#
# 不启动模拟器、不碰用户默认 tmux、不扫 argv。
set -u
fail() { echo "FAIL $1"; exit 1; }
pass() { echo "PASS $1"; }

ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"
APP_MAIN="$ROOT/app/app/src/main"
[ -d "$APP_MAIN" ] || fail "找不到 app/app/src/main（ROOT=$ROOT）"

export FAV_PROBE_ROOT="$ROOT"
export FAV_PROBE_MAIN="$APP_MAIN"

python3 - <<'PY'
import json, os, re, sys
from pathlib import Path

ROOT = Path(os.environ["FAV_PROBE_ROOT"])
MAIN = Path(os.environ["FAV_PROBE_MAIN"])

TITLE = "PROBE_TITLE_067_TASKSUMMARY_WILL_CHANGE_xyz"
SESS = "favprobe_sess"
WIN_I = "13"
WIN_N = "favprobe_win"
ADDED = 1_700_000_000_123
KEY = (SESS, WIN_I, WIN_N)


def fail_line(msg):
    print("FAIL " + msg)


def pass_line(msg):
    print("PASS " + msg)


# ---------------------------------------------------------------------------
# World: persist a favorite, rebuild, reconcile against a live session list.
# ---------------------------------------------------------------------------
class FavWorld:
    def __init__(self, persist_fields, reconcile_policy):
        self.persist_fields = list(persist_fields)
        self.reconcile_policy = reconcile_policy
        self.records = []
        self.blob = ""

    def add(self, session_name, window_index, window_name, title, added_at):
        values = {
            "session_name": session_name,
            "sessionName": session_name,
            "window_index": window_index,
            "windowIndex": window_index,
            "window_name": window_name,
            "windowName": window_name,
            "title": title,
            "pane_title": title,
            "paneTitle": title,
            "added_at": added_at,
            "addedAt": added_at,
            "addedAtMs": added_at,
            "added_at_ms": added_at,
        }
        rec = {}
        for field in self.persist_fields:
            rec[field] = values.get(field, "")
        self.records.append(rec)
        self.blob = json.dumps(self.records, ensure_ascii=False)

    def rebuild(self):
        other = FavWorld(self.persist_fields, self.reconcile_policy)
        other.blob = self.blob
        other.records = json.loads(self.blob) if self.blob else []
        return other

    def _key_of(self, rec):
        sess = rec.get("session_name") or rec.get("sessionName") or ""
        wi = rec.get("window_index") or rec.get("windowIndex") or ""
        wn = rec.get("window_name") or rec.get("windowName") or ""
        return (sess, wi, wn)

    def rows(self, live_keys):
        live = set(live_keys)
        if self.reconcile_policy == "auto_remove":
            out = []
            for rec in self.records:
                if self._key_of(rec) in live:
                    out.append({"key": self._key_of(rec), "online": True, "gray": False})
            return out
        if self.reconcile_policy == "keep_gray":
            out = []
            for rec in self.records:
                k = self._key_of(rec)
                online = k in live
                out.append({"key": k, "online": online, "gray": not online})
            return out
        return []


def assert_key_no_title(world, label):
    blob = world.blob or ""
    title_in = TITLE in blob
    has_sess = SESS in blob
    has_wi = WIN_I in blob
    has_wn = WIN_N in blob
    print(
        f"{label} ① operands blob={blob!r} title_in_blob={title_in} "
        f"has_session_name={has_sess} has_window_index={has_wi} has_window_name={has_wn}"
    )
    if not blob or not world.records:
        fail_line(f"{label} ① 写入收藏后没有持久化记录")
        return False
    if title_in:
        fail_line(f"{label} ① 收藏记录含标题字符串 {TITLE!r}")
        return False
    if not (has_sess and has_wi and has_wn):
        fail_line(
            f"{label} ① 收藏记录缺结构字段 "
            f"session_name={has_sess} window_index={has_wi} window_name={has_wn}"
        )
        return False
    pass_line(f"{label} ① 记录无标题且含结构字段")
    return True


def assert_persist(world, label):
    rebuilt = world.rebuild()
    keys = [rebuilt._key_of(r) for r in rebuilt.records]
    print(f"{label} ② operands before_n={len(world.records)} after_keys={keys!r} want={KEY!r}")
    if KEY in keys:
        pass_line(f"{label} ② 重建后收藏仍在")
        return True
    fail_line(f"{label} ② 重建 App 状态后收藏丢失 keys={keys!r}")
    return False


def assert_ghost(world, label):
    live_before = [KEY]
    live_after = []
    rows_before = world.rows(live_before)
    rows_after = world.rows(live_after)
    hit = next((r for r in rows_after if r["key"] == KEY), None)
    present = hit is not None
    gray = bool(hit["gray"]) if hit else False
    online = bool(hit["online"]) if hit else None
    print(
        f"{label} ③ operands live_before={live_before} live_after={live_after} "
        f"rows_before={rows_before} rows_after={rows_after} "
        f"present={present} gray={gray} online={online}"
    )
    if present and gray and online is False:
        pass_line(f"{label} ③ 会话消失后该行仍在且置灰")
        return True
    fail_line(
        f"{label} ③ 会话消失后未保留置灰行 present={present} gray={gray} online={online}"
    )
    return False


def assert_cold_start(pages, initial, label):
    print(
        f"{label} ④ operands pages={pages!r} page_count={len(pages)} initial={initial!r} "
        f"want_pages=['favorites','sessions','settings'] want_initial='sessions'"
    )
    if pages == ["favorites", "sessions", "settings"] and initial == "sessions":
        pass_line(f"{label} ④ 冷启动落在中间页（会话）")
        return True
    fail_line(
        f"{label} ④ 冷启动不是中间会话页 pages={pages!r} initial={initial!r}"
    )
    return False


# ---------------------------------------------------------------------------
# Phase 0: ③ must go red on the auto-remove implementation.
# ---------------------------------------------------------------------------
print("=== phase 0 自动移除写法（③ 必须红）===")
auto = FavWorld(
    persist_fields=["session_name", "window_index", "window_name", "added_at"],
    reconcile_policy="auto_remove",
)
auto.add(SESS, WIN_I, WIN_N, TITLE, ADDED)
auto_ok1 = assert_key_no_title(auto, "auto-remove")
auto_ok2 = assert_persist(auto, "auto-remove")
auto_ok3 = assert_ghost(auto, "auto-remove")
if auto_ok3:
    print("PROBE BROKEN: ③ 在自动移除写法上竟然绿了（断言没打到『行还在且置灰』）")
    sys.exit(2)
print(
    "RED-EVIDENCE ③ auto-remove: "
    "favorite written, session dropped from live list, row vanished "
    "(auto-remove filtered it out) → ③ FAIL as required"
)
if not auto_ok1 or not auto_ok2:
    print("note: auto-remove 世界的 ①② 用结构字段落盘，本应绿；上面已打印操作数")

# ---------------------------------------------------------------------------
# Product inspection — execute the product's persist/reconcile/pager algorithm
# on the same concrete world. No file-exists check as a pass criterion.
# ---------------------------------------------------------------------------
IDENT_FIELDS = {
    "session_name", "sessionName",
    "window_index", "windowIndex",
    "window_name", "windowName",
}
TITLE_FIELDS = {"title", "pane_title", "paneTitle", "pane_title_raw"}
TIME_FIELDS = {"added_at", "addedAt", "addedAtMs", "added_at_ms", "addedAtEpochMs"}


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    src = re.sub(r"//.*?$", " ", src, flags=re.M)
    return src


def load_main_kotlin():
    files = []
    if not MAIN.is_dir():
        return files
    for p in MAIN.rglob("*.kt"):
        try:
            text = p.read_text(encoding="utf-8")
        except OSError:
            continue
        files.append((p, text, strip_comments(text)))
    return files


def is_favorite_impl(path, raw, code):
    name = path.name.lower()
    if "favorite" in name or "favourite" in name:
        return True
    if re.search(r"(data\s+class|class|interface|object|enum\s+class)\s+\w*Favorite", code):
        return True
    if re.search(r"(data\s+class|class|interface|object)\s+\w*(Starred|Bookmark)\w*", code):
        return True
    return False


def parse_data_class_props(code, class_name):
    m = re.search(
        r"(?:data\s+)?class\s+" + re.escape(class_name) + r"\s*\((.*?)\)\s*(\{|:|$)",
        code,
        re.S,
    )
    if not m:
        return []
    body = m.group(1)
    props = []
    for pm in re.finditer(r"\bval\s+(\w+)\s*:", body):
        props.append(pm.group(1))
    return props


def discover_persist_fields(fav_files):
    fields = []
    seen = set()
    class_names = []
    for _, _, code in fav_files:
        class_names += re.findall(
            r"(?:data\s+)?class\s+(\w*(?:Favorite|Starred|Bookmark)\w*)",
            code,
        )
    displayish = ("row", "ui", "view", "vm", "state", "screen", "badge")
    persistish = []
    for name in class_names:
        low = name.lower()
        if any(s in low for s in displayish):
            continue
        if any(s in low for s in ("record", "entry", "item", "key", "store", "pref", "entity")):
            persistish.append(name)
    if not persistish:
        persistish = [n for n in class_names if not any(s in n.lower() for s in displayish)]
    for _, _, code in fav_files:
        for name in persistish:
            for prop in parse_data_class_props(code, name):
                if prop not in seen:
                    seen.add(prop)
                    fields.append(prop)
    return fields


def discover_reconcile_policy(fav_files):
    if not fav_files:
        return "none"
    code = "\n".join(c for _, _, c in fav_files)
    auto_hit = bool(
        re.search(
            r"\.(filter|retainAll|removeIf|removeAll|intersect)\s*[<{]",
            code,
        )
        or re.search(r"if\s*\([^)]*(live|online|present|exists)[^)]*\)\s*(continue|return)", code)
        or re.search(r"favorites?\s*=\s*favorites?\s*\.\s*filter", code)
    )
    keep_hit = bool(
        re.search(r"不在线|isOffline|offline|ghost|grayed|greyed", code)
        or re.search(r"\b(isOnline|online|gray|grey)\s*=", code)
    )
    # 有「按 live 丢掉」且没有置灰/不在线语义 → 自动移除
    if auto_hit and not keep_hit:
        return "auto_remove"
    if keep_hit:
        return "keep_gray"
    if auto_hit:
        return "auto_remove"
    return "none"


PAGE_ALIASES = {
    "favorites": "favorites",
    "favorite": "favorites",
    "favourite": "favorites",
    "favourites": "favorites",
    "starred": "favorites",
    "收藏": "favorites",
    "sessions": "sessions",
    "session": "sessions",
    "workspace": "sessions",
    "workspaces": "sessions",
    "会话": "sessions",
    "settings": "settings",
    "setting": "settings",
    "设置": "settings",
}


def canon_page(name):
    if not name:
        return None
    return PAGE_ALIASES.get(name.strip().lower())


def discover_pager(all_files):
    pages = []
    initial = None
    fav_related = []
    other = []
    for path, raw, code in all_files:
        name = path.name.lower()
        if any(k in name for k in ("pager", "threepane", "threecol", "home", "nav")):
            fav_related.append(code)
        elif re.search(r"HorizontalPager|rememberPagerState|ThreePane|HomePane|HomePage", code):
            fav_related.append(code)
        else:
            other.append(code)

    blob = "\n".join(fav_related) if fav_related else "\n".join(other)

    enum_m = re.search(
        r"enum\s+class\s+\w*(?:Page|Pane|Tab|Column)\w*\s*\{([^}]+)\}",
        blob,
    )
    if enum_m:
        entries = re.findall(r"\b([A-Za-z_\u4e00-\u9fff]\w*)\b", enum_m.group(1))
        for e in entries:
            c = canon_page(e)
            if c and c not in pages:
                pages.append(c)

    if not pages:
        listed = re.findall(
            r"(?:Favorites?|Sessions?|Settings|收藏|会话|设置)",
            blob,
        )
        for e in listed:
            c = canon_page(e)
            if c and c not in pages:
                pages.append(c)

    init_m = re.search(
        r"(?:rememberPagerState|PagerState)\s*\([^)]*initialPage\s*=\s*([^\s,)]+)",
        blob,
    )
    if not init_m:
        init_m = re.search(r"\binitialPage\s*=\s*([^\s,)]+)", blob)
    if init_m:
        expr = init_m.group(1).strip()
        if re.fullmatch(r"\d+", expr):
            idx = int(expr)
            if pages and 0 <= idx < len(pages):
                initial = pages[idx]
            elif idx == 1:
                initial = "sessions"
            elif idx == 0:
                initial = "favorites"
            elif idx == 2:
                initial = "settings"
        else:
            tail = expr.split(".")[-1]
            mapped = canon_page(tail)
            if mapped:
                initial = mapped
            elif pages:
                # Foo.Sessions.ordinal → sessions
                mapped = canon_page(tail.replace("ordinal", ""))
                if mapped:
                    initial = mapped

    # 三页常量 PAGE_FAVORITES=0 PAGE_SESSIONS=1 PAGE_SETTINGS=2
    const_map = {}
    for cm in re.finditer(
        r"const\s+val\s+(PAGE_|PANE_|TAB_)?(\w+)\s*=\s*(\d+)",
        blob,
    ):
        c = canon_page(cm.group(2))
        if c:
            const_map[int(cm.group(3))] = c
    if const_map and not pages:
        pages = [const_map[i] for i in sorted(const_map) if i in const_map]

    if initial is None and const_map:
        init_c = re.search(
            r"(?:INITIAL(?:_HOME)?_PAGE|DEFAULT_PAGE|START_PAGE)\s*=\s*(\d+|\w+)",
            blob,
        )
        if init_c:
            rawv = init_c.group(1)
            if rawv.isdigit() and int(rawv) in const_map:
                initial = const_map[int(rawv)]
            else:
                initial = canon_page(rawv)

    # 现在的壳：设置是布尔覆盖层，没有三页 Pager。不得把「进工作区」当成中间页。
    if "HorizontalPager" not in blob and "rememberPagerState" not in blob and "PagerState" not in blob:
        if len(pages) != 3:
            return [], None

    return pages, initial


kt_files = load_main_kotlin()
fav_files = [(p, r, c) for p, r, c in kt_files if is_favorite_impl(p, r, c)]
persist_fields = discover_persist_fields(fav_files)
policy = discover_reconcile_policy(fav_files)
pages, initial = discover_pager(kt_files)

print("=== product scan ===")
print(
    "favorite_impl_files="
    + json.dumps([str(p.relative_to(ROOT)) for p, _, _ in fav_files], ensure_ascii=False)
)
print(f"persist_fields={persist_fields!r} reconcile_policy={policy!r}")
print(f"pager pages={pages!r} initial={initial!r}")

product = FavWorld(persist_fields, policy)
# 只有产品声明了可落盘字段时才写入；否则世界保持「没收藏」。
if persist_fields:
    product.add(SESS, WIN_I, WIN_N, TITLE, ADDED)

print("=== phase 1 产品世界 ===")
fails = []
if not assert_key_no_title(product, "product"):
    fails.append("①")
if not assert_persist(product, "product"):
    fails.append("②")
if not assert_ghost(product, "product"):
    fails.append("③")
if not assert_cold_start(pages, initial, "product"):
    fails.append("④")

if fails:
    print("RED product " + ",".join(fails))
    sys.exit(1)
print("probe-fav ALL PASS")
sys.exit(0)
PY
rc=$?
if [ "$rc" -eq 2 ]; then
  fail "探针自证失败：③ 在自动移除写法上未红"
fi
if [ "$rc" -ne 0 ]; then
  fail "收藏探针未全绿（当前无收藏/三栏预期红）"
fi
pass "四条断言全过"
exit 0
