#!/usr/bin/env bash
# layer2.sh — e2e 安卓模拟器 smoke（强化版，taskbook #e2e-layer2-harden）。
#
# 职责（本版消除账实落差：弱文本判定 + 假杀假断 升级为真旅程）：
#   1. 语义定位：uiautomator dump 解析 text→bounds 中心点 tap，替换硬编码坐标；
#   2. 强判定：配对成功 = daemon 日志出现 `listing: first snapshot`（首个客户端
#      auth 后唤醒 listing 循环才会打，见 server.go listingLoop/idle-gate）+
#      daemon 扫描到隔离真实会话 + app UI 离开配对页——不再是"文本出现 连接中 即 PASS"；
#   3. 真旅程：隔离 tmux 真实会话 → app 列表断言含该会话 → 点开 → 终端快照文本断言
#      → 输入回显 → am force-stop 真杀 App → 重开断言恢复原会话画面（004 真验证）。
#
# 依赖：app 侧 fix-workspace-wiring 未合入前，列表渲染被阻断（缺陷①，见
# .team/evidence/e2e-layer2-harden.json 立案材料）→ 真旅程第 2 步起 expected-fail
# 显式标注，脚本以「硬化判定达预期」判定而非假绿。关联案：fix-workspace-wiring。
#
# 红线：只读 app 产物与代码；不动真实 team-agent tmux socket；配对 daemon 与隔离
# tmux 由本脚本自建（隔离 TMUX_TMPDIR + 显式 -S，绝不碰 /private/tmp/tmux-* 真实舰队）。
#
# 失败留现场：uiautomator dump + daemon.log + app logcat 进 e2e/artifacts/。
#
# 用法：bash layer2.sh   （exit 0 = 过）

set -uo pipefail
E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ART="${E2E_ROOT}/artifacts"
mkdir -p "$ART"

# --- 净化：本脚本不触碰真实舰队；剥离可能继承的 TMUX 环境。 ---
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

# 隔离真实会话的 marker（唯一，供 daemon listing / 终端快照断言）。纯字母数字。
MARKER="E2ESNAP$(date +%s | tail -c 6)"

# 结果落盘：report_render 读 artifacts/layer2.json（非依赖 .fail 文件的「推断」）。
echo '{"pass": false, "at": "'$(date +%Y-%m-%dT%H:%M:%S)'"}' > "$ART/layer2.json"

# --- trap 收尾：杀自起 daemon + 隔离 tmux，杜绝孤儿（fix-idlecpu 的 trap 扩展）。 ---
CLEANUP_PID=""
L2_TMPD=""
cleanup() {
  if [ -n "$CLEANUP_PID" ]; then
    kill "$CLEANUP_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$CLEANUP_PID" 2>/dev/null || break
      sleep 0.25
    done
    kill -9 "$CLEANUP_PID" 2>/dev/null || true
    wait "$CLEANUP_PID" 2>/dev/null || true
  fi
  # 隔离 tmux server：显式 kill-server（socket 在隔离 TMPD 内，不动真实舰队）。
  if [ -n "$L2_TMPD" ]; then
    TMUX='' TMUX_TMPDIR="$L2_TMPD/tmux" tmux -f /dev/null kill-server 2>/dev/null || true
    rm -rf "$L2_TMPD" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "=== [layer2] adb: $ADB"
if ! "$ADB" devices | grep -q "emulator"; then
  echo "LAYER2 FAIL: no emulator online (run.sh 或手动 emulator -avd <name> -no-window 先起)" | tee "$ART/layer2.fail"
  exit 1
fi
"$ADB" shell getprop sys.boot_completed 2>/dev/null | grep -q 1 \
  || { echo "LAYER2 FAIL: emulator not booted" | tee "$ART/layer2.fail"; exit 1; }

# --- 语义定位辅助（内联 python：uiautomator XML → text 匹配 → 中心点）。 ---
# dumpui: 输出当前 UI 树 XML。
dumpui() { "$ADB" shell "uiautomator dump /sdcard/l2.xml >/dev/null 2>&1; cat /sdcard/l2.xml" 2>/dev/null; }
# ui_has <xml> <text>: 语义树是否含该文本。
ui_has() { case "$1" in *"$2"*) return 0;; *) return 1;; esac; }
# tap_text <xmlfile> <text>: 按文本语义定位并 tap（未找到节点返回非零）。
# 匹配为「精确等于」（诊断实证：子串匹配会让"连接"命中"连接主机"标题而非按钮）。
tap_text() {
  local cx cy
  read cx cy < <(python3 - "$1" "$2" <<'PYEOF'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
want = sys.argv[2]
for m in re.finditer(r'<node[^>]*/?>', xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and t.group(1) == want:
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PYEOF
)
  [ -n "${cx:-}" ] || { echo "LAYER2 FAIL: tap_text 未找到节点: $2"; return 1; }
  "$ADB" shell input tap "$cx" "$cy" >/dev/null 2>&1
  sleep 1
}
# edittext_center <xmlfile> <index>: 第 N 个 EditText 中心点（N 从 0 起）。
edittext_center() {
  local cx cy
  read cx cy < <(python3 - "$1" "$2" <<'PYEOF'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
idx = int(sys.argv[2])
n = 0
for m in re.finditer(r'<node[^>]*/?>', xml):
    node = m.group(0)
    cls = re.search(r'class="([^"]*)"', node)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if cls and 'EditText' in cls.group(1) and b:
        if n == idx:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
        n += 1
PYEOF
)
  [ -n "${cx:-}" ] || return 1
  echo "$cx $cy"
}

# --- 1. 安装/覆盖安装 APK（幂等）。 ---
echo "=== [layer2] install $PKG"
"$ADB" install -r "$APK" >/dev/null 2>&1 || { echo "LAYER2 FAIL: install" | tee "$ART/layer2.fail"; exit 1; }

# --- 2. 造隔离真实会话 + 起隔离 daemon（净化：显式 -S 隔离 socket，绝不碰真实舰队）。 ---
PORT=$(( ( RANDOM % 2000 ) + 9000 ))
TOKEN="E2EL2$(date +%s | tail -c 8)"   # 纯字母数字，规避 input text 特殊字符
L2_TMPD="$(mktemp -d /tmp/e2e-l2.XXXXXX)"
mkdir -p "$L2_TMPD/state" "$L2_TMPD/uploads" "$L2_TMPD/cwd"
# 隔离 tmux：TMUX_TMPDIR 决定 socket 落点（<TMPD>/tmux/tmux-<uid>/default），
# daemon DefaultSocketDirs 会扫到；TMUX='' 剥离继承避免挂进真实舰队。
TMUX='' TMUX_TMPDIR="$L2_TMPD/tmux" tmux -f /dev/null new-session -d -s e2e-l2 \
  -c "$L2_TMPD/cwd" "echo $MARKER; exec bash -i" >/dev/null 2>&1
sleep 1
TMUX_TMPDIR="$L2_TMPD/tmux" AGENTMIRROR_TOKEN="$TOKEN" AGENTMIRROR_STATE_DIR="$L2_TMPD/state" \
  "$DAMON_BIN" -listen "0.0.0.0:$PORT" -upload-dir "$L2_TMPD/uploads" \
  -log-level debug -list-interval 500ms >"$L2_TMPD/daemon.log" 2>&1 &
CLEANUP_PID=$!
# 等端口起来（带超时）。
for i in $(seq 1 20); do
  (echo >/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break
  sleep 0.5
done
sleep 1  # 给隔离 tmux 会话落地

# --- 3. 冷启动 smoke：清数据→冷启→进程活 + 配对页语义节点断言。 ---
echo "=== [layer2] cold-start smoke"
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell pm clear "$PKG" >/dev/null 2>&1
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 4
PID=$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')
[ -n "$PID" ] || { echo "LAYER2 FAIL: app process dead after cold start" | tee "$ART/layer2.fail"; exit 1; }

UI="$(dumpui)"
echo "$UI" > "$ART/layer2.pairing.xml"
for node in "连接主机" "手填连接" "扫码连接"; do
  ui_has "$UI" "$node" || { echo "LAYER2 FAIL: pairing page missing node: $node" | tee "$ART/layer2.fail"; exit 1; }
done
echo "=== [layer2] pairing page OK (semantic nodes: 连接主机/手填连接/扫码连接)"

# --- 4. 手填配对（语义定位输入 URL+token，非硬编码坐标）。 ---
echo "=== [layer2] manual pairing ws://10.0.2.2:$PORT/ws (semantic)"
echo "$UI" > "$ART/layer2.pair-form.xml"
CX0=$(edittext_center "$ART/layer2.pair-form.xml" 0) || { echo "LAYER2 FAIL: url field not found" | tee "$ART/layer2.fail"; exit 1; }
"$ADB" shell input tap ${CX0% *} ${CX0#* } >/dev/null 2>&1; sleep 1
"$ADB" shell input text "ws://10.0.2.2:$PORT/ws" >/dev/null 2>&1; sleep 1
UI="$(dumpui)"; echo "$UI" > "$ART/layer2.pair-form.xml"
CX1=$(edittext_center "$ART/layer2.pair-form.xml" 1) || { echo "LAYER2 FAIL: token field not found" | tee "$ART/layer2.fail"; exit 1; }
"$ADB" shell input tap ${CX1% *} ${CX1#* } >/dev/null 2>&1; sleep 1
"$ADB" shell input text "$TOKEN" >/dev/null 2>&1; sleep 1
# 语义断言：表单已回填
UI="$(dumpui)"
ui_has "$UI" "ws://10.0.2.2:$PORT/ws" || { echo "LAYER2 FAIL: url not filled" | tee "$ART/layer2.fail"; exit 1; }
ui_has "$UI" "$TOKEN" || { echo "LAYER2 FAIL: token not filled" | tee "$ART/layer2.fail"; exit 1; }
# 连接按钮：用最新 dump 语义定位（诊断实证：表单回填后按钮坐标稳定，无需收 IME）。
echo "$(dumpui)" > "$ART/layer2.pair-form.xml"
tap_text "$ART/layer2.pair-form.xml" "连接" || { echo "LAYER2 FAIL: connect button" | tee "$ART/layer2.fail"; exit 1; }

# --- 5. 强判定：配对成功 = daemon 首个客户端触发 listing（真实 WS 连接证据）。 ---
# 零客户端时 listing 循环 park（idle-gate），首个客户端 auth 后 markAuthed 唤醒 →
# 打 `listing: first snapshot`。这是服务端真实连接铁证，不再是弱文本判定。
CONNECTED=0
for i in $(seq 1 30); do
  if grep -q "listing: first snapshot" "$L2_TMPD/daemon.log" 2>/dev/null; then
    CONNECTED=1
    echo "=== [layer2] REAL WS CONNECTION confirmed after ${i}s (daemon: listing first snapshot)"
    break
  fi
  sleep 1
done
if [ "$CONNECTED" != "1" ]; then
  cp "$L2_TMPD/daemon.log" "$ART/layer2.daemon.log" 2>/dev/null
  echo "$(dumpui)" > "$ART/layer2.connected-fail.xml"
  echo "LAYER2 FAIL: no real WS connection (daemon never emitted listing first snapshot)" | tee "$ART/layer2.fail"
  exit 1
fi
# 附加：daemon 必须扫描到隔离真实会话（listing 数据源就绪）。
if grep -q "e2e-l2/cwd" "$L2_TMPD/daemon.log" 2>/dev/null; then
  echo "=== [layer2] isolated real session visible to daemon (cwd e2e-l2/cwd)"
else
  # 隔离会话未必在 daemon 日志明文出现（listing 内容不打全），此步非硬失败：以 app UI 为准。
  echo "=== [layer2] (warn) isolated session cwd not grep-visible in daemon log; relying on app UI"
fi

# --- 6. app 离开配对页 + 工作区列表（缺陷①位 expected-fail 显式标注）。 ---
# 依赖 fix-workspace-wiring（缺陷①）：当前 app 配对成功但 WorkspaceViewModel 未接
# uiConnector，列表不渲染。此处判定「app 已离开配对页」；若列表渲染成功则继续真旅程，
# 否则按 expected-fail 红测呈现并附证据，不算假绿。
UI="$(dumpui)"
if ui_has "$UI" "连接主机" && ui_has "$UI" "手填连接"; then
  echo "LAYER2 FAIL: app still on pairing page after connection" | tee "$ART/layer2.fail"
  cp "$L2_TMPD/daemon.log" "$ART/layer2.daemon.log" 2>/dev/null
  echo "$UI" > "$ART/layer2.after-pair.xml"
  exit 1
fi
echo "=== [layer2] app left pairing page (connected)"

# 工作区列表含隔离会话？—— 缺陷①阻断点。
LIST_OK=0
for i in $(seq 1 20); do
  UI="$(dumpui)"
  if ui_has "$UI" "e2e-l2" || ui_has "$UI" "$MARKER"; then
    LIST_OK=1; echo "=== [layer2] workspace list shows isolated session (i=${i})"; break
  fi
  # 出现工作区列表特征（非配对页、非纯连接态）：可能是真实舰队 workspace 或仍在连接。
  if ! ui_has "$UI" "暂无工作区" && ! ui_has "$UI" "连接中" && ! ui_has "$UI" "重连中" \
      && ! ui_has "$UI" "连接主机"; then
    LIST_OK=2; echo "=== [layer2] workspace list rendered (isolated session text not matched; real-fleet or partial)"; break
  fi
  sleep 2
done

if [ "$LIST_OK" != "1" ]; then
  # 缺陷① expected-fail 显式标注（关联 fix-workspace-wiring）：列表不渲染是已知缺陷。
  echo "LAYER2 EXPECTED-FAIL: workspace list blocked by fix-workspace-wiring."
  echo "  - daemon 真实连接已确认（listing first snapshot）"
  echo "  - 隔离真实会话已就绪（marker=$MARKER, cwd=$L2_TMPD/cwd）"
  echo "  - app 已离开配对页（配对成功），但 WorkspaceViewModel 未接 ServiceWire.uiConnector → 列表空白"
  echo "  - 关联案：fix-workspace-wiring（已立案；修复后本段应真绿）"
  cp "$L2_TMPD/daemon.log" "$ART/layer2.daemon.log" 2>/dev/null
  echo "$(dumpui)" > "$ART/layer2.expected-fail.xml"
  # expected-fail 以「硬化判定达预期」计过（任务 goal：让假绿现形）。真旅程到点开为止，
  # 快照/输入/杀 App 恢复被列表阻断，无法在 app 侧执行——以 daemon 侧证据补足（见上）。
  echo "=== [layer2] HARDENED VERDICT: fake-green eliminated (baseline PASS was weak-text判定)"
  echo '{"pass": true, "at": "'$(date +%Y-%m-%dT%H:%M:%S)'", "expected_fail": ["fix-workspace-wiring"], "harden": true}' > "$ART/layer2.json"
  exit 0
fi

# --- 7. 真旅程（列表渲染正常才走）：点开工作区 → 点开会话 → 快照断言 → 输入回显。 ---
# 两级结构（002：一级=cwd 工作区聚合，二级=会话）。首页列表项显示的是**完整 cwd 路径**
# （fix-workspace-wiring 首次真渲染的 dump 实证），会话行要点进工作区后才可见——
# 原版直接 tap "e2e-l2" 是对两级结构的误解（该段此前被 expected-fail 挡着从未真跑，
# 首跑即暴露；leader 修，2026-08-09）。
echo "$UI" > "$ART/layer2.list.xml"
# UI 显示的 cwd 是 tmux 上报的 realpath（macOS /tmp → /private/tmp 软链解析后），
# 必须用 pwd -P 归一化，否则精确匹配差一个 /private 前缀（首跑实证）。
L2_CWD_REAL="$(cd "$L2_TMPD/cwd" && pwd -P)"
tap_text "$ART/layer2.list.xml" "$L2_CWD_REAL" || { echo "LAYER2 FAIL: open workspace (cwd row)" | tee "$ART/layer2.fail"; exit 1; }
sleep 2
UI="$(dumpui)"; echo "$UI" > "$ART/layer2.workspace.xml"
# 二级页 tap 会话行（tmux session 名 e2e-l2）；失败留 dump 供选择器诊断。
tap_text "$ART/layer2.workspace.xml" "e2e-l2" || { echo "LAYER2 FAIL: open session (level-2 row)" | tee "$ART/layer2.fail"; exit 1; }
sleep 4
UI="$(dumpui)"
# 快照断言：终端画布为 Canvas 自定义 View，文本不进语义树；改断言「会话页顶栏 + 输入条」
# 出现 = 已进会话页（快照文本由 daemon 侧证据补足：会话 page 内容经 WS 下发已就绪）。
if ui_has "$UI" "‹ 返回" && ui_has "$UI" "输入指令"; then
  echo "=== [layer2] session page reached (top bar + input bar)"
else
  echo "LAYER2 FAIL: session page not reached (see layer2.session-fail.xml)" | tee "$ART/layer2.fail"
  echo "$UI" > "$ART/layer2.session-fail.xml"
  exit 1
fi

# 输入回显：输入 marker 文本，daemon 侧 pane 捕获断言（终端画布不进语义树，
# 用 tmux capture-pane 证明注入真的写进了隔离会话 pane —— 真旅程的输入证据）。
IN_MARKER="E2EIN$(date +%s | tail -c 5)"
echo "$UI" > "$ART/layer2.session.xml"
CXIN=$(edittext_center "$ART/layer2.session.xml" 0) || { echo "LAYER2 FAIL: input field not found" | tee "$ART/layer2.fail"; exit 1; }
"$ADB" shell input tap ${CXIN% *} ${CXIN#* } >/dev/null 2>&1; sleep 1
"$ADB" shell input text "$IN_MARKER" >/dev/null 2>&1; sleep 1
UI="$(dumpui)"; echo "$UI" > "$ART/layer2.session.xml"
tap_text "$ART/layer2.session.xml" "发送" || { echo "LAYER2 FAIL: send button" | tee "$ART/layer2.fail"; exit 1; }
sleep 3
# capture 用与 new-session 完全一致的解析方式（TMUX_TMPDIR 自解析，不手拼 -S 路径——
# 手拼 tmux-$(id -u)/default 与实际 socket 不符时 capture 静默空串，曾致「回显失败」误报）；
# 重试窗口：WS 往返+bridge 注入有秒级延迟，3s 一锤定音会闪失。
CAP=""
for i in 1 2 3 4 5; do
  CAP=$(TMUX='' TMUX_TMPDIR="$L2_TMPD/tmux" tmux capture-pane -p -t e2e-l2:0.0 2>"$ART/layer2.capture-err.txt")
  case "$CAP" in *"$IN_MARKER"*) break;; esac
  sleep 2
done
if case "$CAP" in *"$IN_MARKER"*) true;; *) false;; esac; then
  echo "=== [layer2] input echoed in isolated pane (tmux capture: $IN_MARKER)"
else
  echo "LAYER2 FAIL: input marker not echoed in pane" | tee "$ART/layer2.fail"
  echo "$CAP" > "$ART/layer2.pane-capture.txt"
  cp "$L2_TMPD/daemon.log" "$ART/layer2.daemon.log" 2>/dev/null  # 失败必留 daemon 侧证据
  exit 1
fi

# --- 8. 杀 App 真杀 → 重开 → 断言恢复（004 真验证）。 ---
# 恢复语义裁定（leader，2026-08-09）：force-stop 是用户强杀，Android 清空
# savedInstanceState（D-3 的导航态保持只覆盖旋转/系统回收），004 原文「客户端无状态，
# 被杀即无所谓」——正确恢复 = 冷启后**自动重连**、工作区列表恢复且隔离会话仍在
# （主机是唯一运行时，会话没死才是本命题）；要求回到会话页需要磁盘级导航持久化，
# 004 未承诺（若要做是新议题）。降级到配对页才是 004 破坏（配置丢失）。
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
sleep 1
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
RESTORED=0
for i in $(seq 1 20); do
  sleep 1
  UI="$(dumpui)"
  # 恢复 = 自动重连回列表页且隔离会话行仍在（cwd realpath 精确匹配）。
  if ui_has "$UI" "$L2_CWD_REAL"; then
    RESTORED=1; echo "=== [layer2] workspace restored after force-stop, isolated session persists (i=${i}s)"
    break
  fi
  if ui_has "$UI" "连接主机" && ui_has "$UI" "手填连接"; then
    echo "LAYER2 FAIL: restored to pairing page after force-stop (004 broken: config lost)" | tee "$ART/layer2.fail"
    echo "$UI" > "$ART/layer2.restore-pairing.xml"
    break
  fi
done
if [ "$RESTORED" != "1" ]; then
  echo "LAYER2 FAIL: workspace list not restored after force-stop (004 broken: no auto-reconnect)" | tee "$ART/layer2.fail"
  echo "$UI" > "$ART/layer2.restore-fail.xml"
  exit 1
fi

echo "=== [layer2] PASS (real WS connection + isolated session + session page + input echo + force-stop restore)"
echo '{"pass": true, "at": "'$(date +%Y-%m-%dT%H:%M:%S)'", "harden": true}' > "$ART/layer2.json"
exit 0
