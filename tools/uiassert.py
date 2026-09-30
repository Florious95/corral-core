#!/usr/bin/env python3
"""安卓 UI 树断言：拿结构化文本判，不用多模态读图。

用户 2026-08-19 令：「要考虑一个以 MCP 为基础去测试的办法，而不是频繁实图、
频繁去识别图片、使用多模态去识别图片。」

安卓侧没有现成 MCP，但 `uiautomator dump` 就是等价物——它吐出整棵 UI 树的
结构化 XML，`text` 属性就是屏幕上的字。⇒ 判据可以直接断言内容，
而不是像以前那样只核「三张截图 md5 互不相同」——那个连"图里画的是什么"都没看。

用法（退出码即判据）：
  uiassert.py dump                       # 打印当前屏幕全部可见文本
  uiassert.py has   "远控 leader" ...     # 全部出现 → 0
  uiassert.py absent "claude_code" ...    # 全部不出现 → 0
  uiassert.py distinct 3 --among leader "远控 leader" team-leader-2
                                          # 这些串两两不同且都在屏上 → 0
  uiassert.py save /path/ui.xml          # 存一份 XML 快照当证据
环境：ADB 可用 ADB= 覆盖；默认 ~/Library/Android/sdk/platform-tools/adb
"""
import os, re, subprocess, sys

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))

def tree():
    r = subprocess.run([ADB, "exec-out", "uiautomator", "dump", "/dev/tty"],
                       capture_output=True, timeout=60)
    x = r.stdout.decode("utf-8", "replace")
    if "<hierarchy" not in x:
        sys.exit("⛔ 取不到 UI 树（设备没连？应用没在前台？）—— 尺子坏了和被测空闲同形，直接判不可用")
    return x

def texts(x):
    return [t for t in re.findall(r'text="([^"]*)"', x) if t.strip()]

def main():
    a = sys.argv[1:]
    if not a: sys.exit(__doc__)
    cmd, rest = a[0], a[1:]
    if cmd == "save":
        open(rest[0], "w", encoding="utf-8").write(tree()); print(rest[0]); return
    ts = texts(tree())
    if cmd == "dump":
        for t in ts: print(t)
    elif cmd == "has":
        miss = [n for n in rest if not any(n in t for t in ts)]
        if miss: sys.exit("缺失: %s\n屏上有: %s" % (miss, ts))
    elif cmd == "absent":
        hit = [n for n in rest if any(n in t for t in ts)]
        if hit: sys.exit("不该出现却出现了: %s\n屏上有: %s" % (hit, ts))
    elif cmd == "distinct":
        n = int(rest[0]); names = rest[rest.index("--among") + 1:]
        miss = [x for x in names if x not in ts]
        if miss: sys.exit("这些串不在屏上: %s\n屏上有: %s" % (miss, ts))
        if len(set(names)) < n: sys.exit("互异数 %d < 要求 %d" % (len(set(names)), n))
    else:
        sys.exit("未知命令 " + cmd)

main()
