# 2026-08-11 本轮溢出发现：需用户裁定的产品问题

> 本轮任务是「注释最新 + 补契约」，以下三条**越出了注释范畴**，属产品/架构决策，
> leader 一律**只记不动**，等用户裁定。发现过程见 `git log 879a1eb..9fe4a86`。

---

## P-1 前台服务从未被启动 —— 后台保活与锁屏重连实际未接线

**事实**：`MirrorForegroundService` 在 `AndroidManifest.xml` 里声明了，但全仓库
**没有任何 `startService` / `startForegroundService` / `stopService` 调用点**。
它自己的 KDoc（commit `105a2fe`）就写着"死件家族第六例，接线留待后案"。

**为什么这轮才发现**：三个包各自把它当成活的来描述——
`.service` 称它"由 UI/配对层控制启动"、`.session` 称"fg-service 持有 manager、前台服务决定启动"、
`.workspace` 称"共享连接由 fg-service 持包装监听"。三份注释互相印证，读起来像已经接好了。

**真实接线**：`ConnectionManager` 由 `startPersistentConnection`（`MainActivity.onCreate` 先行调用）
或 `SessionRoute.createSessionViewModel` 创建；时钟泵由**在屏组合的 `LaunchedEffect`** 驱动；
fg-service 的 `pumpRunnable` 从未运行。

**影响**：App 切后台或锁屏后，连接靠什么维持**没有着落**。这直接压在
`BACKLOG-20260810.md` §4 真机验收 8 项里的「进程被杀与锁屏重连」那一项上——
该项目前**不具备通过的条件**，不是测法问题。

**待裁定**：接线（把前台服务真正启动起来并承接连接）／明确放弃（删掉死件与 manifest 声明，
并在需求基记明「不做后台保活」）／维持现状但把真机验收该项标记为已知不支持。

---

## P-2 配对 token 明文存储

**事实**：`.pairing` 的 `manualToken` 注释挂着"存储加密 TODO"，而**全仓库无任何加密实现**，
token 明文落盘。这是本轮新增的形态 ⑩「幽灵 TODO」的实例：注释指向一个不存在的计划。

**边界澄清**：协议 §9 管的是 token **不上屏、不落日志、QR 是唯一出口**，
**没有规定落盘加密**。所以现状不违反已有契约，但与"注释曾承诺过"的预期不符。

**待裁定**：上 Android Keystore / `EncryptedSharedPreferences`（改动面小，属标准做法）／
明确不做并把需求基写清楚（本产品的威胁模型是否包含「攻击者已能读取应用私有目录」）。
注释已按实现改写，不再谎称有在途计划。

---

## P-3 终端渲染丢弃脏区，每帧整窗重绘

**事实**：终端内核 `TerminalEmulator` 确实在算脏区并通过 `DamageListener` 回调出去，
`TermViewPresenter` 也确实接管并换算缓存了——但渲染层**把它扔了**：
`doFrame` 里 `while (p.takeDamage().isNotEmpty()) Unit` 排空即弃，然后 `invalidate()` 整帧全窗口重绘。

**为什么这轮才发现**：同一句假话在两个包散了 **5 处副本**
（`TermSurfaceView` 类 KDoc"脏区精确重绘"、`TermViewPresenter` init"60fps 增量刷新数据源"、
`pendingDamage`、`takeDamage`、`DamageListener`），互相印证成"已经做了增量刷新"的假象。

**影响**：需求 006 要求本地滚动 60fps，`docs/perf-scenarios.md` 的 F1 正要量测终端滚动帧率。
若不知道这件事，性能调查会从"已经有脏区优化了，问题应该不在渲染"这个**错误起点**出发。
现在注释已改实，脏区数据当前只作"画面已变化"的唤醒信号用。

**待裁定**：是否把脏区真正接上做局部重绘（有现成数据，属实打实的性能余量）／
先量测 F1 再决定（若整帧重绘已能稳 60fps 则不必动）。

---

## 附：本轮已记录、不需裁定的观察项

- `internal/bridge` 的 `Pane.Socket` / `Target` / `Timeout` / `WithTimeout` 四个导出符号
  **全仓库零消费**（生产与测试均无调用）——死代码信号，按纪律只提不删。
- `.ui.theme` 的 `brandPrimary` / `brandBackground` 在 `ui-redesign`（`e00e41d`）内联字面量后
  成孤儿导出，零消费；注释已改为"历史 token，无消费点"并指明真实承接方 `TermSurfaceView.DEFAULT_BG`。
- `.tsnet` 的 `TsnetSocksAuthenticator` 全仓库无安装调用点，仅单测引用；
  SOCKS 认证实际走 `TsnetSocks` 自实现握手。
- `StateBadgeStyle` 的 KDoc 链接写 `[unknown]` 而符号是 `[UNKNOWN]`，大小写不符导致 KDoc 断链；
  T3-2 的反引号符号判定抓不到 KDoc 方括号链接语法（判据已知 gap，样本太少暂不加判据）。
- Go 侧 `/* */` 块注释内带 `*` 前缀的 `@contract` 判据看不见（工程约定 Go doc 用 `//`，不阻塞）。
