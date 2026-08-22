#!/usr/bin/env bash
# uiautomator 语义定位工具（照 e2e/layer2.sh 的做法，⛔ 不用坐标硬敲、⛔ 不识图）
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
PKG=dev.agentmirror.app

dumpui() { "$ADB" shell "uiautomator dump /sdcard/pb.xml >/dev/null 2>&1; cat /sdcard/pb.xml" 2>/dev/null; }
ui_has() { case "$1" in *"$2"*) return 0;; *) return 1;; esac; }

# node_center <xmlfile> <text> [contains]  -> "cx cy"，精确等于优先；第三参 contains 时用包含
node_center() {
  python3 - "$1" "$2" "${3:-exact}" <<'PYEOF'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
want, mode = sys.argv[2], sys.argv[3]
for m in re.finditer(r'<node[^>]*/?>', xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not (t and b):
        continue
    hit = (t.group(1) == want) if mode == 'exact' else (want in t.group(1))
    if hit:
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PYEOF
}

edittext_center() {
  python3 - "$1" "$2" <<'PYEOF'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
idx = int(sys.argv[2]); n = 0
for m in re.finditer(r'<node[^>]*/?>', xml):
    node = m.group(0)
    cls = re.search(r'class="([^"]*)"', node)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if cls and 'EditText' in cls.group(1) and b:
        if n == idx:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2); break
        n += 1
PYEOF
}

# tap_text <tmpxml> <text> [contains]
tap_text() {
  local f="$1" want="$2" mode="${3:-exact}" c
  dumpui > "$f"
  c=$(node_center "$f" "$want" "$mode")
  [ -n "$c" ] || { echo "TAPFAIL: 未找到节点 '$want'" >&2; return 1; }
  "$ADB" shell input tap ${c% *} ${c#* } >/dev/null 2>&1
}
