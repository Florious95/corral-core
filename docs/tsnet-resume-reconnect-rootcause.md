# 缺陷⑤ 根因报告：内嵌 tsnet 回前台永远连不上

> 任务 ID: fix-tsnet-resume-reconnect  
> 探针席: w-tsresume-probe  
> 日期: 2026-08-14

---

## 1. 现象与 A/B 差分

| 路径 | 操作 | 结果 |
|------|------|------|
| **A（坏）** | App 内嵌 tsnet（TS token 配对）连上 → 切后台 → 回前台 | **永远连不上** |
| **B（好）** | 官方 Tailscale App 建 tailnet，App 按 tailnet 地址直连（不走 token）→ 杀到后台 → 再打开 | **立刻连上** |

差分把范围锁死在**内嵌 tsnet 的前后台生命周期**上，与 UI/几何/服务端无关。

---

## 2. 根因

### 2.1 状态语义不匹配（"状态说谎"精确版）

`TsnetWire.state == TsnetState.Up` 的真实语义是：

> native Go tsnet 节点调用 `backend.start()` 成功过，SOCKS 端口曾经可用

**不是**：

> 当前 DERP 连接可用，SOCKS 代理真实可拨通

后台冻结期间 DERP TCP 长连接超时断裂，Go goroutine 暂停。恢复后 native tsnet 节点内部异步重连 DERP（自主逻辑），**不回调 Java 层 state 变化**。因此 `TsnetWire.state` 永远停在 `Up`。

### 2.2 幂等守卫锁死恢复路径

**`TsnetWire.kt:91`**：
```kotlin
if (m != null && key == currentKey && (m.state is TsnetState.Starting || m.state is TsnetState.Up)) {
    return  // ← 幂等守卫：state 是 Up 时不重启，即使底层 SOCKS 不可达
}
```

任何外部触发（冷启动、`onNetworkAvailable`、用户操作）调用 `ensureStarted()` 都被这里拦截，节点无法重启。

### 2.3 选路逻辑无 SOCKS 健康检查

**`OkHttpWebSocketTransport.kt:161`**：
```kotlin
val sf = TsnetDial.socketFactoryFor(TsnetWire.state, host)
```

每次拨号仅读 `TsnetWire.state`，不检查 SOCKS 端口可达性。`state.Up` → 返回 SOCKS 工厂 → OkHttp 走 loopback SOCKS5 → SOCKS CONNECT 请求送达代理 → tsnet 路由不通（DERP 断裂）→ 代理立即返回 `host unreachable` → `IOException("SOCKS5 代理建链失败")` → `ConnectionManager` 进 RECONNECTING 退避。

### 2.4 退避与 DERP 重连竞争（"永远"的来源）

`ConnectionManager` 指数退避（1s → 2s → 4s → ... → 32s+）。`NetworkConnectivityWatcher.onNetworkAvailable()` 可重置退避，但如果 WiFi 从未断开（只是进程冻结），`onAvailable` 回调不会再触发。

结果：退避间隔越来越长，而 Go tsnet 的 DERP 重连依赖内部定时器（后台冻结期间未能完成），重连完成时间不确定。两者时序不匹配时，每次退避到期发起的 SOCKS 连接仍失败（DERP 还没好），下次退避间隔翻倍。**用户感知为"永远连不上"。**

---

## 3. 复现步骤（关卡 1）

### 3.1 模拟器实测结果（2026-08-14，emulator-5554，API 35）

#### 已确认（眼见为实）

**tailnet 路径确认激活**：
- 配对：手填 URL=`ws://100.75.207.88:19983/ws`，TS authkey 注入，点"连接"
- App 顶栏由 "LAN" 切换为 **"tailnet"**（截图留证）
- Session 屏显示 `alauda@MacBook-Pro app %`（通过 tailnet 连入真实主机 zsh）

**DERP 连接确认**（`adb shell ss -tn state established`）：
```
10.0.2.16:52622  →  43.136.53.247:8444   ESTABLISHED   ← DERP TCP 已建立
127.0.0.1:41095  ↔  127.0.0.1:47152     ESTABLISHED   ← SOCKS loopback（OkHttp→tsnet代理）
```

**SOCKS 路由证实**（GoLog，daemon 关闭后 App 重连时打出）：
```
I/GoLog(5967): socks5: client connection failed: connect tcp 100.75.207.88:19983: connection was refused
```
此日志出自 tsnet Go 层 SOCKS 代理：OkHttp 请求代理连接 100.75.207.88:19983，代理确实尝试路由，收到 "connection refused"（daemon 已停止监听）。**证明流量确实在走内嵌 tsnet SOCKS 而非直接从 emulator TCP 到 Mac。**

---

---

#### 关卡 1 受阻原因：两堵墙（机理不同，并列记录，供后人不重走）

##### 墙 ①：App 的流量根本不需要隧道（w-up-probe 发现，缺陷① 的场景）

> **机理**：emulator-5554 通过 host NAT 出网，Mac 自身在 tailnet 上，所以 emulator 的 TCP 数据包经 host NAT → Mac LAN IP → Mac tailnet 接口，直达 100.75.207.88。即使 App 完全没有配 tsnet/SOCKS，上传/连接也能成功。
>
> **影响**：缺陷① 验证「上传必须走 SOCKS」时，无法区分「真的走了 SOCKS」还是「直通 host NAT 成功」。
>
> **w-up-probe 实测**：改前 APK（无 SOCKS 代理）上传到 tailnet IP < 1s 成功 —— 证明 host NAT 直达，无需隧道。

##### 墙 ②：tsnet 隧道有 WireGuard 直连备路，DERP 断不掉（本席 w-tsresume-probe 发现，缺陷⑤ 的场景）

> **机理**：当 tsnet 节点（emulator）和目标节点（Mac）都在同一个 tailnet 时，Tailscale 协议会协商 **WireGuard 直连**（UDP hole-punch），不通过 DERP TCP 中继。因此用 `iptables -j DROP` 封锁 DERP IP（43.136.53.247:8444）并不能切断 tsnet 的路由能力——tsnet 自动切到 WireGuard 直连路径，SOCKS 依然成功路由到 Mac。
>
> **实测操作**：
> ```bash
> adb shell iptables -I OUTPUT -d 43.136.53.247 -j DROP   # 封 DERP IP
> adb shell iptables -I OUTPUT -p tcp --dport 8444 -j DROP # 封 DERP 端口
> adb shell iptables -I OUTPUT -p udp --dport 3478 -j DROP # 封 STUN 端口
> # 等待 30s，kill daemon，观察 App 重连日志
> ```
> **观察**：`ss -tn state established` 显示 `43.136.53.247:8444 ESTABLISHED` 持续存在（内核态未清除），且 `GoLog` 显示：
> ```
> I/GoLog(5967): socks5: client connection failed: connect tcp 100.75.207.88:19983: connection was refused
> ```
> "connection refused" 而非 "no route to host"，证明 tsnet 依然路由到了 Mac（只是 daemon 不在了），说明 WireGuard 直连绕过了 DERP DROP。
>
> **与墙①的区别**：墙① 是 App 层完全绕过隧道；墙② 是隧道内部自有备路（DERP → WireGuard fallback），是 Tailscale 协议设计行为。两者机理独立。

**Gate 1 结论（leader 裁定：「已查明不可复现」≠ 失败）**：

「永远连不上」的全流程复现必须在真实设备上（设备离 Mac 足够远，只有 DERP 可用，无 WireGuard 直连路径）。模拟器因上述两堵墙均无法模拟此场景。判据已满足：
- 用户在真机上有决定性 A/B 差分
- JVM 探针（关卡 2）结构性证明根因
- GoLog 实锤：SOCKS 确实在拨，确实会被拒

---

### 3.2 走日志路径（后续）

如诊断日志接入完成，`state 报 Up 而 SOCKS 拨号失败` + `ensureStarted 被幂等守卫拦下` 可从运行时日志直接读出，届时补充本节。

---

## 4. 探针两次自证输出（关卡 2）

探针文件：`app/app/src/test/java/dev/agentmirror/app/tsnet/TsnetResumeReconnectProbeTest.kt`

**运行命令**：
```bash
cd app && env -u TEAM_AGENT_API_KEY ./gradlew :app:testDebugUnitTest \
  --tests "dev.agentmirror.app.tsnet.TsnetResumeReconnectProbeTest"
```

**XML 结果（2026-08-14，当前 HEAD，探针命中 = 缺陷存在）**：
```xml
<testsuite name="dev.agentmirror.app.tsnet.TsnetResumeReconnectProbeTest"
           tests="3" skipped="0" failures="0" errors="0" time="0.036">
  <testcase name="probe1 state 停在 Up 且 ensureStarted 因幂等守卫不重启节点" time="0.033"/>
  <testcase name="probe2 SOCKS 端口关闭后 socketFactoryFor 仍返回 SOCKS 工厂无健康检查" time="0.001"/>
  <testcase name="probe3 多次 ensureStarted 均被幂等守卫拦截 state 永久停在 Up" time="0.001"/>
</testsuite>
```

三条探针全绿（failures=0）= 缺陷存在于当前 HEAD。

**探针语义（命中条件）**：

| 探针 | 命中意义 |
|------|---------|
| probe1 | `ensureStarted` 幂等守卫：state.Up 时无法重启节点（backend.startCount 恒为 1） |
| probe2 | `socketFactoryFor` 无健康检查：端口关闭后仍返回 SOCKS 工厂（选路死锁） |
| probe3 | 结构性缺失：多次外部触发均无效，state 永久停在 Up（无自愈机制）|

---

## 5. 修法建议（只建议，不实现）

### 5.1 核心设计约束（先于方案）

| 约束 | 来源 |
|------|------|
| 自愈只有一个地方：`TsnetWire`/`ensureStarted` 层 | 避免 WS 和上传各写一份，重复实现 |
| 不能「每次回前台都重建节点」 | 静默经济红线：节点 stop+start 是秒级阻塞操作 |
| 官方 Tailscale App 并存时不误触发 | tsnet 处于 `Idle`（未调用 `ensureStarted`）时不应触发任何重启逻辑 |
| 重启必须有节流下限 | 防止拨号连环失败打成重启风暴（建议 ≥30 秒间隔） |

### 5.2 两条路径对比：失败驱动 vs 端口探活驱动

#### 路径 A（❌）：TCP 端口探活驱动

> 「`onNetworkAvailable` 时对 proxy loopback 端口做 TCP 探活」

**两个致命缺陷：**

**缺陷 A-1：假绿**。SOCKS 代理是本地 Go `net.Listener`，它绑定在 loopback 上。DERP 路由断裂后，**这个 listener 照样在监听**——Go listener 的生命周期和 DERP 连接无关。TCP 探活打进去必然成功。探活报「健康」→ 重启永不触发 → 修复写了但不运行。这正是根因的翻版：把「端口曾经能连上」误当「当前路由可用」。

**缺陷 A-2：入口不触发**。用户场景是「切后台再回前台，网络自始至终是同一条蜂窝/WiFi」。`ConnectivityManager.onAvailable` 只在网络**从无到有**时触发——网络从未断开，它一次都不响。`onNetworkAvailable` 路径永远不会被调用，自愈入口永远不激活。

**结论**：端口探活驱动不可用，两个缺陷相互独立，任何一个都导致修复无效。

---

#### 路径 B（✅）：失败驱动

**核心思路**：让 SOCKS CONNECT 失败本身当触发源。失败是地面真相，不可能假绿。

**信号路径**：
```
OkHttpTransportFactory.create(url) → sf != null (SOCKS 路径)
  → transport 启动 → SOCKS CONNECT → "host unreachable" → onFailure(IOException)
  → ConnectionManager → RECONNECTING
  → 同时：ServiceWire.onTailnetSocksFailure() → TsnetWire.notifySocksRouteFailure()
```

`TsnetWire.notifySocksRouteFailure()` 内部逻辑：
```
fun notifySocksRouteFailure() {
    synchronized(this) {
        if (state !is TsnetState.Up) return      // Idle/Starting/Error：不干预
        val now = clock.elapsedRealtime()
        if (now - lastRestartMs < THROTTLE_MS) return  // 节流：≥30s 才重启
        lastRestartMs = now
        // 用 currentKey（已存储在 TsnetWire）重启，不需要调用方再传 key
        val key = currentKey ?: return
        manager?.stop()                          // 停旧节点（manager 锁内）
        // 建新 manager + start：走 ensureStarted 内部逻辑（跳过幂等守卫）
        val exec = executorForTest
        val created = TsnetManager(backend = backendFactory(), executor = exec ?: defaultExecutor(), onState = ::onState)
        manager = created
        created.start(stateDirForKey(environment!!.stateDir, key), environment!!.hostname, key)
    }
}
```

**失效场景分析**：

| 场景 | 行为 | 结论 |
|------|------|------|
| DERP 断裂，SOCKS CONNECT 失败 | 触发 `notifySocksRouteFailure` → 节点重启 → ConnectionManager 下次重试成功 | ✅ 正确 |
| 节流窗口内（<30s）多次失败 | 只有第一次重启，后续失败被节流 | ✅ 防止重启风暴 |
| 网络本身断开（SOCKS 之外的原因失败） | 同样触发，但 tsnet 重启后网络仍断 → SOCKS 继续失败 → 节流保护，不连环重启 | ✅ 可接受（上层退避兜底） |
| 直连路径失败（state==Idle，LAN 断） | `state !is Up` → 直接 return，不误触发 | ✅ 官方 TS 并存安全 |
| 目标地址非 tailnet（LAN 路径失败） | `sf == null` → 不走 SOCKS → 不触发 `onTailnetSocksFailure` | ✅ LAN 路径隔离 |

**关键判据**：`onTailnetSocksFailure` 只在 `OkHttpTransportFactory.create()` 选择了 SOCKS 工厂（`sf != null`）且该 transport 随后 `onFailure` 时触发。LAN 路径的 `sf == null`，对应的 transport 失败不走这个回调。

---

### 5.3 「第一次失败必然暴露给用户」问题

**结论：必然暴露，且不应该试图掩盖。**

用户体验序列：
1. 回前台 → ConnectionManager 立即重试 → SOCKS "host unreachable" → 通知变为「连接中断，正在重连…」（用户看到一次断连）
2. `notifySocksRouteFailure()` 触发 → tsnet stop+restart（executor 线程，3-10 秒）
3. tsnet 重新 Up → `TsnetWire.state` 更新
4. ConnectionManager pump（2s 周期）或 `onNetworkAvailable` 驱动下一次重试 → 成功 → 通知变为「已连接」

**用户感知**：从「永远连不上」变为「断了几秒后自动恢复」。这是正确的修复结果，不需要也不应该对用户掩盖：
- 「失败可见」是工程红线（018 标准 5）：任何动作必须有可见结果
- 状态可见让用户知道 App 在工作，不是死机
- 真正需要避免的是「永远不自愈」，而不是「用户看到一次断连」

**能否静默重试？** 理论上可以让 `OkHttpTransportFactory.create()` 在检测到 SOCKS 失败后内部重启 tsnet 并重试，但：
- 这会让 `create()` 变成秒级阻塞调用（停旧节点 + 等新节点 Up）
- 违反 transport 层职责边界（transport 不应管 tsnet 生命周期）
- 复杂度远高于收益

不建议。

---

### 5.4 幂等守卫的改法

`TsnetWire.ensureStarted()` 的幂等守卫（`TsnetWire.kt:91`）保持不变，它的语义是正确的（防止重复起网）。

新增一条**不经过幂等守卫的内部重启路径**，只从 `notifySocksRouteFailure()` 调用：

```kotlin
// 内部方法，不对外暴露（private/internal）
@Synchronized
private fun forceRestartWithCurrentKey() {
    // 幂等守卫已在 notifySocksRouteFailure 外层检查过（state is Up + 节流），
    // 这里直接执行重建流程，与 ensureStarted 的「换 key」分支等价。
    val key = currentKey ?: return
    val env = environment ?: return
    manager?.stop()
    currentKey = null  // 清除后再调 ensureStarted，才能绕过幂等守卫
    ensureStarted(key) // 此时 currentKey==null，幂等守卫跳过，走重建分支
}
```

这样：
- `ensureStarted` 的幂等语义（同 key 同 state 不重复起网）不变
- 重启路径唯一，不散落到调用方
- `TsnetWire` 是唯一决策者（探针只需测试 `notifySocksRouteFailure` 的触发条件，不需要知道内部实现）

---

## 6. 与缺陷①的对账

**leader 裁定（2026-08-14）**：①照常收工，⑤单独修，不在上传器里塞重启逻辑。

理由：`HttpUrlConnectionUploader` 走同一条 SOCKS（缺陷①的修复目标）。
它继承的是**已经存在的**脆弱性（WebSocket 也走 SOCKS，同样脆弱），不是引入新问题。
⑤修好之后 WebSocket 和上传**一起**受益，不需要各自实现自愈。

**对 w-up-dev 的提示**：上传器无需关注 SOCKS 健康状态，按 SOCKS 失败即抛异常、让调用方重试即可，自愈逻辑由⑤统一在 TsnetWire 层处理。

---

---

## 7. 更硬的根因：系统代理渗入 tsnet SOCKS 路径（2026-08-14 用户真机日志新证）

> **本节覆盖并补充 §2–§5 的结构性分析。两者独立，可同时存在。**

### 7.1 真机日志证据（6/6 相关性）

用户真机（Android + Clash/Shadowrocket 常驻），复现手段：TS token 配对 → 长时间后台 → 回前台 → 连接重试循环连不上。

```
12:00:01.425  [socks] dial ok  host=127.0.0.1  port=7892  via=127.0.0.1:37557  ms=88
12:00:06.453  [ws] failure  ex=IOException  msg=unexpected end of stream on http://100.75.207.88:9900/...
              （同样模式重复 5 次）
12:00:33.194  [socks] dial ok  host=100.75.207.88  port=9900  via=127.0.0.1:41873  ms=1391
12:00:33.229  [ws] CONNECTING → AUTHENTICATING
12:00:33.347  [ws] AUTHENTICATING → READY   ← 唯一一次目标是真服务器的，立刻 READY
```

**6 次 SOCKS 拨号，5 次目标是 127.0.0.1:7892（Clash/Shadowrocket 本地代理端口），全部随后 "unexpected end of stream"。唯一 1 次目标是 100.75.207.88:9900，立刻 READY。相关性 6/6。**

### 7.2 推断链（leader 核查代码后确认）

```
OkHttpTransportFactory.create(url, sf != null)
  → client.newBuilder().socketFactory(sf).build()   ← 无 .proxy(Proxy.NO_PROXY)
  → OkHttp 继承 ProxySelector.getDefault()          ← 系统代理（Clash）返回 127.0.0.1:7892
  → OkHttp 用 tsnet socketFactory 连接 127.0.0.1:7892
  → tsnet SOCKS 路由到 host loopback 上的 Clash
  → Clash 尝试 HTTP CONNECT 到 100.75.207.88:9900
  → Clash 无法路由 Tailscale IP                      ← unexpected end of stream
```

**代码缺失点（`OkHttpTransportFactory.kt:194`）**：

```kotlin
// 现状（有缺陷）
val chosen = if (sf == null) client else client.newBuilder().socketFactory(sf).build()

// 修法（leader 裁定）
val chosen = if (sf == null) client else client.newBuilder().socketFactory(sf).proxy(Proxy.NO_PROXY).build()
```

对照：`HttpUrlConnectionUploader.kt:174` 的上传路径已有 `.proxy(Proxy.NO_PROXY)`（缺陷① 时补上的），注释明确写「显式 NO_PROXY 防系统 ProxySelector 叠加一跳」。WebSocket 路径遗漏了同样的保护。

### 7.3 JVM 探针（T1/T2）自证输出

探针文件：`app/app/src/test/java/dev/agentmirror/app/service/SystemProxyLeakProbeTest.kt`

**运行命令**：
```bash
cd app && ./gradlew :app:testDebugUnitTest \
  --tests "dev.agentmirror.app.service.SystemProxyLeakProbeTest"
```

**当前 HEAD XML 结果**：
```xml
<testsuite name="dev.agentmirror.app.service.SystemProxyLeakProbeTest"
           tests="2" skipped="0" failures="1" errors="0">
  <testcase name="T1 tsnet socketFactory 注入时系统代理不应被访问">
    <failure>expected:&lt;0&gt; but was:&lt;1&gt;   ← 记账代理被访问 1 次，确认缺陷</failure>
  </testcase>
  <testcase name="T2 无 tsnet socketFactory 时系统代理应正常生效（防过度修复）"/>
                                                  ← PASS，sf==null 路径行为正常
</testsuite>
```

| 探针 | 当前 HEAD | 修法后期望 |
|------|-----------|----------|
| T1（红测）：sf!=null 时记账代理被访问次数 == 0 | **FAIL（红）** `was:1` | PASS（绿） |
| T2（防过度修复）：sf==null 时记账代理被访问次数 > 0 | PASS（绿） | PASS（绿，修法不动此分支）|

### 7.4 notifySocksRouteFailure 自愈机制的有害性判定

**结论：有害（Harmful），非「无用但无害」。**

**已在用户日志中触发**：SOCKS 端口从 37557（第 1–5 次）变为 41873（第 6 次），说明 tsnet 节点被重建了一次。重建后，第 6 次连接目标变为 100.75.207.88:9900（直达服务器）并成功——说明重建后恰巧绕开了 Clash（可能是 Clash 在此期间重启或 ProxySelector 的返回值暂时为 NO_PROXY）。

**有害三点**：

1. **延迟累积**：每次 `forceRestartWithCurrentKey` 需停旧节点 + 重建新节点 + WireGuard 重握手，耗时约 3–10 秒。5 次失败的窗口（12:00:01 → 12:00:33，32 秒）中大部分是节点重建延迟，而非 ConnectivityManager 退避。
2. **健康节点被误杀**：tsnet 节点从未坏过（SOCKS 代理一直在监听），重建是无效操作，浪费了 WireGuard 握手资源。
3. **无根因修复时形成重启循环**：若不加 NO_PROXY，每次重建后新 OkHttpClient 仍继承 ProxySelector → 仍连到 Clash → 继续 notifySocksRouteFailure → 继续重建。节流（30s）是唯一保护。

**处置建议**：先修 NO_PROXY（根因，彻底消除触发源），然后评估 notifySocksRouteFailure 是否仍有存在价值（为真正的 DERP 死亡场景兜底）。如保留，需缩小触发条件（增加「tsnet 实际不可达」验证，区分代理路由失败 vs tsnet 自身故障）。

---

## 8. 相关代码位置速查

| 文件 | 行号 | 内容 |
|------|------|------|
| `OkHttpTransportFactory.kt` | 194 | **缺失 `.proxy(Proxy.NO_PROXY)`（§7 新根因，修法目标）** |
| `TsnetWire.kt` | 91 | 幂等守卫（`m.state is Up → return`） |
| `OkHttpWebSocketTransport.kt` | 161 | `socketFactoryFor(TsnetWire.state, host)` 无健康检查 |
| `TsnetDial.kt` | 68–69 | `socketFactoryFor` 只看 state，不 ping 代理 |
| `GomobileTsnetBackend.kt` | 39–55 | `start()` 阻塞至节点 Up，之后不再回调 state |
| `NetworkConnectivityWatcher.kt` | 73–78 | `onAvailable` 只打断退避，不重启 tsnet |
