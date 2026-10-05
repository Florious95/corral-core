# GUI 演进历程、历史尝试与技术资产总卷

> 编写者：固定开发 developer。核验日期：2026-10-05 UTC（历史提交大多使用 2026-10-04 -06:00）。
> 本轮只做历史梳理、源码核对、证据归档和 CLI help 核查，**未继续产品实现、未合并、未部署、未操作生产 daemon 或用户 pane**。
> 本卷不是新实现任务书，也不把旧验收结论提升为用户真机最终认可。

## 0. 总结：哪些成立，哪些失败，哪些仍未成立

1. **结构化原生 GUI 的方向成立，但不能靠 ANSI 屏幕抓取补出语义。** Pi 有双向 JSONL RPC 和进程内 SDK；ACP 是另一套 JSON-RPC 互操作协议。已运行的普通 TUI，不能在“不重启、不侵入”的约束下被无损转换成消息、思考、工具语义流。
2. **第一轮确实写出了服务端 worker、Android 原生消息/工具卡、持久化显示模式和真实 SessionScreen 分流。** 但服务端另开 `/gui`、客户端另建 WebSocket，脱离了已部署 `/ws` 的能力和发布边界。生产端没有该路由时，手机创建失败不是模型问题，而是新通道与实际生产不配套。
3. **断线曾被误当“没有 GUI 能力”，自动冷切 TUI。** 相册点击只是暴露问题的触发场景；系统主线程栈落在 TermSurfaceView 字形绘制，21,209ms 事件派发超时形成 ANR。先修连接内 ready 分类，再修跨重连尝试的会话级 `guiReadyOnce`，两次缺陷必须分开记录。
4. **最终 cfbac 候选有真实 MainActivity 路由的模拟器截图：连续失败重连不冷切，恢复端点可回 GUI，普通旧 PTY 初次进入仍可回 TUI。** 不能把这些说成生产全链路通过，更不能说成所有 Agent 原生 GUI 已实现。
5. **必须收窄历史“全绿”口径。** 本次追溯发现，模拟器 GUI 服务使用自编的合成 Pi 进程；其 `message_update` 携带完整 `message` 快照，而研究报告和当前官方 Pi wire 都是 delta-only。第一轮 Reducer 依赖前一种形状。已验证的是装置中的 UI/路由/工具状态，并非真实官方 Pi 流式重建完备性。
6. **CLI 参数必须以实装版本为准。** 当前 Pi 1.0.0、Claude 2.1.282、Codex 0.158.0、Cursor Agent 2026.10.01-e373342、Grok 1.0.46 的 help 已核。04 号契约的若干推荐参数没有依据，甚至已被 parser 明确拒绝；“文档写了”不等于“参数存在”，更不等于“生产支持”。

---

## 1. 范围、身份与证据等级

### 1.1 本次看到的仓库身份

- cwd：`/Volumes/nvme/Projects/远程Agent安卓`。
- 当前主工作树：`main`，HEAD `194a4ca647b275b6934d06ef24d3100dfab5cf5d`；开始核查时 tracked 工作树干净。
- 第一轮产品源码分支：`dev/issue50-gui-session-mode`，HEAD `2c1440e5d517f4172f687bf5df142ef2b0e8cb6b`。
- 后续契约分支：`feat/issue50-native-gui-session-mode`，HEAD `a8cd4b9e5`；本机亦有同名 app remote ref。
- **当前 main 不包含这轮 GUI 产品文件。** 本卷读取的是 Git 中冻结的历史分支/候选对象，未切换或覆盖主工作树。`.team/` 交付物不在 tracked 产品源码中；本次没有产品施工，因此未新建开发工作树。
- 研究目标 `.team/fixed-workflow/pi-rpc-conversation-gui-goal.md` 标注 2026-09-19、serve `c119569` / app `b57ca54`；这只是该版目标书写下的基线，不能替代本次实际 Git 身份。

### 1.2 证据如何读

| 等级 | 本卷含义 | 不能推导出的东西 |
|---|---|---|
| 原件实核 | 存在的截图、栈、日志、APK hash、Git 源码或 CLI help | 一个场景不能推广为所有场景 |
| 历史记录 | 席位当时的报告、消息、恢复出的 receipt | 不是本次重新跑出的测试绿 |
| 研究/契约 | 官方文档核对或目标设计 | 不是已落地功能 |
| 未判/缺原件 | 没有完整原始数据，或未执行 | 不补想象，不判通过 |

本次关键原件的存在、大小、SHA-256 见：
`gui-dossier-assets/historical-artifact-manifest.json`。

### 1.3 当前证据缺口

- `.team/nodes/issue50-tester/` 在本次核查时不存在。历史消息中引用的全量 Go 原始 log/exit 文件无法原地再读。
- 已从 tester 当日会话中恢复三份 **当时写入 receipt 的内容**，放在 `gui-dossier-assets/RECOVERED-*.txt`；每份标明源会话、行号、时间、原路径。它们不是重新制造的原始执行日志。
- 生产手机“创建失败：结构化连接中断”及粗糙 UI 被驳回，由本次 leader 派单和 `a8cd4b9e5` 的 04 号契约记录支持；本次没有取得该手机原截图、HTTP 404 响应原件或当时生产二进制 hash。下文会分别标注“现场反馈”和“源码可解释的链条”，不伪造一份生产复现。
- `ACCEPTANCE-REPORT.md` 标题写着 real-device，但 device identity 是 `emulator-5554` / AVD。**本卷统一称模拟器，不冒称用户真机。**

---

## 2. 前期深度调研资产：RPC、SDK、ACP 与 ANSI 路线裁定

主资产：`.team/fixed-workflow/PI-ACP-AND-CLI-TO-GUI-RESEARCH-REPORT.md`，共 456 行，已通读。它的 Pi 证据版本是 **0.85.1**；本次实装 CLI 已是 **1.0.0**，必须区分历史版本与当前版本。

### 2.1 Pi RPC 的核心事实

**进程接口**：`pi --mode rpc`，长期子进程，stdin 写 command，stdout 连续读 response / session event / extension UI record。

**framing**：每记录一个 JSON object，严格 LF 分隔，可兼容 CRLF；U+2028/U+2029 是 JSON 字符串内容，不能当换行。stdout 只承载协议，诊断应走 stderr。必须连续消费 stdout，正确处理双向 backpressure；它不是 PTY，也不需要 ANSI parser。

**请求与生命周期**：

- 每 command 可带 `id`，response 回显；并发时按 id 而非到达顺序关联。
- `prompt` response 成功，只表示接受/排队/被 extension 处理，不代表 turn 已完成。
- `agent_end` 是一次底层 run 的结束；retry、compaction、steer/follow-up 可能继续。
- `agent_settled` 才表示当前 session 自动工作已真正收敛。订阅应先于 prompt，避免错过快速完成。
- 进程退出、非法 stdout、输入/输出关闭、取消及 deadline 都需要产品自己监管。

**准确事件名**：

| 层 | 事件与关键字段 | 能用于什么 |
|---|---|---|
| 消息外层 | `message_start.message`、`message_update.assistantMessageEvent`、`message_end.message` | 初始节点、增量、最终权威校正 |
| 文本块 | `text_start/text_delta/text_end`、`contentIndex` | 按块追加和终态替换 |
| 思考块 | `thinking_start/thinking_delta/thinking_end` | provider 真正提供时才展示 |
| 模型工具调用块 | `toolcall_start/toolcall_delta/toolcall_end` | id/name 与参数增量；**不是 `tool_call`** |
| 实际工具执行 | `tool_execution_start/update/end`、`toolCallId`、args、result、isError | 工具生命周期卡片 |
| 工作状态 | `agent_start/end/settled`、turn、queue、retry、compaction | 工作灯、队列、压缩与重试状态 |
| 持久化/元信息 | entries/tree、session_info、thinking_level | 历史、分支、名称与设置 |

**最重要的性能和 Reducer 结论**：研究报告 §2.2 已记录 0.85.1 的 JSON/RPC 去累积快照改动；当前 1.0.0 `docs/json.md` 亦明确：wire `message_update` **没有累积 `message` 字段，也没有 `partial` 快照**。正确模型是：

```text
message_start 初始化
→ 按 contentIndex / block id 累积 text/thinking/toolcall delta
→ text_end / thinking_end / toolcall_end 校正块
→ message_end.message 校正完整消息
```

Reducer 需要不可变状态、稳定 message/block/tool/request 身份，以及显式 seq/cursor 的去重、回放和缺口处理。不能把 SDK 的内存事件快照等同于 RPC wire；也不能把每个 update 替换成一整条不存在的 `message`。

**可用 commands**：prompt、steer、follow_up、abort、clear_queue；get_state/messages/entries/tree；new/switch/fork/clone；模型与 thinking 设置；compact/retry；bash；get_commands。研究资产覆盖了这些接口，第一轮产品并未全部覆盖。

**审批边界**：Pi RPC 没有独立通用 `approve_tool`。`extension_ui_request` 的 confirm/select/input/editor 是有 id 的双向交互；若要求每次工具审批，应通过 extension hook / 明确安全策略建立，不能把 ACP permission method 当成 Pi 已提供的命令。

### 2.2 SDK 为什么没有被选为第一轮 Go 服务端底座

`AgentSession` / `createAgentSession()` 是 Node/Bun 进程内 API，提供 subscribe、prompt/abort/steer、messages、工具/模型设置、session tree 与 runtime 管理。runtime 换 session 后须重绑订阅。

本项目 daemon 是 Go，因此 RPC 的进程隔离与语言无关边界更直接；这不意味着 SDK 不成熟，只是当前宿主不适合直接嵌入它。Android 不能直接调用该 Node SDK。

### 2.3 ACP 是什么，Pi 生态究竟支持到哪里

ACP 是 **Agent Client Protocol**，不是 MCP，也不是 ANSI 转换格式：

- JSON-RPC 2.0；initialize 协商版本/capabilities。
- session/new/load、session/prompt、session/update、session/cancel。
- tool call/update、权限请求 `session/request_permission`、plan、slash commands。
- filesystem/terminal delegation 是能力边界，不能假设所有 adapter 都有。
- 当时研究核对的稳定 transport 是 stdio；Streamable HTTP 仍是 draft/WIP。该生态时点结论未在本次重新联网核验。

历史 0.85.1 官方 Pi exports/dependencies/docs 未提供原生 ACP；社区 **`svkozak/pi-acp`** 被 ACP registry 收录，以 JSON-RPC stdio 启动并桥接 `pi --mode rpc`。研究时 README 标记 latest v0.0.33；不能称它是本次重新锁定的最新版本。

研究记录的 adapter 能力与限制：

- assistant chunk、tool call/update、部分 locations/edit diff、session 恢复映射可用。
- 无 ACP fs/terminal delegation；工具仍在 adapter 主机执行。
- 无独立 thought stream；Pi extension slash commands 未全面支持。
- MCP 参数接收/保存，不代表已经接到 Pi。
- 面向 Zed 的 MVP，有 breaking changes 和其他 client 兼容性风险。

因此选择 **Go 直连 Pi RPC 为本产品主线，ACP 留作互操作 adapter** 有事实依据；不是笼统宣布“ACP 无用”。

### 2.4 为什么否定 ANSI 抓屏：不是审美偏好，而是信息不存在

研究报告 §9 追加了既有 tmux TUI 零重启接管核验（Pi 0.85.1 / tmux 3.7c）：

1. `pipe-pane -O` / control-mode `%output` 得到原始 PTY 字节。tmux 有 pane/layout/session 管理通知，没有 Agent role、message id、thinking、tool args/result。
2. `send-keys` / `pipe-pane -I` 是键盘字节注入，不是消息 API；无可靠 message ack/并发输入仲裁。每 pane 的 pipe 槽也不是可随意叠加的隐形 RPC 通道。
3. Pi user/assistant UI 都使用 `OSC 133;A/B/C`，标记相同，assistant 有 tool call 时还可能不发；无法稳定区分 role。
4. `ESC[?2026h/l` 是 synchronized drawing 批次，不是语义消息边界。OSC 8、标题、图像、keyboard protocol 也没有所需语义。
5. 语义事件存在进程内 AgentSession；普通 TUI 未发现文档化的附着 IPC/control socket。不能把 Node/tmux 内部 pipe 认成 RPC API。
6. Session JSONL 在 message_end 后追加，首条 assistant 前还可延迟 flush，不是 token/thinking/tool 实时源；用户已反馈 tail 有延迟/不同步，不能用它补救实时 GUI。

**裁定范围必须精确**：可保留低延迟 PTY 镜像；不可承诺既有普通 TUI 不重启、不侵入时，还能无损得到结构化气泡、思考、工具卡及可靠输入回执。正则/ANSI parser 遇到宽度变化、滚屏、重排、工具输出、弹窗、retry/abort 会失去高置信度。新建 managed RPC worker 与接管存量 TUI 是两件事。

### 2.5 研究建议不等于第一轮完成

研究建议包括统一 v2 语义帧、seq、cursor、snapshot/replay、按需历史、工具输出 artifact/分页、多设备输入仲裁、审批与沙箱。它指出避免重复 redraw/reflow 的潜力，但**未给出本产品量测的固定降幅**。第一轮没有 seq/after_seq 缺口恢复；04 号契约的“带宽降低 80% 以上”和 60/120fps 是目标表述，不是已取得的结果。

---

## 3. 演进时间线与候选身份

时间以 Git 中 -06:00 为准；截图文件名为 UTC。两种时间不应直接按钟面混排。

| 阶段 / 提交 | 实际发生的事 | 状态边界 |
|---|---|---|
| 前期研究报告 | Pi RPC/SDK/ACP 及 TUI 无侵入边界分析 | 研究资产；0.85.1 时点 |
| 2026-09-19 目标书 | 提出 Pi RPC → 原生 ConversationScreen，独立 v2 路由、seq/replay | 目标，不是完成证据 |
| `6f23ab8ff` | Issue 50 双模目标/验收文档 | 产品实现前的契约 |
| `9f0b8ccc3`（10-04 12:48） | 24 文件、原生 GUI、DisplayMode、独立 `/gui`、guirpc worker | 第一轮最小实现；仍有失败 |
| `4818f966a`（12:52） | 被拒绝发送保留草稿；RPC 图片 payload 有界 | 正向修正，不解决架构兼容 |
| `6329ee70e`（12:55） | Go 测试 Unix socket 使用自有短路径 | 量具修复，不是产品性能通过 |
| `b53855b82`（13:00） | GUI transport 契约补齐、记录既有测试债 | 对应 app 候选 `f2082796` |
| `16f3a3e7e`（13:55） | Markdown 改用 native `palette.rowTitleText` | app `b0768d72`；浅色实见 |
| `f43d36db2`（14:56） | 已 ready 连接断线发 gui_error，保 GUI 与重试入口 | app `eda8e4991`；第一次断线修复 |
| `2c1440e5d`（15:36） | SessionScreen 按 ref 记忆 guiReadyOnce，跨失败重连仍保 GUI | 含测试链候选 `cfbac12a4` |
| `a8cd4b9e5`（20:24） | 04 号全主流 Agent / 高保真契约，否定第二条网络 Socket | 新目标；参数表存在错误，未实施 |
| 本轮 | 停止实现，编制本卷并核查 help、原件和来源 | 文档交付，不是下一轮开工 |

**源码 SHA 与 APK 候选 SHA 不能混用**：`16f3a3e7e` 与 `b0768d72`、`f43d36db2` 与 `eda8e4991` 是历史源码/交付谱系中的对应修正；`cfbac12a4` 含测试链，其 receipt 指明产品修复 `2c1440e5d`。判断截图必须使用当时 APK 的 hash，不能拿源码提交名代替包身份。

| APK 候选 | 大小 bytes | 实核 SHA-256 |
|---|---:|---|
| b0768d72 | 41,266,235 | `f1dbc81131fc049fbe927ab480dd3d47ec1427790da14132401bf44ee902bac9` |
| eda8e4991 | 41,266,235 | `5c7061007843a20f08fac70c1c6c79579ec45b2497759e8e5ae0de8367f5e3e0` |
| cfbac12a4 | 41,266,235 | `b1d519d63b6ec3ec905bfd4adefbdfd8d41ec474042d3a718a6a727bf1a1d73d` |

原件保留于 `.team/nodes/app-tester/tmp/issue50/`。初始 f208/c12ce 的 hash 见恢复的候选 receipt；本卷未把它计入上述三个实核 APK。

---

## 4. 第一轮服务端的真实实现：不是 ANSI 抓取，也不是普通 TUI 接管

源码锚点统一为 `dev/issue50-gui-session-mode` 冻结对象。

### 4.1 拓扑与启动

```text
Android GuiConnection（独立网络 WebSocket /gui）
  → Go api.handleGUI（认证、create/subscribe、命令筛选）
  → 私有 Unix JSONL socket（按 socket+pane ref）
  → tmux 新 pane 内的 agentmirrord gui-worker
  → Pi child：pi --mode rpc --name <name>
```

- `create_agent.go` 的 structured 分支只允许 Pi 且 guiDir 已配置；先校验 anchor ref、工作区 cwd、tmux socket/session。
- 通过现有 `bridge.CreateWindow` 在新 pane 启动 **同一 daemon executable** 的 `gui-worker <dir> <name>`，不是向旧 pane 注入 JSON。
- ref 为 `<tmux socket> + U+001F + <pane id>`。
- 创建等待 `WaitReady`，产品 deadline 5 秒；失败杀掉刚创建的 pane，返回可见 launch_failed。
- `cmd/agentmirrord/main.go` 提供该子命令入口；旧 PTY 创建链保留。

### 4.2 私有 Unix socket 与资源边界

`server/internal/guirpc/worker.go`：

- 从 TMUX/TMUX_PANE 取得当前自有 pane 身份；要求绝对 socket 路径与 `%` pane id。
- SocketPath 对完整 ref 做 SHA-256，取前 8 bytes hex 作为 `.sock` 文件名；它是身份映射，不是认证协议。
- mkdir 0700、socket chmod 0600；Available 用 Lstat 检查 socket，无需派生探测子进程。
- exec.CommandContext 启动 `pi --mode rpc --name`；stdin/stdout 真正 pipe，stderr 接 worker stderr。
- 子进程独立 process group；取消发组 SIGTERM，最终清理 SIGKILL 防残留，WaitDelay 3 秒；返回时关闭 listener、移除 socket。
- stdout 连续 Scanner、JSON 校验；**MaxRecord 32 MiB**。历史最多 **4 MiB 或 2000 records**，每 client queue **64**，写 deadline **5 秒**；慢 reader 被关闭，不无限堆积。
- 过大的合法 event 可 live 下发，但不永久保存；history_truncated 明示。new_session 成功清空历史。
- `WaitReady` 的短间隔检查仅用于有界启动，不是空闲常驻轮询。它看到有效 JSON/worker ready，**并非完成官方 Pi get_state 或模型请求的语义握手**。

### 4.3 pane 中的终端兼容到底是什么

worker 的 pane stdin 把一行普通文本转换为 RPC prompt；`/compact`、`/clear`、`/help` 特殊处理。终端输出是 worker 把 text delta/tool status 翻译为可读行。

所以新 managed pane 仍能用经典终端输入，但**不是启动了另一个完整 Pi TUI**，也不是从 Pi TUI 的屏幕反推结构化事件。GUI 用户输入通过私有 Unix socket 直接写 child stdin；不是 tmux send-keys 中塞 JSON。

### 4.4 API 真实覆盖与未覆盖

`server/internal/api/gui.go`：独立路由注册在 Server.Handler；auth/read/write deadline 各 5 秒，Unix dial 3 秒；接受 gui_create/gui_subscribe，未知或无 managed socket 的 ref 返回 gui_unavailable。合法 RPC command 限定为 prompt/abort/compact/new_session/get_state，图片仅从 uploadDir 内取，数量/大小有界。

worker 对尚不支持的 extension confirm/select/input/editor **主动回 cancelled**；客户端显示“扩展交互暂不支持，已安全取消”。这是失败可见且不挂住的资产，**不是审批 UI 已实现**。

第一轮 replay 是有界原始 records 的全段重发：**没有单调 seq、after_seq、ack、持久化 replay log、分页历史或精确缺口恢复**。不能沿用早期目标书的名字，就声称已经实现 v2 断点续传。

---

## 5. 生产 `/gui` 404 与手机创建失败：完整链条及证据边界

### 5.1 现场事实与源码解释必须分开

**现场反馈**：leader 本次派单明确要求归档“独立 `/gui` 在生产 404 挂死、手机出现创建失败：结构化连接中断”；04 号契约 §四.1 亦写明生产 9900 当时仅开放 `/ws`，第二路由失败。

**源码证实的链条**：

1. Workspace GUI 创建分支调用 `WorkspaceViewModel.createGuiAgent`，不是旧的 `/ws` create request。
2. `GuiConnection.start()` 用 `config.url.substringBeforeLast('/') + "/gui"` 构造 URL，并调用 transportFactory **创建另一条 WebSocket**。
3. 第一轮候选 Server.Handler 确实新增了 `/gui`，但生产只有既有路由时，客户端请求新 URL 必然得不到该 handler；404 不能完成 WebSocket Upgrade。
4. WebSocket onFailure 被翻译为“结构化连接中断，请重新连接”。建会话的临时 connection 还未 ready，将其转为 gui_unavailable。
5. GuiConnection.create 回调从 event 的 reason/error 取失败信息；ViewModel 把它写入 CreateAgentUiState.error。
6. `ui/screens/SessionListScreen.kt` 创建面板渲染 `"创建失败：$it"`，因此出现手机所报创建失败文案（源码文案还包含“，请重新连接”后缀）。
7. 请求尚未走到生产创建 handler，不能将此归为 Pi 模型/API 生成失败；也不能把客户端单独构建绿当成服务端同源发布已完成。

### 5.2 “挂死”的技术口径

GUI 初始请求有 **10 秒** startup timeout，已提交命令有 **15 秒**结果 deadline。源码不是无界等待；现场“挂死”可以是用户感知的无法创建/连接失败。本次没有该次生产耗时原件，不能替它编造无限循环或新的 ANR 栈。

**404/创建失败与下一节 21,209ms ANR 是不同事件**：前者发生在新网络通道与部署不兼容；后者是已就绪 GUI 断线触发破坏性路由切换后，终端主线程绘制阻塞。

### 5.3 教训与后续契约裁定

- “使用相同 host/token”不等于“复用同一连接”；独立 GuiConnection 没有自动继承现有连接的选路、foreground 恢复与能力协商生命周期。
- 增加服务端路由后，客户端/服务端不同步上线、生产 capability 未证明，用户仍会直接撞失败。
- 04 号契约改为**复用现有 `/ws`，协商 `conversation_v1`，不建第二条网络 WebSocket**，参照 notifications_v1 装饰器。
- 这只是后续正确边界，**第一轮源码仍是 `/gui`，本次没有完成迁移**。
- **私有本机 Unix JSONL socket 与“第二条手机网络 WebSocket”不是同一层。** 禁止后者不等于历史 worker IPC 资产全部作废。

---

## 6. UI 被用户驳回：功能部件存在，不代表产品体验成立

### 6.1 可确认事实

- 本次派单记录用户严厉驳回粗糙 GUI，具体是刺眼红白错误面板、方块按钮等。用户审美判断不能被测试绿抵销。
- b076 的真实 SessionScreen 截图确实能看到基础消息卡/工具卡，但底部是有边框的矩形输入框、emoji ⚡/📎、简单文字动作；cfbac 的断线界面直接大段 error 色文案。源码 `GuiSessionView` / `GuiInputBar` 亦对应基础 Material 控件。
- 本次没有取得“用户手机红白弹窗”的独立原图，因此不将模拟器断线红字截图冒充那张真机图，也不捏造逐字用户对话。
- 04 号契约追加高保真液态玻璃/圆角/排印/动效要求，说明第一轮视觉交付不是最终可接受样式。**该契约存在，不代表这些效果已实现或已获用户认可。**

### 6.2 具体失败教训

1. 将“气泡有了、工具卡有了、能输入”当作“Codex App 风格完成”，是交付口径错位。
2. 混用 terminal 色板与 native surface 造成浅色白底白字，是功能可见性缺陷，不仅是美观问题。
3. 把基础控件硬摆在原生页面、直接显示大段失败提示，没有与既有 Dock/主题系统统一，就会出现生硬边框和刺眼错误区。
4. 审美验收与路由/状态测试应分账：好看不能覆盖断线风险，测试绿也不能覆盖用户明确驳回。

本卷只保存事实与有效色板修正，不借机开展新 UI 设计或调用视觉模型。

---

## 7. ANR 案：已 ready 断线 → 误判 unavailable → 冷切 TUI → 字形绘制阻塞

### 7.1 现场与原件身份

- AVD `agentmirror_test_b` / `emulator-5554`，1080×2400、density 420。
- b076 APK hash：`f1dbc81131fc049fbe927ab480dd3d47ec1427790da14132401bf44ee902bac9`。
- 真实路径：MainActivity → workspace → GUI 会话；不是 MobileSessionFixtureActivity。
- 相册菜单已出现；点击“从相册选择”原始坐标 `(160,2145)` 后出现系统 `corral isn't responding`。
- Android 原因：MotionEvent 等待 **21,209ms**，Input dispatching timed out；进程 `dev.agentmirror.app`，pid 7746，Activity MainActivity，设备本地时间 2026-10-04 14:22:37。
- 原件：`20261004T2022_photo-picker-direct.png`、`...photo-picker-wait.png`、`photo-picker-anr-log.txt`、`anr-stack-identity.txt`、`anr-main-stack.txt`。

### 7.2 栈是什么，不是什么

主线程 Runnable，核心路径：

```text
CharWidth.isWide / CharWidth.of
→ GlyphFallbackPolicy.resolve
→ GlyphRunBuilder.build
→ TermSurfaceView.drawGlyphRuns / drawTextRuns / drawLine
→ drawFrame / onDraw
→ Android View / Compose 绘制
```

ANR 截图背景已经是黑色 TUI，带“结构化通道不可用；旧会话请使用 TUI”和残留相册菜单。

因此取证支持：GUI 断线后发生 TUI 绘制，主线程在该路径忙于处理字形，阻塞输入事件。**不是 Photo Picker Binder 调用栈，也不是由文件选择回调同步 I/O 已被证明阻塞。** 当时初始调查曾怀疑点击回调同步 I/O，但系统栈改变了归因；必须留下这次被证据修正的过程。

同时，单份栈不能证明 CharWidth 本身永远慢、某字符必然死循环或所有终端 ANR 都是同因。此次修复是消除不应发生的冷切，不是完成了通用终端字体性能优化。

### 7.3 原始守卫如何出错

- 初版 `GuiConnection.fail()` 将连接失败归为 gui_unavailable，未区分当前会话已成功 ready 与从未具备 GUI。
- SessionScreen 接到 gui_unavailable 设置 `guiFallback=true`；`useGui = displayMode==GUI && !guiFallback` 立即变 false。
- 原生分支销毁/替换为 SessionScreenScaffold + AndroidView(TermSurfaceView)。这发生在附件交互仍进行的时刻。
- 网络存活性被误用为 session capability，导致无用户选择的破坏性路由切换，重新进入昂贵 TUI draw 路径。

### 7.4 第一层修复：f43 / eda

`f43d36db2`：

- GuiConnection 在收到 gui_ready 后记录 connection-local ready。
- fail 按 ready 分成 gui_error / gui_unavailable，并标记 disconnected。
- SessionScreen 在 gui_error 时只修改 connected/error/running，保持原生分支。
- GuiSessionView 显示“原生对话已断开”、重新连接、返回 TUI。
- Retry 的 attempt 改变触发新 connection；非首轮连接保留 conversation，只清 error。
- 独立 ready-disconnect 红探针 `68cec798d` 历史上红转绿；**本卷不复述为本次重跑**。

模拟器 eda：Photo Picker 打开（`PhotoPickerActivity`）、无该次 ANR；第一次端点 down 仍保 GUI，证据 `20261004T2120-photo-direct.png`、`20261004T212203Z_gui-eda2-after-picker.png`。

**此时不能结案全部防线**：同候选 retry-down 仍冷切，下一节记录第二个缺陷。

---

## 8. 重连再次冷切：连接对象生命周期不能抹掉会话能力

### 8.1 复现与原因

在 eda 已 ready 的 GUI 下停止端点，第一次断线保 GUI；端点仍 down 时点击重新连接，等待 6 秒，`20261004T212259Z_eda-reconnect-down.png` 再次出现黑色 TUI + Esc/Tab/方向键/Ctrl-C 行。

原因：新 GuiConnection 的 local ready 默认 false，还没收到新 gui_ready 就失败，又发 gui_unavailable；上层只信本次 connection，遗忘同一 session 已被证明具备结构化能力。

**网络不可达不能撤销同一会话已证明的能力；“重连”不是“选择 TUI”。**

### 8.2 第二层修复：2c / cfb

`2c1440e5d` 在 SessionScreen 增加：

```text
guiReadyOnce by remember(viewModel.ref)
gui_ready → guiReadyOnce=true
收到 gui_unavailable：
  guiReadyOnce=true  → disconnected GUI error，running=false，不置 fallback
  guiReadyOnce=false → 初次无结构化能力，允许 legacy TUI fallback
用户点“返回 TUI” → 显式 fallback
```

这是会话级分类防线，不是仅靠一次 WebSocket 的布尔值。其实际持久范围是当前 Compose 页面按 ref 的 remember；**未写成跨进程/冷启持久 capability store**，不应扩大承诺。

### 8.3 最终原件支持的结论

同 cfbac APK：

- `20261005T005920Z_cfb-error-down.png`：断线 GUI 错误态。
- `20261005T005958Z_cfb-reconnect-fail-1.png`、`010005Z_...-2.png`、`010013Z_...-3.png`：三次失败重连仍为原生错误态，无黑色 TUI 冷切。
- `20261005T010158Z_cfb-reconnect-success.png`：端点恢复后回到 GUI。
- `20261005T014218Z_cfb-real-oldpty.png` + XML：相同 APK、GUI 偏好开启、MainActivity → workspace → 自有普通 pane `%0`，无 guirpc socket、从未 ready，动态旧 PTY fallback 可见。

可判定的资产是“**首次没有结构化能力可 fallback，已证明能力后失败重连不自动冷切，用户仍可显式回 TUI**”。这不是已验证精确历史回放、去重、非空消息保留、多设备重连或所有前后台组合；最终失败重连截图还显示空对话提示，不能拿它证明海量历史无损恢复。

---

## 9. 静态夹具误当动态验收，以及合成 Pi 掩盖 wire 差异

### 9.1 MobileSessionFixtureActivity 到底画了什么

源码：`app/src/debug/java/dev/agentmirror/app/session/MobileSessionFixtureActivity.kt`。

- 直接调 SessionScreenScaffold，不进入 SessionScreen 的 GUI/TUI capability 守卫。
- terminalCanvas 是 **Column + Text**，写死 `agent@mobile ~/workspace` 和 `$ git status...working tree clean`。
- key callback 只在本地 Text 显示 lastKeyToken；send 只清空本地 value。
- `onPickAttachment = {}`，根本不调真实相册入口。
- 无真实 TermSurfaceView / 网络订阅 / guirpc worker / 动态回退。

历史 `20261005T013235Z_cfb-old-pty-fixture.png` 只能证明经典组件存在。它甚至不是“真实 TermSurfaceView 截图”，这一点已被开发核对源码后纠正。将它判成“同候选旧 PTY fallback PASS”会漏掉实际守卫错误。

此后改用相同 cfb APK 的 MainActivity 动态路径，并补 `014218Z_cfb-real-oldpty`；这才是本轮旧 PTY 初次 fallback 的有效证据。不能把初始候选 `194215Z_session-open` 偷换成最终候选证据。

### 9.2 第二种装置边界：真实 MainActivity，仍不等于真实官方 Pi

本次从 tester 历史会话恢复到两个写入记录：

- `.team/nodes/issue50-tester/bin/pi` 的 shell 合成 agent。
- 后续 `.team/nodes/issue50-tester/tmp/pi.go` 的合成 agent。

它们接受 JSONL command、生成固定 user/assistant、bash ToolCard success；截图正文亦直接写着 `GUI fixture prompt`、`The isolated GUI fixture is ready.`。相关安全摘录：`gui-dossier-assets/RECOVERED-SYNTHETIC-PI-SCOPE.txt`。

这说明**确实走了真实 App/MainActivity、服务端、tmux worker/IPC 的装置链路，但模型事件源是合成 Pi**。它不是用户现存普通 Pi 会话，也没有证明真实 provider 的 tool/thinking/lifecycle 全集。

### 9.3 本次核出的重要协议不一致

- 合成 agent 与候选测试资源 `app/src/test/resources/issue50/pi-rpc-stream.jsonl` 的 message_update：

```json
{"type":"message_update","message":{"role":"assistant","content":"The isolated GUI fixture is ready.","timestamp":"2026-10-04T13:00:00Z"}}
```

- 官方当前 Pi JSON/RPC 示例（与历史研究的 delta-only 结论一致）：

```json
{"type":"message_update","usage":{},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hello"}}
```

- 第一轮 `ConversationState.reduce` 对 start/update/end 共用分支，先执行 `event["message"] as? JsonObject ?: return this`；注释还写“message_update carries the current full message alongside the delta”。它没有按 assistantMessageEvent/contentIndex 累积 text/thinking/toolcall delta。

**源码层的直接结论**：面对合法的 delta-only message_update，这个分支会原样返回状态；message_end 的最终全文仍可能显示，但这不等于流式显示正常。这里没有再次运行官方 Pi 模型请求，因此不冒称新测得某毫秒数或某真机故障；这是可复核的 schema/源码不匹配。

**历史“全绿”的正确解释**：部分工具状态、模式、UI 路由和断线防线通过了其已执行装置；“真实 Pi RPC 逐 token 渲染完备”并没有因此被证明。合成数据不能只模拟实现喜欢的格式，然后拿绿去宣布官方协议已经打通。

---

## 10. 积极资产清单：保留价值与适用边界

### 10.1 ConversationState / ToolCallState

`session/gui/ConversationState.kt` 的已存在接口：

- ConversationItem 统一 id；ChatMessage 为 role/text；ToolCallState 为 id/name/arguments/output/status/expanded。
- ConversationState 保存 items、activeMessageId、running、error、historyTruncated。
- agent_start 开 running，agent_settled 关闭；new_session 成功重置；错误可见。
- 工具按 **toolCallId** 更新，不新建一串重复卡；toolResult 可补齐终态。
- RUNNING / SUCCESS / FAILED，工具结果详情可折叠；expanded 在后续更新中保留。
- items 最多 500；单展示 payload 最多 100,000 chars，截断有提示；服务端 replay 另有限额。
- GuiConversationList 使用稳定 item id 的 LazyColumn，ToolCallCard 可展开 args/output。

这些是可复用的状态/组件资产，历史工具成功/展开截图与独立用例有支持；但它还不是研究中完整的 Message/Block/Thinking/Interaction Reducer。消息身份基于 role/timestamp/activeMessageId，不是已经完成跨客户端稳定 messageId/blockId 契约；seq 去重也未实现。

### 10.2 DisplayModeStore 与输入隔离

`DisplayMode.kt`：DisplayMode.TUI/GUI、SharedPreferencesDisplayModeStore，默认 TUI；非法 preference 回 TUI；save 立即写入；observe 注册偏好监听并可解除。设置更改即时生效，重启后保留偏好。

历史模式红测从符号 unresolved 转绿；模拟器 GUI 选择、重启 persistence 图片：`191001Z_settings-gui-selected`、`191121Z_persistence-check`。报告说明这是初始候选验证，后续沿用相同 wiring，不把它伪装成最终 cfb 全场景重跑。

SessionScreen 的 `structuredInput=useGui` 防止 GUI 草稿逐键透传 PTY；图片复用上传但不向 TUI 发 attach preview。明确结构化输入与 terminal keystroke 是不同路径，这个隔离原则有效。4818 修正拒绝时保留草稿及图片大小上限；**未因此验证所有拍照/图片发送组合**。

SlashCompletion 插入候选文本，不自动提交、不跨 provider 猜命令；GUI 不呈现 Esc/Tab 终端功能键。部分 slash UI、附件菜单在 IME visible/hidden 的真实路由截图成立。

### 10.3 Markdown 与 native card 同源色板：有效且最小

`16f3a3e7e` 在 `GuiConversationList.kt` 只改一处：

```text
MarkdownText(message.text, Color(terminal.defaultFg))
→ MarkdownText(message.text, palette.rowTitleText)
```

原因：assistant 卡片底色来自 native palette，terminal.defaultFg 是终端色板前景，可为白色；在浅色 native 白卡上构成白底白字。应让文字和背景同源，而不是改全局终端色板去补原生卡片。

`20261004T201747Z_b076-light-agent.png` 实见：Agent 正文为可读深色，浅色 native 白卡；`201710Z_b076-gui50b-hello` / `201838Z_b076-tool-expanded` 支持消息与工具卡展示。

这是有效修复，不能因为后续架构返工抹掉；也不能由一张浅色图推广为所有主题、代码高亮、链接/selection 色板全部通过。

### 10.4 会话能力与连接存活分离

f43 的连接级 ready 分类 + 2c 的页面/ref 级 guiReadyOnce，是这轮最重要的状态防线资产。它避免把网络错误当能力否定、避免操作中无授权切换视图；具体边界与截图见 §7–8。

### 10.5 资产盘点表

| 资产 | 有何证据 | 应保留 | 不应宣传 |
|---|---|---|---|
| managed Pi worker / Unix JSONL IPC | 源码、fake child 进程测试、装置链路 | 进程归属、清理、有界 queue/history | 所有真实 Pi provider 已验 |
| ToolCallState / ToolCallCard | 工具生命周期用例、真实 App 合成数据截图 | toolCallId 与展开态 | 全工具 partial 合并语义通用 |
| DisplayModeStore | 独立模式测试、设置/重启图 | 默认、合法值、监听、持久化 | 所有进程恢复路线已验 |
| native Markdown ink | 一行 diff + 浅色实图 | 色板同源配对 | 视觉设计已获用户认可 |
| ready / guiReadyOnce | 红探针、断线/retry 原件 | 能力与存活分离 | capability 已跨冷启持久化 |
| 静态 MobileSessionFixture | 固定控件截图 | 组件快照/键盘布局用途 | 真实终端或动态 fallback |
| `/gui` 网络路径 | 第一轮实现与生产失败记录 | 历史教训和诊断素材 | 正确的后续网络架构 |

---

## 11. 全主流 Agent 真实参数核查：当前支持、未证实与错误项

### 11.1 核查身份和方法

本次 developer 只运行 `--version`、`--help`、`codex exec --help`、`codex app-server --help`，共 12 条，都退出 0。未发模型 prompt、未联网启动会话、未读账号配置/凭据、未创建 Agent 席位。

证据：`gui-dossier-assets/cli-help-receipts.json` 与对应 `*-help.txt` / `*-version.txt`。Claude/Cursor 来自本机 proxy wrapper；这只是 executable 定位，不代表账户授权或网络能力已验证。

tester 的独立核查：`.team/nodes/tester/issue50-native-gui-cli-help-20260918/`，`MANIFEST_v2.txt`、`STRUCTURED_FLAGS_MANIFEST.txt` 及各 stdout/stderr。目录名的 20260918 不是当前采样时间；manifest 的 epoch 为 1791168020/1791168071，实际是本轮 2026-10-05 UTC 核查。

### 11.2 正面支持项

| Agent / 实装版本 | help 明示的真实入口 | 能确认 | 仍未知或未判 |
|---|---|---|---|
| **Pi 1.0.0** | `pi --mode rpc`；`--mode json` | RPC 双向长期 JSONL，json 一次 invocation 事件流；官方文档可核 schema | 官方真实模型 prompt / thinking / tool / abort / replay 的本产品全链路未完成 |
| **Claude Code 2.1.282** | `-p --input-format stream-json --output-format stream-json`；`--include-partial-messages`；`--replay-user-messages` | print 模式 stream input/output；partial chunk 与 stdin 用户消息回显有明确 flag | permission/control/abort、事件 schema 和长期 session lifecycle 尚未实测；不是证明原生 ACP |
| **Codex CLI 0.158.0** | `codex exec --json`；`codex app-server --stdio` 或 `--listen stdio://` | exec 输出 JSONL events；app-server 是真正存在的子命令，help 标 **experimental**，另列 unix/ws transports | app-server 初始化、通知、审批、恢复、客户端映射未执行；不能将 exec 单次输出等同于长期双向 RPC |
| **Cursor Agent 2026.10.01-e373342** | `agent --print --output-format stream-json`；`--stream-partial-output` | print 模式的 stream-json，partial 文本 deltas；resume/continue 入口可见 | help 未给 stream-json 输入控制协议；双向长期控制、cancel、tool schema 未证实 |
| **Grok 1.0.46 (2765805b9442)** | `--output-format streaming-json`；另有 `streaming-messages-json` 与 `--include-partial-messages` | help 定义 streaming-json 为 NDJSON **ACP session update** native format；后一种为 Anthropic Messages wire；partial flag 只作用于后一种 | ACP update 输出不等于完整 ACP initialize/prompt/permission 双向 server；Grok agent headless 生命周期尚未实测 |

Grok help 同时给 `-p/--single <PROMPT>` / prompt-file/json 的单次 headless 输入。不要把 plain/json/streaming-json 混成同一格式；不要把 `--json-schema` 的最终响应约束当成实时事件协议。Claude/Codex 的 JSON schema flags 同理。

### 11.3 04 号契约参数表哪些不可靠

`a8cd4b9e5:docs/contracts/04-native-gui-conversation-spec.md` §三记录了以下推荐参数。它有“权威任务书”标题，但这些具体技术事实仍需验证：

| 文档写法 | 本次证据 | 正确结论 |
|---|---|---|
| Claude `--acp` | 正常 help 不列；`claude --acp --help` 返回通用 help rc0 | **未证实**；help 可提前退出，rc0 不是未知 flag 获支持 |
| `claude-code --json-rpc` | 本机 binary 不存在，probe rc127 | 此环境不可执行；不能作为生产命令 |
| Codex `--stdio-rpc` | probe rc2，明确 parser reject | 本版本不支持该参数 |
| Codex `app-mode` | 正常 Commands 不列；`app-mode --help` 返回通用 help rc0 | 未证实该子命令，不能判可用；应核已存在 app-server |
| Cursor `--acp-mode` / `--headless-json` | 正常 help 不列，带 --help probe 仍 rc0 | 未证实，不能替代 print stream-json |
| Grok `--mode rpc` / `--jsonl` | 两个 probe 都 rc2，明确 reject | 本版本不支持；实际 flag 是 output-format |
| Pi 的 `tool_call/message_delta` 事件举例 | 官方外层/嵌套事件并非此命名 | 应使用 message_update + text_delta、toolcall_* / tool_execution_*，不能按错误字符串实现 |

**本卷只纠正事实，不修改该契约，也不自动推进新实现。** 多厂商输出可能都是 JSON，却不是同一种 schema；要各自 adapter、版本/capability 和 lifecycle 映射。给任意启动命令加一个 flag，也不自动让服务端捕获其 stdin/stdout；进程归属、接线与握手必须真实存在。

---

## 12. 历史测试账：保留红、绿与不可判，不用“总 PASS”抹平差异

以下是历史证据核对，不是本次重新运行 Gradle/Go：

| 检查 | 当时结果 | 事实边界 / 来源 |
|---|---|---|
| DisplayMode 红测 | 基线 unresolved → 候选绿 | tester 历史消息 + 候选测试 Git 对象 |
| Issue50 专项 | 初始候选 23s；cfb 19s；ready probe 18s，禁缓存绿 | 恢复候选/最终 receipt；不是全 App unit 绿 |
| 初始完整 App unit | 1052 tests、26 失败，历史报告称既有主题/视觉/性能债 | 本次未独立重验每项归属，不直接赦免或升级为基线通过 |
| 最初 Go worker 用例 | WaitReady 3s deadline，socket 未就绪 | 独立测试路径过长，6329 改自有短 Unix 路径；不延长断言掩盖 |
| 后续并行 Go 全量 | bridge/guirpc 时序失败，exit1 | receipt 保留；不得删除红或称并行全绿 |
| cfb 定向 Go race | guirpc PASS 1.711s | 历史 receipt；不能代替全量 |
| cfb 串行 Go 全量 | `go test -p 1 -count=1 ./...` 全绿 | 原 3s 断言未弱化；原 log/exit 本次缺失，仅恢复 receipt 与消息 |
| cfb 唯一 assembleDebug | `--rerun-tasks`，53s，包 hash 已实核 | 构建身份成立，不等于生产兼容/视觉认可 |
| b076 ANR | FAIL，21,209ms | 系统栈、截图、日志原件 |
| eda 初次断线 / PhotoPicker | PASS | 该场景原件；eda retry-down 同时 FAIL |
| cfb 失败重连三次 / 端点恢复 | PASS | 同候选真实 MainActivity 装置原件 |
| cfb 旧 PTY 初次 fallback | 静态 fixture 不判；补真实路径后 PASS | 014218 图/XML，不用旧候选图替代 |
| 官方 Pi delta-only wire | 第一轮 Reducer 源码不匹配 | 本次静态核出；合成 Pi 绿不能覆盖 |
| 性能 | 没有合格量测通过证据 | 初始 receipt 明确不声称 envcheck 通过；不编造节流倍数、首 delta 50ms 或帧率 |
| 用户真机视觉 / 生产创建 | 被驳回 / 创建失败现场记录 | 不因模拟器部分绿宣布用户已认可 |

**Unix 路径失败是量具问题**：`t.TempDir + filepath.Rel` 仍可超过 sockaddr_un；6329 用项目内自有短 relative scratch，明确清理所有权。它不是“把超时调大就绿”，也不是产品逻辑优化。

**并行失败归因有边界**：串行同测试通过支持资源/调度敏感的解释，但没有独立负载采样证明唯一根因。保留失败，不把猜测写成确诊。

---

## 13. 证据索引与复核入口

### 13.1 研究 / 契约 / Git 源码

- R1：`.team/fixed-workflow/PI-ACP-AND-CLI-TO-GUI-RESEARCH-REPORT.md`，§2 RPC，§4 架构/Reducer，§9 普通 TUI 接管边界，§8 官方 URL/本机来源。
- R2：`.team/fixed-workflow/pi-rpc-conversation-gui-goal.md`，早期独立路由与 seq/replay 目标。
- R3：`dev/issue50-gui-session-mode:docs/issue50-gui-session-mode.md`，第一轮真实路径、资源与测试债。
- R4：`a8cd4b9e5:docs/contracts/04-native-gui-conversation-spec.md`，后续单 `/ws`、视觉目标与需纠正的参数表。
- C1：该历史分支 `server/internal/guirpc/worker.go` / `worker_test.go`。
- C2：`server/internal/api/gui.go` / `create_agent.go` / `server.go` / `cmd/agentmirrord/main.go`。
- C3：`app/src/main/java/dev/agentmirror/app/session/gui/` 的 ConversationState、DisplayMode、GuiConnection、GuiSessionView、GuiConversationList、GuiInputBar、ToolCallCard。
- C4：SessionScreen 的 `useGui/guiFallback/guiReadyOnce`，WorkspaceViewModel 的 createGuiAgent，SessionListScreen 创建错误面板。
- C5：`app/src/debug/java/dev/agentmirror/app/session/MobileSessionFixtureActivity.kt` 的硬编码假终端。
- C6：`cfbac12a4` 的 Issue50 七个测试文件与 `app/src/test/resources/issue50/pi-rpc-stream.jsonl`。

使用 `git show <冻结ref>:<path>` 可复核，不需要切主工作树；源码图谱不依赖当前 main 已部署。

### 13.2 Android 原件

统一目录 `.team/nodes/app-tester/tmp/issue50/`：

- A1：`ACCEPTANCE-REPORT.md`；设备/APK身份与逐候选结果。
- A2：`anr-stack-identity.txt`、`anr-main-stack.txt`、`photo-picker-anr-log.txt`；21,209ms 与 TermSurfaceView 主栈。
- A3：`20261004T201747Z_b076-light-agent.png`；浅色 Markdown。
- A4：`20261004T2022_photo-picker-direct.png` / wait；ANR。
- A5：`20261004T212259Z_eda-reconnect-down.png`；第一层修复后仍冷切。
- A6：`20261005T005920Z_cfb-error-down.png`；`005958Z`、`010005Z`、`010013Z` 连续失败。
- A7：`20261005T010158Z_cfb-reconnect-success.png`；恢复。
- A8：`20261005T013235Z_cfb-old-pty-fixture.png`；**静态，仅作反例**。
- A9：`20261005T014218Z_cfb-real-oldpty.png` + XML；当前候选真实动态 fallback。

本次实际打开核看的关键图片是 A3/A4/A5/A6 第三次失败/A9；不是只相信报告标题。未打开含配对信息的截图或读取用户密钥。

### 13.3 恢复与本轮采样资产

本卷旁目录 `gui-dossier-assets/`：

- `historical-artifact-manifest.json`：13 份关键非配对原件实核，含三个 APK hash。
- `RECOVERED-ISSUE50-CANDIDATE-RECEIPT.txt`：原始 f208 测试债/不声称性能通过。
- `RECOVERED-GO-FULL-RETEST-RECEIPT.txt`：并行红、race 绿、串行全量绿。
- `RECOVERED-FINAL-CFBAC-RECEIPT.txt`：cfb 专项与 APK 身份。
- `RECOVERED-SYNTHETIC-PI-SCOPE.txt`：合成 Pi 事件来源与 message_update 快照形状。
- `cli-help-receipts.json`、12 个 help/version 文本：本轮真实 CLI 采样，exit0；不含模型调用。

恢复来源：
`.team/runtime/pi/regressions-20260907/tester/sessions/2026-10-04T18-14-20-928Z_08e934cb-81f8-41a4-9d05-105d9fc77919.jsonl`，receipt 原写入行 903、2645、2659；合成 Pi 原写入行 958、1104。只摘取非凭据内容，未复制 endpoint handoff/账号配置。

tester 当前独立参数核查路径及 manifests 见 §11；不存在的老原始 log 明确留缺口，不将恢复 receipt 冒充退出码文件。

### 13.4 官方来源与时点

- Pi 当前本机：`/opt/homebrew/lib/node_modules/@earendil-works/pi-coding-agent/docs/rpc.md`、`json.md`、`rpc-extension-ui.md`、`sdk.md`；当前 CLI version 1.0.0。
- 历史研究 Pi package/types 路径和 ACP 官方站点链接完整保留在 R1 §8；本次未重新宣称 registry/社区 adapter 最新版本。
- 各厂商当前参数事实来自本机 help；不借宣传文章或 04 契约替代可执行接口。

---

## 14. 封卷结论与禁止重犯的事项

这轮并非“一切失败”，也不是“已经全部成功”。**保留** worker 归属与 IPC、有界队列、ToolCallState、模式持久化、native 色板配对、ready 防线和真实路由取证方法；**否定**第二条生产网络 Socket、存活性撤销 capability、静态夹具替代动态验收、合成快照替代官方 delta wire、未核参数表和无量测性能承诺。

下一轮如获授权，不能跳过以下事实边界；本卷不据此自行开工：

1. 先核实装版本、真实 stdout schema 和 lifecycle，再写 adapter。
2. 生产连接能力/同源发布必须可证明；不因本地新增 handler 就假设手机可用。
3. 已证明会话能力与当前网络状态分离；失败重连不能隐含选择 TUI。
4. UI 必须同时满足用户审美与可见性，不用专项绿覆盖用户驳回。
5. 动态守卫必须用同候选真实入口；合成数据必须标明覆盖与缺失。
6. delta-only wire、thinking、tool、abort、权限、seq/replay 各自有证据，不能揉成一个“RPC 已通”。
7. 性能按可执行环境闸/同批基线门取证；无量测就是未判，不能写固定节流或 60/120fps 已实现。

**本轮交付范围仅为本卷与所列证据资产；产品代码、用户现场、主分支和生产部署均保持不变。**
