# TS 链路本体指标（Tailscale 链路优化中心）

> 用户裁定：**「核心是优化 tailscale 的链路」**（2026-08-13）。局域网数字没有意义——
> 过去所有性能判断建立在局域网上，正是用户反复指出的问题。
> 本文档是 `performance-baseline-spec.md` 的 TS 链路中心重排。

## 我们的 TS 链路形态（只读查证）

客户端 = **tsnet 内嵌用户态 Tailscale（gomobile）+ loopback SOCKS5 拨号**：
- 节点 Up 后，`TsnetManager` 提供 loopback SOCKS5 代理凭据（TsnetBackend.kt:20-22）
- OkHttp 经 `TsnetProxySocketFactory`（自实现 SOCKS5 CONNECT，RFC 1928/1929）进 tailnet（TsnetDial.kt:68-69）
- 上传经 `TsnetSocks` 自实现握手（模拟器实证 Android 内建 SOCKS 认证不生效）

## 四个怀疑点逐条查证

| # | 怀疑点 | 查证结果 | 影响 |
|---|---|---|---|
| ① | **直连还是中继** | **tsnet backend 无 direct/relay/DERP 状态暴露**（Kotlin 层 grep 零命中）——**App 无法知道/显示当前直连还是中继** | 用户说「TS 接近 LAN」大概率直连；之前抱怨慢可能在中继上。**优化第一件事=让用户知道自己在哪种模式**（不改代码，先可见） |
| ② | **SOCKS5 每跳代价** | **WebSocket 是长连接**（OkHttp newWebSocket 一次，复用到整个会话）——delta 路径无重复握手 ✓；**但上传是每次新建连接**（HttpURLConnection「一次性短连接」，Session 上传器）——每次上传 = SOCKS 握手 + TCP + HTTP，TS 上多个往返 | 上传路径每请求多跳；delta 路径已高效 |
| ③ | **用户态栈每包开销** | tsnet 用户态跑完整 TCP/IP 栈；**delta 逐 chunk 转发（不合并）**（relay 每 chunk 一个 WS 帧，stream.go:414-421）→ 小帧多、每包过用户态栈 | 小包代价被放大；需测每包额外延迟与 CPU |
| ④ | **小包与缓冲** | delta 无合并（逐 chunk）；tsnet/SOCKS 链上 Nagle 类缓冲**未查**（需实测） | 小包可能被攒住，增加感知延迟 |

## 指标要区分「网络本身」和「我们的开销」（关键）

用户在 TS 下慢，可能是 Tailscale 自身往返（改不了），也可能是我们加的（能改）。

- **裸 TS 往返**（ping 或最小回声）= 基础延迟，不归我们。
- **我们的操作耗时 − 裸 TS 往返 × 往返次数** = 我们的开销。**只有后者是优化目标。**
- 前者只能靠减少往返次数规避（回到往返审计）。

## delta 背压合并（零延迟代价，leader msg_aad159775b98 主线索）

**这可能就是闪烁的机制本身**：慢链路（TS）上每个 chunk 单独到达 → 各自触发一次渲染 →
用户看见它们一个一个出现。局域网几乎同时到、合成一帧，所以看不出 —— 对得上「局域网流畅、TS 就闪」。

### 现状（代码确认）
- relay（ws_conn.go:412-432）拿到 chunk 立即 `EncodeBinary` + `sendMirror`。
- `sendMirror`（:235-241）非阻塞发 `sendCh`（容量 256）；**队列满 = `default` 分支 = 丢弃 delta**。
- 丢弃由「下个快照对账」（004 契约）。**即：慢客户端丢数据是现状。**
- 帧载荷上限 1 MiB（`MAX_BINARY_PAYLOAD` 两端一致）。

### 零延迟合并设计（查证可行）
**只合并「已经在排队等着发的」**：
- 发送侧：若 `sendCh` 满（背压 = `sendMirror` 的 `default` 分支），**不丢**，把 chunk 并入待发缓冲；
  后续到达的 chunk 继续并入；队列排空后把合并缓冲 flush 成**一个** delta 帧（≤ 1 MiB）。
- 链路空闲 → 从不排队 → 从不合并 → 行为与现在完全一致（零回归）。
- 链路慢 → 自然排队 → 自动合并 → 中间状态个数下降。
- **不引入时间参数、不增加延迟**（合并的是本来就在等的东西）。

### 语义安全（查证）
- 客户端 `onBinary(DELTA) → emulator.feed`（SessionViewModel.kt:171）→ AnsiParser 顺序状态机，
  **字节序保持，逐字节等价**——一个大 delta == N 个小 delta，渲染结果一致。
- 顺序合并等价于分别发送。协议帧头允许一帧装更多字节（payload ≤ 1 MiB）。

### 判据（可写红测，不依赖网络）
**合并前后客户端收到的字节流逐字节相同，只是分帧不同。** 这条红测在任何网络条件下都能断言。
「闪烁改善」在注延迟下用机器眼量中间状态个数。

### 与 P6 捏合的关系
delta 不合并**影响所有内容更新，不只捏合**——比 P6 更根本。两条并行：
P6 捏合去测失败态基准（w-base-v2 + 模拟器）；本条合并你自己查代码即可推进。

## 待测（w-base-v2）

1. 裸 TS 往返 p50/p95/p99/max（直连 vs 中继，若可判）。
2. 每个用户动作（P1-P8）在 400ms TS 下的实际耗时，减去「裸往返 × 往返次数」= 我们的浪费。
3. 上传单次 vs 长连接的往返差（上传每次新建连接，用户当前不常传图，排后）。
4. delta 小包合并对中间状态个数的改善（注延迟机器眼）。
