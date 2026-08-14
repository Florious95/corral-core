# 用户场景覆盖审计与补齐设计（scenario-coverage v1）

> **2026-08-14**：本文件是 2026-08-09 的一次性审计快照，①/②/③ 覆盖态与缺陷候选会过期。
> 此后以需求基派生的用例目录为准：[`docs/use-case-design.md`](use-case-design.md)。
> 本文保留作当时账实与 needs-ruling 的证据，不在这里续改覆盖矩阵。

- 任务：scenario-audit（Fable 5 攻坚席 w-scenario-audit，一次性）
- 日期：2026-08-09
- 触发：真机首触即败（缺陷 A/B），用户回炉裁定入库为需求 016——根因是覆盖跟着"可自动化程度"走，
  从无以用户场景为纲的覆盖设计。本文件即该总图纸。
- 性质：**只审计不修码**。所有"缺陷候选"只立案不动手；所有 ④ 设计缺失只列 needs-ruling 不拍板。

## 0. 审计方法与证据源

### 0.1 证据源

| 证据 | 内容 | 状态 |
|---|---|---|
| tools/gate/gate-report.json | 全量门 3 套件 638 用例（server 230 / app 407 / archwiki 1）全绿 | 2026-08-09 01:38 |
| e2e/report.md | 三层 e2e PASS（首帧 p90 54.8ms；老化 40/40） | 2026-08-09 09:51 |
| server/ 21 个测试文件约 123 个 Test 函数逐一盘点 | 本次专项探查 | 审计期间 pairing 修复在并发提交，以当时工作树为准 |
| app/ 23 个测试类约 245 个 @Test 逐一盘点 | 本次专项探查 | 无 @Ignore |
| e2e/run.sh、layer2.sh、harness/layer1\|3_test.go 逐行读 | 本次专项探查 | — |
| requirement-base 001–016、taskbook.yaml、.team/evidence/ | 需求与任务账面 | — |
| 载荷级断言（状态装配断链、空闲轮询、旋转丢态、深链断链、拍照缺失）| 审计席亲手 grep/读码复核 | 非转述 |

### 0.2 四态定义（对每格先问"这里怎么坏"，再问"测过没有"）

- **①已覆盖**：该场景的用户可见行为有具名自动化测试或门禁断言兜底。判定务实口径：决策逻辑与协议行为有具名测试、
  且其上只剩纯映射薄壳时计 ①；但**真机专属风险（相机/IME/ROM/传感）绝不因逻辑层有测而计 ①**——那部分强制进 ③ 清单。
- **②可自动化-未覆盖**：现有基建（JVM 单测 / Robolectric / 隔离 tmux / e2e harness / 模拟器）可测而没测。给出测试形状与优先级。
- **③不可自动化**：只能真机/人工验证。进 §7 真机验收清单；未走到即入"未验证清单"交付用户（016d）。
- **④设计缺失**：产品行为本身未定义。列 needs-ruling（§5），**裁定前相关补齐不施工**（工程红线）。
- 特设标注：**【缺陷候选】**= 本次审计实锤的疑似真实缺陷（有代码坐标）；**【在修】**= 已立案任务施工中；
  **【欠账】**= 任务书承诺但实现缺失（账实落差，非设计缺失）。

### 0.3 总体诊断（先给结论）

638 用例全绿 + e2e PASS 的账面下，覆盖分布与用户旅程呈**系统性倒挂**，三大结构性盲区：

1. **App 零 instrumentation、零 Compose UI 测试**。全部 245 条止步 ViewModel/纯逻辑层；7 个 Composable 文件、
   前台服务、通知投递、深链、相机、Photo Picker、IME、旋转、进程回收——凡与 Android Framework 交界处全部无测。
   由此漏网并被本次审计实锤的缺陷候选就有两个（深链断链、旋转丢导航态）。
2. **e2e 账实落差**。taskbook#e2e 的 goal 写"杀 App 重开恢复→断网重连恢复"，实现里"杀 App"降级成杀主机 daemon、
   "断网"降级成 harness 自关 socket——**App 从未被杀过一次、从未真断过一次网**。层 2 唯一真 UI 路径是手填配对，
   判定弱到"文本出现「工作区」或「连接中」即 PASS"；扫码（016 首触第一项）三层皆零。
3. **用户价值主链"状态→通知→深链"三处断裂且全部无测**：服务端 agentstate 包从未被 cmd/api 装配（生产恒 unknown）→
   即使装配，App 点通知也进不了会话（深链无消费方）。016 首触清单第 7 步"blocked 通知"在两端源头都是断的。

这三条共同印证 016(c)：覆盖跟着可自动化程度走了——纯逻辑层测试密度极高且质量好（黄金向量、红测先行、假时钟竞态），
恰恰是用户手指真正碰到的那一层为零。

### 0.4 实锤缺陷候选汇总（均有代码坐标，建议立即立案）

| # | 缺陷候选 | 代码坐标 | 用户症状 | 去向 |
|---|---|---|---|---|
| D-1 | 状态解析从未装配进 daemon：cmd/ 与 internal/api/ 对 agentstate 零 import，`Options.StateProvider` 恒缺省 `unknownState` | server/internal/api/server.go:84-86 | 真机上所有会话恒灰 unknown；blocked/done 通知**永不触发**；012 聚合规则实际不可达 | fix-state-wiring（P0） |
| D-2 | 通知深链断链：`ACTION_OPEN_SESSION`+`EXTRA_SESSION_REF` 只有构造方，全仓无读取方；MainActivity 不读 intent、无 onNewIntent | app/.../service/NotificationHelper.kt:148-158；app/.../MainActivity.kt | 点 blocked 通知只回首页，找不到是哪个 agent 在叫 | fix-notify-deeplink（P0） |
| D-3 | 旋转/进程回收丢导航态：`activeSession`/`showPairing` 用 `remember` 非 `rememberSaveable`，manifest 无 configChanges | app/.../AgentMirrorApp.kt:49-52 | 旋转或回收重建即被踢回列表页 | fix-rotation-navstate（P0） |
| D-4 | 空闲轮询违反工程常识红线①静默经济：`listingLoop` 2s ticker 无条件扫描，零客户端零订阅时照样派生 tmux 子进程 | server/internal/api/server.go:189-201 | 常驻 daemon 空闲期持续吃 CPU/派子进程 | fix-idle-econ（P0） |
| D-5 | 拍照直传缺失：taskbook#session-ui goal 明写"加号（相册/**拍照**→上传→路径注入）"，实现仅相册 Picker，相机只用于扫码 | app/.../session/SessionScreen.kt（全仓无 TakePicture/ACTION_IMAGE_CAPTURE） | 加号里没有拍照 | 【欠账】→ R-8 裁定当期与否 |

另有两笔账面卫生问题一并提请 leader：**scenario-audit 本身尚未回填 taskbook.yaml**；taskbook#e2e 的 goal
与实现落差（上文盲区 2）应按"只增不改"规矩在任务书行内注释或 REVISIONS 登记。

---

## 1. 主矩阵 A：首触与配对

| # | 场景（怎么坏） | 状态 | 证据 / 缺口 | 补齐去向 |
|---|---|---|---|---|
| A1 | 扫码→自动配对→进列表（扫完静默、无进度、无报错） | 【在修】+③ | 真机实证缺陷 B。fix-pairing-scan-flow 当前**红测态**：PairingViewModelTest 新增 `scanAutoFillsManualFormForEditRetry`、`scanDialFailureSurfacesImmediatelyNotSilent` 已落，PairingViewModel/Screen 产品码未动 | 在修任务收口；真机清单 T1 复验 |
| A2 | 相机权限拒绝→手填降级 UI（权限弹窗拒绝后卡死/无引导） | ③ | PairingScreen 的 CameraX 绑定、ZXing 解码、1500ms 节流、权限降级全部零测（零 instrumentation 所致） | 真机清单 T1a |
| A3 | 坏 QR：非本产品码/JSON 畸形/缺 url/缺 token | ① | QrPayloadParserTest 6 条 + PairingViewModelTest `scanRejects*` 4 条；UI 呈现补强并入缺陷 B 整改 | — |
| A4 | QR 版本不匹配（新 App 扫旧服务端、反之） | ① | QrPayloadParserTest 版本不符拒绝；protocol 侧 TestUnmarshalRedPaths 版本 2 拒绝 | 提示文案人话程度→真机 T1b |
| A5 | 手填合法地址配对 | ① | PairingViewModelTest `manualSubmitValidPairsAndPersists`；e2e 层 2 唯一真 UI 路径（但判定弱：见 §0.3-2） | e2e-layer2-harden 强化判定 |
| A6 | 手填非法 URL / 空白 token | ① | `manualRejectsInvalidUrl` / `manualRejectsBlankToken` | — |
| A7 | 错 token 被服务端拒绝（提示是否人话、是否泄 token） | ① | 三层齐：`authFailureSurfacesExplicitRejection` + `errorMessageNeverContainsTokenValue`（app）；TestAuthBadToken 必带 reason 并关连接（server）；层 1 `auth_reject_bad_token` | — |
| A8 | 服务端未启/地址不可达/配对超时 | ① | `pairTimeoutSurfacesExplicitFailure`、ConnConnectionTest `testDialFailureIsReconnectable`、OkHttpWebSocketTransportTest `dialFailureSurfacesOnFailure` | 真机 T1c 复验体感时长 |
| A9 | QR 载了不可达地址（代理 fake-IP TUN 198.18.x / link-local / 多网卡选错） | 【在修】 | 真机实证缺陷 A。probe_test 修后已充分：TestClassifyIP 含 198.18.0.0/15 三边界、TestDetectAddressesFakeIfacesExcludesVirtualTunnels 注入 6 网卡假表、-host 覆盖、全候选清单。**残余缺口**：知识基底要求的按接口名（utun*/awdl*/bridge*）排除未见实现；`defaultRouteSource` 排废回退分支无直接单测 | 在修任务收口时核残余两点；真机 T0 在开代理主机上复验 |
| A10 | 换 WiFi/主机地址变更后重配；多主机 | ④ | AgentMirrorApp 注释写"配对页可从设置/重配入口重进"，但入口实现与"多主机档案 vs 单档覆盖"行为均未定义 | R-3 needs-ruling |
| A11 | tsnet authkey 入网（tailnet 场景） | ①逻辑+③ | TsnetAuthKeys/Manager/Dial 共 16 条（含 stop 竞态）；单测红线不真连控制面 ⇒ 实链路只能真机 | 真机 T10 |
| A12 | token 泄漏面（日志/错误串/屏显） | ① | server TestErrorsNeverContainToken（带正向控制）+ token 不落日志断言 + app 文案断言；缺陷 B 附带的"裸 JSON 全文上屏"在修范围内 | — |

## 2. 主矩阵 B：日常会话交互

| # | 场景（怎么坏） | 状态 | 证据 / 缺口 | 补齐去向 |
|---|---|---|---|---|
| B1 | 点开会话秒开（首帧 <200ms，006） | ① | 层 1 `subscribe_first_frame` 5 连测 <200ms（报告 p90 54.8ms）；App 端无首帧计时断言 | 真机 T3 体感复验 |
| B2 | 输入→发送→回显，回执四态（成功/失败/超时/断连） | ① | SessionViewModelTest 4 条 ack 态 + server TestInputAlwaysAcks / TestInputUnsubscribedNotAckedWithReason + 层 1 `input_echo_ack` | — |
| B3 | 中文 IME：拼音组合态/候选期误发/组合区间截断 | ③+②部分 | 零测。VM 只持 TextFieldValue；组合区间行为可 JVM 单测，键盘实况只能真机 | test-ime-input（待 R-2）；真机 T4 |
| B4 | 多行粘贴（含 \n 是否误发/拆多次 send-keys/bracketed paste） | ④ | 注入语义未定义：引擎无 ?2004 处理，VM 无拆分逻辑，仅 maxLines=4 的展示语义。server 侧 TestInjectMultiline 只证明 bridge 层多行可注入 | R-2 needs-ruling（裁定后 test-ime-input） |
| B5 | 特殊键：Esc（打断 agent）/Ctrl-C/方向键/Tab | ④ | 完全未实现：SessionScreen 无按键条无 onKeyEvent。Claude Code 场景 Esc 打断与方向键选菜单是日常硬需求，016 首触未列但首日必碰 | R-1 needs-ruling |
| B6 | 相册发图→上传→路径插入→注入 CLI | ①编排+② | VM `attachmentPathInsertedAtCursor`/`uploadFailureSurfacesErrorAndKeepsDraft` + 层 1 `upload_image_path_inject`（协议面闭环）；**HttpUrlConnectionUploader 整类零测**（multipart 组包/非 200/JSON 不可解析/path 空/base 未配，每个错误分支都有独立文案却无一验证）；Photo Picker 真机专属 | test-app-android-seams；真机 T5 |
| B7 | 拍照发图 | 【欠账】 | taskbook 承诺、实现缺失（D-5） | R-8 |
| B8 | 上传超限/失败的用户提示 | ①浅 | server 413 有测（TestUploadTooLarge）；App 对 413/500 的文案映射零测 | test-app-android-seams |
| B9 | 滚动历史/锁定视口/回到底部/触顶分页 | ① | 三层贯通：TermViewPresenterTest 10 条视口 + SessionViewModelTest 5 条分页协议 + 引擎 prependHistory 系 + server TestScrollbackConvergedRange/字节头 | 真机 T7 流畅度体感 |
| B10 | 捏合字号→行列重算→CLI 重排（005） | ① | pinch 3 条（Presenter 2 + VM 直达 manager/emulator 1）；ScaleGestureDetector 真手势零测 | 真机 T6 |
| B11 | 旋转/深色切换重建 | 【缺陷候选 D-3】 | remember 丢 activeSession；②可测（Robolectric 重建） | fix-rotation-navstate |
| B12 | 暗色模式渲染 | ③ | Theme.kt 接了 isSystemInDarkTheme，零测 | 真机 T8（各屏目检） |
| B13 | resize 争抢（双端操作，window-size latest 最后操作者赢） | ② | 零测：未订阅 resize no-op 分支、未知 ref 报错分支、双客户端争抢均无 | test-multi-client |

## 3. 主矩阵 C：真实终端内容

| # | 场景（怎么坏） | 状态 | 证据 / 缺口 | 补齐去向 |
|---|---|---|---|---|
| C1 | 256 色/真彩/SGR 属性 | ① | SgrParsingTest 10 条（含 `38:2:` 冒号子参数这个易漏点）；server 侧 `-e` 保色有 TestSnapshotPreservesColorEscapes | — |
| C2 | 宽字符 CJK/emoji 基础（占格/半格覆盖/行尾换行/组合并入） | ① | CharWidthTest 5 + WideCharTest 9 | — |
| C3 | 多码点 grapheme 整簇：ZWJ 家庭/旗帜/肤色修饰（簇被拆/占格错/退格拆簇） | ② | 仅测到"ZWJ、VS16 宽度=0"，无 `👨‍👩‍👧`/`🇨🇳` 整簇落格与整簇删除用例；纯 JVM 可测 | test-term-content |
| C4 | alt-screen 全屏 TUI（vim/htop；退出恢复主屏；历史降级） | ①引擎+② | AltScreenTest 5 条（1049/47/屏蔽历史/恢复光标）；端到端从未跑过真 TUI（层 1 真 claude 只跑 `-p` 后 exec bash） | e2e-real-tui；真机 T9 |
| C5 | 滚动区 DECSTBM（区域内滚动/非法参数/与 IL/DL、DECOM 交互） | ② | 仅 1 条正面用例（区域内 LF 不进 scrollback）；CSI S/T 区域行为、参数反序、`ESC[r` 复位均无 | test-term-content |
| C6 | 进度条：`\r` 原地重绘 / OSC 9;4（Claude Code 常态输出） | ② | 零覆盖。AnsiParser 的 OSC 是空实现，唯一相关用例断言"OSC 被忽略"；`\r` 重绘无一条端到端用例 | test-term-content |
| C7 | 大输出洪峰（build 日志/yes；引擎 OOM/掉帧；server 背压） | ② | 全仓最大用例 256 字节。引擎无洪峰/内存用例；server 无 burst 测试，send 队列 cap 256 的**溢出丢 delta 策略零测**（丢了会话画面会静默缺帧） | test-term-content（引擎）+ test-conn-lifecycle（server 背压）；真机 T11 |
| C8 | 真实 Claude Code 交互 TUI 全要素（状态条/spinner/框线/代码高亮） | ②+③ | 层 1 `real_claude_cli` 只验证打印与回显；交互式全要素端到端零覆盖；"画面一致"最终只能真机对照 | e2e-real-tui；真机 T3 |
| C9 | 快照↔delta 接缝完整性（切换瞬间丢字节/重复字节） | ② | 现有断言只到"marker 出现在流里"，无无重无漏断言；SnapshotReplayTest 只测引擎侧重放 | e2e-real-tui 加接缝校验和场景 |

## 4. 主矩阵 D：舰队与状态

| # | 场景（怎么坏） | 状态 | 证据 / 缺口 | 补齐去向 |
|---|---|---|---|---|
| D1 | 私有 socket 枚举（team-agent 舰队恰好不可见即产品命题失败，001） | ① | discovery 8+6 条：双 socket 同 CWD 聚合、socket 路径带回、僵尸 socket/杂散文件/缺目录容错、/tmp 与 /private/tmp 去重 | — |
| D2 | 几十目录几百会话规模（列表卡顿/扫描超时/delta 风暴） | ② | 最大规模 2 socket×1 pane；零 Benchmark；无扫描耗时上限断言；无"扫描期间 session 增删"竞态测试 | test-fleet-scale |
| D3 | agent 识别（claude/codex/wrapper 进程树下钻/判不出降 unknown） | ① | agentstate 24 条（含"指令文本含 codex 字样不得误匹配"、超时必 unknown、树优先于 title） | — |
| D4 | 状态判定装配进 listing（识别了却没接上） | 【缺陷候选 D-1】 | agentstate 从未被 cmd/api import；生产恒 unknown。state-parser 与 fg-service 两任务各自绿了，**接缝无主、无集成测试** | fix-state-wiring |
| D5 | 012 聚合优先级（blocked>done>working>idle；unknown 剔除；全 unknown 才 unknown） | ② | `statePriority`/`aggregateState`/`wsAggregate` **零直接单测**；仅 TestListReturnsFullListing 擦到"全 unknown"一例。协议已固化的裁定规则处于无测状态 | test-aggregate-status |
| D6 | list_delta 增/删/改/跨 cwd 迁移/噪声抑制 | App① server② | App WorkspaceViewModelTest 14 条充分（含搬迁、空工作区回收）；server `modelSnapshot.diff()` 约 90 行只被"加一条"1 例擦到，removed/changed/迁移/抖动抑制全无 | test-aggregate-status |
| D7 | blocked/done 通知决策（沿触发/一次性/断连补发/消失清除） | ① | ServiceStateWatcherTest 10 条，质量好（含基线抑制与断连累积补发） | —（但受 D-1/D-2 断链拖累，端到端为 0） |
| D8 | 点通知直达对应会话 | 【缺陷候选 D-2】 | 深链无消费方；②可测（Robolectric intent 路由） | fix-notify-deeplink |
| D9 | 状态徽章渲染（颜色/文案/每态可辨） | ② | StateBadge/StateBadgeStyle 纯 Composable 零测；VM 层仅连接态映射 2 条 | test-app-android-seams（Compose rule） |
| D10 | 多订阅/并发切换（tmux 每 pane 单 pipe，新订阅替换旧的——双客户端下旧连接静默失流？） | ② | stream.go 注释明写替换语义，**多客户端下该语义零测**，是高疑缺陷面；全仓无第二条 WS 连接的测试 | test-multi-client |

## 5. 主矩阵 E：网络角落

| # | 场景（怎么坏） | 状态 | 证据 / 缺口 | 补齐去向 |
|---|---|---|---|---|
| E1 | 断连重连（退避/封顶/重放订阅/断连期状态补发） | ①逻辑 | 本仓覆盖最好的主题：ConnManagerTest+ConnConnectionTest+ReconnectPolicy 共 30+ 条，四层齐 | 真断网见 E2/E3 |
| E2 | WiFi↔蜂窝切换 | ③+②部分 | `testNetworkAvailableSkipsWait` 只测入参级信号；ConnectivityManager.NetworkCallback 注册/注销零测（Robolectric 可测注册逻辑，切换实况只能真机） | test-app-android-seams；真机 T12 |
| E3 | 弱网/丢包/高延迟 | ③ | 无任何弱网注入；e2e"断连"是进程内关 socket，非网络层 | 真机 T13（蜂窝弱信号区实测） |
| E4 | daemon 重启自愈（残留 pipe 死锁/重启后 ref 稳定/seq 重置） | ①+②残余 | restart_pipe 3 红测扎实 + 层 3 20 轮重启；残余：ref 跨重启稳定性是**假定**而非断言，listing seq 重置语义无测，pane 消失/tmux server 死亡的重连行为无测 | test-conn-lifecycle |
| E5 | 服务端优雅关闭（不残留 pipe-pane cat） | ① | TestCloseDrainsSubscriptions（带 `#{pane_pipe}` 正向控制） | — |
| E6 | 客户端异常断连后服务端订阅回收 | ② | `wsConn.teardown` 零直接测试；泄漏会累积 pipe-pane | test-conn-lifecycle |
| E7 | 多设备同时连接（镜像互不干扰/一方断开不影响另一方） | ② | 全仓 19 处 harness 全是 1 server+1 client；连 D10 替换语义一起属最大结构性空洞 | test-multi-client |
| E8 | tailnet 实链路（tsnet 双栈/退出 tailnet 降级 LAN） | ③ | tsnetd 10 条单测（降级路径充分）但红线不真连控制面 | 真机 T10 |
| E9 | 主机多网卡/代理 TUN 环境（缺陷 A 现场） | 【在修】 | 同 A9 | 真机 T0 |

## 6. 主矩阵 F：生命周期（App 与 daemon）

| # | 场景（怎么坏） | 状态 | 证据 / 缺口 | 补齐去向 |
|---|---|---|---|---|
| F1 | 杀 App→重开→1s 恢复原画面（004 核心承诺） | ②+③ | **三层 e2e 从未杀过 App**（账实落差）；模拟器可自动化（am force-stop→重启→断言恢复），彻底验证需真机 | e2e-layer2-harden；真机 T14 |
| F2 | 锁屏久置/Doze/App Standby 后重连 | ③ | 零覆盖；前台服务是否豁免 Doze 未验证 | 真机 T15 |
| F3 | 系统回收进程后重建（savedInstanceState） | 【缺陷候选 D-3】+② | 同 B11；`am kill` + don't-keep-activities 可模拟器自动化 | fix-rotation-navstate；e2e-layer2-harden |
| F4 | 手机重启后冷启动直达 | ③ | PairingConfigStore（SharedPreferences 持久化）**本体零测**（VM 测试用内存假实现） | test-app-android-seams；真机 T16 |
| F5 | 厂商 ROM 杀后台/电池优化白名单（MIUI/EMUI/ColorOS…） | ③ | 不可自动化，多厂商矩阵 | 真机 T17（每机型） |
| F6 | 前台服务生命周期（START_STICKY 重建/通知常驻/Android 14 dataSync 时限） | ② | MirrorForegroundService 零测；仅协作者 StateWatcher 有测 | test-app-android-seams |
| F7 | daemon 长时驻留（小时级：内存/FD/goroutine 泄漏） | ② | 层 3 20 轮跑完即止，非时长老化；零资源曲线断言 | aging-longrun |

## 7. 主矩阵 G：异常与安全

| # | 场景（怎么坏） | 状态 | 证据 / 缺口 | 补齐去向 |
|---|---|---|---|---|
| G1 | 上传文件名路径穿越（`../../etc/passwd`、控制字符） | ② | `sanitizeBaseName` **零测**——安全相关最高优 | test-upload-hardening |
| G2 | 磁盘满/写失败（500 + 半截文件清理） | ② | 零测；写失败分支存在但无验证 | test-upload-hardening |
| G3 | input 帧超长（InputFailTooLarge，1MiB） | ② | ws_handler.go:133 分支零 API 层测试（config 只测默认值） | test-upload-hardening |
| G4 | 认证前发帧/未知帧型/坏 ref | ① | TestUnauthorizedBeforeAuth、TestUnknownFrameType、TestSubscribeUnknownRef、层 1 `subscribe_bogus_ref` | — |
| G5 | token 轮换/吊销（token 泄露后怎么办） | ④ | 机制完全未定义（当前 token 永久有效、0600 落盘复用） | R-4 needs-ruling |
| G6 | discovery 扫描失败时 API 行为 | ② | harness 的 `scriptedDiscoverer.err` 字段从未被赋值——建好从未用过的死接缝 | test-aggregate-status 顺带 |
| G7 | 并发正确性（-race） | ② | 门禁与任务书验收全是裸 go test；connSeq/sendCh/双客户端并发无 race 验证 | test-multi-client（-race 进 gate） |
| G8 | 明文策略与权限清单回归（INTERNET/明文放行防回退） | ① | ManifestNetworkPolicyTest 4 条断言 merged manifest（debug+release）——fix-app-network-manifest 的回归锚，形态可作范本 | — |

## 8. 主矩阵 H：无障碍与国际化（裁定后置，先列全）

| # | 场景 | 状态 | 证据 / 缺口 | 补齐去向 |
|---|---|---|---|---|
| H1 | TalkBack/语义树（徽章状态可读出） | ④ | 无障碍基线未定义；StateBadge 无 contentDescription 断言 | R-7 |
| H2 | 系统字体缩放/显示大小 | ③后置 | 零覆盖 | 真机后置项 |
| H3 | RTL | 后置 | manifest 声明 supportsRtl 但零测 | 随 R-6 |
| H4 | 多语言（当前 UI 与通知文案硬编码中文；开源产品英文受众） | ④ | 国际化策略未定义 | R-6 |

## 9. 环境轴矩阵

| 环境变量 | 自动化现状 | 缺口与去向 |
|---|---|---|
| 真机（多厂商 ROM） | **零自动化**（当前 e2e 不含真机） | 全量依赖 §11 真机清单；未走到的项按 016(d) 入未验证清单 |
| 模拟器 | 仅层 2 单实例（不指定 API level，谁在跑用谁）；判定弱 | e2e-layer2-harden：固定 API level 矩阵至少 26（minSdk）与 35（target）各一 |
| Android 版本跨度 26→35 | 单测全为 JVM，无版本分层；**minSdk 26 从未被任何测试运行过** | 层 2 双 level（上行）；PickVisualMedia/POST_NOTIFICATIONS/前台服务时限均是版本敏感面 |
| 暗色 | 零 | 真机 T8 目检；可选 Compose 截图测试（后置） |
| 横屏/旋转 | 零 | fix-rotation-navstate 回归测试 + 真机 T6 |
| 小屏/分屏 | 零 | 真机后置项；层 2 可加 resizable 模拟器变体（后置） |
| 门禁基建 | gate 三面并行+棘轮，但 server 面裸 go test：无 -race、无覆盖率、min_cases=1 | test-multi-client 附带 -race 入门禁 |

## 10. 工程常识红线审计（CLAUDE.md 2026-08-09 增设五条）

| 红线 | 现状判定 | 证据 | 去向 |
|---|---|---|---|
| 1 静默经济 | **违反候选（实锤）** | listingLoop 2s ticker 无条件 scan（server.go:189）：零客户端零订阅时持续派生 tmux 子进程 | fix-idle-econ（P0） |
| 2 进程卫生 | 部分达标 | 优雅关闭 drain（close_drain）与崩溃残留 pipe 自愈（restart_pipe）有测；**单实例守卫无实现无测**；测试脚本孤儿进程无断言 | fix-idle-econ 顺带单实例；e2e 收尾断言零残留 |
| 3 资源有界 | 未自证 | 上传目录无上限/轮转说明；daemon 日志无轮转说明；引擎 scrollback 环形有界（已测）是唯一亮点 | aging-longrun 加资源曲线；文档补轮转说明 |
| 4 可达性常识 | 在修收口 | 缺陷 A 整改（198.18/15、link-local 排除、-host、全候选）修后单测充分；接口名过滤两残余点见 A9 | fix-qr-host-detect 收口核验 |
| 5 失败可见 | 部分达标 | input 四态/上传失败/配对失败均有显式文案与测试；缺陷 B（扫码静默）是本条的实证违反，在修 | fix-pairing-scan-flow 收口 |

---

## 11. ④ 设计缺失清单（needs-ruling，裁定前不施工）

| # | 议题 | 为什么现在要裁 | 待裁定点 |
|---|---|---|---|
| R-1 | 会话页特殊键（Esc/Ctrl-C/方向键/Tab） | Claude Code 日常操作硬依赖：Esc 打断、方向键选菜单、Ctrl-C 杀进程。当前完全没有，用户首日必撞 | 要不要键条？最小键集？长按/组合键形态？ |
| R-2 | 多行粘贴注入语义 | 粘贴含 \n 文本是高频操作；当前行为未定义（可能被 CLI 当多次回车执行——有误执行风险） | bracketed paste（?2004）/逐行 send-keys/整段注入三选一；与 003"一次性注入"的关系 |
| R-3 | 重配对/换主机入口与多主机 | 换 WiFi、主机地址变更是常态；缺陷 A 场景的用户自救通路 | 设置页入口？多主机档案还是单档覆盖？切主机时连接层如何处置 |
| R-4 | token 轮换/吊销 | token 永久有效；手机丢失/token 泄露无任何补救 | 服务端重新生成即吊销全部？还是版本化多 token？ |
| R-5 | 通知细粒度（锁屏可见性/每工作区静音） | 舰队用户几百会话，通知风暴会逼用户关掉全部通知，摧毁 003 标准四 | 当期只做全局开关还是每工作区？可后置但需明示 |
| R-6 | 国际化策略 | UI/通知/错误文案硬编码中文，与 Apache 2.0 开源全球受众矛盾 | 当期锁中文（明示）还是抽 strings.xml 双语 |
| R-7 | 无障碍基线 | 徽章纯色无语义标注 | 当期最低标准（contentDescription 全覆盖？）还是显式后置 |
| R-8 | 拍照直传欠账处置 | taskbook#session-ui 承诺"相册/拍照"，实现仅相册（D-5） | 补实现入当期，还是账面修正为后置并在任务书行内注释追认 |

## 12. 自动化补齐任务清单（按优先级；id 沿用任务书命名习惯，五栏形状供 leader 定稿派发）

### P0 —— 016 首触链路断点与红线违反（最先最重）

| # | 建议 id | goal 形状 | acceptance 形状 | 建议 write_scope |
|---|---|---|---|---|
| 1 | fix-state-wiring | 修复状态解析未装配实证缺陷（D-1）：main.go 组装 agentstate.Registry → api.Options.StateProvider，打通"识别→listing→聚合→通知"主链 | 新增 api 集成测试：隔离 tmux 起假 claude 进程树，断言 listing 中该 pane state≠unknown 且聚合态正确；`cd server && go test ./...` 绿；层 1 增状态断言场景 | server/cmd/、server/internal/api/、e2e/harness/ |
| 2 | fix-notify-deeplink | 修复通知深链断链实证缺陷（D-2）：MainActivity/AgentMirrorApp 消费 ACTION_OPEN_SESSION+EXTRA_SESSION_REF 路由直达会话页 | Robolectric 测试：构造深链 intent 启动/onNewIntent，断言 activeSession 置为对应 ref；红测先行（修前红） | app/app/src/main、src/test |
| 3 | fix-rotation-navstate | 修复旋转/回收丢导航态实证缺陷（D-3）：remember→rememberSaveable（activeSession/showPairing） | Robolectric activity 重建测试：旋转与回收重建后仍在会话页；修前红 | app/app/src/main、src/test |
| 4 | fix-idle-econ | 修复静默经济红线违反（D-4）：零客户端零订阅时挂起 listing 轮询（有连接/首连时恢复），顺带单实例守卫 | 单测：假时钟下零连接 N tick 无 discoverer 调用，接入首个连接后恢复；单实例二启即退且报错可见 | server/internal/api/、server/cmd/ |
| 5 | test-aggregate-status | 补齐 012 聚合规则与 server diff 的零覆盖（D-5/D-6/G-6）：statePriority 全序、unknown 剔除、全 unknown 聚合、diff 的 removed/changed/跨 cwd 迁移/噪声抑制、discovery 失败时 API 行为 | `go test ./internal/api/` 新增 ≥12 具名用例覆盖上述每条；用例数棘轮上行 | server/internal/api/（仅 _test.go） |
| 6 | test-app-android-seams | 引入 Robolectric 基建，覆盖 Android 接缝零测四类：NotificationHelper（渠道/构建/权限缺失降级）、HttpUrlConnectionUploader（multipart/非200/坏JSON/空path）、SharedPreferencesPairingConfigStore（真持久化round-trip）、StateBadge 语义（Compose rule） | `./gradlew :app:testDebugUnitTest` 绿且新增 ≥20 用例；Robolectric 依赖入 build 且 gate app 面照常并行 | app/app/build.gradle.kts、src/test |
| 7 | e2e-layer2-harden | 层 2 判定与旅程强化，消除账实落差：语义定位替换硬编码坐标；断言列表含真实会话→点开会话→断言快照文本→输入回显→am force-stop→重开断言恢复原画面；API 26 与 35 双模拟器变体 | `bash e2e/run.sh` 层 2 新旅程全绿；旅程步骤与断言写入 layer2.json 逐项可读 | e2e/ |

### P1 —— 高危面（安全/并发/内容保真）

| # | 建议 id | goal 形状 | acceptance 形状 | 建议 write_scope |
|---|---|---|---|---|
| 8 | test-upload-hardening | 补上传与输入安全零测（G-1/2/3）：sanitizeBaseName 穿越红测、磁盘写失败 500+半截清理、空文件/非 multipart/405、命名冲突重试、input 超长 InputFailTooLarge API 层 | `go test ./internal/api/` 新增 ≥10 具名用例含 `../` 穿越向量；修出的实缺陷另立 fix- 案 | server/internal/api/（测试；发现缺陷则另案） |
| 9 | test-multi-client | 补并发多客户端结构性空洞（D-10/E-7/B-13/G-7）：双 WS 客户端同 pane 订阅的 pipe 替换语义、并发 input/resize 争抢、一方断开不影响另一方、wsConn.teardown 回收；`-race` 进 gate server 面 | 新增多客户端集成测试 ≥8 条全绿且 `go test -race ./...` 绿；gate 配置更新 | server/internal/api/、tools/gate/ |
| 10 | test-term-content | 补引擎真实内容缺口（C-3/5/6/7）：`\r` 进度条重绘、OSC 9;4 容忍、ZWJ/旗帜/肤色整簇落格与整簇删除、DECSTBM 补全（S/T/非法参数/DECOM）、1MB 洪峰不 OOM 且脏区摊还有界 | `./gradlew :terminal:test` 新增 ≥15 用例；洪峰用例断言内存上界与耗时上界 | app/terminal/src/test |
| 11 | e2e-real-tui | 层 1 增真实终端内容场景：交互式 claude（非 -p）、vim/htop alt-screen 进出、seq 洪峰、快照↔delta 接缝校验和、（依赖 fix-state-wiring）blocked/working 状态断言 | `bash e2e/run.sh` 层 1 新场景绿；report.md 新增各场景指标 | e2e/harness/ |
| 12 | test-conn-lifecycle | 补 server 连接生命周期残余（E-4/6、C-7 背压）：ref 跨 daemon 重启稳定性断言、seq 重置语义、pane 消失/tmux server 死亡时订阅端行为、send 队列溢出丢帧策略显式化 | `go test ./internal/api/ ./internal/bridge/` 新增 ≥8 具名用例 | server/internal/ |
| 13 | test-fleet-scale | 补规模零覆盖（D-2）：300 会话×多 socket 枚举 Benchmark 与耗时上限、扫描期增删竞态、listing/delta 构造耗时 | `go test -bench` 基准入库 + 上限断言用例；竞态用例 -race 绿 | server/internal/discovery/、api/（测试） |

### P2 —— 纵深（部分依赖 §11 裁定）

| # | 建议 id | goal 形状 | acceptance 形状 | 依赖 |
|---|---|---|---|---|
| 14 | test-ime-input | 按 R-1/R-2 裁定实现后补：组合区间不误发、多行粘贴注入语义、特殊键映射 | VM/引擎层新增用例；真机 IME 项仍留 §11 清单 | R-1、R-2 裁定 |
| 15 | e2e-scan-emulator | 模拟器虚拟相机注入 QR 图，自动化扫码全链路（可行性先行侦察，不可行则明确留真机） | 层 2 扫码变体绿，或产出不可行结论文档 | fix-pairing-scan-flow 收口 |
| 16 | aging-longrun | 小时级驻留老化：RSS/FD/goroutine/上传目录曲线有界断言（红线 3 自证） | 老化脚本 + 资源报告入 e2e/artifacts；越界即红 | — |

## 13. 真机验收清单 v1（016 首触清单的执行细化）

> 执行规范：每步记 pass / fail / blocked，fail 必附截图与复现步骤并立 fix- 案；**未走到的步骤一律进「未验证清单」
> 交付用户（016d），禁止计入已验收**。标注（依赖 D-x）的步骤在对应缺陷修复合入前预期 fail，先修后验。

| 步 | 操作 | 预期 | 关联 |
|---|---|---|---|
| T0 | 前置：主机**开着代理（fake-IP TUN 活跃）**+多网卡环境冷启服务端 | 终端引导打出 QR + 全部候选 ws URL 清单；QR 载 LAN 真地址（非 198.18.x/169.254.x） | 缺陷 A 回归 |
| T1 | 首装 App→扫码 | 相机权限弹窗→授权→取景识别→"已识别·正在连接 <地址>"→3s 级进列表；token 明文不上屏 | 缺陷 B 回归 |
| T1a | 变体：拒绝相机权限 | 明确引导降级到手填，不卡死 | A2 |
| T1b | 变体：扫无关二维码/过期 QR | 显式报错+可重试；识别出的 url/token 回填手填表单 | A3/A4 |
| T1c | 变体：服务端已停再扫码 | 有限时间内显式失败（含地址），非无限"连接中" | A8 |
| T2 | 工作区列表 | team-agent 私有 socket 舰队全部可见；cwd 分组正确；徽章非全灰（依赖 D-1） | D1/D4 |
| T3 | 打开一个真实 Claude Code 会话 | 1s 内首屏；画面与电脑端 tmux 逐要素一致（颜色/框线/emoji/中文）；比例为手机布局（005） | B1/C8 |
| T4 | 输入回显：中文（带联想选字）+英文+emoji，多次发送 | 零延迟本地编辑；发送即回执提示；CLI 全部准确回显 | B2/B3 |
| T5 | 相册发图 | 选图→上传提示→路径插入输入框→发送→Claude Code 读到图 | B6 |
| T6 | 旋转横屏再转回；捏合缩放字号 | 不被踢出会话页（依赖 D-3）；捏合后 CLI 按新行列重排而非缩放 | B10/B11 |
| T7 | 滚动历史到顶再回底 | 惯性流畅；触顶分页加载；"回到底部"按钮工作 | B9 |
| T8 | 暗色模式切换走完 T1-T7 各屏 | 全部可读、无反色残留 | B12 |
| T9 | 会话里开 vim 再退出；htop 观察 10s | alt-screen 正确进出；退出后主屏与历史恢复 | C4 |
| T10 | tailnet 场景：手机切蜂窝，经 tsnet 连接 | 填 authkey 入网；蜂窝下全功能可用 | A11/E8 |
| T11 | 触发大输出（构建日志/`yes | head -100000`） | 不闪退不冻结；滚动仍可用；洪峰后画面正确 | C7 |
| T12 | 会话页中 WiFi↔蜂窝切换 | 有限时间内自动重连并恢复画面；期间断连态可见 | E2 |
| T13 | 弱信号区/电梯实测 5 分钟 | 断连提示与恢复行为符合预期，无假死 | E3 |
| T14 | 上滑杀 App→重开 | 1s 内恢复到被杀前画面与导航位置（004） | F1 |
| T15 | 锁屏 30 分钟（不充电）→解锁 | 自动重连；期间 blocked 通知仍能送达锁屏 | F2 |
| T16 | 手机重启→打开 App | 免重配直达列表 | F4 |
| T17 | blocked 通知全链：让 agent 提问→锁屏收通知→点通知 | 通知及时（分钟内）；点击**直达该会话**（依赖 D-1+D-2） | D7/D8 |
| T18 | daemon 重启：主机 kill 再起 | App 自动恢复，delta 流继续（fix-bridge-restart-pipe 真机面回归） | E4 |
| T19 | 静默经济抽查：全部客户端退出后观察主机 `top`/`ps` 5 分钟 | daemon CPU 趋近 0，无固定频率子进程刷屏（依赖 D-4） | 红线 1 |
| T20 | 每新增厂商机型：重复 T1-T6、T14、T17 核心段 | 同上 | F5 |

### 未验证清单模板（每次交付随附）

交付时列出本轮真机未执行的 T-步编号与原因（无该环境/依赖缺陷未修/时间），由用户决定是否阻断发布。
本审计时点：**T0-T20 全部未在修复后的构建上执行过**——缺陷 A/B 修复合入后应完整走一轮作为 v1 基线。

## 14. 提请 leader 的裁定与账面事项汇总

1. §11 R-1～R-8 共 8 项 needs-ruling；其中 R-1（特殊键）、R-2（多行粘贴）、R-3（重配对入口）建议优先裁定——均在用户首周必碰路径上。
2. D-1～D-4 四个实锤缺陷候选建议立即立案（P0 表已给五栏形状）；D-5 归 R-8 裁定。
3. 账面卫生：scenario-audit 回填 taskbook.yaml；taskbook#e2e goal 与实现落差登记（"杀 App/断网"实为"杀 daemon/关 socket"）。
4. 门禁基建升级（-race、min_cases 抬高）已并入 test-multi-client；覆盖率阈值本审计不建议当期引入（避免为指标而测）。
