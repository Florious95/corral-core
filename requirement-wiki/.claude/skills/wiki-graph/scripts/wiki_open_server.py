#!/usr/bin/env python3
"""wiki-open-server — 极小 HTTP 服务，让 _graph.html 节点点击 → 在 PyCharm 编辑器开 tab。

跨平台自适应：
- Python 解释器：用 #! 任意 python3 启动即可
- PyCharm 路径：env(PYCHARM_PATH) → 缓存 → PATH → 平台 glob → Toolbox
- 端口：默认 7777，被占自动选空闲，写到 .wiki-runtime/port

启动方式（任选其一）：
  python3 .claude/skills/wiki-graph/scripts/wiki_open_server.py
  python  .claude/skills/wiki-graph/scripts/wiki_open_server.py
或在 PyCharm Run Configuration 里指定本脚本为 Script path。

环境变量（全部可选）：
  PYCHARM_PATH   覆盖自动探测，直接指定 PyCharm 可执行文件
  WIKI_OPEN_PORT 指定监听端口，默认 7777
"""
from __future__ import annotations

import glob
import os
import platform
import shutil
import socket
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

# 项目根 = 本脚本往上走 4 层（scripts → wiki-graph → skills → .claude → root）
PROJECT_ROOT = Path(__file__).resolve().parents[4]
RUNTIME_DIR = PROJECT_ROOT / ".wiki-runtime"
PORT_FILE = RUNTIME_DIR / "port"
PYCHARM_CACHE = RUNTIME_DIR / "pycharm-path"

DEFAULT_PORT = int(os.environ.get("WIKI_OPEN_PORT", "7777"))

IS_WINDOWS = sys.platform == "win32"
IS_MACOS = sys.platform == "darwin"
IS_WSL = (not IS_WINDOWS and not IS_MACOS) and "microsoft" in platform.release().lower()


# ─────────────────────────────────────────────────────────────────────
# PyCharm 路径自动探测
# ─────────────────────────────────────────────────────────────────────

def _candidates_windows() -> list[str]:
    out: list[str] = []
    for d in ("C:", "D:", "E:", "F:"):
        out += glob.glob(rf"{d}\Program Files\JetBrains\PyCharm*\bin\pycharm64.exe")
        out += glob.glob(rf"{d}\Program Files (x86)\JetBrains\PyCharm*\bin\pycharm64.exe")
    home = os.path.expanduser("~")
    out += glob.glob(rf"{home}\AppData\Local\Programs\PyCharm*\bin\pycharm64.exe")
    out += glob.glob(rf"{home}\AppData\Local\JetBrains\Toolbox\apps\PyCharm*\**\bin\pycharm64.exe",
                     recursive=True)
    return out


def _candidates_wsl() -> list[str]:
    out: list[str] = []
    for d in ("c", "d", "e", "f"):
        out += glob.glob(f"/mnt/{d}/Program Files/JetBrains/PyCharm*/bin/pycharm64.exe")
        out += glob.glob(f"/mnt/{d}/Program Files (x86)/JetBrains/PyCharm*/bin/pycharm64.exe")
        out += glob.glob(f"/mnt/{d}/Users/*/AppData/Local/Programs/PyCharm*/bin/pycharm64.exe")
        out += glob.glob(
            f"/mnt/{d}/Users/*/AppData/Local/JetBrains/Toolbox/apps/PyCharm*/**/bin/pycharm64.exe",
            recursive=True)
    return out


def _candidates_macos() -> list[str]:
    home = os.path.expanduser("~")
    out: list[str] = []
    out += glob.glob("/Applications/PyCharm*.app/Contents/MacOS/pycharm")
    out += glob.glob(f"{home}/Applications/PyCharm*.app/Contents/MacOS/pycharm")
    out += glob.glob(
        f"{home}/Library/Application Support/JetBrains/Toolbox/apps/PyCharm*/**/MacOS/pycharm",
        recursive=True)
    return out


def _candidates_linux() -> list[str]:
    home = os.path.expanduser("~")
    out: list[str] = []
    out += glob.glob("/opt/pycharm*/bin/pycharm.sh")
    out += glob.glob("/opt/pycharm*/bin/pycharm")
    out += glob.glob("/snap/pycharm-*/current/bin/pycharm.sh")
    out += glob.glob(f"{home}/.local/share/JetBrains/Toolbox/apps/PyCharm*/**/bin/pycharm.sh",
                     recursive=True)
    out += glob.glob(f"{home}/.local/share/JetBrains/Toolbox/apps/PyCharm*/**/bin/pycharm",
                     recursive=True)
    return out


def find_pycharm() -> str | None:
    # 1. 显式覆盖
    if v := os.environ.get("PYCHARM_PATH"):
        if Path(v).exists():
            return v

    # 2. 缓存
    if PYCHARM_CACHE.exists():
        try:
            cached = PYCHARM_CACHE.read_text(encoding="utf-8").strip()
            if cached and Path(cached).exists():
                return cached
        except Exception:
            pass

    # 3. PATH
    for cmd in ("pycharm64.exe", "pycharm", "charm", "pycharm.sh"):
        if v := shutil.which(cmd):
            return v

    # 4. 平台 glob
    if IS_WINDOWS:
        cands = _candidates_windows()
    elif IS_WSL:
        cands = _candidates_wsl()
    elif IS_MACOS:
        cands = _candidates_macos()
    else:
        cands = _candidates_linux()

    cands = sorted({c for c in cands if Path(c).exists()})
    if not cands:
        return None
    chosen = cands[-1]  # 同名一般版本号大的排后面
    try:
        RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
        PYCHARM_CACHE.write_text(chosen, encoding="utf-8")
    except Exception:
        pass
    return chosen


# ─────────────────────────────────────────────────────────────────────
# 路径转换 & 端口选择
# ─────────────────────────────────────────────────────────────────────

def to_native_path(p: Path) -> str:
    """传给 pycharm 的路径。WSL 下要把 /mnt/... 转成 Windows 路径。"""
    if IS_WSL:
        try:
            return subprocess.check_output(["wslpath", "-w", str(p)], text=True).strip()
        except Exception:
            return str(p)
    return str(p)


def pick_port(preferred: int = DEFAULT_PORT) -> int:
    for port in [preferred] + [p for p in range(7778, 7800) if p != preferred]:
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind(("127.0.0.1", port))
            return port
        except OSError:
            continue
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


# ─────────────────────────────────────────────────────────────────────
# HTTP handler
# ─────────────────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):
    pycharm_path: str | None = None  # main 里注入

    def do_OPTIONS(self):
        self._send(204, "")

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/health":
            return self._send(200, "ok")
        if u.path != "/open":
            return self._send(404, "no route")

        qs = parse_qs(u.query)
        rel = unquote(qs.get("path", [""])[0])
        if not rel:
            return self._send(400, "missing ?path=")

        abs_path = (PROJECT_ROOT / rel).resolve()
        try:
            abs_path.relative_to(PROJECT_ROOT)
        except ValueError:
            return self._send(403, "path traversal blocked")
        if not abs_path.exists():
            return self._send(404, f"not found: {rel}")

        if not Handler.pycharm_path:
            return self._send(500, "PyCharm executable not found; set PYCHARM_PATH env var")

        try:
            target = to_native_path(abs_path)
            subprocess.Popen([Handler.pycharm_path, target], shell=False,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            print(f"→ 打开 {rel}", flush=True)
            return self._send(204, "")
        except Exception as e:
            return self._send(500, f"failed to spawn: {e}")

    def _send(self, code: int, body: str):
        self.send_response(code)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        if body:
            self.wfile.write(body.encode("utf-8"))

    def log_message(self, fmt, *args):
        pass


# ─────────────────────────────────────────────────────────────────────
# main
# ─────────────────────────────────────────────────────────────────────

def main():
    pycharm = find_pycharm()
    Handler.pycharm_path = pycharm

    port = pick_port(DEFAULT_PORT)
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    PORT_FILE.write_text(str(port), encoding="utf-8")

    print(f"✅ wiki-open-server   http://127.0.0.1:{port}")
    print(f"   PROJECT_ROOT      {PROJECT_ROOT}")
    print(f"   PyCharm           {pycharm or '(未找到 — /open 将返回 500，请设 PYCHARM_PATH)'}")
    print(f"   Platform          {'WSL' if IS_WSL else sys.platform}")
    print(f"   Port file         {PORT_FILE}")
    print(f"   Test              curl 'http://127.0.0.1:{port}/health'")
    if not pycharm:
        print(f"\n⚠️  未找到 PyCharm 可执行文件。可手动指定：")
        print(f"   export PYCHARM_PATH=/path/to/pycharm64.exe   # 或 .sh / 二进制")

    try:
        HTTPServer(("127.0.0.1", port), Handler).serve_forever()
    except KeyboardInterrupt:
        print("\n👋 stopped")
    finally:
        try:
            PORT_FILE.unlink()
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
