# 现场基 · audit-prod-daemon-lifecycle

## 人工事件原文事实

- 2026-08-10 01:2x 前某时刻，原生产 daemon PID 46081 消失，TCP :9900 无监听。
- 原实例约 3.5 小时龄，经 osascript Terminal 启动，未带日志落盘。
- 人工已用旧二进制恢复为 PID 3393；日志 `.team/logs/agentmirrord-prod.log`，横幅含
  `ws://192.168.31.116:9900/ws`。
- 工作树含未验收 TS wire 改动，禁止重编生产二进制。

## 冻结边界

1. 当前 PID 3393 与 TCP :9900 只允许 `ps`/`lsof`/日志元数据等只读取证；禁止 signal、restart、attach、注入请求。
2. 用户真实 tmux 与 Team Agent 私有 tmux 都不得作为实验对象；不得读 worker 原始 pane。
3. 禁止读取 `.team/current/profiles/*.env` 及 provider env；禁止输出 token/authkey/QR 原文。
4. 时间窗先按 2026-08-10 00:45–01:30 +0800 收集；若证据需要扩窗，报告中说明理由。
5. 对团队活动只认结构化事件、测试 argv、脚本源码与进程证据；`send ok` 不等于执行。
6. 结论必须为 `product`、`environment`、`unknown` 三选一；没有证据就写 unknown。

## 必查假设

- 席位或验收脚本是否含过宽 `pkill`/`killall`/按 basename 清理，误杀生产 PID 46081。
- 单实例/pidfile、signal、panic、fatal、HTTP/WS serve 返回、父 Terminal 生命周期等退出路径是否可能无日志。
- macOS unified log、当前 prod log、Team Agent events 与 artifact 时间线能否证明或排除外部终止。
- 当前 PID 3393 是否确有 stdout/stderr 日志接管；值守探针能否在监听或日志接管丢失时去重升级。

## 值守面验收

- `.team/prod-daemon-launch.sh` 固定把 stdout+stderr 追加到 `.team/logs/agentmirrord-prod.log`；不自动 kill/takeover，
  不读取或记录任何密钥；本任务只做语法/形状验证，禁止用它重启当前生产实例。
- `.team/watchdog.py` 每轮只读检查 :9900 的 agentmirrord 监听、对应进程存活、生产日志存在且由进程接管；
  失败仅追加 `.team/logs/watchdog-escalation.log`，同一故障去重，不自动重启或发 signal。
- 自测只能用假 PID/高端口/临时日志；落 `e2e/artifacts/audit-prod-daemon-lifecycle/prod-guard-selftest.log`，
  必须证明 healthy、missing listener、missing log 三形状及零 signal/零生产触碰。

## 交付形状

- `REPORT.md`：分钟级时间线、逐证据表、三选一结论、不能证明的点、值守改动说明。
- evidence JSON：`status`、`classification`、`timeline`、`tests`、`prod_untouched`、`secrets_absent`、
  `followup_task`（product 时为完整五栏提案，否则为 null）。
