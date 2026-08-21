# T3 判据报告（自动生成）

> ⚠️ **生成物，勿手改。** 由 `tools/archwiki/build_wiki.py --check --t3-report` 从源码现算生成，重跑无 diff（幂等）。人工改动会被覆盖。

扫描 **25** 个包（Go 10 + Kotlin 15）。

## T3 扫描覆盖（阳性对照：扫描量必须 > 0）

| 项 | 数量 |
|---|---|
| 导出符号索引（Go+Kotlin） | 342 |
| Go CLI flag 索引 | 11 |
| 仓库文件基名索引 | 5441 |
| T3-2 扫描的 Go doc 行 | 2895 |
| T3-2 扫描的 Kotlin KDoc 行 | 6561 |
| **T3-3 扫描到的 `@contract` 符号总数** | 234 |
| **T3-4 `@consumes` 声明总数** | 21 |
| **T3-4 参与比对的 import 边数** | 64 |

## T3-1 符号级 doc 覆盖

导出符号缺紧邻 doc/KDoc，共 **6** 条：

| 包 | 语言 | 文件 | 行 | 符号 | 原因 |
|---|---|---|---|---|---|
| dev.agentmirror.app.conn | kotlin | app/app/src/main/java/dev/agentmirror/app/conn/FrameEnums.kt | 191 | `CreateFailReason` | 顶层 public 声明缺紧邻 KDoc |
| dev.agentmirror.app.termview | kotlin | app/app/src/main/java/dev/agentmirror/app/termview/ViewportGeomStore.kt | 39 | `ViewportGeomStore` | 顶层 public 声明缺紧邻 KDoc |
| dev.agentmirror.app.termview | kotlin | app/app/src/main/java/dev/agentmirror/app/termview/ViewportGeomStore.kt | 44 | `SharedPreferencesViewportGeomStore` | 顶层 public 声明缺紧邻 KDoc |
| dev.agentmirror.app.ui.components | kotlin | app/app/src/main/java/dev/agentmirror/app/ui/components/CommonUi.kt | 292 | `BackChevron` | 顶层 public 声明缺紧邻 KDoc |
| dev.agentmirror.app.workspace | kotlin | app/app/src/main/java/dev/agentmirror/app/workspace/L2Models.kt | 111 | `L2UiState` | 顶层 public 声明缺紧邻 KDoc |
| internal/overlay | go | server/internal/overlay/tmux.go | 65 | `NewTmux` | 顶层导出声明缺紧邻 doc |

## T3-2 引用真实性

扫描**全部注释形态**（KDoc/doc 注释 + 函数体内普通 `//` 注释 + `/* */` 块注释 + 行尾注释，Go 与 Kotlin 两侧对齐，含 Kotlin 侧 `--flag` 判定）。

> **诚实边界**：T3-2 只验证**引用形状可判者**——反引号包裹的大写符号、含 `/` 且带已知扩展名的路径、`--flag`。**不验证语义事实**：自然语言断言（如"设置里有重配按钮"）没有可判形状，静态判据解析不出"某组件里有没有某按钮"，这类行为性断言由用例覆盖（如 PairingUxTest 的重配入口可达性断言），不在此列。注释里指认代码实体时务必写成反引号符号或真实路径，让引用变成判据可验的形状。

无违规：注释引用的符号名/仓库文件路径/CLI flag 均真实存在。

## T3-3 契约标签完备

凡标了 `@contract` 的符号，四标签 `@pre` / `@post` / `@err` / `@inv` 必须齐全；允许显式写 `none`（表示「确无此项」），但不许缺项。缺项即「契约半成品」——它比没有契约更坏，因为读者会以为契约已经定好了。

> **诚实边界**：T3-3 只验标签**齐不齐**，**不验契约内容是否描述正确**——`@post` 写的是不是真的、`@err` 描述的错误语义对不对，属语义事实，静态判据判不了，那一面由用例覆盖。判据不保护「内容撒谎的齐全契约」。

`@contract` 符号缺契约标签，共 **17** 条：

| 包 | 语言 | 文件 | 行 | 缺失标签 | 原因 |
|---|---|---|---|---|---|
| dev.agentmirror.app.conn | kotlin | app/app/src/main/java/dev/agentmirror/app/conn/Frames.kt | 548 | @err | 缺契约标签: @err |
| dev.agentmirror.app.conn | kotlin | app/app/src/main/java/dev/agentmirror/app/conn/Frames.kt | 644 | @inv | 缺契约标签: @inv |
| dev.agentmirror.app.conn | kotlin | app/app/src/main/java/dev/agentmirror/app/conn/Frames.kt | 665 | @inv | 缺契约标签: @inv |
| dev.agentmirror.app.overlay | kotlin | app/app/src/main/java/dev/agentmirror/app/overlay/OverlayEmulator.kt | 19 | @err | 缺契约标签: @err |
| dev.agentmirror.app.ui.components | kotlin | app/app/src/main/java/dev/agentmirror/app/ui/components/CommonUi.kt | 256 | @inv | 缺契约标签: @inv |
| dev.agentmirror.app.ui.components | kotlin | app/app/src/main/java/dev/agentmirror/app/ui/components/ProviderIcon.kt | 24 | @inv | 缺契约标签: @inv |
| dev.agentmirror.app.ui.components | kotlin | app/app/src/main/java/dev/agentmirror/app/ui/components/SessionOverflowMenu.kt | 10 | @inv | 缺契约标签: @inv |
| dev.agentmirror.app.ui.model | kotlin | app/app/src/main/java/dev/agentmirror/app/ui/model/Models.kt | 11 | @inv | 缺契约标签: @inv |
| dev.agentmirror.app.ui.model | kotlin | app/app/src/main/java/dev/agentmirror/app/ui/model/Models.kt | 25 | @inv | 缺契约标签: @inv |
| dev.agentmirror.app.workspace | kotlin | app/app/src/main/java/dev/agentmirror/app/workspace/FavoriteBook.kt | 21 | @err | 缺契约标签: @err |
| dev.agentmirror.app.workspace | kotlin | app/app/src/main/java/dev/agentmirror/app/workspace/FavoriteRecord.kt | 22 | @err | 缺契约标签: @err |
| dev.agentmirror.app.workspace | kotlin | app/app/src/main/java/dev/agentmirror/app/workspace/FavoriteRecord.kt | 34 | @err | 缺契约标签: @err |
| dev.agentmirror.app.workspace | kotlin | app/app/src/main/java/dev/agentmirror/app/workspace/FavoriteRecord.kt | 55 | @err | 缺契约标签: @err |
| dev.agentmirror.app.workspace | kotlin | app/app/src/main/java/dev/agentmirror/app/workspace/FavoriteStore.kt | 47 | @inv | 缺契约标签: @inv |
| dev.agentmirror.app.workspace | kotlin | app/app/src/main/java/dev/agentmirror/app/workspace/WorkspaceScreen.kt | 68 | @err | 缺契约标签: @err |
| internal/api | go | server/internal/api/level2.go | 207 | @inv | 缺契约标签: @inv |
| internal/api | go | server/internal/api/level2.go | 227 | @inv | 缺契约标签: @inv |

## T3-4 跨层声明一致

`@consumes` 声明的包必须真在该包的 import 图里；反之，跨层 import 了却没声明的判架构漂移。import 图由 build_wiki 既有采集结果现算（不重新解析）。

> **诚实边界**：T3-4 只验声明与 import 图**一致不一致**。它保证架构维基能从代码现算真依赖、防止「声明了没 import / import 了没声明」的漂移；但**不验 `@consumes` 写的是不是业务上真该依赖**——那是设计语义，静态判据判不了。

`@consumes` 与 import 图不一致，共 **19** 条：

| 包 | 语言 | 目标包 | 原因 |
|---|---|---|---|
| cmd/agentmirrord | go | `internal/provider` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app | kotlin | `dev.agentmirror.app.tsnet` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app | kotlin | `dev.agentmirror.app.ui.components` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app | kotlin | `dev.agentmirror.app.ui.model` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app | kotlin | `dev.agentmirror.app.ui.screens` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.ui.components | kotlin | `dev.agentmirror.app.tsnet` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.ui.components | kotlin | `dev.agentmirror.app.ui.model` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.ui.components | kotlin | `dev.agentmirror.app.ui.theme` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.ui.model | kotlin | `dev.agentmirror.app.workspace` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.ui.screens | kotlin | `dev.agentmirror.app.tsnet` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.ui.screens | kotlin | `dev.agentmirror.app.ui.components` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.ui.screens | kotlin | `dev.agentmirror.app.ui.model` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.ui.screens | kotlin | `dev.agentmirror.app.ui.theme` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.workspace | kotlin | `dev.agentmirror.app.diag` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.workspace | kotlin | `dev.agentmirror.app.service` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.workspace | kotlin | `dev.agentmirror.app.ui.model` | import 了却未声明 @consumes（架构漂移） |
| dev.agentmirror.app.workspace | kotlin | `dev.agentmirror.app.ui.screens` | import 了却未声明 @consumes（架构漂移） |
| internal/api | go | `internal/overlay` | import 了却未声明 @consumes（架构漂移） |
| internal/api | go | `internal/provider` | import 了却未声明 @consumes（架构漂移） |
