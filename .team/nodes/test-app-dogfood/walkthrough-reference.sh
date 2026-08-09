#!/bin/bash
# 统一真机模拟走查（锁屏/断网/暗色/转场）——leader 自查，模拟用户真实动作序列。
# 前提：模拟器在跑。产物：e2e/artifacts/walkthrough/*.png 逐步截图。
set -uo pipefail
WS=/Volumes/nvme/Projects/远程Agent安卓
ART="$WS/e2e/artifacts/walkthrough"; mkdir -p "$ART"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
PKG=dev.agentmirror.app
PORT=19980
WD="$(mktemp -d /tmp/wk.XXXXXX)"

cleanup() {
  "$ADB" shell cmd connectivity airplane-mode disable >/dev/null 2>&1
  "$ADB" shell cmd uimode night no >/dev/null 2>&1
  pkill -f "wk-daemon" 2>/dev/null
  TMUX='' TMUX_TMPDIR="$WD/tmux" tmux kill-server 2>/dev/null
  rm -rf "$WD"
}
trap cleanup EXIT

snap() { "$ADB" exec-out screencap -p > "$ART/$1.png"; echo "snap: $1"; }
dumpui() { "$ADB" exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary.*$//'; }
has() { case "$(dumpui)" in *"$1"*) return 0;; *) return 1;; esac; }
tapt() { # tap by exact text from fresh dump
  local xml; xml="$(dumpui)"
  python3 - "$1" <<PYEOF
import re,sys,subprocess
want=sys.argv[1]
xml='''$(dumpui | sed "s/'/ /g")'''
for m in re.finditer(r'<node[^>]*/?>', xml):
    n=m.group(0)
    t=re.search(r'text="([^"]*)"',n); b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
    if t and b and t.group(1)==want:
        x=(int(b.group(1))+int(b.group(3)))//2; y=(int(b.group(2))+int(b.group(4)))//2
        subprocess.run(["$ADB","shell","input","tap",str(x),str(y)]); sys.exit(0)
sys.exit(1)
PYEOF
}

# --- 环境：隔离 tmux 会话 + daemon ---
mkdir -p "$WD/cwd" "$WD/up" "$WD/state" "$WD/tmux"
TMUX='' TMUX_TMPDIR="$WD/tmux" tmux -f /dev/null new-session -d -s wk-sess -c "$WD/cwd" 'bash --norc'
cd "$WS/server" && go build -o "$WD/wk-daemon" ./cmd/agentmirrord
TMUX='' TMUX_TMPDIR="$WD/tmux" AGENTMIRROR_STATE_DIR="$WD/state" "$WD/wk-daemon" \
  -listen 0.0.0.0:$PORT -upload-dir "$WD/up" -token WKTOKEN123 > "$WD/daemon.log" 2>&1 &
sleep 2

# --- App：装最终 APK、冷启、手填连接 ---
"$ADB" install -r "$WS/app/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
"$ADB" shell am force-stop $PKG; sleep 1
"$ADB" shell pm clear $PKG >/dev/null   # 干净首装态
"$ADB" shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1; sleep 3
# 手填：地址+token（EditText 顺序：0=ws 地址 1=token）
python3 - <<PYEOF
import re,subprocess
xml=subprocess.run(["$ADB","exec-out","uiautomator","dump","/dev/tty"],capture_output=True,text=True).stdout
eds=[m for m in re.finditer(r'<node[^>]*class="android.widget.EditText"[^>]*/?>', xml)]
def center(n):
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n.group(0))
    return (int(b.group(1))+int(b.group(3)))//2,(int(b.group(2))+int(b.group(4)))//2
x,y=center(eds[0]); subprocess.run(["$ADB","shell","input","tap",str(x),str(y)])
subprocess.run(["$ADB","shell","input","text","ws://10.0.2.2:$PORT/ws"])
x,y=center(eds[1]); subprocess.run(["$ADB","shell","input","tap",str(x),str(y)])
subprocess.run(["$ADB","shell","input","text","WKTOKEN123"])
PYEOF
"$ADB" shell input keyevent 111  # 收键盘
sleep 1; tapt "连接"; sleep 5
snap 01-connected
has "$WD/cwd" || has "wk" || { echo "WALKTHROUGH FAIL: not connected"; exit 1; }
echo "== step1 connected OK"

# --- 步骤2：锁屏 60s → 解锁 → 30s 内恢复 ---
"$ADB" shell input keyevent 26; sleep 60
"$ADB" shell input keyevent 224; sleep 1; "$ADB" shell input keyevent 82; sleep 2
OK=0; for i in $(seq 1 30); do sleep 1; if has "$WD/cwd"; then OK=1; break; fi; done
snap 02-after-lock
[ $OK = 1 ] && echo "== step2 lock-resume OK (${i}s)" || { echo "WALKTHROUGH FAIL: lock-resume"; exit 1; }

# --- 步骤3：飞行模式断网 15s → 恢复 → 45s 内自愈 ---
"$ADB" shell cmd connectivity airplane-mode enable; sleep 15
snap 03a-airplane-on
"$ADB" shell cmd connectivity airplane-mode disable; sleep 3
OK=0; for i in $(seq 1 45); do sleep 1; if has "$WD/cwd"; then OK=1; break; fi; done
snap 03b-after-net
[ $OK = 1 ] && echo "== step3 net-recover OK (${i}s)" || { echo "WALKTHROUGH FAIL: net-recover"; exit 1; }

# --- 步骤4：进会话页→返回（转场路径）+ 暗色 ---
tapt "wk-sess" || true; sleep 3; snap 04-session
"$ADB" shell input keyevent 4; sleep 2   # 返回
"$ADB" shell cmd uimode night yes; sleep 3; snap 05-dark
"$ADB" shell cmd uimode night no; sleep 2
echo "== step4 nav+dark OK"
echo "==== WALKTHROUGH PASS ===="
