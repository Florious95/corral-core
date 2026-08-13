# 诊断日志 + 设置页导出 — 审查席对抗性预审（feat-diagnostic-log-export）

**审查立场**：证明这套日志会泄密、会长胖、或者会白记，不是来点头的。
**审查时点说明**：本轮审查发起时 `app/app/src/main` 下**没有 `diag/` 目录**，开发席尚未落地任何代码，
测试席也未提交场景红测。因此本报告分两部分：

- **§1 脱敏**：对现有代码库中"未来诊断日志系统必然会碰到/复用"的凭据流路径做预审，
  找到 3 个真实存在的漏洞（已用真实代码构造红测证明，非假设），归档在
  `app/app/src/test/java/dev/agentmirror/app/{conn,tsnet}/`。
- **§2 静默经济 + §3 白记**：diag 模块不存在，**无法做实测**（没有代码可测、没有二进制可跑）。
  本轮只能做"落地前必须满足的验收判据"清单，供开发席/测试席对照，等实现出现后本席会补一轮
  实测审查（空闲 CPU 采样、写满测占用、对缺陷⑤/②逐条判据）。这不是"没查"，是"查了，目标不存在"。

---

## §1 脱敏（硬红线，最高优先）

### 发现 1【高】AuthFrame 无安全 toString() 覆盖 — 已用红测证明

**位置**：`app/app/src/main/java/dev/agentmirror/app/conn/Frames.kt:123-129`

```kotlin
data class AuthFrame(
    @SerialName("token") val token: String,
) : FramePayload {
```

**怎么坏**：KDoc 承诺"token 绝不被记录或回显"，但这只是注释，没有代码兜底。Kotlin data class
默认 `toString()` 会生成 `AuthFrame(token=pairtoken-xxx)`。诊断日志系统最自然的实现方式就是在
`Connection`/`ConnectionManager` 收发帧处加一行 `Log.d(TAG, "frame=$frame")` 做调试可见性——这条路径
一旦落地，token 整串写入环形缓冲，**脱敏无论多严密都拦不住**，因为泄露发生在"记录了什么对象"这一步，
不是"记录后有没有正则清洗"这一步。

对比全仓库其它携带凭据的 data class：`QrPayload`、`PairingConfig`（pairing/PairingModels.kt）、
`TsnetProxy`（tsnet/TsnetBackend.kt）都**已经**显式覆盖了 `toString()` 做 `[redacted]`。
`AuthFrame` 和下面的 `ConnectionConfig` 是全仓库唯二的例外——这说明团队知道这个模式，只是这两处漏做了。

**红测**：`app/app/src/test/java/dev/agentmirror/app/conn/AuthCredentialToStringLeakTest.kt`
（`AuthFrame 默认 toString 明文吐出 token`）— **跑绿，证明漏洞现在就存在**（不依赖 diag 模块）。

**严重度**：高（一旦诊断系统对帧对象调用默认 toString 做调试日志，200% 命中；且这是"看起来做了防护，实际没做"的隐蔽陷阱，代码审查靠肉眼很容易漏过）。

**建议**：在 `AuthFrame` 补 `toString()` 覆盖，返回 `AuthFrame(token=[redacted])`。这应该在 diag 模块动工**之前**修，作为诊断系统的前置依赖，而不是诊断系统自己再做一层过滤。

---

### 发现 2【高】ConnectionConfig 无安全 toString() 覆盖 — 已用红测证明

**位置**：`app/app/src/main/java/dev/agentmirror/app/conn/ConnectionManager.kt:40-43`

```kotlin
/** 连接配置：目标 URL 与配对 token（token 只上行一次，不回显、不落日志）。 */
data class ConnectionConfig(
    val url: String,
    val token: String,
)
```

**怎么坏**：与发现 1 同类陷阱，且风险面更大——`ConnectionConfig` 是 `ServiceWire.config` /
`ConnectionManager` 的核心状态对象，"当前连接配置快照"是诊断系统最自然会想加的一项
（用户报"连不上"，第一反应就是 dump 一下当前配置在连哪个地址）。一旦某处诊断代码写
`Log.d(TAG, "current config: $config")` 或把 config 对象整个塞进导出的诊断快照 JSON（用反射/
kotlinx.serialization 默认序列化），token 原样落盘。

**红测**：`app/app/src/test/java/dev/agentmirror/app/conn/AuthCredentialToStringLeakTest.kt`
（`ConnectionConfig 默认 toString 明文吐出 token`）— **跑绿**。

**严重度**：高，理由同发现 1。

**建议**：同上，补 `toString()` 覆盖。**这两处修复建议直接转给开发席，作为 diag 模块的前置依赖项**，不属于本审查席的写权限（write_scope 限定 `docs/` + `app/app/src/test/`）。

---

### 发现 3【高】redactAuthKey 只清洗顶层 message，不触达 cause 链 — 已用红测证明

**位置**：`app/app/src/main/java/dev/agentmirror/app/tsnet/TsnetManager.kt:118-121`

```kotlin
private fun redactAuthKey(error: Throwable, authKey: String): String {
    val reason = error.message ?: error.javaClass.simpleName
    return reason.replace(authKey, "[redacted]")
}
```

**怎么坏**：这是"看起来做了脱敏，实际防线不完整"的典型案例。`redactAuthKey` 只读
`error.message`（顶层），生成的干净字符串进了 `TsnetState.Error.reason`，UI 展示确实安全。
但 `runStart`（TsnetManager.kt:99-116）拿到的**原始 Throwable 对象本身从未被清洗**，现在也没人记录它，
所以现状安全——**问题在于诊断日志系统落地后会怎么用它**。

Android 里"记录一个异常"的标准写法是 `Log.e(TAG, "tsnet start failed", it)`，直接把原始 Throwable
传给 Log 的第三个参数，而不是先转成字符串再传。Android Log 对待这个参数的方式等价于
`Throwable.printStackTrace()`：**递归打印整条 cause 链的每一层 message**，不只是顶层。
如果 gomobile/tsnet 原生层的错误是"重新包装再抛"模式（顶层是通用描述，真正的 dial 失败原因作为
cause 保留——这是很常见的错误包装惯例），authkey 藏在 cause 里，`redactAuthKey` 完全看不见它。

也就是说：只要诊断代码在这个 catch 分支旁边**多写一行** `Log.e(TAG, msg, it)` 把原始异常（而不是
已脱敏的 `reason` 字符串）传给 Log，凭据就会经 cause chain 原样落盘，而 `state.reason` 本身看起来
完全正常——**代码审查看 UI 展示的 reason 字段看不出任何异常，只有去看谁碰了原始 Throwable 才发现问题**。

**红测**：`app/app/src/test/java/dev/agentmirror/app/tsnet/TsnetManagerCauseChainLeakTest.kt`
（`redactAuthKey 清洗顶层 message,但原始 Throwable 的 cause 链仍原样携带 key`）——用真实
`TsnetManager` + fake 后端构造"顶层异常干净、cause 里带 key"的场景，`reason` 断言不含 key（**通过**，
证明现状真的安全），随后对原始 Throwable 做 `printStackTrace()` 还原，断言 dump 里**包含** key
（**通过**，证明一旦诊断代码直接记录原始异常对象，防线立刻失效）。

**严重度**：高。这是留给未来实现者的一个"看起来已经处理过凭据"的假安全感陷阱，最容易在代码评审时被放过。

**建议**：diag 模块的日志接入点**禁止**直接把 `Throwable` 对象传给 Log/环形缓冲；一律先经过
一层"取 message 全链（含所有 cause）→ 统一脱敏"的转换函数，脱敏逻辑要能处理整条 cause 链，不能
只处理 `error.message`。这条建议应该写进 diag 模块的日志接入规范，而不是逐个调用点自己判断。

---

### 发现 4【中，前瞻性】"认前缀"式脱敏对 headscale 格式 key 必然失效 — 已用红测证明

**位置**：`app/app/src/main/java/dev/agentmirror/app/tsnet/TsnetAuthKeys.kt:20-26`

```kotlin
/**
 * 契约：只做结构校验——trim 后非空、纯可见 ASCII（0x21..0x7e）。
 * 不校验厂商前缀：tailscale 官方 `tskey-*` 与 headscale 纯 hex 都必须放行
 */
```

**怎么坏**：这条不是在测已存在的 bug（diag 模块还没有脱敏函数可测），是给开发席一个可执行的反例。
`TsnetAuthKeys` 的契约已经明确写死：本项目**合法接受**不带 `tskey-` 前缀的纯 hex 格式凭据
（headscale 自建控制面场景）。如果 diag 模块的脱敏实现走"认 `tskey-` 前缀"的正则捷径
（这是最省事、最直觉的实现方式，也是最常见的"looks right but isn't"陷阱），headscale 部署下的
key 会**原样漏出**，而且开发/测试用的默认场景多半用 tskey- 格式的假 key 测试，测试全绿，
直到某个用 headscale 的用户导出日志才炸雷——正好复现这个项目 08-13/08-14 两次事故的模式
（"看起来做了防护，换个输入就漏"）。

**红测**：`app/app/src/test/java/dev/agentmirror/app/tsnet/TsnetAuthKeyFormatRedactionRiskTest.kt`
两个 test：① 确认 headscale 格式 key 确实是本项目认可的合法格式（锚定真实契约，非杜撰）；
② 构造一个 naive 的 `tskey-\S+` 正则脱敏器，喂入含 headscale key 的日志行，断言脱敏后
key **仍然存在**（**通过**，证明这条捷径必然失效）。

**严重度**：中（依赖开发席具体怎么实现脱敏，目前是纯前瞻性风险，不是已发生的 bug；但一旦踩中，
后果和已发生的两次事故同级）。

**建议**：diag 模块的脱敏红测（测试席职责）**必须**包含至少一条非 `tskey-` 前缀的凭据用例
（如纯 hex 格式），不能只用 `tskey-xxx` 形式的假 key 测试脱敏逻辑，否则测试本身就有盲区。

---

### 已核查、未发现问题的路径（脱敏侧，供后续核对范围参考）

以下路径经代码审查（非模拟器实测，因目标行为不涉及 UI/资源）确认当前不构成凭据泄露入口：

- `pairing/QrPayloadParser.kt` 全部异常消息为固定文案，未插值 token/authkey。
- `pairing/PairingConfigStore.kt`：TS authkey 走 Android Keystore AES-GCM 加密落盘；无日志调用。
  （旁注：`token`/`url` 明文存 SharedPreferences，超出"日志"审查口径，仅提示不计入本报告发现。）
- `tsnet/TsnetDial.kt`、`tsnet/TsnetSocks.kt`：无 `Log.*`/`println`；`IOException` 消息均为协议阶段名/REP 码文案，不插值凭据。
- `tsnet/TsnetBackend.kt` 的 `TsnetProxy`：已正确覆盖 `toString()`。
- `pairing/PairingModels.kt` 的 `QrPayload`、`PairingConfig`：已正确覆盖 `toString()`，是本项目正确防御模式的范例。
- 全项目搜索 `HttpLoggingInterceptor`/`addInterceptor`/`addNetworkInterceptor`：未发现启用，OkHttp 客户端未做请求/响应体日志拦截。
- 全项目搜索 `setDefaultUncaughtExceptionHandler`：未发现任何全局异常处理器/崩溃上报机制——**意味着 diag 模块若要接崩溃/ANR trace 必须从零设计接入点，这也是 leader 交办里明确要求覆盖的入口，目前完全空白，需要开发席在设计阶段就规划，而不是后补**。
- `session/HttpUrlConnectionUploader.kt`：`uploadToken` 只写入 `Authorization` header，不出现在字符串插值/异常消息里；URL 不含凭据 query param。
- `conn/Connection.kt`、`ConnectionManager.kt` 的拨号 URL 只含 `config.url`，token 走独立 `AuthFrame` 一次性上行，不拼进 URL。
- `service/MirrorForegroundService.kt` 的 `onReconnect` 把拨号 URL（不含 token）写入常驻通知文案——**这是一个游离于"日志"定义之外、但同样可能被诊断系统采集的数据出口**（通知栏内容可被系统截图/通知历史/无障碍服务读取）。当前安全，仅作为设计提醒：diag 模块不应该"偷懒"直接复用通知文案作为日志源。

### 尚未覆盖、需要开发席明确设计的入口（leader 交办里点名，现状代码库为空白）

- **异常堆栈 / 崩溃与 ANR trace**：全项目无 `setDefaultUncaughtExceptionHandler`，diag 模块如果要覆盖崩溃场景必须新增该接入点。这个接入点收到的 `Throwable` 可能来自任意调用链，**发现 3 的 cause-chain 脱敏缺口在这里风险最大**（崩溃堆栈天然会带出深层 cause）。
- **第三方库（OkHttp/tsnet native）自打日志被一并收进缓冲**：若 diag 实现用 Hook `android.util.Log`（全局拦截所有 tag）的方式采集，会把 OkHttp/gomobile 内部可能存在的调试日志一并吸入缓冲，这些日志的内容不受本项目控制，无法在写入点做针对性脱敏——**建议 diag 模块明确只挂钩本项目自己的 Log 调用点（白名单 tag），不做全局 Hook**，否则脱敏范围永远滞后于第三方库的输出变化。
- **配对流程中间态（QR 解析结果、剪贴板、Intent extra）**：`QrPayloadParser` 解析出的 `QrPayload` 本身 toString 安全，但如果 diag 记录"收到的原始 Intent extra"或"扫码得到的原始字符串"（这在排查配对失败时是很自然的诊断需求），会绕过 `QrPayload` 这层已做的脱敏，直接把二维码原始内容（含 token/authkey）写入日志。**这个入口在当前代码库里没有具体代码可指，纯属设计阶段提醒，需要开发席落地时明确"只记录解析后的 QrPayload（已脱敏），绝不记录原始扫码字符串/Intent extra"**。

---

## §2 静默经济（无法实测，附验收判据清单）

**现状**：`app/app/src/main` 无 `diag/` 目录，没有代码、没有可运行的诊断线程/定时器/写入循环，
**物理上无法采样空闲 CPU、无法测写满后的磁盘占用**。已确认的检查方式（供开发席交付后本席复测）：

1. 空闲 CPU：`adb shell top -p <pid>` 采样 60s+，diag 子系统不跑写入时 CPU 增量应为 0。
2. 无固定频率轮询：`adb shell dumpsys activity services` + 反编译/日志确认 diag 模块没有自建
   `Handler.postDelayed`/`ScheduledExecutorService`/`AlarmManager` 周期任务；对比现有前台服务时钟泵
   （`MirrorForegroundService`，2s 一拍，已在架构基里记录）——diag 写入应挂在**事件触发**（状态迁移/
   帧收发时机）上，不应该新起一条独立心跳去"扫描要不要写日志"。
3. 磁盘上限：写入远超容量的记录后 `adb shell du` 实测文件大小，且**进程被杀重启后**上限依然生效
   （不能只在进程存活期间做内存计数，要看落盘文件本身是否有轮转/截断机制）。
4. 热路径影响：写入是否发生在 UI 渲染帧（Compose recomposition）或拨号（`TsnetDial`/`Connection.dial`）
   的同步调用栈内——若是，需要实测写入耗时对渲染帧率/拨号延迟的影响（不能假设"就几行字符串拼接，肯定快"）。

**结论**：本条目标不存在，标记「阻塞于开发席交付」，不是「查了没问题」。开发席交付后本席会跑上述 4 项实测并更新本文档。

---

## §3 白记（无法实测，附验收判据清单）

**现状**：同 §2，diag 模块不存在，无法拿真实导出产物去对缺陷⑤/②做判定。以下是**验收时必须逐条能回答"是"**的判据清单，供开发席自查、测试席写场景红测时对照：

### 对缺陷⑤（内嵌 tsnet 回前台连不上）

- [ ] 日志能看出 `TsnetWire.state` 停在哪个值（是否卡在 `Up` 但实际拨号已经不通）？
- [ ] 日志能看出 SOCKS 拨号是否在尝试、尝试了几次、每次失败码是什么？
- [ ] 日志能看出 `ensureStarted()` 每一次被调用的时间点，以及**是否被幂等守卫拦下**（这是知识基底里点名的⑤根因候选，若日志只记"start 被调用"不记"是否被拦"，看到的还是假象）？
- [ ] 前后台生命周期事件（`ON_STOP`/`ON_START`）与上述 tsnet 事件是否共享同一时间轴/可关联（否则光看各自独立的日志段落无法判断因果）？

**若以上任一项现在打勾不了 → 记漏了，等实现落地本席会用构造场景（fake 幂等守卫拦截 + 假状态卡死）验证，验证方式：构造"state=Up 但守卫拦截 start"的场景，检查导出日志能否单凭文本重建这个事实。**

### 对缺陷②（右列文字跑到屏幕外）

- [ ] 日志有没有记录**名义 cellWidth**（设计期望值）？
- [ ] 日志有没有记录**实测 cellWidth**（实际渲染值，可能因字体度量而与名义值不同）？
- [ ] 日志有没有记录上报给服务端的 `cols`？
- [ ] 日志有没有记录画布/View 实际宽度？
- [ ] 日志有没有记录末列字形的右缘坐标？
- [ ] **光凭以上字段能不能算出"超出 View 边界几个像素"**（末列右缘坐标 − 画布宽度）？如果字段单位不统一（比如一个是 dp 一个是 px，没记 density）算不出来也算记漏。
- [ ] 捏合缩放事件前后，上述每个值的变化是否都有独立记录（不能只记最终值，缺陷复现往往在"变化过程"里）？

**判据是可执行的算术题**：拿到日志文本，能不能不问用户要截图，直接心算/笔算出像素偏移量。这是唯一的验收标准，不是"记了相关字段就算过"。

---

## Round 2（diag 本体已落地，`aeef16920` + 回归闸翻转 `8fa0961a7`）

round1 挖到的四处泄露已修复，回归闸极性已翻转为"断言不泄露、永远绿"（详见 §1 各测试文件
KDoc）。本轮复审静默经济、资源有界、白记三条，**全部基于对已落地代码的实测/驱动真实代码得出结论，
不读代码猜结果**。

### 一、静默经济——结论：热路径开销可忽略，设备级空闲 CPU 本轮未测通（原因见下）

**代码级确认（零线程/零定时器主张）**：
```
grep -nE "Thread\(|Handler\(|postDelayed|Timer\(|ScheduledExecutor|CoroutineScope|launch\s*\{|GlobalScope" \
  app/app/src/main/java/dev/agentmirror/app/diag/*.kt
```
零命中。`DiagLog.record`/`registerSecret`/`exportTo`/`recordCrash` 全部是同步调用（`ReentrantLock`
临界区 + 环形缓冲追加/正则替换），没有任何后台线程、`Handler.postDelayed`、`Timer`、协程或全局态
轮询——"零线程零定时器"的字面主张成立。

**热路径开销实测（JVM，非猜测）**：`DiagLogHotPathBenchmarkTest`（新增，round2），预热 5000 次后跑
50,000 次 `record()`（消息长度对齐 `TsnetSocks` 真实 dial-fail 行的量级），实测：

```
[DiagLog 热路径基准] 50000 次 record() 调用，总耗时=207ms，单次平均=4.15µs
```

单次 4.15µs 相对渲染帧预算（16.6ms@60fps）和 SOCKS 拨号超时窗口（秒级）都是三到四个数量级之外的
开销，可以下结论：**diag 记录不会拖慢热路径**。这条判据满足。

**设备级 idle CPU——本轮未能测通，如实说明**：
1. 查了 `emulator-5554` 当前安装的 APK：`dumpsys package dev.agentmirror.app` 显示
   `lastUpdateTime=2026-08-14 03:16:13`，而 diag 本体落地提交 `aeef16920` 的时间戳是
   `2026-08-14 04:11:14`——**设备上跑的是 diag 落地之前的旧包**，此刻在它上面测 CPU/线程数
   测的是"没有 diag 代码"的基线，对本轮判据没有意义。
2. 查了当前是否能安全地重装：`ps aux` 显示此刻有 14 个 gradle 相关进程在跑（其他席位正在
   共享 checkout 上构建/测试），此时跑 `assembleDebug`/`installDebug` 有很高概率撞见本轮
   round1 已经实证过的构建竞态（见 round1 记录的一次 `compileDebugUnitTestKotlin` 瞬时失败），
   而且会覆盖别的席位在途的构建产物。**本轮选择不重装，避免抢共享构建资源**。
3. 结论：静默经济里"设备实测空闲 CPU 是否真零"这一项**本轮未验证**，不是"验证过没问题"，
   是"没敢在共享 checkout 上跟别人抢构建窗口"。需要协调一个独占构建窗口（或等其他席位收工）
   后补测：`adb shell dumpsys cpuinfo | grep agentmirror` 连续采样 + `adb shell ls
   /proc/<pid>/task | wc -l` 前后对比。

### 二、资源有界——发现一处真实缺口（高危，新发现）：导出目录无上限

**位置**：`app/app/src/main/java/dev/agentmirror/app/SettingsScreen.kt:190-198`（`exportDiagLog`）

```kotlin
private fun exportDiagLog(context: android.content.Context): ExportOutcome {
    val dir = File(context.filesDir, DiagLog.DEFAULT_STORAGE_DIR).apply { mkdirs() }
    val file = File(dir, "diag-${System.currentTimeMillis()}.log")
    return when (val r = DiagLog.exportTo(file)) { ... }
}
```

**怎么坏**：`DiagLog.exportTo(file)` 确实守住了**单个文件** ≤ `maxFileBytes`（默认 1MiB，
`DiagLogBoundedTest` 已验证）。但 `exportDiagLog` 每次导出都用时间戳造一个**全新文件名**，
从不复用、从不清理旧文件。`DiagLog.listExports()`（KDoc 写"设置页展示历史导出用"）在
`SettingsScreen.kt` 里搜不到任何调用点——是个没接线的死接口，**没有任何代码会删除
`filesDir/diag/` 下的旧导出文件**。

这条任务的验收判据就是"用户复现一次、导出一份日志"——**复现多次意味着导出多次**（用户很可能
反复触发同一个缺陷来确认复现，或者被开发席要求"再导一次给我们看新变化"，这两种场景都是这个
任务存在的直接理由，不是边缘情况）。每次导出都留下一个新文件，单文件有上限 ≠ 目录有上限。

**实测证明**：新增 `DiagLogExportAccumulationTest`，复刻 `exportDiagLog` 的真实文件命名模式
（同目录、时间戳前缀），单文件上限设为 500 字节（保持与默认 1MiB 同比例，加速验证），反复导出
30 次：

```
BUILD SUCCESSFUL —— 30 次导出后目录里留下 30 个文件，总字节数远超单文件上限（500B）的 5 倍，
且没有随导出次数收敛的迹象
```

单文件确实每次都 ≤500 字节（`DiagLog.exportTo` 的边界没有被打破），但目录总大小随导出次数
**线性增长、无上限**——这正是"资源有界"红线在真实调用模式下的破防点：`DiagLog.exportTo`
本身的红测测不出这个缺口，因为它只测"这一次导出"，没人测"重复导出会怎样"。

**严重度**：高。理由：①这不是极端场景，是这个功能被使用的**唯一模式**（导出＝写盘）；
②默认单文件上限 1MiB，如果用户在排查缺陷⑤（tsnet 回前台连不上，需要反复切前后台复现）过程中
导出几十次，`filesDir/diag/` 可以轻松攒到几十 MiB，长期使用可以攒到不可控体量，且没有任何 UI
提示这件事在发生；③这正是"资源有界"验收条款字面要求的场景（`taskbook.yaml` 验收第 2 条：
"缓冲有界——写入远超容量的记录后，内存与磁盘占用不超过设定上限"——磁盘占用这里指的应该是
用户设备上诊断功能的总足迹，不能只理解成"某一次导出的那个文件"）。

**建议**：`exportDiagLog` 导出前调用 `DiagLog.listExports()` 清理超出个数/总字节上限的旧文件
（比如只保留最近 N 份，或者总目录大小上限），或者干脆复用同一个文件名（每次导出覆盖上一份，
`DiagLog.exportTo` 本身用的是 `appendText`，需要先删旧文件或者改成覆盖写）。这是本任务的
前置依赖级缺口，建议参照 round1 四修的流程处理（先修再继续）。

**次要提醒（低危，不阻塞）**：`DiagLog.secrets`（`registerSecret` 登记的凭据表）只增不减，
进程存活期内理论上可以随"反复重新配对/换 TS authkey"缓慢增长；相对导出文件的问题量级小得多
（内存里的字符串列表，不是磁盘文件），不需要本轮处理，记录在案供后续参考。

### 三、白记——逐条对着两个真实缺陷验证，均满足判据

**判据**：用户复现一次、导出一份日志，我们光看它能不能定位根因？

**缺陷⑤**：新增 `DiagLogDefect5WhiteRecordAuditTest`，**驱动真实的 `TsnetWire` + 真实的
`TsnetManager`**（经 `TsnetWire` 内部持有，未 mock）+ 假后端，实际触发：
- 信号①（`state → Up`）：真实起网，由 `TsnetManager.transition` 里真实的
  `DiagLog.record("tsnet", "state $state → $next")` 产出（不是测试手写的字符串）；
- 信号③（`ensureStarted` 被幂等守卫拦下）：同 key 二次调用 `TsnetWire.ensureStarted`，
  真实触发它内部的幂等守卫分支，产出真实的 `"ensureStarted 被幂等守卫拦下…"` 记录；
- 信号②（SOCKS 拨号失败）：纯 JVM 单测无法驱动真实 socket 失败，这一行是手写的，但**格式
  逐字对照 `TsnetSocks.kt` 的真实拼接模式**，测试 KDoc 里明确标注这一点，不冒充信号②本身的
  记录逻辑已被验证（那部分覆盖属于 `TsnetSocksTest` 职责，不重复造轮子）。

断言：导出文本里三个信号关键词同时存在，且按行号（对应真实写入时间顺序）能排出"先到 Up →
后续 ensureStarted 被拦"的因果顺序。**跑绿**——三信号缺一不可的判据，本轮用真实代码路径核实过
两条（①③），第三条（②）的记录格式经代码比对确认一致。**结论：缺陷⑤满足白记判据。**

**缺陷②**：核对了测试席已有的 `DiagLogGridComputabilityTest`——它的第一个测试
（`userRealParams_overflowComputableFromExport`）已经做了 leader 要求的"从导出产物本身算，
不是看测试通过"这件事：只从导出文本里解析 `viewport_width_px`/`cell_width_nominal`/
`cell_width_measured` 三个原始字段，**独立重新推导** `reported_cols`/`canvas_capacity_cols`/
`overflow_px`，再和记录里的 `overflow_px` 字段做一致性断言（不是单方面信任记录值）。

本席用 leader 给的真机参数独立复核了一遍算术（不依赖任何测试代码，手算）：
`vw=1260, nominal=10 → reported=1260/10=126`；`measured=11 → capacity=1260/11=114`；
`reported(126) > capacity(114)` → `overflow = (114+1)*11 - 1260 = 1265-1260 = 5`——与
leader 给出的"应得 5"完全一致；修复后 `nominal=measured=11` → `reported=capacity=114` →
`overflow=0`，与"修复后应得 0"一致。**结论：缺陷②满足白记判据，公式与字段都经得起独立验算。**

### 四、round1 遗留确认：第三方库全局 Hook Log 会绕开脱敏——开发席答复"不 hook 全局 Log"已核实成立

```
grep -rnE "class.*: *Log|Log\.setLogHandler|System\.setOut|System\.setErr|Timber\.plant" app/app/src/main/java/
grep -rn "android.util.Log" app/app/src/main/java/dev/agentmirror/app/diag/
```
两条搜索均零命中：全项目没有任何代码覆盖/拦截 `android.util.Log` 的全局写入路径，`DiagLog`
自身也不包装/转发 `android.util.Log`。`DiagLog.record`/`recordCrash` 是唯一入口，只在
round1 §1 已列出的具体调用点（tsnet/conn/service/session/termview/MainActivity）被显式调用。
**round1 提的边界成立**：第三方库（OkHttp/gomobile）自己往 `android.util.Log` 打的日志，
不会被这套诊断日志系统采集——它们进不了导出管道。

### Round 2 小结

| 主攻线 | 结论 | 证据 |
|---|---|---|
| 静默经济·零线程 | 成立 | 代码级 grep 零命中 |
| 静默经济·热路径开销 | 可忽略（4.15µs/次） | `DiagLogHotPathBenchmarkTest` 实测 |
| 静默经济·设备空闲 CPU | **未测通**（设备包过期 + 共享构建冲突回避） | 如实说明，待协调独占构建窗口补测 |
| 资源有界·单文件 | 成立 | round1 已有的 `DiagLogBoundedTest`（测试席） |
| 资源有界·导出目录 | **破防，高危新发现** | `DiagLogExportAccumulationTest` 实测：30 次导出 = 30 个文件，目录总量无上限 |
| 白记·缺陷⑤ | 满足 | `DiagLogDefect5WhiteRecordAuditTest` 驱动真实代码 |
| 白记·缺陷② | 满足 | 独立复核 + 已有测试的复算逻辑核对一致 |
| round1 遗留·Log hook 边界 | 成立 | grep 零命中 |

---

## 交付物清单

**Round 1（脱敏预审，已随 diag 本体一起修复并回归闸翻转为绿）**：
- `app/app/src/test/java/dev/agentmirror/app/conn/AuthCredentialToStringLeakTest.kt`（2 项，长期回归闸，断言不泄露）
- `app/app/src/test/java/dev/agentmirror/app/tsnet/TsnetManagerCauseChainLeakTest.kt`（1 项，长期回归闸）
- `app/app/src/test/java/dev/agentmirror/app/tsnet/TsnetAuthKeyFormatRedactionRiskTest.kt`（2 项，长期回归闸，走真实 `DiagLog.registerSecret` 全链路）

**Round 2（静默经济 + 资源有界 + 白记复审，新增）**：
- `app/app/src/test/kotlin/dev/agentmirror/app/diag/DiagLogHotPathBenchmarkTest.kt`（热路径开销实测：4.15µs/次）
- `app/app/src/test/kotlin/dev/agentmirror/app/diag/DiagLogExportAccumulationTest.kt`（**导出目录无上限**，本轮最高优先级新发现）
- `app/app/src/test/kotlin/dev/agentmirror/app/diag/DiagLogDefect5WhiteRecordAuditTest.kt`（驱动真实 `TsnetWire`/`TsnetManager` 复现缺陷⑤三信号）
- `docs/diag-log-review.md`（本文件）

全部 8 个新增/翻转测试类已跑绿（`bash -lc "env -u TEAM_AGENT_TASK_ID -u TEAM_AGENT_NAME -u TEAM_AGENT_HOME ./gradlew :app:testDebugUnitTest --rerun-tasks"`，BUILD SUCCESSFUL）。

**本轮未完成项**（如实列出，不冒充已验证）：设备级空闲 CPU 实测——原因见"静默经济"节，待协调独占构建窗口后补测。

模拟器 `emulator-5554` 本轮只做了只读查询（`dumpsys package`/`pidof`），未安装/未重启，未影响其他席位在途工作。
