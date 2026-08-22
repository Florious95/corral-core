# TS 入网失败 · 只读侦察（release 包）

工作树：`.worktrees/wt-pb-core2` HEAD=`ff603db8a`。未改产品文件、未出包、未开模拟器。

用户现象：release 包卡在「TS 入网中」后入网失败。debug 是否同失败用户还在测。UI 文案对应配对页 `tailnet 入网中…` / `入网失败：…`。

---

## 1. 网络安全配置

**结论：debug/release 共用一份 main manifest；无 `networkSecurityConfig`；明文放行对 debug/release 一致。没有 `src/debug/` 或 `src/release/` 覆盖。从清单合并角度看不出「debug 能入网、release 不能」的配置差。**

`app/app/src/main/AndroidManifest.xml`：

- **没有** `android:networkSecurityConfig`（全文无此属性）。
- `res/` 下也没有 network_security XML（仅 `xml/file_paths.xml` 给 FileProvider）。
- 明文：

```48:60:app/app/src/main/AndroidManifest.xml
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AgentMirror"
        android:usesCleartextTraffic="true"><!--
          明文策略（leader 裁定，debug/release 一致，勿改判）：targetSdk 35 默认禁明文，
          ws:// 必抛 CleartextNotPermitted（e2e 实证缺陷根因②）。明文放行依据需求 007/011：
          ws:// 是本产品出厂传输；tailnet 层已 WireGuard 加密；LAN 明文属用户自网；
          TLS 列后续版本议题，不许用禁明文杀死产品本体。
        -->
```

INTERNET 等同在 main（24–28 行），注释写 ws:// 与 tsnet 都要 socket。

另一份 manifest 只有 androidTest 仪器：

```1:7:app/app/src/androidTest/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- 自执行 runner 只依赖 Android 平台 API，避免越过写盘边界修改产品构建配置。 -->
    <instrumentation
        android:name="dev.agentmirror.app.termview.PinchHarnessInstrumentation"
        android:targetPackage="dev.agentmirror.app" />
</manifest>
```

`src/` 目录实际只有 `androidTest` / `main` / `test`，**没有 `debug`、`release` sourceSet 目录**（见第 4 条 `ls`）。

---

## 2. `BuildConfig.DEBUG` 分支

**结论：全 app（含 core 模块）源码 `BuildConfig.DEBUG` 零命中。不存在「debug 走 A、release 走 B」的 Kotlin 分支。**

命令：`grep -r BuildConfig.DEBUG app/`（`*.kt`）→ 无匹配。  
同时 `debuggable` / `isDebuggable` 在产品 `*.kt`/`*.xml` 中也零命中。

因此：若 debug 包入网成功而 release 失败，**不是这份源码里的 BuildConfig 分支造成的**（可能是 native/系统/运行时差异，源码层面查不清）。

---

## 3. tsnet 入网路径（点入网 → 成功/失败）

UI：「连接」或扫码带 key → 文案 `tailnet 入网中…`（Starting）或 `入网失败：…`（Error）。

```557:561:app/app/src/main/java/dev/agentmirror/app/pairing/PairingScreen.kt
private fun tsStateLine(state: TsnetState): Pair<String, Boolean> = when (state) {
    TsnetState.Idle -> "填入 auth key 后点「连接」，或直接扫携带 key 的二维码，自动加入 tailnet。" to false
    TsnetState.Starting -> "tailnet 入网中…" to false
    is TsnetState.Up -> "已入网：节点已连接，数据通道需要几秒建立。" to false
    is TsnetState.Error -> "入网失败：${state.reason}" to true
```

调用链：

1. 手填/扫码起网  
   `PairingViewModel.submitManual` / `onQrScanned` → `tsnetStarter(currentTsAuthKey)`  
   生产接线：`PairingRoute.kt:82` `tsnetStarter = TsnetWire::ensureStarted`

```206:209:app/app/src/main/java/dev/agentmirror/app/pairing/PairingViewModel.kt
        currentTsAuthKey = manualTsAuthKey.trim()
        if (currentTsAuthKey.isNotEmpty()) tsnetStarter(currentTsAuthKey)
        startPairingSequence(listOf(url), token, resetCandidates = true)
```

2. `TsnetWire.ensureStarted`：注册 secret、要 environment、同 key 且 Starting/Up 则 return、否则 `TsnetManager.start`

```84:114:app/app/src/main/java/dev/agentmirror/app/tsnet/TsnetWire.kt
    fun ensureStarted(authKey: String) {
        val key = authKey.trim()
        DiagLog.registerSecret(key)
        val env = environment
        if (env == null) {
            onState(TsnetState.Error("tsnet 环境未初始化（内部接线缺陷，请重启 App）"))
            return
        }
        ...
        created.start(stateDirForKey(env.stateDir, key), env.hostname, key)
    }
```

3. environment 注入：`TsnetBootstrap.install` → **私有目录** `filesDir/tsnet`（非外部存储）

```33:40:app/app/src/main/java/dev/agentmirror/app/service/TsnetBootstrap.kt
    fun install(context: Context) {
        if (TsnetWire.environment != null) return
        TsnetWire.environment = TsnetWire.Environment(
            stateDir = File(context.applicationContext.filesDir, "tsnet").absolutePath,
            hostname = TsnetWire.sanitizeHostname(Build.MODEL ?: "device"),
        )
    }
```

4. `TsnetManager.start`：非法 key → 立刻 Error；否则 Starting，后台线程 `backend.start`（**Kotlin 侧无超时**）

```86:102:app/app/src/main/java/dev/agentmirror/app/tsnet/TsnetManager.kt
    fun start(stateDir: String, hostname: String, authKey: String): Boolean {
        if (state is TsnetState.Starting || state is TsnetState.Up) {
            DiagLog.record("tsnet", "start 被幂等守卫拦下 state=$state（key 指纹相同）")
            return false
        }
        ...
        transition(TsnetState.Starting)
        executor.execute { runStart(gen, stateDir, hostname, key) }
        return true
    }
```

5. 真后端 `GomobileTsnetBackend.start`：**阻塞到 gomobile `Tsnetbind.start`（内部 tsnet.Up）**；可选读 `control_url.txt`（节点目录或父目录）；无 Kotlin 超时/重试。

```39:54:app/app/src/main/java/dev/agentmirror/app/tsnet/GomobileTsnetBackend.kt
    override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy {
        installInterfaceProvider()
        val controlUrl = runCatching {
            val nodeDir = File(stateDir)
            val controlFile = File(nodeDir, CONTROL_URL_FILE).takeIf { it.isFile }
                ?: nodeDir.parentFile?.let { File(it, CONTROL_URL_FILE) }?.takeIf { it.isFile }
            controlFile?.readText()?.trim()
        }.getOrNull().orEmpty()
        val n = tsnetbind.Tsnetbind.start(stateDir, hostname, authKey, controlUrl)
        node = n
        return TsnetProxy.parse(n.proxyAddr(), n.proxyCred())
    }
```

KDoc 写明：Start 语义为阻塞至真正入网（31–32 行）。失败折叠为 `TsnetState.Error`（`TsnetManager.runStart` 114–123 行）。

6. 配对是否等 Up：目标 host 是 CGNAT 且有 key 时 `waitingForTsnet = true`，**此时不占用 3s/15s 拨号预算**。

```406:413:app/app/src/main/java/dev/agentmirror/app/pairing/PairingViewModel.kt
        if (mustWaitForTsnet(url)) {
            when (val state = tsState) {
                is TsnetState.Up -> startProbe()
                is TsnetState.Error ->
                    advanceAttempt(PairingFailCause.UNREACHABLE, "tailnet 入网失败：${state.reason}")
                else -> waitingForTsnet = true
            }
```

```275:275:app/app/src/main/java/dev/agentmirror/app/pairing/PairingViewModel.kt
        if (!waitingForTsnet && pairingStatus is PairingStatus.Pairing && now - pairingStartedAt > attemptBudgetMs) {
```

超时常数：`PAIR_TIMEOUT_MS = 15_000L`、`CANDIDATE_TRY_MS = 3_000L`（489–492 行）。**等 tsnet 期间这条超时不跑**，UI 会一直「入网中」直到 native `Start` 返回。

**依赖盘点：**

| 依赖 | 有没有 | 出处 |
|---|---|---|
| 本地明文 HTTP | 配对 WS 可能 `ws://`；tsnet 控制面由 gomobile 决定（空 = 官方控制面）。Kotlin 未写死明文 HTTP 去入网 | Manifest 55–59；Gomobile 41–52 |
| 本地回环端口 | **入网成功之后** SOCKS 才走 loopback（`TsnetDial.socketFactoryFor` 仅 `state is Up`） | TsnetDial.kt:68–69；TsnetSocks 156 行连 `proxy.host:port` |
| `android:debuggable` 才允许的能力 | 源码零引用。release 包 aapt 无 debuggable。native Start 是否依赖 debuggable **查不清** | 第 2 条；此前 release 自证 |
| 写路径 | **应用私有** `filesDir/tsnet`，按 key SHA-256 分子目录 | TsnetBootstrap 37；TsnetWire.stateDirForKey 121–130 |

网卡：API 30+ 不用 netlink，用 Java `NetworkInterface`（Gomobile 28–30、82–107）。与 debug/release 无关。

SOCKS 握手超时只在 **Up 之后拨号**：`HANDSHAKE_TIMEOUT_MS = 10_000`（TsnetSocks.kt:181）。卡在「入网中」时还没走到这里。

---

## 4. 权限与 sourceSet 差异

`app/app/src` 实际目录：

```
androidTest/
main/
test/
```

**不存在 `src/debug/`、`src/release/`**（`find … \( -name debug -o -name release \)` 为空）。  
因此没有 debug 专属 manifest/res 覆盖。权限全部在 main manifest（INTERNET、ACCESS_NETWORK_STATE、前台服务、CAMERA 等）。

androidTest 的 instrumentation **不进 release APK 产品清单合并的主路径**（仪器测试包）。对用户安装的 release 包无影响。

---

## 5. 今晚的改动有没有碰到 tsnet / 连接建立

**结论：没有动 tsnet 起网/入网判定。`addBinaryListener` 只改 WS 二进制帧按 ref 分发，发生在连接已 READY、会话已 subscribe 之后。**

`addBinaryListener` 正文：

```156:159:app/core-conn/src/main/java/dev/agentmirror/app/conn/ConnectionManager.kt
    fun addBinaryListener(ref: String, listener: Listener) {
        binaryListeners.getOrPut(ref) { LinkedHashSet() }.add(listener)
    }
```

消费点只有 `onBinary`（镜像帧），647–669 行：按 `frame.ref` 投递。不调用 `TsnetWire` / `TsnetManager.start` / `GomobileTsnetBackend.start`。

`ConnectionManager.start()` / `scheduleReconnect` / tsnet 文件均无 `addBinaryListener`。  
PerfTrace/`ConnPerf` 在 conn 里打的是 subscribe/收帧/layout，不是 tsnet 状态机。

配对探针用**独立** `ConnectionManager`（PairingRoute.kt:75–79），不经 ServiceWire 常驻槽；起网是 `TsnetWire.ensureStarted`，与 listener 分发正交。

若 release 入网失败，用现有证据**不能**归因于 t.instr* / t.fixblank。debug 是否同样失败仍待用户实测。

---

## 6. 诊断日志（本轮不补代码）

**已有：**

- `TsnetManager`：`start 被幂等守卫拦下`（91）；每次 `state $from → $to`（145）。Error 会留下 `state Starting → Error(...)`。
- `TsnetWire`：`ensureStarted 被幂等守卫拦下`（101）；`environment == null` 变 Error 但**没有单独 DiagLog**（91–93 只 `onState`）。
- SOCKS：`dial ok` / `dial fail` 带 host/port/ex/ms（TsnetSocks 166–174）——**仅 Up 之后**。
- PerfTrace：**不覆盖 tsnet 入网**（八事件是打开会话链路）。

**缺口（卡「入网中」再失败时，现有日志分不清 native 卡在哪）：**

`GomobileTsnetBackend.start` **全程无 DiagLog**。进 native 前/返回后/抛错时都是沉默。

建议补点（⛔ 本轮不动手），每条带比较两边操作数：

1. **`GomobileTsnetBackend.start` 入口**（约 40 行后）：`tsnet start enter stateDir_len= hostname= has_control_url=0|1 control_url_len=`（不要写 authkey）。
2. **`Tsnetbind.start` 返回前后**：`tsnet start native begin` / `native ok proxy_port= elapsed_ms=` 或 `native fail ex= msg= elapsed_ms=`（msg 须先 redact）。
3. **`waitingForTsnet=true` 时的等待**：`PairingViewModel.beginAttempt` 412 行旁：`wait_tsnet=1 url_is_tailnet= ts_state=`；以及 `onTsnetState` 94 行：`wait_tsnet 解除 reason=Up|Error elapsed_ms=`。  
   现状：等 Up 期间 **15s 超时被关掉**（275 行 `!waitingForTsnet`），若 native 挂死，日志里只有 Starting、没有失败时刻。
4. **`ensureStarted` env==null**（91–93）：补 `env_null=1` 的 record，否则只有 UI Error、DiagLog 没有。

有导出日志时：有 `state Idle → Starting` 无后续 `→ Up/Error` = 仍堵在 native Start；有 `→ Error(reason)` = 入网失败原因在 reason（已脱敏）。

---

## 总括（给摘要用）

1. 无 networkSecurityConfig；`usesCleartextTraffic=true` 写在 main，debug/release 无 sourceSet 覆盖。  
2. 全仓无 `BuildConfig.DEBUG`。  
5. `addBinaryListener` 只动收帧分发，不动 tsnet 起网。
