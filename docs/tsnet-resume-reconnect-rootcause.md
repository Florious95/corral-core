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

> ⚠️ 关卡 1 依赖模拟器实测，正在与 w-up-probe 协调环境共用。探针（关卡 2）已在 JVM 层自证通过，实机复现补充后更新本节。

**预期步骤：**
1. 安装 App，扫 QR 配对（含 TS authkey），确认工作区列表正常渲染（连接已建立）
2. 按 Home 键将 App 切到后台（不 kill），等待 30–60 秒
3. 重新打开 App
4. 观察连接状态：预期看到「连接中断，正在重连…」通知且**永不恢复**

**对照组（B 路径，关卡 1 必做）：**
1. 手机安装官方 Tailscale App 并加入 tailnet
2. App 使用服务端的 tailnet IP（100.x.x.x）直连（不配 TS authkey）
3. 杀到后台 → 重新打开 → 确认立刻连上

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

## 7. 相关代码位置速查

| 文件 | 行号 | 内容 |
|------|------|------|
| `TsnetWire.kt` | 91 | 幂等守卫（`m.state is Up → return`） |
| `OkHttpWebSocketTransport.kt` | 161 | `socketFactoryFor(TsnetWire.state, host)` 无健康检查 |
| `TsnetDial.kt` | 68–69 | `socketFactoryFor` 只看 state，不 ping 代理 |
| `GomobileTsnetBackend.kt` | 39–55 | `start()` 阻塞至节点 Up，之后不再回调 state |
| `NetworkConnectivityWatcher.kt` | 73–78 | `onAvailable` 只打断退避，不重启 tsnet |
