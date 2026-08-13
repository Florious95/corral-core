# C1 · delta 背压合并 —— 三席共同简报

> 2026-08-13 leader 派单。任务 `perf-delta-backpressure-merge`。
> 三席并行：`w-c1-probe`（探针/前置关卡）、`w-c1-test`（场景红测）、`w-c1-dev`（实现）。
> 在红测上汇合。**探针席的结论决定另外两席白不白干,所以它优先级最高。**

---

## 〇、第一关是证伪我们自己（最重要,先读这节）

**leader 的假设可能是错的,你们的第一件事是检验它,不是实现它。**

`sendMirror` 只在 `sendCh`（cap 256）**满**时丢帧;C1 的合并也只在满时触发。
**但我们从未证实过这条队列在真实链路上会满** —— 此前 20 KB/s 限速实测
`deltas_dropped` = 0（LLM 流式输出受生成速率限制,数百字节/秒,远低于任何合理慢链上限）。

**如果队列根本不满,合并永不触发,C1 就是在修一个不存在的问题**,
而用户主诉「慢链上看得见每个中间状态」的真因在别处（很可能就是慢链本身把
本来就分散到达的 delta 按到达时刻各渲染一次,与队列压力无关）。

所以关卡顺序是硬的：

| 关卡 | 内容 | 谁 | 不过怎么办 |
|---|---|---|---|
| **1** | **证明 `sendCh` 会满**（真实链路 `conn.queue_peak` 接近 256 或 `dropped>0`） | probe | **halt 报 leader,另外两席停手** |
| 2 | 红测：合并前后客户端字节流逐字节相同 | test | 修到绿 |
| 3 | `cd server && env -u TEAM_AGENT_* go test ./...` 全绿 | dev | 修到绿 |
| 4 | `python3 tools/archwiki/build_wiki.py --check --strict-t3` exit 0 | dev | 修到绿 |
| 5 | `deltas_dropped` 语义随丢弃路径消失同步订正 | dev | 补 |
| 6 | 眼见为实：真机/模拟器慢链下中间状态个数下降且不倒退 | leader 安排 | 回炉 |

**关卡 1 不过就 halt。** 本工程今天已经有五次「单测全绿、装上去不行」,
根因都是**没先确认问题真的存在**。不要再来第六次。

---

## 一、改动点（代码位置已定位,不用重找）

`server/internal/api/ws_conn.go`

```go
// L281-295 现状
// sendMirror enqueues a binary mirror frame without blocking: a slow client
// whose queue is full drops the delta, and the next snapshot reconciles
// (requirement 004 — the tmux pane is the source of truth, not this queue).
func (c *wsConn) sendMirror(data []byte) {
	select {
	case c.sendCh <- wsMsg{typ: wsBinary, data: data}:
		c.s.sendQueue.recordQueued(len(c.sendCh))
		c.connMetrics.recordFramesSent()
	default:
		c.s.log.Debug("ws: dropping mirror delta for slow connection", "conn", c.id)
		c.s.sendQueue.recordDrop()   // ← 这条路径消失后计数器语义要改（关卡 5）
		c.connMetrics.recordDrop()
	}
}
```

相关：`sendCh` 在 L104 `make(chan wsMsg, 256)`；写侧 `writeLoop` L155、
`flushQueued` L182；`sendMirror` 的唯一调用点在 L481。

**设计与语义安全性已由 `docs/ts-link-baseline.md` 查证**：字节序逐字节等价、
AnsiParser 是顺序状态机 —— 即把 N 个连续 delta 拼成一个大帧,客户端解析结果不变。
**但那是文档结论,你们要用红测把它钉死,不要采信账面。**

约束：合并后单帧 ≤ 1 MiB；**不引入定时器**（合并的必须是"本就在排队的东西",
一旦加延时就变成了拿延迟换合并,那是另一件事,不在本任务内）。

---

## 二、真实链路实测数字（2026-08-13 23:29–23:41，全部瞬时值）

用户手机蜂窝数据 + Tailscale，同一台手机三种条件：

| 条件 | min | avg | max |
|---|---|---|---|
| 局域网直连 | 9.2 | 47.6 | 130.8 ms |
| 家里 WiFi + TS | 327.4 | 668.9 | 1478.6 ms |
| 蜂窝 + TS | 394.3 | 1221.0 | 2298.4 ms |
| 路由器（物理下限） | 4.4 | 9.8 | 24.3 ms |

**应用层比 ICMP 更贵**：手机那条真 WS 连接的内核 srtt **1762.66 ms**、
重传 **10.4%**、rx_dupe 332（nettop 对 daemon pid 70317 实测,非探针）。
往返单价按 **1.5–1.8 s** 算。

链路真相：`Relayed connection (HKG)` —— DERP 中继（手机端 Tailscale 状态页实证）。
Mac 侧 Tailscale 跑在 Shadowrocket 里,其对外 TCP 指向用户自建新加坡节点
sg-proxy2.team-agent.net,Mac→该节点实测 121–190 ms、**丢包 20%**。
即链路为**双重中继**：手机(国内) → HKG DERP → 新加坡 → Mac(国内)。

详见 `docs/cellular-ts-optimization.md`（w-perf-link 交付）。

**这些数字对关卡 1 的意义**：srtt 1.7 s + 重传 10% 下 TCP 吞吐受限,
socket 缓冲填满后 `writeFrame` 会阻塞,`sendCh` 才可能积压。
**"可能"不等于"会"** —— 去测。

---

## 三、仪表（已写好,未部署）

`server/internal/api/sendq_metrics.go`：
- per-connection：`conn.DeltasDropped` / `SnapshotsPushed` / `SnapshotsFromResize` /
  `SnapshotsFromSubscribe` / `QueuePeak` / `FramesSent`
- process-level：`total.*` 同名
- `ws_conn.go` 拆除时记 `close_reason`（`read_error` / `write_error` / `client_close`）

**为什么分 conn/total**：此前有个坏仪表把进程级累计计数打在 per-connection 日志行上,
我们据此追了好几轮「单连接快照数 1→9」的假线索。
**纪律⑨：新增的观测仪表,第一件事是验证它测的是不是你以为的那个东西,作用域必须显式标出。**

**部署需要重启生产 daemon（断线几秒）—— 等 leader 拿到用户点头,任何席位不许自己动生产 daemon。**
在此之前 probe 席用隔离 daemon 先把探针本身跑通。

---

## 四、红线（今天用五次失败换来的,不许违反）

1. **眼见为实**：改之前必须复现,改之后必须看到修复。**单元测试绿 ≠ 问题修了。**
2. **凡「复现不了」的结论,先证明三件事**：测的是用户那个版本、工具本身有效、
   网络条件与用户一致。今天三条都栽过。
3. **一条修复 = 一个提交。** 混合提交同时毁掉单条回退、单条审查、单条追溯。
4. **临时取证/诊断代码放独立文件**,不塞进高频改动文件（今天被误提交三次）。
5. **判不出就 halt 报 leader**,绝不猜。缺字段、判不出 ⇒ 停下问。
6. **绝不触碰生产 daemon 与用户真实 tmux**,只读也不行。
   起隔离 daemon 必须用 `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄扫描。
7. **别 tail `.team/logs/agentmirrord-prod.log`** —— daemon 把配对 token 明文打进去。
8. **不许跑无过滤的 `ps aux`**（会暴露席位 API key）,核进程用 `pgrep -fl <精确路径>`。
9. **密钥只存在于 `.team/current/profiles/*.env`**,禁止读其原文。
   **今天 leader 刚因为 grep 一个 plist 把用户的 TS authkey 打上屏,已请用户轮换。
   不要重蹈：任何 grep/plist/db 查询前先想一想会不会带出凭据。**
10. **禁止 git push。**

---

## 五、测试工具的已知缺陷（今天栽过,别再栽）

- **`e2e/delay_proxy.py` 迭代了四版**。前三版只加转发延迟,**不产生背压** ——
  daemon 的写入瞬间完成进代理的 OS 接收缓冲,测不出慢链。
  终版靠 **~20 KB/s 带宽限流 + `SO_RCVBUF` 缩到 2048 字节**才造出真背压。
  **动它之前先读文件头的四次失败原因。**
  注意：正是这个工具此前测出 `deltas_dropped`=0 —— 那个结论本身要重新审视,
  因为 LLM 输出速率远低于 20 KB/s,该档位可能根本没造成压力。
  **关卡 1 该用的是真实链路,不是这个代理。**
- 捏合注入器在新 AVD 上完全不生效（工具本身坏的,不是被测对象好）
- `e2e/harness/cmd/recapprobe/` 两次失灵

---

## 六、必读

1. 本文
2. `docs/cellular-ts-optimization.md` —— 真实链路实测（§1 现状判读、§3 代码侧排序）
3. `docs/ts-link-baseline.md` —— 合并的字节等价性与语义安全性查证
4. `docs/roundtrip-audit.md` —— 往返账
5. `CLAUDE.md` —— 工程铁律
6. `.team/nodes/perf-delta-backpressure-merge/` —— basegen 知识基底（cards=2 fwd=4 rev=1）

交件写 `.team/evidence/perf-delta-backpressure-merge.json`（三席各自写自己那份进度,
最终合并由 leader 收口）。
