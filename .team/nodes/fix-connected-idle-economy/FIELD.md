# 现场基 · fix-connected-idle-economy（裁定席取证，2026-08-10）

## 契约与严重度

- 根 `CLAUDE.md` 工程常识红线 1 已由 leader 裁定扩充：已连接但用户无操作时 CPU/子进程派生必须有界，不得仅因舰队规模线性常烧；交付必须分别量测零连接、已连接零订阅、已连接单订阅。
- dogfood D-16 初审定级 P1：常连静默是主用形态，用户当前 27-pane 舰队下约烧掉 1/7 核；功能测试/e2e 绿不能替代能耗证据。

## 已证事实（只允许说到这里）

- 同一隔离 daemon、同一批 tmux 会话：零连接 30 点/约 6 分钟，CPU time 恒定，0.0%；已连接+1 活跃订阅+用户零操作 20 点/171 秒，CPU time 增 25.32 秒，约 14.8%。
- 原始样本：`/tmp/dg1/d1-samples.txt`、`/tmp/dg1/d16-samples.txt`；报告：`e2e/artifacts/dogfood/REPORT.md` D-16。
- “已连接零订阅”未测；listing 与单订阅各占多少未测。任何根因或占比都须先补拆分证据。
- 27 pane 包含用户当前真实舰队与本轮隔离测试 pane；可代表当前机器现场，不等于所有用户规模。

## 代码疑点（方向，不是结论）

- `server/internal/api/options.go:22`：listing 默认 2s。
- `server/internal/api/state_wiring.go:64`：单 pane state TTL 1s；`State()` 在过期时调度后台 refresh。
- 真正的状态采样入口是 `server/internal/api/state_wiring.go:264` 的 `tmux capture-pane`，不是 `bridge.go:51`。
- `defaultStateSamplingBudget=3s` 是单次采样 timeout 上界，不是频率限流；不得使用“3s>2s 所以没限住”作为论证。

## 验收暗卷（阈值现在冻结，禁止为过门修改）

1. 红测必须先证明旧实现：稳定 fleet 每轮 TTL 全过期导致采样数随 pane 数增长；测试写入后保留 red-first 证据。
2. 确定性测试覆盖 3/27/200 pane：静默稳态全局 `capture-pane` 调度速率有固定上界，不因 pane 数线性增长；公平性保证最坏 pane 60s 内刷新并可把状态变化推进 listing/通知源。
3. `e2e/connected-idle-economy.sh` 只用隔离 `TMUX_TMPDIR`、高端口和自建 daemon，自动构造 27 pane，并顺序量测三态各 ≥60s：零连接、已连接零订阅、已连接单订阅。
4. 两个在线静默态平均 CPU 均须 ≤5%；同时记录 pane 数、CPU time 起止、墙钟、均值、`capture-pane` 派生计数/秒。5% 不可达则如实红交，不准改阈值或缩短窗口。
5. 每态结束和脚本退出后查自身 daemon/客户端/tmux/监听端口零残留；绝不扫描、杀或连接生产 daemon 与用户真实 tmux。
6. 协议与 App 行为不改；blocked/done 状态源最坏 60s 内仍可见；全量 server 回归绿。
