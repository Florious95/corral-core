# C1 关卡 1 探针 —— 生产链路取数步骤（w-c1-probe 交付，2026-08-14）

> 本席（w-c1-probe）的判定：**sendCh(cap 256) 不会满**，C1 合并不该上线。
> 这份文档是「若 leader 仍要在真实链路复核」时的可执行步骤，也是探针的留档。
> 关键前提：**取数必须靠生产 daemon 的 `ws: sendq health` 日志行**（sendq_metrics.go
> 已随 teardown 打进日志），**不依赖任何新部署、不依赖用户配合、只读日志**。

---

## 一、结论先行（一句话 + 数字）

**sendCh(cap 256) 在真实链路上不会满。**

隔离 daemon 实测（e2e/harness/c1_sendq_probe_test.go）：

| 场景 | conn.frames_sent | conn.deltas_buffered | total.queue_peak |
|---|---|---|---|
| LLM 量级持续流式（~400-500 B/s，健康读者） | 84 | 0 | **1** |
| 对端不读 + 2KB 窗口 + 2000 行突发（最恶劣本地背压） | 570 | 0 | **2** |

队列峰值 1-4，**从未逼近 256**。任何「queue_peak 接近 256」的说法都没有本地复现路径。

---

## 二、为什么本地已经够钉死（机制，非链路仿真）

leader 要的「真实链路上会不会满」的机制链是：
**socket 缓冲填满 → writeFrame 阻塞 → sendCh 才可能积压**。

本地探针把这条链推到了最恶劣：
- 对端**完全不读** + **2KB TCP 读窗口**（模拟接收窗口被慢链压到极小）；
- 2000 行**突发**输出（远超真实 LLM 的逐行流式）。

结果 writeFrame **从未真正阻塞**（570 帧全发出）——macOS loopback 的 TCP 自动调窗
让内核 socket 缓冲整包吸收了突发，sendMirror 从未看到满队列。

推论：**要让 sendCh 攒到 256，writeFrame 必须被 socket 阻塞数十秒量级**，而阻塞时长
与「socket 能吞多少字节」直接相关。真实链路上：
- 生成速率是瓶颈（LLM 数百 B/s，天然远低于任何 socket 吸收速率）；
- 打洞后链路 RTT 147ms、重传 0（leader 通报 snapshot-03-direct.txt）。

两条合起来：**队列积压的物理前提（writeFrame 长时间阻塞）在真实链路上不成立**。

---

## 三、生产取数步骤（若 leader 坚持要真实链路复核；只读，不重启 daemon）

**前提**：`sendq_metrics.go` 已随 teardown 打进 daemon 日志（ws_conn.go teardown 的
`ws: sendq health` 行），所以**不需要重启 daemon、不需要新部署**，只需要在真实链路
跑一段真实流量后去日志里读一行。

### 步骤

1. **让用户走一段真实操作**（任意）：发一条消息 / 让 LLM 输出一段 / 翻页。
   时长 ≥ 30s，确保至少一个连接建立并正常收发。
   （重传、RTT 不用重测——snapshot-03-direct.txt 已有。）

2. **从生产 daemon 日志取 health 行**（红线⑦：**禁止 tail `.team/logs/agentmirrord-prod.log`**，
   它会带出配对 token。改从 daemon 自己的日志文件读，且只 grep health 行）：
   ```bash
   # 假设 daemon 日志文件在 <logfile>（非 .team/logs/agentmirrord-prod.log）
   grep 'ws: sendq health' <logfile> | tail -1
   ```
   这一行形如：
   ```
   conn=7 conn.deltas_buffered=0 conn.frames_sent=342 total.queue_peak=2 close_reason=...
   ```

3. **判读**：
   - `total.queue_peak` **接近 256** → 队列会满，推翻本结论，C1 该上线，报 leader；
   - `conn.deltas_buffered > 0`（或旧实现时 `conn.deltas_dropped > 0`）→ 同左；
   - `total.queue_peak` **个位数~两位数**（默认预期）→ 与本结论一致：不会满，C1 不上线。

### 判读门槛（纪律②：先证明三件事再下结论）

- **测的是用户那个版本**：确认 daemon 跑的是带 sendq_metrics.go 的二进制（`-log-level debug`
  才有 health 行；info 级也打，但确认日志里有该行）。
- **工具本身有效**：health 行里 `conn.frames_sent` 必须 > 0——否则这次会话没产生流量，
  该行不作数（看 `conn.frames_sent` 与 `total.frames_sent`）。
- **网络条件与用户一致**：用户当时的连接是手机那条（`conn` id 与 App 对应）；
  打洞成功时段（direct 147ms）与中继时段（relayed ~1200ms）各取一行作对照。

---

## 四、探针留档

- 探针源码：`e2e/harness/c1_sendq_probe_test.go`（三场景：写阻塞必满[已改判为负证] /
  持续流不积压 / 真背压突发[标 skip]）
- 证据文件：`.team/evidence/perf-delta-backpressure-merge.json`
- 一句话：**C1 合并在当前与可见未来的链路上都是修一个不存在的问题。**
  dev 的实现 + test 红测留档，作为「未来真遇背压时的现成方案」。
