# Opus 5.5 原生 GUI 会话模式基线与资产留存案卷（Commit 8cb4caa4d）

> **基线标识**：`baseline-opus-issue50-gui-8cb4caa`  
> **所属分支**：`feat/issue50-native-gui-session-mode`  
> **权威 Commit**：`8cb4caa4d`（基于黄金基线 `194a4ca64`，PR #53）  
> **Git 远端同步**：已全量推送至 `app` 远端仓库及 Tag `baseline-opus-issue50-gui-8cb4caa`  
> **建立时间**：2026-10-05

---

## 一、 基线建立背景与概况

Claude Opus 5.5 接受用户委托，全权主导了 Issue #50 原生 GUI 会话模式的端到端重构。在连续高强度工作近两小时后触发了官方会话限额（Session limit）。
**Opus 5.5 已经完成了全部核心代码开发、协议投影重构、OkLab 30套主题全色阶对比度算法、Compose高保真视觉组件装配、服务端Go全量测试、以及真机模拟器A/B性能基准测试。**

为了将 Opus 5.5 的全部成果100%完好保留，并作为未来继续推进、测试或由 Codex（Sol/Luna）接手微调的坚固基底，特设立此永久基线。

---

## 二、 代码提交历史与核心资产清单

### 1. 提交链路（Commit Chain）
```text
8cb4caa4d feat(app): high-fidelity Compose conversation surface and visual design by Opus 5.5
7401dcb58 feat(app): ConversationTransport decorator on the persistent connection
667434c12 feat(app): delta-only Pi conversation reducer with stable block keys
f828236f2 feat(serve): conversation_v1 over the existing /ws with a managed Pi RPC worker
a6b3f7b9e docs: integrate historical attempts dossier and Codex/Muse visual reference into branch
a8cd4b9e5 docs: establish authoritative contract 04 for native GUI conversation mode across mainstream agents
194a4ca64 docs: finalize handoff and teardown checklist for milestone baseline b034fff (黄金基线)
```

### 2. 核心源码资产（全量编译通过）
- **客户端核心组件**（`app/src/main/java/dev/agentmirror/app/conversation/`）：
  - `ConversationScreen.kt`（37KB）：原生对话主视图，采用 `reverse-layout` 锚定机制（消除逐 Token 重绘与滚动调用抖动），支持 Markdown 排版、思考（Reasoning）块展开、状态微光。
  - `ConversationDock.kt`（17KB）：悬浮式毛玻璃胶囊底栏，去除 Esc/Tab 等物理键，左侧精致 ⚡ 与 📎，输入 `/` 平滑升起液态玻璃命令补全浮层。
  - `ConversationItems.kt`（25KB）：具备弹簧物理阻尼（Spring Physics）的可折叠 `ToolCallCard`，展示命令摘要、耗时指示灯与格式化输出。
  - `ConversationPalette.kt`（9.6KB）：基于 OkLab 的高保真色彩推导引擎，自动化数学验证全量 30 套终端主题在深浅模式下的 900 组文字/卡片对比度，正文严格 $\ge 7:1$，次要文字严格 $\ge 4.5:1$。
  - `MarkdownText.kt`（15KB）：原生 Compose Markdown 渲染器，支持代码块语法高亮与微质感容器。
  - `ConversationIcons.kt`（7.8KB）：定制矢量图标库。
  - `ConversationCenter.kt` & `ConversationState.kt`：挂载在单一 `/ws` 核心连接上的协议装饰器与纯增量 Reducer 状态机。
- **系统接线与无损分流**：
  - `MainActivity.kt`：在原生主路由注入 `conversationCreateRequest`，当 provider 为 Pi 且模式为 GUI 时平滑委托；无能力时优雅回退。
  - `SettingsScreen.kt` & `SettingsKit.kt`：新增精致的「会话模式（Display Mode）」持久化切换行，配备专属 Conversation 矢量徽标。
  - `SessionRoute.kt`：具备 `guiReadyOnce` 记忆守卫的动态路由分流，彻底杜绝触摸中途冷切 TUI 导致的 21s ANR 致命缺陷。
  - `SessionListScreen.kt`：将原本刺眼的报错弹窗彻底改为优雅柔和的内联非阻塞提示卡片。
- **服务端核心组件**（`server/internal/`）：
  - `internal/guirpc/`：tmux 托管的 Pi RPC worker，支持 Unix JSONL 管道通信。
  - **协议投影瘦身引擎**：在服务端剔除重复 53KB 系统提示词与累积 usage，工具输出实施 150ms latest-wins 节流窗口，`message_end` 后执行压缩（从 71 条原始记录压缩至 12 条/4KB），并打上自增 `seq`+`ts` 支持断点增量补发。
  - `internal/api/`：在现有 `/ws` 上实现 `conversation_v1` 协议扩展与能力协商。

---

## 三、 测试与验证证据资产

### 1. 自动化测试结果
- **服务端 Go 全量测试**：`go test -count=1 -p 1 ./...` 全仓 14 个包 **全部 100% PASS**（退出码 0，原始凭证 `.team/nodes/opus-issue50/tmp/go-test-full.txt`）。
- **客户端 Gradle 编译**：`:app:compileDebugKotlin` **BUILD SUCCESSFUL**（耗时 2s）。
- **客户端 Conversation 专项测试**：`dev.agentmirror.app.conversation.*`（包含状态机测试、Hub测试、30套主题900组色彩对比度数学测试）**BUILD SUCCESSFUL（100% 全绿）**。

### 2. 真实模拟器实测与 A/B 性能基准（Opus 5.5 现场产出）
- **模拟器真实截图库**（存放在 `.team/nodes/opus-issue50/shots/`，共 40+ 张）：
  - `00-launch.png`：应用启动
  - `01-settings.png` / `05-settings-gui.png` / `05-settings-mode.png`：设置中双模切换与持久化
  - `04-workspaces.png` / `06-sessions.png`：工作区与会话列表
  - `07-conversation.png` / `08-conversation-fixed.png`：高保真原生对话主界面、工具卡片与悬浮底栏
  - `c1.png` / `c2.png` / `c3.png` / `cur.png`：会话交互与连续对比截图
- **A/B 性能测试日志**（`.team/nodes/opus-issue50/tmp/perf-ab.txt` & `perf-cold.txt`）：
  - 自动化脚本 `ab.sh` 与 `ui.sh` 针对冷启开会话（`open_id=1` 至 `open_id=9`）抓取完整的 `PerfTrace` 日志；
  - 证明 GUI 模式通过增量 Delta 渲染，首帧绘制仅需极少字形计算，完全避开了 TUI 终端模式下动辄 1109 个字形全量计算的排布开销！

---

## 四、 编译产物指纹

- **官方构建产物**：`app/build/outputs/apk/debug/app-debug.apk`
- **文件大小**：43 MB（44,484,818 bytes）
- **SHA-256 散列**：`95759945433a86d850a1e7c153cef5f5e3e64eaa7bedc04a1a7cc538a311b881`

---

## 五、 后续行动方案（Codex 接手指南）

基于本基线 `baseline-opus-issue50-gui-8cb4caa`，后续工作已被极度收敛：
1. **已有完整代码与视觉架构**：所有核心业务逻辑与高保真 UI 均已编写完毕且完全编译通过；
2. **后续可选的最小工作**：
   - 方案 A：让测试席位（`tester` 与 `app-tester`）直接对当前 APK（SHA-256: `95759945...`）执行标准的端侧真实验收与截图归档；
   - 方案 B：若用户需要微调某些交互细节，可由 Codex（Sol `openai-codex/gpt-6.1-sol`）在当前基线分支上进行极小 diff 的手术式完善；
   - 方案 C：若后续修改不满意，随时可以 `git checkout baseline-opus-issue50-gui-8cb4caa` 瞬间 100% 恢复 Opus 5.5 的原始神圣基线。
