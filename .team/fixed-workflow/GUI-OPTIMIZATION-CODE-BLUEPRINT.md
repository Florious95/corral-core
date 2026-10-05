# 六大优化点代码定位与技术改造蓝图（增补第七、八项输入交互）

> 固定开发 developer；2026-10-05。冻结基线：`7eead93debfd84fe88ab1b7136de328f3595fddc`，分支 `feat/issue50-native-gui-session-mode`。
> 本轮是源码预读/蓝图，不写产品实现、不操作生产/用户 pane、不调用模型请求。下列行号绑定该基线；本机 Pi 文档/源码另标 **1.0.0**。
> **源码事实、可确定的机制、待现场证伪的假设分开写。**尤其不把静态分析冒充已测得的滚动根因或性能提升。

## 0. 总体判断与几个必须先纠正的前提

| 优化点 | 当前代码事实 | 最小改造方向 | 风险 |
|---|---|---|---|
| Skill 折叠 | 无 Skill 类型；显式 `/skill:name` 可展开成 **user** XML 包装文本；自动加载通常是 read 工具 | 在消息投影识别正式包装，复用折叠组件；普通 Markdown 原样保留 | 误分类、用户追加参数丢失 |
| 多行原子输入 | **原生 GUI→JSONL 已是一条 command**；会删除首尾空白。worker 的“终端兼容输入”才逐行 Scanner | 先证明走哪条路；GUI保原文/加单次请求测试；混合终端按独立问题处理 | 把正确 RPC 改坏、误修全局终端 |
| 回底跳动 | 箭头/Toast/面板高度计入 dockPx，再反向影响 list padding；全行 animateItem，index0还有动态working项 | 先拆回底控制与测量反馈，稳定边界/尾部动画；用逐帧量具归因 | 失去尾锚、IME/手动历史回归 |
| 模型/Effort | Pi RPC支持，**本产品白名单和Hub参数不支持**；state只保模型显示名 | 扩可控命令、模型身份/选项、确认后refresh；UI使用真实levels | 只做按钮而没有可用后端 |
| 同pane真TUI/RPC置换 | 当前只是切视图；worker仍是RPC，并无session恢复元数据/置换协议 | 托管进程生命周期与同session handoff；运行中确认+服务端原子守卫 | 高风险，不能归为纯UI微调 |
| 双主题 | ThemeSuite已有两风格；GUI直接调用底层玻璃primitive，忽略材质/几何配方 | 复用LocalThemeSuite/ruledSurface，分离颜色与材质，完整覆盖子组件 | 白底白字、风格缓存与测量变化 |
| 动态输入栏 | GUI无focus展开状态；⚡/附件两只40dp按钮恒占主Row；TUI已有250ms高度曲线/IME隐藏世代守卫 | 主行仅+，聚焦后上层辅助行显⚡；共享高度进度，复用IME边缘状态机 | 与回底padding反馈、多行/硬键盘、旧hide事件竞争 |
| TUI斜杠候选 | GUI Slash与⚡用户快捷其实是两个候选源；TUI只手动开菜单，mirror仍接收不同步文本 | 在本地draft层复用候选匹配/填入，非模态overlay；按provider/capability取真实源 | 误用RPC数据覆盖普通TUI、点击后自动提交、抢IME焦点 |

名称纠正：

- `ConversationCenter.kt` 没有 `sendPrompt` / `hub.sendInput`；实际是 `ConversationScreen.send()` → `ConversationHub.send()` → `ConversationCodec.command()`。
- `ConversationState` 没有 `isStreaming` 属性；有 `running/compacting/queued` 和各 item 的 streaming/ToolPhase。`isStreaming` 是 Pi get_state 原始字段。
- 没有独立 `ThemeRegistry.kt`；**ThemeRegistry对象在 `ui/theme/ThemeSuite.kt:189`**。
- “在终端中打开”目前不等于真正交互式 Pi TUI，更不等于同session进程置换。

路径简称：下文 `conversation/` = `app/src/main/java/dev/agentmirror/app/conversation/`；`theme/` = `app/src/main/java/dev/agentmirror/app/ui/theme/`；服务端路径从 `server/` 起。

---

## 1. Skill 大段文本：实际流转与安全卡片化

### 1.1 当前传输/状态/渲染链

1. `server/internal/guirpc/worker.go:405–478 ingest` 保留 user/assistant message_start/end、message_update；丢 system和重复toolResult消息。不是按“Skill”挑文本。
2. `server/internal/api/conversation.go:95–170 relayConversation` 转为 conversation_event（ref/seq/ts/event），不会ANSI抓屏或解析Skill。
3. `conversation/ConversationCenter.kt:284–318 drain/flush` 按session聚合events，coalesceDeltas只合并同块相邻delta，再调用state.apply。
4. `ConversationState.kt:130–211 apply/reduce`：按seq去重；message_start/update/end分别进入消息Reducer。
5. `:227–242 messageStart`：user经userContent转换成 **UserTurn**，替换最早local echo；assistant只建立ActiveMessage。
6. `:246–301 messageUpdate`：按contentIndex创建/累积 **AssistantText / Reasoning / ToolCall**，key来自 `m<startSeq>#<contentIndex>` / toolCallId。
7. `:321–365 assistantEnd`：按最终content的text/thinking/toolCall校正，确保终态全文权威。
8. `ConversationScreen.kt:441–450`：密封类型when分发；UserTurn→UserBubble，AssistantText→AssistantProse，Reasoning/Tool/Notice各自组件。
9. `ConversationItems.kt:101–144`：UserBubble直接Text原文；AssistantProse→MarkdownText。**无SkillCard，无Skill识别。**

### 1.2 Pi 1.0.0 中 Skill 的两条不同来源

官方 `docs/skills.md` 与 `dist/core/agent-session.js:49–64,1628–1652` 已核：

- **显式命令 `/skill:name args`**：Pi读取SKILL.md、去frontmatter，展开为发送给模型的user文本：

```text
<skill name="name" location="/absolute/path/SKILL.md">
References are relative to /absolute/path.

正文
</skill>

用户的追加请求（可选）
```

官方parseSkillBlock是**从全文起始到结尾的完整匹配**，额外用户请求与skill content分开。因为客户端userContent不做解析，这个完整包装会落到UserTurn大气泡，而不是特殊assistant事件。

- **模型自动按需加载**：system只广告name/description/path；模型通过read加载SKILL.md。当前会表现为普通 **read ToolCall**，工具输出默认已折叠。不是名为skill的必然工具，也不必然带上述XML。
- assistant可能讲述Skill、引用XML、输出代码示例；那些仍是普通Markdown，不能一律折叠。

### 1.3 推荐最小投影，不动模型输入真相

- 在 `messageStart(user)` 与 `messageEnd(user)` 共用分类入口：只对完整正式包装匹配；保留name、location、正文、追加请求、原始文本/截断标记。
- 新增 `SkillInvocation` ConversationItem（建议名称，尚不存在）及 `SkillCard`；保留源消息的稳定key，不用正文hash、随机id或LazyColumn下标。
- 卡片默认收起，仅摘要“调用Skill / name / 来源”；expanded仍放Screen现有 `mutableStateMapOf<String,Boolean>`，与ReasoningRow/ToolCallCard共用披露方式。
- 追加请求不能塞入隐藏正文后悄悄丢失；在卡片外正常展示，或明确独立“请求”区域。一个消息拆成展示子区时，用派生稳定key/固定顺序，不制造新的RPC消息。
- 正文展开时才调用MarkdownText/SelectionContainer；收起时不要预解析大Markdown。复制应能拿到完整可用正文/请求，不能只复制摘要。
- MAX_TEXT=200,000的截断发生点要先审：不能截断后再要求XML闭合，导致正式调用失去识别。识别与截断应共享原始消息入口，未完整/未知格式保留普通气泡并明确截断。
- read工具路径basename=SKILL.md可改善工具摘要，但不能把任意read结果改成新的assistant Skill语义；优先复用现有ToolCallCard。
- 不修改通用Markdown parser，不按“文本够长/含skill关键词”猜测；不丢服务器内容、不宣称折叠会减少网络bytes。

**最小施工面**：ConversationState、ConversationItems、ConversationScreen三处；MarkdownText只复用，服务端原则上不用改。

**验收**：正式包装（有/无args）、自动read、未知Skill、malformed/XML代码示例、普通长Markdown、多次同Skill、重连replay、展开/复制、截断、两风格明暗均需区分。必须拿到一条真实Skill调用wire作正例，而不是只造实现偏好的夹具。

---

## 2. 多行输入：已原子化的GUI路径，与确实逐行的兼容终端路径

### 2.1 原生 GUI 路径逐层核对

| 层 | 代码坐标 | 事实 |
|---|---|---|
| 输入框 | ConversationDock.kt:194–213 | BasicTextField直接收TextFieldValue，maxLines=6是显示行数；没有按Enter提交/逐行split的KeyboardActions |
| 点击发送 | ConversationScreen.kt:330–367 | 一次send；draft.text.trim()；24,000 UTF-8 bytes以内一次hub.send(prompt,text) |
| 运行中 | 同上:352–365 | prompt携带streamingBehavior=steer；不是每行分别steer |
| Hub | ConversationCenter.kt:223–256 | 每次调用生成一个id/local echo/JsonObject；put(message)保留内部换行；一个conversation_command |
| Codec/传输 | 同文件:477–487,510–562 | envelope序列化文本，一条现有/ws；不通过tmux键盘注入 |
| WS接收 | server/internal/api/ws_conn.go:746–762 | capability门后分发ConversationCommand |
| 服务端命令 | api/conversation.go:223–290 | 解析JSON对象，保message内容，盖id，marshal回一个JSON record |
| IPC→Pi | api/conversation.go:230–236；guirpc/worker.go:363–368、672–705 | 单条JSON record加一个LF delimiter，经inputMu串行写Pi stdin；原文内部LF由JSON转义，不是新的record |

**因此代码不能支持“原生GUI这一链在\n处拆包”的结论。**对象中的转义`\n`与JSONL记录分隔LF是不同东西；不能为了修Bug删除JSONL必须的末尾LF。

已确认的原文损失：send的 `.trim()` 会移除首尾空白/换行；不拆内部行，但不满足严格逐字符保留。大于24KB走文本文件上传+reference（一次prompt，文件内保文本），不是直接全文message；这也必须独立记账。

### 2.2 确实逐行的路径

`guirpc/worker.go:297–328`：RPC worker还有一个**面向pane stdin的兼容文本输入reader**，`bufio.Scanner(input)`逐行Scan，每行marshal一个prompt，运行中加steer。

它只在终端键盘/粘贴进入worker的PTY时生效，不在GUI Unix JSONL路径上。代码对bracketed-paste仅在每行TrimPrefix/TrimSuffix起止标记，**没有跨行聚合缓冲**。

当前“在终端中打开”仅切到SessionScreen，进程仍是该worker。因此此路径可解释多行文本变多个prompt的机制。`SessionViewModel.kt:543–578 sendDraft` / `:655`起onPassthroughInput还会实时同步或提交文本至PTY；CLI的line-mode语义不能与RPC command混为一谈。

### 2.3 改造与定位顺序

1. 给发送和接收留非敏感操作数：source=GUI|TUI、ref_hash、command id、字符串char/UTF8 byte/LF数量、是否file-reference、prompt事件数量；不打正文或凭据。
2. 用包含空行、首尾LF、CJK、emoji、引号的三行原文，**点一次发送**。原生链应只有一个command id、一个Pi user消息，内容相同。Pi一次请求可内部多轮工具/LLM调用；不能把assistant/tool turn数量当成输入拆包。
3. GUI最小改变：空白判定与取原文分开，保留draft.text，不trim真实payload；统一单次提交/在途行为。不要给每行增加循环或改全局TUI发送器。
4. 若确认用户在兼容终端：要么明确采用完整的paste/提交缓冲协议（不能只去首尾标记，需先核TTY cooked/raw如何区分LF与CR），要么由第5点提供**真正Pi TUI**来消费粘贴。不应临时把兼容console升级成第二套终端编辑器。
5. 捕获到多个id时再找多次UI action；一个id但多个user消息时查实际Pi插件/队列行为。未抓到真机wire前，不指定Compose或Hub为已确诊根因。

---

## 3. 手滑回底/箭头回底跳动：确定的布局反馈与待测物理机制

### 3.1 当前布局坐标

- `ConversationScreen.kt:222` rememberLazyListState；`:328` scrollToLatest创建协程并animateScrollToItem(0)，条件为index>0或offset>0。
- `:409–411` topPad=headerPx+10dp；bottomPad=dockPx+14dp；following仅看index==0 && offset<**24 pixels**（不是24dp）。
- `:419–442` rows=items.asReversed；reverseLayout=true；spacedBy(10dp,Alignment.Bottom)；所有row稳定key，**每row都有fade+spring placement animateItem**。
- `:438–440` 条件working项插在rows前，因此“index0”可能是working，不永远是最新消息。
- `:458–466` 空页项fillParentMaxHeight(0.82f)，与skeleton/首消息替换也有animateItem。
- `:521–585` 底部Column同时容纳Toast、回底箭头、composer面板/附件/多行输入；整个Column的onSizeChanged写dockPx，**位于windowInsetsPadding前**，包含IME/navigation占位。
- `:547–556` 箭头AnimatedVisibility以`!following`为条件，退出动画仍有缩放/淡出；箭头40dp并有bottom10dp，所在Box能贡献底部stack高度。

### 3.2 可以从代码证明的反馈环

```text
滚动达到following阈值
→ 箭头隐藏/退出，底部Box尺寸可能下降
→ dockPx改变
→ LazyColumn.bottom contentPadding改变
→ 边界/可见offset/尾部item placement重新测量
→ following再评估
```

**回底控制的显隐反过来改变“底部”的几何定义。**手动到达边缘和animateScrollToItem逼近0都经过这条反馈。输入换行、附件、面板、Toast、IME动画同样改变测得高度；每个placement spring又可叠加位移动画。

这是源码已确定的耦合，不是“reverseLayout索引反转必然有Bug”。key已稳定，worker.compactMessage还保留message/block start；不能没证据就重写索引或把原因甩给预加载。

**尚不能宣布唯一物理根因**：没有本轮逐帧offset/尺寸轨迹、掉帧采样或隔离开关对照。padding变化是否导致阈值反复翻转、哪个动画占主因、是否另有预取测量或GPU玻璃采样开销，须实測。

### 3.3 首选消耦，不用延迟掩盖

1. **箭头移出被测Dock高度**，作为独立overlay锚在composer之上；或固定保留相同gutter，不因显隐改list viewport。Toast宜同理。composer/系统insets高度的测量边界独立且只有一处负责。
2. 保留reverseLayout及稳定keys；不恢复“每token animateScroll”的旧路径。只有用户主动回底触发一次滚动。
3. following用真实start-boundary/canScrollBackward等信号和明确密度阈值，必要时小幅滞回；不要把24 raw px当通用尺寸。先消除反馈，再讨论阈值。
4. 定义回底目标：index0可能是working行。working状态可放不改变transcript起始身份的固定区域，或把尾锚与数据keys明确对应；不能认为item0永远同一块。
5. while following/连续流式尾部，慎用placement/fade动画；保留新历史项/主动披露的动效，但避免尾部在锚定时再跑spring。折叠Skill卡也会改row高度，须共同回归。
6. header/dock首次测量0→实高、IME/nav union、面板展开应有单一几何源；不能双加IME，也不能少让出键盘而遮正文。
7. 调试日志须setprop/诊断开关控制。当前`:181,:417–418`的ConvTrace每次compose/draw都log，不能用其常开输出当“零成本性能仪表”。

**证据操作数**：帧时间、firstVisible key/index/offset、canScrollBackward、viewport start/end、before/after padding、headerPx/dockPx、IME/nav高度、working key是否存在、箭头动画状态/是否scrolling、触发source=drag|jump|ime|sheet|delta。没有这组值不要判“offset0根因已彻底修复”。

**验收矩阵**：无IME/有IME、短/长对话、空页转首消息、running/settled、箭头多次点击、手指甩至底、Skill/Tool披露、面板/Toast、用户读历史时新delta、两风格。功能稳定外，按既有环境门+同批性能测量，不编造60/120fps通过。

---

## 4. 顶部模型与思维强度：协议存在，产品接线缺失

### 4.1 当前状态

- `ConversationScreen.kt:639–689 ConversationHeader`：名称+状态胶囊、返回/更多；胶囊没有模型选择click handler。
- `:785–795 headerStatus`：只展示Pi/model/thinkingLevel，运行中展示工作中。
- `ConversationState.kt:108–123`：model是 **String显示名**，没有provider/modelId/选项列表；thinkingLevel是String。
- `:433–458 response(get_state)`：把model.name（或id）简化为String，取thinkingLevel/sessionName；get_state.isStreaming=true时抬running，不完整覆盖isCompacting/pending count。
- worker `:785–814 projectState` 已保留model.id/name/provider，但客户端把身份丢掉；它**不保sessionFile/sessionId**。
- Hub.send只有kind/message/attachments/streamingBehavior参数；无provider/modelId/level payload。
- API conversationCommand白名单目前只含prompt/steer/follow_up/abort/clear_queue/compact/new_session/get_state/get_commands；set_model等会被拒绝。

### 4.2 Pi 1.0.0 真实命令（已核官方rpc-commands.md:210–328）

| 目的 | 命令payload | 响应/边界 |
|---|---|---|
| 模型列表 | `{type:get_available_models}` | data.models完整对象；不宜把pricing/endpoints无差别转手机 |
| 指定模型 | `{type:set_model,provider,modelId}` | response.data为模型对象；Pi实际检查该provider auth，失败必须可见 |
| 可用思维级别 | `{type:get_available_thinking_levels}` | data.levels；无reasoning模型可只有off |
| 设置思维级别 | `{type:set_thinking_level,level}` | Pi按模型能力clamp，发送thinking_level_changed |
| 可选cycling | cycle_model / cycle_thinking_level | 有scope、单一/不支持时可null，不能凭空保证所有厂商值相同 |

级别可含off/minimal/low/medium/high/xhigh/**max**，后两者仅在模型支持时出现。**不要把全局角色effort或供应商token budget直接等同于此枚举。**

`dist/core/agent-session.js:1910–1930 setModel`会做auth检查、写session model change；`:2016–2047 setThinkingLevel`按availableLevels夹取、记录变化、发事件。不应乐观显示用户想要但实际被clamp的值。

### 4.3 精确接线蓝图

1. 服务端白名单增加所需四命令，验证provider/modelId/level长度/类型；不接受手机任意provider endpoint或可执行命令。
2. projectState保留现有轻量模型身份；为get_available_models增加**瘦投影**（id/name/provider及UI真正需要的能力），不传配置密钥/headers/大pricing。其他新response也明确投影/上限。
3. Hub抽出通用typed JSON command发送入口，复用现有id、deadline、pendingCommands、错误回调；保留prompt接口与local echo语义。不要重开网络Socket。
4. State增加模型身份与选项/支持级别及受控loading/error，不把显示名当模型id；Reducer处理新命令response。
5. onReady已有get_state/get_commands；模型选项可按需加载，避免每次reconnect重复巨量列表。切模型成功后get_state与thinking levels再同步，失败保持旧选择并内联提示。
6. Header分离模型/思维选择触点，与更多菜单不冲突；顶部组件仍用真实session状态，不另存一套假的模型偏好。
7. 第一阶段建议idle时操作；Pi源码没有为此明确保证“运行中切换当前stream即时改变模型”。如要支持忙时变更，需要定义下一run生效时点并实测，客户端/服务端都不能仅根据陈旧running判安全。

**验收**：provider相同/不同、未授权模型、reasoning/off、xhigh/max clamp、失败/timeout/reconnect、列表更新、设置是否持久（session vs全局）、多端修改、忙时策略。不要只验证菜单点击后文字改变。

---

## 5. GUI ↔ 真正TUI：当前只是视图切换，必须补生命周期契约

### 5.1 当前行为与缺失资产

- `session/SessionRoute.kt:115–134`：nativeConversation进入时remember(ref)裁定；onOpenTerminal只设terminalForced=true。
- `ConversationScreen.kt:748–751`菜单调用onTerminal；不向服务端发置换命令，不根据running询问。
- `guirpc/worker.go:218–279` childCommand为 `pi --mode rpc --name name`；**没有`--session`**。
- worker的终端输出是printTerminal把RPC delta/tool转可读行；输入是逐行prompt兼容reader。它不是Pi交互TUI。
- worker持久文件只有socket/activity `.state`；projectState丢sessionId/sessionFile；客户端也未保存这两个字段。现在不能确定地拉起“同一个session”的另一进程。
- `bridge/CreateWindow`创建新window；KillPane销毁pane。基线bridge未找到respawn-pane封装。**KillPane+CreateWindow不是同pane**，ref和收藏/路由身份会变。
- RPC child stdin/stdout当前是pipe；真正TUI必须接pane PTY，不能仍把ANSI送进JSON Scanner。

### 5.2 恢复参数是可用资产，但还没接成无损置换

Pi 1.0.0 `docs/cli.md:79–103`：`--session <path|id>`支持文件/精确或部分id，可能跨项目选择/分叉；`--session-id`是另一套准确id打开/创建规则，不能随意与--session混用。

建议使用**server从该子进程get_state取得并验证的明确sessionFile/精确id**，保持cwd、资源/工具设置、模型与thinking。不能用`--continue`猜最新会话，也不能接受手机传任意本机路径。

保存transcript不等于保存任意进行中的工具副作用、extension UI状态或未完成模型stream。所谓“无损”应限于：已完成的session history/当前分支/模型配置及pane身份；强行中断正在执行的bash不可能保证副作用回滚。

### 5.3 推荐受控方案：稳定pane内托管supervisor

这不是已存在API，需先由leader裁定协议/失败语义后施工：

1. 管理层维护同ref的真实模式 `RPC|TUI|Switching|Failed`、operation id和server自有session恢复元数据；**显示偏好与真实进程模式分开**。
2. GUI请求模式切换仍复用/ws；服务端校验managed ref/权限、当前mode、busy、同ref独占lease，不用用户tmux send-keys注入命令。
3. pane内托管supervisor负责停旧child、确认reap/flush、启动新child。RPC态使用现有JSON pipe，TUI态直接继承pane PTY的stdin/stdout/stderr，兼容行reader停止，避免两个reader抢stdin。
4. 控制IPC与Pi命令分层：切换是supervisor控制，不是把不存在的Pi switch_mode命令送给Pi。TUI期间还需保留管理控制渠道，才可反向回RPC。
5. 持久handoff metadata须在child退出后仍存在且归属清楚；worker当前defer删除sock/state逻辑需区别临时状态与长期托管身份。
6. GUI→TUI后服务端明确ack模式，客户端才挂TUI；反向RPC Ready需新stream/reset，旧seq不能跨进程直接继续。保持7行socket canonicalization与公开ref不变。
7. GUIHub的everReady仍保护“失败不冷切”；真实模式改变是**用户批准并收到服务端ack的转换**，不能让迟到listing自动替换屏幕。SessionRoute需独立处理显式转换事件，而不是删除remember防线。
8. 若新进程失败，有限deadline内返回阶段/error/是否回滚；仅回滚同session的受管进程，不杀相邻pane，不换window，不默默新建空会话。

替代选项：用tmux `respawn-pane -k`保pane id，但仍需要graceful handoff、session metadata、busy lease、PTY重新订阅及退出/rollback处理；当前无helper。直接kill会丢未完成工作，原pane进程先退出还可能被tmux删除；不应把一行respawn命令描述成已安全无损。

### 5.4 运行中拦截要看真实工作，不看最后一条字是否streaming

前端初筛：`state.running || state.compacting || state.queued>0 || 当前未finished工具`；source是State及ToolPhase/finished，不是不存在的state.isStreaming。

- 忙时弹与当前theme一致的确认，明确模型输出/工具/排队输入会如何处理；默认取消。纯UI“我知道了”后直接强杀不算保护。
- 确认后仍由**服务端重新读取忙状态并加lease**，否则另一客户端可在点击间隙启动任务。
- 取消/保留queued策略必须定：Pi abort本身可继续队列；若要求切换前停净，需要按批准策略clear_queue+abort并等agent_settled，记录被取消项，而不是只sleep固定秒数。
- compacting/网络不可达/无法确认sessionFile时fail-closed，提示原因；不能降级成KillPane。
- 原有“在终端中查看RPC console”可暂保留，但必须与“转换成真正TUI”名称/操作语义区分。

**验收至少**：idle来回、运行中取消确认/批准中断、长工具/队列/compact、不同设备并发、退出/启动失败/回滚、pid与同pane ref不变、cwd/sessionFile/branch/messages/model一致、PTY行数/重订阅、杀进程零孤儿、public ref别名、未经托管的普通TUI明确不支持。不得借此自动接管用户原有普通Pi。

---

## 6. 两主题体系：现成注册/令牌可复用，GUI绕过了材质配方

### 6.1 两个正交轴

- **App风格**：`theme/ThemeSuite.kt:189–215 ThemeRegistry`注册LiquidGlass/Modernist；ResolvedThemeSuite包含colors/geometry/typography/surfaces/recipes。
- **终端颜色主题**：`TermPalette.kt:233–245 of(dark)`按TermThemeSelection装配缓存scheme（30家族深浅槽）。不是App ThemeRegistry。
- `AppTheme.kt:166–190`已通过LocalThemeSuite/LocalAppPalette/LocalModernistTokens下发全局风格；ConversationRoute嵌套AppTheme默认继承，不是把风格重置为Glass。
- **缺的是GUI消费这些令牌**：ConversationPalette只按TermPalette.Scheme导色，无风格id/材质/几何。

### 6.2 明确的硬编码位置

| 组件 | 代码坐标 | 现状 |
|---|---|---|
| GUI palette | ConversationPalette.kt:63–137 | OkLab/对比修复有效，但glass alpha、glint、surface一套配方；memo仅按scheme identity |
| 取palette | ConversationScreen.kt:189–192 | remember(dark)，未以风格id/终端scheme变化作完整key；同明暗切终端主题可能留下旧p，须测 |
| 玻璃primitive | Screen:149–159 frostedGlass | 直接glassControl，20dp blur、10/16dp lens、crystalHighlight+shadow |
| 背景/录制 | Screen:398–434 | 无条件glow、rememberLayerBackdrop、layerBackdrop录transcript |
| 顶栏/菜单 | Screen:649–688,739–755 | 渐变、Capsule/20/14dp圆角、GlassCircleButton |
| Dock/面板/按钮 | Dock:171–213,227–375 | 26/22/16dp圆角、圆按钮、按压spring、frostedGlass |
| 消息/工具 | Items:101–266 | 22/18/10dp圆角、surface glint/描边/固定字阶 |
| Markdown/代码 | MarkdownText.kt:242–338 | 固定Inter字阶、code14dp、copy10dp、圆点/引用圆角 |

关键区别：`ui/components/LiquidGlass.kt:301–365 glassControl`是**底层Modifier primitive**，没有LocalThemeSuite风格分支，真的执行drawBackdrop/blur/lens。因此不能因为同文件的GlassButton等上层组件已有Modernist分支，就推断GUI调用也自动适配。

现成正例：`ui/screens/SettingsKit.kt:93–130 settingsGlassTokens/settingsGlass`读取kit.surfaces.isGlass；Ruled分支用 **`ruledSurface`（LiquidGlass.kt:387）**，非采样、实色、发丝线。

### 6.3 推荐复用，而不是再注册第三套主题系统

1. GUI读取LocalThemeSuite，材质helper（建议conversationSurface）明确分Glass与Ruled；底层glassControl不改成全局隐式判断，避免影响其它既有组件。
2. Ruled分支复用ruledSurface/ThemeGeometry.corner或shape，取消blur/lens/shadow/glint、Capsule/圆卡等不符合sharpCorners的形状；用当前geometry.hairline/headerRule/pageInset、typography、recipes定义栅格和分区。
3. `ModernistThemeSuite.kt:36–69`已有金属surface→diffuseEnd、ink、红accent/divider；`:378–433`指定sharpCorners=true、Ruled、ambientLight=false、shadows=false。用这些已有语义，不发明独立材质枚举/持久化id。
4. 背景glow/backdrop录制按surfaces.ambientLight/isGlass条件使用；现代主义不创建无用采样层。玻璃下保持“被录transcript不含玻璃采样面板”的边界，避免自采样成环（LiquidGlass.kt:122–132已写明SIGSEGV风险）。
5. 卡片/气泡/思考/代码/复制/选项菜单/附件/连接错误/新增SkillCard与ModelPicker必须一起吃几何/材质令牌；只改顶栏留下圆润正文不叫全量适配。
6. 字体/字号/字距/眉题/网格取已有ThemeTypography，圆角不只依赖M3 Shapes（这些组件用的是自定义Kyant shapes）。动效仍可保状态披露；现代主义的弹性取舍需明确视觉契约，现有ThemeSuite没有独立motion字段，不能假称已有专属物理参数。不要同时改消息Reducer。
7. palette缓存key包含实际scheme与style必要参数；Compose remember不能只有dark。切主题不能重置hub/session/listState/draft/expanded或RPC进程。

### 6.4 颜色权威冲突必须先明确

现GUI强调色/背景从30套TermPalette推导；ModernistTokens有固定金属底与纯红强调。**两套颜色不能在同一部件同时被当成唯一真相。**

推荐先遵既有产品“风格=材料/几何/排印，终端主题=消息颜色”分层，现代主义金属漫反射与直线几何用既有tokens/配方；如用户要求GUI背景/强调完全固定Modernist颜色，则由leader明确裁定这些角色覆盖终端颜色的范围。

不论采用哪种，**对比度必须针对最终真实surface重新算**：不能拿TermPalette背景算出的ink，放到另一套金属背景上还宣称7:1。保留ConversationPalette的OkLab/ensureContrast资产，扩的是实际基底和材质角色，不是删除安全色板。

验收：两风格×明暗×30颜色家族，正文/用户块/代码/工具成功失败/禁用/链接/selection、系统栏、IMe/菜单/弹层、主题即时切换、冷启持久化、滚动锚/草稿不丢、Modernist零玻璃采样、Glass无自采样环。数学对比度与手机视觉实见缺一不可。

---

## 7. 第七章：输入框仿 TUI 动态二段式展开机制代码映射与改造方案

本章新增读取仍绑定7eead93de；只补蓝图，不改TUI/GUI产品。用户目标是：未聚焦只有`+`，聚焦上展后才在上层出现⚡，失焦或键盘收起回紧凑态。**复用的是成熟焦点/IME节奏，不是把TUI逐键输入业务搬进GUI。**

### 7.1 经典 TUI 的真实状态机与几何

| 资产 | 冻结代码坐标 | 实际行为 |
|---|---|---|
| 编辑器高度 | `session/CommandInputBar.kt:112–114` | field单行32dp；展开`20*lines+12`，lines夹在2..5，默认3→72dp |
| 外壳/主Row | `:243–252` | Row底对齐，外padding上下各7dp；典型胶囊46dp→86dp；不是整个终端页任意animateContentSize |
| 焦点状态 | `:179–199` | focused、suppressFocusGain；collapseRequest>0显式压回；editorExpanded=focused且无collapseRequest |
| 键盘显示 | `:189–197` | focus后等withFrameNanos一帧再show，避免text-input session未挂时show被忽略；收起调用hide |
| 首次触摸/真实焦点 | `:354–378` | pointer-down在Initial pass登记新展开请求；onFocusChanged补键盘/程序focus，失焦触发上层collapse |
| 高度动画 | `:201–207` | animateDpAsState + **250ms tween**，Standard(0.4,0,0.2,1)，不是spring |
| 减少动画期重组 | `:134–141 animatedHeight` | layout时读取State<Dp>并约束高度，每帧主要remeasure，不把height.value提升成整屏composition依赖 |
| 快捷钮显露 | `:116–132,:210–212,:255–284` | capsule展开进度超过0.7才compose，后0.3进度同步alpha，8dp升起；收起按同一曲线逆放 |
| 快捷钮结构 | `:249–296` | 主行左仅36×32附件槽；快捷钮**overlay在+上方-36dp**，不增加额外测量高度，也不抢焦点 |
| IME隐藏检测 | `SessionScreenScaffold.kt:73–118 ClearFocusWhenImeHides` | 监听root IME可见的布尔边沿，按最新expansionRequest世代武装/解除 |
| IME状态纯函数 | `:121–181 ObserveImeHide/observeImeVisibility` | 真实可见像素/浮动IME后才武装hide；旧target=0事件不能误杀新展开 |
| collapse唯一入口 | `:214–257`；`SessionScreen.kt:190–220` | hideRequested防重复，collapseRequest计数，keyboard hide+insets hide+clearFocus；新展开复位 |
| 返回/外部触摸 | `SessionScreen.kt:137–169`；Scaffold`:275–289` | Back依据焦点优先收起；画布pointer-down观察但不消费，保留终端滚动/鼠标 |

**两个容易误抄的事实**：

1. TUI的“3行”主要是编辑器3个文字行；快捷钮是测量外overlay，不是第二个恒定全宽Row。整个Scaffold的另一个常驻HotkeyRow与GUI目标无关，禁止一并移植。
2. `SessionDockTheme.kt:43–53`给InputHeightMillis=250、InputBorderMillis=200、KeyboardPushMillis=300。**300ms不是当前IME位移的自制动画**：Scaffold`:263–274`实际用imePadding消费系统逐帧insets，注释也说明旧timer已被系统insets取代。不要再为键盘另加300ms translate/tween。
3. 当前平台接线是ClearFocusWhenImeHides的root可见布尔边沿；完整current/target版本ObserveImeHide是另一个可复用辅助边界。不能把纯函数具备的像素/target能力误写成GUI现已接入完整IME采样。

### 7.2 GUI 当前为什么挤，为什么不能自动收起

`ConversationDock.kt:142–213`只有value/sheet/images/running等参数，没有focus、collapse/expand intent、IME-hide generation。

- 主Row`:186–195`依次放⚡、Clip附件、`weight(1f)`文本、Send。两只DockIconButton`:258–273`均**40dp**；删掉主行⚡可直接释放40dp横向槽，不是靠缩小字号解决。
- 当前附件图标是`Glyph.Clip`，不是用户目标的Plus；`ConversationIcons.kt:39`确实没有Glyph.Plus。仓内已有`session/DockIcons.kt:30`的公开DockIconPlus向量，优先复用；若需保持新Glyph线图族，则只补同源Plus映射，不能只把文案叫“+”。
- BasicTextField`:199–210`是maxLines=6，无onFocusChanged、无聚焦height state、无single-line展示态。内容自己长高，不等于“聚焦即二段式上展”。
- 当前Screen仅在列表tap时clearFocus/关sheet（`:435`）；现有BackHandler`:387–397`先处理sheet/menu，否则onBack，没有输入聚焦→先收起这一层。
- GUI没有复用ClearFocusWhenImeHides。系统隐藏IME未必让Compose失焦；只新增onFocusChanged仍可能保留展开态。

### 7.3 推荐 Compose 状态归属（一个输入意图源，不造四个相互打架的布尔值）

- 在ConversationScreen按ref保存**编辑器展开意图/输入聚焦**及expansionGeneration、collapseRequested；draft/selection/composition仍是现有TextFieldValue，不能因收起重建文本。
- Dock把真实focus和pointer-down事件回传；Screen统一`requestExpand(source)` / `requestCollapse(source)`。必要的suppressFocusGain只用于系统focus未及时清掉的防回弹，不是永久禁用编辑器。
- 新展开先增加generation并清collapseRequested；挂焦点后下一帧show IME。IME-hide只能消费它已武装的最新generation；别用旧hide callback清新focus。
- 下列事件进同一collapse入口：真实失焦、系统IME hide、列表外部触摸、Back（按页面既有sheet/menu优先级协调）。入口幂等：hide/clearFocus/折叠辅助操作不能重复触发回弹。
- 不直接用`focused && imeHeight>0`作展开条件：刚聚焦、硬键盘、浮动键盘可没有遮挡高度。复用observeImeVisibility对像素/target/rootVisible的区别；显示初期0不意味着用户要求收起。
- 附件/快捷菜单要有明确策略：点击辅助控件不无故抢输入焦点；关闭键盘时收起⚡，独立已打开面板可按既有返回规则关闭。必须防“按钮消失导致正在点击的菜单被销毁”。

### 7.4 Row/Column 结构蓝图

推荐按用户指定**上层辅助行**表达，而不是把⚡重新塞回编辑器同一横Row：

```text
ConversationDock / 外壳Column（材质读ThemeSuite）
  附件预览区（已有；附加数据与焦点态分开）
  辅助操作区域（只有Expanded才可触达）
    Row { ⚡快捷指令；Spacer(weight=1)；必要状态 }
  主编辑Row，始终底对齐
    +附件40dp | BasicTextField(weight=1) | Send/Stop40dp
```

- Collapsed：辅助区不占高度也不留隐形点击层；主编辑单行展示，**仅+是左侧按钮**。草稿多行不删除，收起仅改变可见高度。
- Expanded：编辑器显示2或3行，辅助Row在上层；⚡绑定已有ComposerSheet.Shortcuts，不重新实现快捷命令查询或自动发送。
- **总高度只有一个主动画进度**。辅助区分配的高度和editor高度由同一progress导出；不要“field先展开到70%→辅助AnimatedVisibility再独立增高→animateContentSize又弹一次”。
- 若采用更贴近经典TUI的轻量布局，可把⚡做左槽上方的overlay辅助控制，复用其70%揭示法，完全不增加测量高度；但若产品明确要求全宽上层Row，则采用上述Column，不能假称overlay与全宽Row视觉完全相同。
- 文字行数与“辅助行”是两种高度来源，要在设计中明确2/3指editor还是视觉结构。建议editor默认2、最多3，辅助区单独计入同一目标高度预算，最终由用户认可，不机械套86dp。
- 当前GUI BodyStyle是15sp/23sp（MarkdownText.kt:242），TUI是13.5sp/20sp。高度计算应按GUI lineHeight和fontScale +上下padding求目标，**不能照搬20*lines+12然后把CJK/大字体裁掉**。

### 7.5 动画/阻尼：主高度先照成熟曲线，辅助动作共享进度

- 主height：优先 **250ms tween + SessionDockMotion.Standard**。该项目的成熟高度节奏没有spring阻尼，禁止给它虚构dampingRatio。
- 边框/色彩：沿200ms Ease或GUI既有token，不能改变对比度安全色。
- ⚡辅助区域：progress0..0.7不可交互，0.7..1按同一曲线alpha0..1、translationY从8dp到0；反向收起。仅alpha=0仍可能可点击，需与composition/semantics gating一致。
- 按压可以保GUI按钮现有scale spring（DockIconButton damping=0.6、StiffnessMedium）；这是触点反馈，**不是外壳高度/IME的第二套弹簧**。
- 已有sheet spring(.82,520)仅管弹层，不应再叠加到同一Dock高度路径。若Opus选择高度spring，必须单独对照250ms基线、约束overshoot、验证IME/尾锚；不是默认替换。
- TUI的animatedHeight仅重测量思路可复用/提取小helper，避免把每帧高度发布为整屏StateFlow；不复制整套CommandInputBar业务。

### 7.6 与第2/3/6项必须共同守住的边界

1. **多行语义**：保持GUI一条prompt与`\n`；别直接复制TUI的ImeAction.Send/硬件Enter KeyUp提交逻辑（CommandInputBar`:308–350`），那会把GUI原来可换行的编辑行为改掉。Enter/Shift-Enter/IME按钮应按明确契约设计，布局展开不能变成输入拆分。
2. **回底测量**：聚焦展开的真实composer高度可以改变list clearance，这是预期；箭头显隐不能再参与这个高度。测量composer+系统insets一次，辅助Row的显隐由同一展开进度决定，不另生反馈环。
3. **IME**：GUI Screen`:527`现有navigationBars.union(ime)继续作为唯一系统inset入口；不要再给Dock套imePadding或人工KeyboardPushMillis位移。floating/hardware键盘单列测试。
4. **两风格**：同一输入状态机/触控槽/高度曲线，外壳与上层Row用ThemeSuite材质/几何。Modernist不能因为采用直角而丢focus/IME逻辑；Glass不能因采样动画让编辑器重建。
5. **路由/进程**：不改Hub/RPC，不自动切TUI，不触碰真实pane；本项仅GUI Compose布局和焦点协调。

### 7.7 最小修改面与验收

- `ConversationDock.kt`：去主Row⚡、Clip→Plus、辅助区、focus回传、受控高度/maxLines、同进度揭示。
- `ConversationScreen.kt`：按ref展开/收起意图、generation、防旧IME-hide、Back/外部触摸协调、composer清空高度/第3点分离测量。
- 复用候选：`SessionDockMotion`、`observeImeVisibility`/ClearFocusWhenImeHides、布局期读高度的思想；必要的小型共享helper另案声明影响面。**不修改TUI现有节奏作为顺带优化。**
- Plus优先复用`DockIcons.kt`的DockIconPlus；仅在需新Glyph族一致性时改`ConversationIcons.kt`，不加依赖、不改服务端。

验收：未聚焦仅+且文本宽度增加；第一次聚焦上展、⚡后显且可点；IME Back/滑动隐藏/真实blur各自收起；hide途中快速重聚焦不被旧事件折叠；系统focus未清时无回弹；硬件/浮动IME；辅助菜单开合不自动提交；多行draft/selection/composition保留；两风格明暗；长字体/窄屏；回底/IME同时动作无新跳动。通过同APK真实ConversationRoute取证，静态fixture只能测组件，不冒充IME整链。

需要操作数：source、focus/expand意图、generation、collapseRequested、IME current/target/rootVisible、主height目标/当前progress、辅助可见性、composer测量与list padding、触发时刻。应验证同帧开始和单一曲线，而不是只截一张最终展开图。

---

## 8. 第八章：TUI 模式下斜杠弹出快捷命令列表的实现原理与代码映射

### 8.1 GUI 的斜杠源：不能把三个东西混为“快捷命令大全”

冻结代码 `ConversationDock.kt:83–132` / `ConversationScreen.kt:234–251`：

| 候选来源 | 当前获取路径 | 当前用于哪里 |
|---|---|---|
| 两个GUI RPC内置 | slashEntries内硬编码compact/new | Slash面板；发送时Screen把这两项映射为compact/new_session RPC |
| Pi当前session可发现命令 | Hub onReady→get_commands；Center`:373–381`解析name/description/source；worker`:758–781`瘦投影 | Slash面板的commands；来源可为extension/prompt/skill |
| 用户本地快捷配置 | SharedPreferencesShortcutCommandStore→resolveShortcutCommand(command,"pi") | **⚡ Shortcuts面板**，不是当前Slash分支 |

GUI `slashEntries(query,commands)`：去前导`/`、lowercase；前缀rank0、名称包含rank1、描述包含rank2；内置+host commands按name去重；保留source glyph与match高亮。Slash触发要求draft从`/`开始、不含空格/换行、未被当前前缀dismiss；显式Attach/Shortcuts面板优先。

点击流程 `Screen.kt:369–383`：Slash用entry.key+空格填整个draft、caret到末尾；用户快捷用entry.detail填配置原文；两者都只插入、不发送，再关sheet。这里detail同时承载快捷payload的现状不宜扩展成两UI共用的隐式协议。

**Pi 1.0.0的关键边界**：`rpc-commands.md:788–836`明确get_commands只发现extension/prompt/skill，Skill名带`skill:`；**不包含交互式TUI的/settings、/hotkeys等内置命令**。GUI自己补的两个RPC动作也不是任意Agent都通用的TUI命令。

所以用户看到的“很全面”是真实体验反馈，但源码不支持“GUI Slash已合并所有用户快捷，或get_commands可给所有TUI命令大全”的技术口径。反向移植应把源分清，再扩候选，不照搬一个错误假设。

### 8.2 TUI 不同步仍有完整文本监听：缺的是本地触发/候选面板

- `SessionRoute.kt:182–183,227`建VM时读取SharedPreferencesInputSyncStore，将值作为inputSyncEnabled传入；应依据**当前VM实际模式**判行为，不能只看设置文案猜测。
- `SessionScreen.kt:292–297`持有mirror:TextFieldValue、shortcutCommands与shortcutMenuOpen。
- `:448–452`：CommandInputBar onValueChange先调用viewModel.onPassthroughInput(mirror,new)，再mirror=new。**不同步时仍更新本地mirror，不会失去slash输入事件。**
- `SessionViewModel.kt:655–672`：!inputSyncEnabled即return；有IME composition也不派生按键。现成地提供“本地编辑，不碰CLI”的边界。
- `SessionScreen.kt:319–336 applyShortcut`：按当前provider解析配置，next=TextFieldValue(result.text,caretEnd)，仍经过onPassthroughInput再更新mirror；不同步时零远端输入。
- `ShortcutCommand.kt:9–29`定义id/name/providerCommands及fail-closed resolver；unknown/未配置provider不跨供应商fallback。Store无默认命令种子，load缺失返回empty。
- `SessionScreen.kt:431–439`只在手动快捷菜单开启时reload配置；`:491–496`挂ShortcutGlassFloatingMenu。
- `CommandInputBar.kt:255–284`快捷钮仅随聚焦高度进度出现并通知菜单开启；**没有任何value.startsWith("/")触发。**

不同步模式下的问题不是服务器没回slash，而是本地draft没有slash派生状态。无需启用实时透传来“治好”它。

### 8.3 候选源策略：普通 TUI 不具备 RPC 发现通道

1. **用户快捷**：沿用SharedPreferencesShortcutCommandStore与resolveShortcutCommand，过滤当前provider可用项；显示name，插入该providerCommands原文，不拿description当command。
2. **已验证的provider内置slash**：有准确CLI版本/交互命令事实才登记；还要区分真实backend。当前managed Pi的TUI视图仍是RPC兼容console，不能给它广告仅interactive mode支持的/settings、/hotkeys。不能把Pi的/new、/compact无条件复制到Codex/Grok/Cursor。未知provider应说明无法识别或仅显示明确有匹配的配置。
3. **动态资源命令**：只有受管session/host提供真实命令metadata时使用，必须绑定host/workspace/provider的来源。不从另一工作区的Pi session拿skill列表当当前TUI可用命令。
4. 普通裸TUI没有conversation socket，不能对其ref调用hub get_commands并声称可发现全部命令；不能偷偷启动一个新RPC会话、向用户pane注入/help、抓ANSI或tail session JSONL来补列表。
5. 第5项将来若托管supervisor在TUI期间仍提供控制metadata，可以复用；在当前基线**不存在这个能力**。本次纯前端功能可先交付本地配置+准确静态项，动态“大全”必须另有协议资产才能承诺。
6. 将来源/可用性显示清楚；empty不是成功“查到全部”。ShortcutProvider枚举目前只有Pi/Codex/Grok，但resolver是Map查找；不能擅自扩厂商默认命令作为顺带需求。

推荐两UI共用一个小型**纯候选构建/匹配**入口：稳定key、title、insertText、description、source、必要provider信息。复用排序/匹配，不抽大框架、不导入整个GUI screen/glass材料到TUI。

- Host/Builtin Slash选择→插入`/name `。
- 用户Shortcut选择→插入配置的完整原文（可能多行），不自动补`/`或尾空格。
- 同display name但不同id/来源不可用distinctBy(name)直接丢掉；去重依据真实命令身份/插入文本与明确优先级。来源可以分组，让用户看出相同名字的区别。

### 8.4 TUI 触发状态与关闭/回写

在SessionScreen的mirror层新增派生query/panel状态，不塞进SessionViewModel网络层：

```text
本地draft以/开头
AND 当前有效输入焦点/编辑展开意图
AND 首命令前缀内（首阶段与GUI一致：无空格、无LF）
AND 未被当前“展开generation + query”dismiss
AND 不被手动附件/其它面板覆盖
→ Slash候选overlay
```

- query可支持`/`、`/co`等；光标中部/选择区/IME composition策略明确，不能在组合期擅改draft。初期复用GUI严格前缀规则，勿从段落中间正则猜命令。
- 仅在面板从关闭→开启、provider改变或配置返回刷新时load快捷配置；不要每击键解析SharedPreferences JSON或发网络发现请求。
- 手动⚡与自动Slash可以共用render，但使用明确mode，不能用shortcutMenuOpen一个bool混淆“全部用户快捷”和“slash过滤”。附件/overlay冲突遵既有优先级。
- 用户Back/外点dismiss后记录该generation/query，不然同一`/`下下一次recompose立即又打开；文字改变或新展开世代可重新触发。键盘收起/失焦则关自动面板，draft不清空。
- 点击候选只构造新的TextFieldValue（caret末尾），走与applyShortcut同源的受控回写。**不调用sendDraft/onSendText/hub.send，不插CR/Enter，不自动执行。**
- 不同步时onPassthroughInput早退，远端应收到0字节；同步模式如也启用，必须保留已有DiffSync替换/退格机制，不能直接改mirror使CLI留旧前缀。
- 用户强调不同步是主验收路径；若本轮只保证该模式，应明确范围。若覆盖同步，需单列与CLI自带候选/多行粘贴的交互，不用开关切换绕过问题。

### 8.5 浮层挂载、IME与第7项协同

旧 `ShortcutGlassFloatingMenu`（CommandInputBar.kt:469–516）不是可直接开关复用的完整Slash面板：

- 只接List<ShortcutCommand>，宽190dp，无query/排序/host source。
- 根是全屏zIndex20的tap-dismiss遮罩；适合手动菜单，但自动补全还要持续输入，不能因此把编辑器挡住。
- 锚点固定BottomStart + bottom72dp；第7项输入展开、IME、字体/附件都会改变实际composer高度，固定72dp不再可靠。

新Slash采用**同一个Compose window里的非模态overlay**：

- 挂在SessionScreen/Scaffold提供的真实Dock上边缘，zIndex高于terminal canvas，但不进入Column流式高度，也不改变PTY viewport余量。
- 候选可LazyColumn有界高度；编辑器仍可打字，列表点击不要求焦点，保留IME。不要用Popup/Dialog默认抢焦点触发第7项collapse→overlay消失。
- 根据真实composer bounds或同一动画高度State定位，而不是固定72dp；IME只由所在坐标系消费一次。旧menu作为Scaffold外sibling自己imePadding，与Scaffold内部已消费insets是不同坐标域，不能把两条一起套到新panel。
- 面板外点击的范围不要罩住editor/辅助钮；对历史画布的真实外点，按“dismiss候选+收输入”规则观察而不消费，不能吞滚动/终端点击。
- Back优先dismiss候选，再收输入/IME，再离会话；与SessionScreenBackHandler和现有菜单BackHandler顺序一起定，不留两个同时onBack的监听器。
- 第7项的generation/旧IME-hide保护一并使用；collapse即候选不再可点。候选数据到达不能重新拉起键盘，也不能抢用户当前selection。
- 样式复用既有ThemeSuite（旧ShortcutGlassMenu已有Glass/Ruled分支）；可复用GUI匹配高亮，不绕过Modernist材质协议。

### 8.6 最小施工面、判据与禁止暗改

施工面：SessionScreen（query/源/回写/overlay协调）、CommandInputBar/Scaffold（focus及anchor接口，保持TUI高度/发送器）、纯候选helper与面板。ShortcutCommand resolver继续复用。普通本地快捷版本原则上不用改服务端；动态metadata部分必须另定真实能力来源。

验收必须使用真实SessionRoute→TUI且inputSyncEnabled=false：

- 输入`/`自动浮现、`/prefix`筛选、space/LF/普通文本收起；selection/组合期不丢。
- 当前provider匹配、unknown/unconfigured、空库、同名候选、无动态源可见；编辑快捷配置后重新开面板可见更新。
- 点Slash与多行用户Shortcut分别填入、caret正确；**未点发送前远端0输入/0turn**；之后发送才执行一次。
- Back dismiss后不反复重开、失焦/IME hide、hide中重focus、浮动/硬键盘；继续输入前缀面板不抢focus。
- 与第7项上层⚡、附件、扩展高度联合验证；候选锚随真实Dock走但不推动PTY多次resize。
- 同步模式若纳入，另测DiffSync远端前缀替换，无Enter/重复提交；经典快捷菜单原行为不回退。
- 两风格深浅、窄屏/大字/长列表/全无结果提示；不能用硬编码静态fixture替代真实TUI不同步链。

---

## 9. 交给实现者的边界、顺序与机械判据

### 9.1 不该做的事

- 不用ANSI/截图/尾随session JSONL猜语义，不新建网络Socket。
- 不以“默认任何Pi都进GUI”修原子输入/置换；保留capability与everReady冷切防线、已修socket别名身份。
- 不把8件合成一个无法归因的产品提交；首选逐件冻结/验收，界面部分可以共享只读预读。
- 不把Skill fold、跳动修复当作无量测的性能提升，不改断言/超时来假绿。
- 第5点涉及协议与进程所有权，不能授权给纯视觉实现后默认“无损”已解决；先定迁移/abort/队列/失败回滚契约。

### 9.2 建议实施排序（只作蓝图，不自行推进）

1. 先给第2/3点补可见操作数与真实路由/wire/逐帧证据。
2. Skill投影与折叠最小功能；单独测普通Markdown/回放不回归。
3. 回底测量反馈消耦与第7项输入展开共享几何边界；分别冻结改动，独立A/B及IME矩阵，不随意变reverseLayout。
4. 模型/Effort后端命令+轻量metadata闭环，再挂顶部UI。
5. ThemeSuite全量消费（覆盖新增卡/选择器），不重写状态流。
6. 第8项TUI本地Slash候选与第7项共享focus/anchor契约，单独验0远端输入和显式发送。
7. 同pane真TUI置换单独高风险协议/生命周期任务，先服务端红测/同session实证，再接确认UI。

### 9.3 每点最低取证

- Skill：真实Pi user XML + 自动read双样本、普通Markdown反例、稳定key/replay。
- 多行：一次点击→一个id→一个JSONL command→一个完整user消息；首尾/内部LF/空行/CJK均保留；file-reference单列。
- 回底：箭头显隐不改变内容padding；逐帧anchor/offset/insets证据和不倒退测量；不能仅截图“看着到了底”。
- 模型：真实可用列表、实际model identity、ack/clamp、失败/timeout、session/global持久化区分。
- 置换：同pane/ref/cwd/sessionFile+内容分支+模型；忙状态race、失败回滚、零孤儿、旧TUI不可无侵入附着边界。
- 双主题：材质选择/shape/token单测+全颜色对比度+同APK真路由深浅视觉与滚动/IME。
- 输入展开：单行仅+→焦点展开/后显⚡→IME-hide收起；新展开世代抵御旧hide、保多行原文与草稿、单一高度/insets测量。
- TUI Slash：实际不同步mirror监听、provider真实候选、点击仅填入、提交前0输入、IME与动态Dock锚不冲突。

---

## 10. 预读凭据与本轮交付边界

源码manifest：`.team/fixed-workflow/GUI-OPTIMIZATION-CODE-BLUEPRINT-SOURCES.json`，保存上述核心文件冻结blob/sha、Pi版本和本轮检查。行号是函数级/相关段落定位，不是对未读路径猜测。

本轮已覆盖：ConversationState消息/工具/元信息Reducer、Items披露与摘要、Screen发送/列表/overlay/header、Dock输入和sheet、Center协议/状态/命令、Palette与Markdown、SessionRoute/TUI输入、服务端command/worker投影/生命周期/IPC，以及AppTheme/ThemeSuite/Modernist/TermPalette与材质primitive。第7项增读CommandInputBar、SessionScreenScaffold的IME世代守卫、SessionDockMotion及SessionScreen的焦点/Back协调；第8项核GUI slashEntries/get_commands与用户快捷分源、ShortcutCommand resolver、mirror/不同步早退、旧快捷浮层与真实Dock锚点。

未执行：新产品修改、Go/Gradle新测试、模拟器/手机滚动复现、性能A/B、用户会话置换、生产部署。上面的验收是**未来判据**，不写成已通过。现有无关throughput-after改动未碰；没有读凭据、Pi个人配置或用户聊天内容。

**最终技术裁定**：原生多行路径已原子、Skill有正式user包装来源、回底存在可定位的几何反馈、动态模型需补产品命令通路、真TUI置换缺生命周期资产、双主题可复用既有ThemeSuite而非重建体系。将这些事实带入实现，才不会在漂亮UI下再次隐藏协议和进程缺口。
