# baseline-bundle-v1 resident driver 启动序列

本序列只适用于当前仓根和当前 Pi/Team Agent harness。当前没有可调用的受管后台提交器，故序列在 preflight/dry-run 后合法停机；下面仅记录未来提交器恢复后的硬约束：启动命令保持前台，禁止在 shell 中加 `&`，也禁止 `nohup`、自 daemonize 或通过真实 tmux 启动。

当前账本是 `.team/ledgers/baseline-bundle-v1.json`，`ledger_id=ledger.baseline-bundle.v1`、`revision=1`。第一次 frontier 应为 `t.baseline-bundle.repro`，owner 是 `sampler-test-luna2`。

## 1. 受管任务提交前（仓根执行）

```sh
set -eu
WS=/Volumes/nvme/Projects/远程Agent安卓
cd "$WS"
test "$(pwd -P)" = "$WS"
LEDGER="$WS/.team/ledgers/baseline-bundle-v1.json"
DRIVER_DIR="$WS/.team/nodes/_driver"
OUT="$DRIVER_DIR/baseline-bundle-v1.out"
PIDFILE="$DRIVER_DIR/baseline-bundle-v1.pid"
test -r "$LEDGER"
test -d "$DRIVER_DIR"
jq -e '.ledger_id == "ledger.baseline-bundle.v1" and .revision == 1' "$LEDGER" >/dev/null
ledger-run --preflight --json "$LEDGER"
DRY_JSON="$(ledger-run --dry-run --json "$LEDGER")"
printf '%s\n' "$DRY_JSON"
printf '%s\n' "$DRY_JSON" | jq -e '
  .ok == true and
  (.frontier | map(.task_id) | index("t.baseline-bundle.repro")) != null and
  (.excluded | map(.task_id) | index("t.baseline-bundle.test")) != null and
  (.excluded | map(.task_id) | index("t.baseline-bundle.probe")) != null
' >/dev/null
test ! -e "$PIDFILE"
```

预检必须是 `preflight_rejected=false` 且退出码 0；dry-run 必须退出 0 并确认上述 frontier/excluded 形状。任何失败都停止，不启动、不改账本。

`OUT` 是驱动器的直接日志路径，正好位于 `.team/nodes/_driver/`，无需软链或事后搬运；不得让日志只留在 harness 临时目录。若受管执行器只能提供另一日志文件，必须在同一文件系统用 `ln` 建硬链到 `$OUT`，不得用软链。

## 2. 受管后台提交器核查：verdict: blocked

当前 Pi 可调用面只有 bash/read/edit/write；bash API 没有 background 或 managed-task 参数。仓内没有可调用的受管提交器，因此本节**没有可安全执行的 resident 启动命令**。不能把下面的普通前台命令伪装成“受管后台”：

```sh
exec ledger-run --drive --resident --json "$LEDGER" >> "$OUT" 2>&1
```

它会长期阻塞当前 bash 调用；`&`、`nohup`、自 daemonize、真实 tmux 和临时 launchd/服务注册均被本任务禁止。PID 文件只能在真正提交后写入，不能先写一个伪 PID。

## 3. PID 与 cwd 归属核验（只读、禁止 argv）

受管任务已报告进程存活后执行：

```sh
set -eu
WS=/Volumes/nvme/Projects/远程Agent安卓
PIDFILE="$WS/.team/nodes/_driver/baseline-bundle-v1.pid"
PID="$(sed -E -n 's/^pid=([0-9]+)$/\1/p' "$PIDFILE")"
test -n "$PID"
test "$(cat "$PIDFILE")" = "pid=$PID"
kill -0 "$PID"
# 只读允许字段；禁止 ps -f、ps aux、任何 args/argv 选项。
test "$(ps -o pid=,ppid=,etime=,stat=,comm= -p "$PID" | awk -v p="$PID" '$1 == p {print $5}')" = ledger-run
CWD="$(lsof -a -p "$PID" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
test "$CWD" = "$WS"
```

`comm != ledger-run`、`kill -0` 失败、或 cwd 不是仓根，均是归属失败：不发信号、不重启、不重投。不要用 `pgrep -f` 或读取命令行来确认 PID。

## 4. 首格 dispatch-landed + Pi nodeprobe 核验

先只等 direct log 中的 `dispatch-landed`，不能把 `send-ok` 当作落地：

```sh
set -eu
WS=/Volumes/nvme/Projects/远程Agent安卓
OUT="$WS/.team/nodes/_driver/baseline-bundle-v1.out"
i=0
while ! rg -q 'dispatch-landed .*task=t\.baseline-bundle\.repro([[:space:]]|$)' "$OUT"; do
  i=$((i + 1))
  test "$i" -lt 60
  sleep 1
done
```

随后从当前 harness 的只读 status 输出取得**当前私有 socket**，不猜 socket 名，也不扫描或触碰默认 tmux：

```sh
PI_TMUX_SOCK="$({ team-agent status --json 2>/dev/null || true; } \
  | jq -r '.leader_attach_command // empty' \
  | sed -n 's/^tmux -S \([^ ]*\) attach.*$/\1/p')"
test -n "$PI_TMUX_SOCK"
test -S "$PI_TMUX_SOCK"
SEAT=sampler-test-luna2
i=0
while :; do
  NODES="$(nodeprobe -S "$PI_TMUX_SOCK")"
  if printf '%s\n' "$NODES" | jq -e --arg seat "$SEAT" '
    any(.nodes[];
      (.name == $seat or .window_name == $seat or .session == $seat)
      and .state == "working"
    )
  ' >/dev/null; then
    printf '%s\n' "$NODES"
    break
  fi
  i=$((i + 1))
  test "$i" -lt 30
  sleep 1
done
```

最终必须同时有：日志中的 `dispatch-landed`（目标为 `t.baseline-bundle.repro`）、PID 的 `comm=ledger-run`、PID cwd 精确等于仓根、nodeprobe 在当前 Pi harness 上把 `sampler-test-luna2` 判为 `working`。只有 `send-ok`、`team-agent status` 的 `worker_state`，或 driver 存活本身都不构成首格核验。由于第 2 节 blocked，以下核验命令只能在未来获得受管提交器后使用；现在不得运行启动段或伪造首格证据。socket/status/nodeprobe 任一步不可判时停下，保留已有证据，不读取 argv/凭据、不碰真实会话、不改账本。

## 5. 阻塞证据与最小缺口

本次只读核查结果：

- `.team/watchdog-supervisor.sh` 是 watchdog 的循环守护，源码中的入口是 `setsid nohup ... &`；它不是 driver 提交器，而且该入口违反本任务红线。
- `.team/watchdog.sh`、`.team/artifacts/orch-watch.sh` 和 skill 的 `stall-alert.sh` 都是停滞/判活监视器：它们读取已有 PID、日志和 nodeprobe，不提供提交长期 foreground 命令的 API。
- `team-agent --help` 的命令面只有 quick-start/send/status/collect/results、agent 生命周期、diagnose/recovery 和 provider launcher；没有 run/background/managed-task 提交命令。
- 现存 driver 的只读进程事实：`pid=96139 ppid=1 etime=12:11:42 stat=S comm=ledger-run`，`pid=85754 ppid=1 etime=05:38:31 stat=S comm=ledger-run`；两条父链都直接到 PID 1。它们证明当前 driver 已脱离原调用者，但不能证明有 Pi 可调用的受管提交器。
- 对应的运行后留痕确实存在：`.team/nodes/_driver/input-full-auto-v1.pid`（inode 706858843，links=1，mtime 2026-08-24T12:36:45+0800）及 `input-full-auto-v1.out`（inode 706858842，links=1，mtime 2026-08-24T13:00:59+0800）；`perf-regress-v1.pid`（inode 708814723，links=1，mtime 2026-08-24T19:09:57+0800）及 `perf-regress-v1.out`（inode 708788902，links=1，mtime 2026-08-24T19:22:07+0800）。这只能证明 pid/log 留痕，不提供提交器。
- 仓内现有匹配文件没有 launchd plist、service unit 或 driver launcher；`.team/nodes/_driver/*.pid` 与对应 `.out` 只是运行后留痕，不是提交入口。

最小缺口是二选一：①给 Pi 暴露真实的 managed-task submit API/参数（至少接受 cwd、foreground argv、stdout/stderr 路径并返回可管理句柄/PID）；或 ②提供一个已注册且受管的本机服务入口，明确上述同一组参数。补齐前只能停在本文件的 preflight/dry-run，verdict 保持 `blocked`；不得用普通 bash 后台替代。
