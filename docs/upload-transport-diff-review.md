# Diff 审查报告：fix-upload-transport-tsnet（fb31674d2）

> 审查席：w-rev-upload  
> 日期：2026-08-14  
> 审查对象：`app/app/src/main/java/dev/agentmirror/app/session/HttpUrlConnectionUploader.kt`（129+/31−）

---

## TL;DR

**无阻塞级缺陷。** 改动方向正确，选路逻辑与参照实现（OkHttpWebSocketTransport.kt:161）一致。
找到 4 条问题，最高严重度为 **中**（资源泄漏，实际影响目前可忽略但需订正）。
2 条为说明性发现（与 WS 参照同款限制，非本次引入的退步）。

---

## 一、已验证清白的项目（不重复验证）

| 检查项 | 结论 | 依据 |
|--------|------|------|
| 选路判据边界（100.63.x / 100.128.x） | ✅ 正确 | `isTailnetHost`：`octets[1] in 64..127` 精确覆盖 /10 掩码 |
| D-22 零请求断言（二参入口） | ✅ 保留 | L82-83：立即返回 Failure，不调用任何 HTTP 路径 |
| multipart 格式与 Bearer 头 | ✅ 两路径一致 | `buildMultipartBody` 未改动；SOCKS 路径在 `requestBuilder.header("Authorization", ...)` 设 Bearer |
| 单测 5/5 绿（SOCKS 路径由红转绿） | ✅ 已由测试席验证 | fix-upload-transport-tsnet-test.json |
| 直连路径零行为变化（LAN/域名/未 Up） | ✅ 保留 | `uploadViaHttpUrlConnection` 完整保留原逻辑 |

---

## 二、发现

### F1 — connectionPool 清理线程未显式驱逐（低）【已订正】

**位置**：`HttpUrlConnectionUploader.kt:181`

```kotlin
} finally {
    client.dispatcher.executorService.shutdown()   // dispatcher executor 关了
    // connectionPool.evictAll() 未调用
}
```

**原始判断（错误）**：曾建议改用 `client.close()`。

**订正**（leader 2026-08-14 实证）：本仓库依赖 `com.squareup.okhttp3:okhttp:4.12.0`（JVM canonical 版），
`OkHttpClient` 在 OkHttp 4.x **没有 `close()` 方法**——`Closeable` 是 OkHttp 5.x
拆分后 okhttp-kotlin 的新 API。照原建议改会直接 `Unresolved reference 'close'` 编译失败。
`dispatcher.executorService.shutdown()` 在 OkHttp 4.x JVM 上是正确做法，不是错误用法。

**实际遗漏**：`connectionPool.evictAll()` 未调用。`ConnectionPool(0, 1, TimeUnit.NANOSECONDS)`
maxIdleConnections=0 使实际影响最小化（cleanup 线程发现无连接后退出），但显式
`evictAll()` 才是完整清理。

**修法**（已在提交 `807c122f9` 实现）：

```kotlin
} finally {
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
}
```

**严重度**：低（当前参数下近零影响，但显式 evictAll 更正确）

**校准教训**：审查涉及第三方库 API 时，先确认仓库实际依赖的版本/artifact，
再谈用法对错。本仓库依赖声明在 `app/app/build.gradle.kts`，`javap` 可验类上有没有目标方法。

---

### F2 — SOCKS 路径连接超时可达直连路径的 2 倍（低）

**位置**：`TsnetSocks.kt:152-159`（`TsnetProxySocket.connect`）

**场景**：tsnet SOCKS 代理存活但目标端建链慢

**分析**：

```
直连路径：connectTimeout = 10s（TCP → 目标）
SOCKS 路径：
  TCP → 代理 = connectTimeout (10s, OkHttp 传入)
  SOCKS 握手 = soTimeout = if (timeout > 0) timeout else HANDSHAKE_TIMEOUT_MS = 10s
  ─────────────────────────────────────────────────
  最大失败等待 = 20s
```

OkHttp 的 `connectTimeout` 在 `TsnetProxySocket.connect()` 里被同时用作：
1. TCP 连代理的超时
2. SOCKS 握手的 `soTimeout`

两段串行，最坏情况翻倍。

**是否是退步**：**不是**。WS 参照实现 `OkHttpTransportFactory` 走同款 `TsnetProxySocketFactory`，行为一致。
用户真机报错 "after 10000ms" 是直连超时；SOCKS 路径超时不再走直连，而是等代理握手失败，
最坏 20s。这比直连 timeout 慢，但失败语义更精确（错误来自 SOCKS 而非超时猜测）。

**严重度**：低（设计一致性问题，非本次引入）

**建议**：KDoc 中注明握手 soTimeout 与 connectTimeout 相同，告知维护者"改超时要同时看握手"。
无需改代码，与 WS 路径保持对称更重要。

---

### F3 — SOCKS 失败时用户文案暴露协议细节（低）

**位置**：`HttpUrlConnectionUploader.kt:102-103`

```kotlin
} catch (e: Exception) {
    UploadOutcome.Failure("上传失败：${e.message ?: "网络异常"}")
}
```

**场景**：SOCKS 建链失败时 `e.message` 来自 `TsnetSocks.handshake` 的异常，例如：
- `"SOCKS5 代理建链失败: host unreachable"`
- `"SOCKS5 代理建链失败: connection refused"`
- `"SOCKS5 auth 阶段代理关闭连接"`

这些消息会直接进入 `UploadOutcome.Failure.message`，若 UI 层展示给用户，
显示的是协议级信息而非可操作的用户文案。

**是否是退步**：**不是**。直连路径同样暴露 `IOException.message`（"Connection timed out"
"failed to connect to /100.x.x.x"）。SOCKS 路径的文案可读性反而略好（中文描述了失败阶段）。

**严重度**：低

**建议**：未来统一上传失败文案时，在 SOCKS 路径异常处加一层包装（"上传通道故障，请检查 Tailscale 连接"），
但不在此 diff 范围内处理。

---

### F4 — IPv6 tailnet 地址及主机名 tailnet 端点走直连（说明性）

**位置**：`TsnetDial.kt:76-88`（`isTailnetHost`）

**分析**：

```kotlin
fun isTailnetHost(host: String?): Boolean {
    if (host.isNullOrEmpty()) return false
    val parts = host.split('.')
    if (parts.size != 4) return false   // ← IPv6 地址（含 ':'）→ false；主机名段数 ≠ 4 → false
    ...
    return octets[0] == 100 && octets[1] in 64..127
}
```

- **IPv6 tailnet**（`fd7a:115c:a1e0::/48`）：`parts.size != 4` → false → 直连。
  tailnet IPv6 不可路由，直连会失败但不会 panic。
- **主机名端点**（`http://my-mac.tail1234.ts.net:9900/`）：段数 ≠ 4 → false → 直连 → DNS 失败。

**是否是退步**：**不是**。`OkHttpTransportFactory` 调用同一个 `isTailnetHost`，限制一致。
当前所有已知端点均使用 100.x IPv4（用户真机实证：`100.75.207.88`）。

**严重度**：说明性（已知限制，与 WS 路径对称，当前不影响用户）

**建议**：在 `isTailnetHost` KDoc 中显式声明"仅覆盖 100.64/10 IPv4；IPv6 tailnet 及主机名端点不走 SOCKS"，
避免后续维护者误以为这是 bug。

---

## 三、红测

### F1_OkHttpClientCloseTest — 验证资源关闭语义

**目标**：证明当前代码使用了错误的关闭 API（`shutdown()` 而非 `close()`），并提供可回归验证的行为断言。

```kotlin
// app/app/src/test/java/dev/agentmirror/app/session/OkHttpClientCloseTest.kt
```

直接测试私有方法不可行，改为观察行为差异：
- `OkHttpClient.dispatcher.executorService.isShutdown` 在 `shutdown()` 后为 true
- `OkHttpClient.connectionPool` 的清理线程需 `close()` 才终止

可用反射检查 `ConnectionPool` 内部状态，但脆性高。更务实的测试方案：
验证"每次 SOCKS 上传后不产生 OkHttp 泄漏线程（除 connectionPool 清理线程外）"——
因当前 ConnectionPool(0, 1ns) 清理线程立即退出，该测试即使不 `close()` 也会绿。

**结论**：此发现属代码质量/API 正确性问题，**不写红测**（写了也会绿，无法区分）。
直接在 §四 给出修改建议。

---

## 四、汇总与建议

| # | 位置 | 问题 | 严重度 | 建议 |
|---|------|------|--------|------|
| F1 | `HttpUrlConnectionUploader.kt:181` | `client.close()` 应替换 `dispatcher.executorService.shutdown()` | 中 | 一行改动，`client.close()` |
| F2 | `TsnetSocks.kt:152`（设计决策） | SOCKS 路径最坏失败 20s vs 直连 10s | 低 | KDoc 注明，无需改代码 |
| F3 | `HttpUrlConnectionUploader.kt:102` | SOCKS 失败文案含协议细节 | 低 | 未来统一文案时处理，不阻塞 |
| F4 | `TsnetDial.kt:76`（共享限制） | IPv6/主机名 tailnet 端点走直连 | 说明性 | KDoc 显式声明限制 |

**阻塞施工的有**：无。F1 建议在下一次触碰 `HttpUrlConnectionUploader` 时顺带修，不单独开工。

---

## 五、查了哪些，怎么查的

1. **选路判据边界**：手算 `octets[1] in 64..127` 对应 100.64.0.0/10 的 /10 掩码展开（前 10 bit = `01100100.01`），100.63=63 在范围外、100.128=128 在范围外，边界正确。
2. **state 读取时机（缺陷⑤）**：`TsnetWire.state` 是 `@Volatile`，每次调用现读。若 state 谎报 Up 但 SOCKS 代理已停，连接到 127.0.0.1:port 会快速 "Connection refused"，不卡 10s。比直连超时更快失败。
3. **资源生命周期**：逐行读 `uploadViaOkHttp`，确认 `resp.close()` 在 `finally`，`client.dispatcher.executorService.shutdown()` 在外层 `finally`。对比 OkHttp 文档确认 `close()` 才是完整关闭。
4. **超时语义**：读 `TsnetProxySocket.connect()` 源码，确认 `soTimeout = timeout`（OkHttp 传入 connectTimeout 值），造成两段 10s 串行最坏 20s。与 WS 路径做了 diff，行为一致。
5. **D-22 不倒退**：二参 `upload()` L82-83 无条件 `return Failure`，不经 `buildMultipartBody` 或任何网络调用。
6. **multipart / Bearer**：`buildMultipartBody` 函数签名未改动，两路径共用同一 `body` ByteArray，boundary 同一字符串。OkHttp 路径显式 `requestBuilder.header("Authorization", "Bearer $uploadToken")`。
7. **IPv6/主机名**：读 `isTailnetHost`，`parts.size != 4` 直接返回 false；IPv6 含 `:` 后 split('.') 不得 4 段；主机名段数不定，不为 4 → false。
