#!/usr/bin/env bash
# layer2.sh — e2e 安卓模拟器 smoke（知识基底 §1 层 2）。
#
# 职责：真实 emulator（-no-window）上 冷启动 → UI 关键节点可达断言 →
# 手填配对（10.0.2.2 映射到主机 daemon）→ 工作区可达断言。
#
# 分层：层 2 只验收 app 在模拟器上的可用性（进程活、配对页/工作区可达、配对链路通）。
# 与层 1 的关系：层 1 用 Go harness 走协议，层 2 验证 app 实机把协议跑起来。
#
# 红线：只读 app 产物与代码；不动真实 team-agent tmux socket；配对目标 daemon
# 由本脚本自起（隔离端口），随脚本退出。
#
# 失败留现场：uiautomator dump + daemon.log + app logcat 进 e2e/artifacts/。
#
# 用法：bash layer2.sh   （exit 0 = 过）

set -uo pipefail
E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ART="${E2E_ROOT}/artifacts"
mkdir -p "$ART"

# --- 净化：本脚本不触碰真实舰队；以下变量剥离（见 env.go cleanEnv 注释）。 ---
export -n TMUX_TMPDIR 2>/dev/null || true
unset TMUX 2>/dev/null || true

# --- Android SDK 定位（本机事实：主 SDK 在 /Volumes/nvme/android-sdk）。 ---
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
[ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ] || ANDROID_SDK_ROOT="/Volumes/nvme/android-sdk"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
if [ ! -x "$ADB" ]; then
  echo "LAYER2 FAIL: adb not found under $ANDROID_SDK_ROOT" | tee "$ART/layer2.fail"
  exit 1
fi
APK="$E2E_ROOT/../app/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "LAYER2 FAIL: apk missing: $APK" | tee "$ART/layer2.fail"; exit 1; }
PKG=dev.agentmirror.app
DAMON_BIN="$E2E_ROOT/bin/agentmirrord"
[ -x "$DAMON_BIN" ] || { echo "LAYER2 FAIL: daemon binary missing" | tee "$ART/layer2.fail"; exit 1; }

E2E_PASS=1
# 结果落盘：report_render 读 artifacts/layer2.json（非依赖 .fail 文件的「推断」）。
echo '{"pass": false, "at": "'$(date +%Y-%m-%dT%H:%M:%S)'"}' > "$ART/layer2.json"
# trap 收尾：杀自起 daemon（若还活着）；失败现场保留在 artifacts。
CLEANUP_PID=""
cleanup() {
  [ -n "$CLEANUP_PID" ] && kill "$CLEANUP_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "=== [layer2] adb: $ADB"
if ! "$ADB" devices | grep -q "emulator"; then
  echo "LAYER2 FAIL: no emulator online (run.sh 或手动 `emulator -avd <name> -no-window` 先起)"
  exit 1
fi
"$ADB" shell getprop sys.boot_completed 2>/dev/null | grep -q 1 \
  || { echo "LAYER2 FAIL: emulator not booted"; exit 1; }

# --- 1. 安装/覆盖安装 APK（幂等）。 ---
echo "=== [layer2] install $PKG"
"$ADB" install -r "$APK" >/dev/null 2>&1 || { echo "LAYER2 FAIL: install" | tee "$ART/layer2.fail"; exit 1; }

# --- 2. 起隔离 daemon（供配对；端口取高值随机区避免撞车）。 ---
PORT=$(( ( RANDOM % 2000 ) + 9000 ))
TOKEN="e2e-layer2-$(date +%s)"
TMPD="$(mktemp -d /tmp/e2e-l2.XXXXXX)"
TMUX_TMPDIR="$TMPD/tmux" AGENTMIRROR_TOKEN="$TOKEN" \
  "$DAMON_BIN" -listen "0.0.0.0:$PORT" -upload-dir "$TMPD/uploads" \
  -log-level debug -list-interval 500ms >"$TMPD/daemon.log" 2>&1 &
CLEANUP_PID=$!
# 等端口起来（带超时）。
for i in $(seq 1 20); do
  (echo >/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break
  sleep 0.5
done

# --- 3. 冷启动 smoke：清数据→冷启→进程活 + 配对页节点断言。 ---
echo "=== [layer2] cold-start smoke"
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell pm clear "$PKG" >/dev/null 2>&1
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 4
PID=$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')
[ -n "$PID" ] || { echo "LAYER2 FAIL: app process dead after cold start" | tee "$ART/layer2.fail"; "$ADB" shell uiautomator dump "$ART/layer2.pairing-dead.xml" >/dev/null 2>&1; exit 1; }

dumpui() { "$ADB" shell "uiautomator dump /sdcard/l2.xml >/dev/null 2>&1; cat /sdcard/l2.xml" 2>/dev/null; }
UI="$(dumpui)"
for node in "连接主机" "手填连接" "扫码连接"; do
  case "$UI" in
    *"$node"*) ;;
    *) echo "LAYER2 FAIL: pairing page missing node: $node" | tee "$ART/layer2.fail"; exit 1 ;;
  esac
done
echo "=== [layer2] pairing page OK (nodes: 连接主机/手填连接/扫码连接)"

# --- 4. 手填配对 → 工作区（10.0.2.2 = 模拟器→主机映射）。 ---
# 注意：ws:// 明文需 app 允许（fix-app-network-manifest 在途）；修复前此段红是预期。
echo "=== [layer2] manual pairing ws://10.0.2.2:$PORT/ws"
"$ADB" shell input tap 540 789   # 服务端 ws 地址 字段
sleep 1
"$ADB" shell input text "ws://10.0.2.2:$PORT/ws"
sleep 1
"$ADB" shell input tap 540 978   # 配对 token 字段
sleep 1
"$ADB" shell input text "$TOKEN"
sleep 1
"$ADB" shell input tap 540 1136  # 连接 按钮
WORKSPACE=0
for i in $(seq 1 20); do
  sleep 1
  UI="$(dumpui)"
  case "$UI" in
    *"暂无工作区"*|*"工作区"*) WORKSPACE=1; echo "=== [layer2] workspace reached after ${i}s"; break ;;
    *"配对成功"*) WORKSPACE=1; echo "=== [layer2] pairing success after ${i}s"; break ;;
    *"配对超时"*|*"拒绝"*|*"失败"*) echo "LAYER2 FAIL: pairing error: $(echo "$UI" | grep -oE 'text=\"[^\"]{2,40}\"' | grep -E '超时|拒绝|失败|错误' | head -1)"; break ;;
  esac
done
if [ "$WORKSPACE" != "1" ]; then
  # 失败留现场：dump + daemon log + 归因。
  echo "$UI" > "$ART/layer2.workspace-fail.xml"
  cp "$TMPD/daemon.log" "$ART/layer2.daemon.log" 2>/dev/null
  "$ADB" logcat -d -t 300 2>/dev/null | grep -iE "SecurityException|Cleartext|agentmirror" > "$ART/layer2.logcat.txt" 2>/dev/null || true
  echo "LAYER2 FAIL: pairing did not reach workspace (see artifacts/layer2.*)" | tee "$ART/layer2.fail"
  exit 1
fi

echo "=== [layer2] PASS (cold-start + pairing + workspace reachable)"
echo '{"pass": true, "at": "'$(date +%Y-%m-%dT%H:%M:%S)'"}' > "$ART/layer2.json"
exit 0
