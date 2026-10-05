# Leader 权威交接与新黄金基线收尾案卷（2026-10-05）

> **受众**：给“刚接手、未看过本轮过程”的后继 Leader 或 compact 重启后的自己。只读本案卷及指向的文件即可完全接管工作区，无需回放历史长对话。  
> **基线状态**：**权威新黄金基线 `baseline-20261005-native-gui-v2`**（Commit `7732374cf`），生产服务端 9900（PID `58013`）稳定常驻，执行席位全量 `--discard-session` 重置完毕，测试模拟器与高位测试端口彻底释放。

---

## §0 compact 后先做什么（后继接手必读）

### 0.1 一句话现状
原生 GUI 会话模式全量演进（涵盖全套高保真组件、OkLab 色板对比度、二段式展开底栏、TUI 斜杠补全、多行原子提交、触底防抖）及 Issue #54（`switch_mode` 未知命令与模式切换简陋占位）**已全部通过真机/独立黑盒实测验收**；当前代码与生产服务端已锚定为最新黄金基线 `baseline-20261005-native-gui-v2`（Commit `7732374cf`）；全环境收尾清理完毕，执行席位处于纯净待命状态。用户确立了下一阶段的根本性解耦战略：**彻底废除私有 `gui-worker` 包装器，支持任何以标准 `pi --mode rpc` 启动的会话由后端透明挂接（Issue #56），以及会话 Resume 支持（Issue #55）**。

### 0.2 开口第一句
> “报告用户：上一阶段的原生 GUI 会话模式全量交付、双模切换与 Issue #54 报错根除已全部验收闭环！新黄金基线 `baseline-20261005-native-gui-v2`（Commit `7732374cf`）已打标封存，生产 9900 服务端（PID `58013`）稳定在线，测试环境与执行席位已全量收尾重置。接下来请指示是否正式启动 Issue #56 纯净解耦（去除私有包装器，外部仅需 `pi --mode rpc`）及 Issue #55（历史会话 Resume）的攻坚！”

### 0.3 必读清单（按优先级）
1. **本案卷本身**：`/Volumes/nvme/Projects/远程Agent安卓/HANDOFF-LEADER-20261005.md`；
2. **Issue #54 复现与根因案卷**：
   - 独立复现与根因诊断：`.team/fixed-workflow/GUI-TO-TUI-SWITCH-REPRO-AND-ANALYSIS.md`
   - 独立验收与红转绿收据：`.team/fixed-workflow/ISSUE54-INDEPENDENT-ACCEPTANCE-20261005.md`
3. **技术蓝图与视觉参考总卷**：
   - 543 行代码映射与预读蓝图：`.team/fixed-workflow/GUI-OPTIMIZATION-CODE-BLUEPRINT.md`
   - 613 行历史踩坑与探索总卷：`.team/fixed-workflow/GUI-EVOLUTION-AND-HISTORICAL-ATTEMPTS-DOSSIER.md`
   - Codex App 与 Meta Muse 视觉意象：`docs/contracts/05-codex-muse-visual-reference.md`
   - 原生 GUI 会话契约：`docs/contracts/04-native-gui-conversation-spec.md`
4. **全局团队管理规范**：`/Users/alauda/.pi/agent/AGENTS.md` 与 `/Volumes/nvme/Projects/远程Agent安卓/AGENTS.md`。

### 0.4 恢复动作与复活命令序列
若协作环境、终端会话发生异常，按以下顺序确认或拉起：
```bash
# 1. 检查 Team Agent 核心协调器与节点状态
team-agent status --json

# 2. 若协调器挂死或需要重连，执行自检与恢复
team-agent doctor --workspace . --json

# 3. 检查生产 daemon 状态（必须监听全网卡 0.0.0.0:9900）
lsof -nP -iTCP:9900 -sTCP:LISTEN
curl -s http://127.0.0.1:9900/pair/whoami
curl -s http://100.75.207.88:9900/pair/whoami
```

### 0.5 恢复工作流程（后继执行纪律，按步骤确认）
1. **第一步（先核对，后开口）**：
   - 执行 `git status -s` 确认工作树处于 clean 状态，位于 `feat/issue50-native-gui-session-mode` 或 `main`（HEAD 为 `7732374cf` 或其后代）；
   - 执行 `lsof -nP -iTCP:9900 -sTCP:LISTEN` 确认 PID `58013`（或最新生产 daemon）处于 LISTEN；
   - 执行 `team-agent status` 确认仅有 4 个核心席位（`developer`, `tester`, `app-tester`, `sol`）且处于 `running / idle`；
2. **第二步（先恢复守护，后推进）**：
   - 若用户安排了自动化长任务，检查周期心跳看门狗状态；当前无活动任务，守护处于待命态；
3. **第三步（恢复期间的禁令）**：
   - **绝对禁止** 亲力亲为编写代码、直接查看代码、轮询或通过 tmux 偷看 teammate 界面；
   - **绝对禁止** 盲目重启生产 9900 守护进程；
   - **绝对禁止** 擅自将 PR #53 合并入 main（PR 保持未合并，需用户真实验收后明确授权）；
   - **绝对禁止** 在未收到用户新指示前擅自开启新分支；
4. **第四步（判“恢复完毕”的标准）**：
   - 工作树 clean、生产 9900 响应 200 OK、4 个核心席位处于 fresh 空闲态、案卷与代码完全对齐；
5. **第五步（恢复时发现与文档不符怎么办）**：
   - 若发现现场状态（如 PID、分支）与文档不符，**以现场客观事实为准**，先通过只读命令查清原因并向用户如实请示，严禁盲目猜想。

---

## §1 身份与不变量（操作铁律）

1. **Leader 编排铁律**：
   - **禁止亲力亲为，禁止轮询，禁止查看 teammate 界面，禁止查看代码**；
   - 我的唯一职责就是编排者：收集信息、问询得到信息、拆分任务、推动流程执行；
2. **Teammate 模型调度标准**：
   - **`developer`**：固定开发（`openai-codex/gpt-6.1-sol`，Pi 驱动，`effort: xhigh`，`bypass: true`）；
   - **`sol`**：复杂根因定位与架构决策（`openai-codex/gpt-6.1-sol`，Pi 驱动，`effort: xhigh`，`bypass: true`）；
   - **`tester`**：服务端红测、回归与唯一构建（`openai-codex/gpt-6-luna`，Pi 驱动，`effort: xhigh`，`bypass: true`）；
   - **`app-tester`**：自有设备与真实 UI 验收（`openai-codex/gpt-6-luna`，Pi 驱动，`effort: xhigh`，`bypass: true`）；
   - **`opus`**：高级视觉模型（单轮派发 UI 重构后必须立即 `--discard-session` 并执行 `remove-agent --from-spec` 彻底移除；与 Opus 交互必须使用英文）；
3. **业务与架构不变量**：
   - **单一连接绝对铁律**：所有对话与控制帧 100% 复用已有 `/ws` 核心连接，严禁开辟第二条 Socket（如 `/gui`）；
   - **状态记忆守卫（`guiReadyOnce`）**：会话一旦成功进入 `gui_ready`，断网重连永远保持在 GUI 错误态重试，严禁破坏性冷切 TUI 导致触摸中途 21s ANR；
   - **私有套接字规范化**：计算私有套接字哈希时必须 `filepath.EvalSymlinks`，杜绝 macOS `/tmp` 与 `/private/tmp` 符号链接分裂；
   - **TUI 零退化**：切换至 TUI 时，画布渲染、底栏 Esc/Tab/Ctrl-C 及输入编辑与原有黄金基线 100% 严格一致；
   - **生产 9900 端口红线**：所有测试严格限制在隔离端口（19900/19905 等），绝对禁止把测试请求或调试 binary 注入生产 9900。

---

## §2 排期与封存令

### 2.1 本轮已彻底闭环完成项（全部通过真实验收）
- [x] **原生 GUI 会话模式全量高保真重构（Issue #50）**：
  * D1：Skill 提示词紧凑折叠卡片化（`SkillCard`，Commit `04d3afd9f`）；
  * D2：多行输入原子发送（保留 `\n` 不拆包，Commit `7baeba6d3`）；
  * D3：回底按钮与手势滑到底部防抖防闪（彻底解决触底 50dp 内边距暴跌下坠，Commit `70e4d2815`）；
  * D7：底栏输入框仿 TUI 动态二段式展开（未聚焦单行仅保留 `+`，聚焦展开两行出现 `⚡`，Commit `70e4d2815`）；
  * D4：顶部浮动条切换模型与思维强度（支持 Pi 原生 7 档思维强度分段滑块，Commit `9da33dd44`）；
  * D6：双主题全量适配（液态玻璃 Liquid Glass vs 现代主义 Modernism 拉丝金属网格，全量满足 WCAG AA 对比度，Commit `652836daa`）；
  * D8：TUI 模式不同步输入斜杠自动弹出快捷命令列表（零字节终端泄漏验证，Commit `14b8010d3`）；
  * D5：同 Tmux Pane 原生 GUI 与真实 TUI 进程无损置换（Commit `a502fbff6`）；
- [x] **macOS 符号链接别名哈希分裂根治（Commit `d67ee4fbb`）**：彻底解决新建 Agent 报 `Pi 没能在主机上启动` 的 5 秒超时假死；
- [x] **Issue #54 双向切换报错与模式残留彻底根治（Commit `7732374cf`）**：
  * 拦截旧 Worker 未知命令透传，消除 `Unknown command: switch_mode` 报错；
  * 加固 4 秒冷启竞态，确保进程置换平滑成功；
  * `SessionRoute.kt` 建立动态偏好响应，并在以 TUI 模式进入 managed 会话前主动请求主机切为真实交互 TUI，彻底消灭 `Pi · native conversation` 简陋占位！

### 2.2 封存令
当前代码已被用户正式裁定为权威新黄金基线：
> **“把当前的我手上的 APK 当做基线，除了这些小问题，其他我都是满意的。虽然还有优化点，但是它依然是可用的。”**
**正式确立 `baseline-20261005-native-gui-v2` 为不可退缩的新黄金基线，任何后续改动必须以此为底座！**

---

## §3 P0 / 插队项复盘（Issue #54）

1. **现象**：
   - 用户真机实测点击回到 TUI 模式报错：`未切换到终端：Unknown command: switch_mode`；
   - 设置切为 TUI 模式后进入会话呈现 `Pi · native conversation` 极端简陋占位，再切 GUI 同样报错；
2. **根因剖析**：
   - 守护进程热更时，既有 Tmux Pane 内的旧 Worker 未自动重启；新客户端发出的 `switch_mode` 被旧 Worker 盲目喂给 Pi，Pi 返回未知命令错误；
   - 客户端 `SessionRoute.kt` 用 `remember(ref)` 静态包裹偏好设置，导致设置修改无法触发重新评估；且进入终端视图时未先向主机发送切模指令，导致抓到 RPC 占位文本；
3. **治理与闭环**：
   - 服务端拦截旧 Worker 命令，强制置换升级并无损保留同一 `sessionId`、同一 pane 与 cwd；加固 4s 冷启就绪竞态；
   - 客户端路由解绑静态死锁，并在渲染终端前主动向主机发起模式置换，彻底消灭简陋占位；
   - 测试席位独立黑盒验证全绿，生产 9900 已热更（PID `58013`）。

---

## §4 在途未收尾任务与根本性解耦演进（核心重点）

用户针对当前架构提出了极其深刻的战略指引：**当前私有模式（`agentmirrord gui-worker`）导致两个框架耦合在一起，脱离了通用 Tmux 管理的初衷。必须彻底解耦！**

### 4.1 GitHub Issue #56（拟立项重塑：零侵入解耦，外部仅需 `pi --mode rpc`）
- **核心目标**：彻底废除 `agentmirrord gui-worker` 私有包装器；
- **启动契约**：Team Agent 或终端用户拉起 Agent，**只需要且只允许携带标准参数 `pi --mode rpc`**（支持 `--session-id <id>`）；
- **主机自适应**：
  * 服务端通过 `nodeprobe` 进程参数嗅探自动识别 `--mode rpc`，自动点亮 `Conversation = true`；
  * 通过 `tmux pipe-pane` 或原生 I/O 在外部透明对接标准输入输出，彻底消除两端框架耦合。

### 4.2 GitHub Issue #55（Pi RPC 历史会话 Resume 机制）
- **核心目标**：支持 Pi RPC 模式下的 session resume 启动参数与 RPC 协议指令；
- **体验目标**：客户端支持列出与无缝切换历史已存会话，恢复完整的对话流、工具调用与思考块。

---

## §5 运维与生产状态

### 5.1 生产服务运行状态
- **常驻进程 PID**：**`58013`**
- **二进制 SHA-256**：`dfa618a5bef5c7d578452d03e8b009af7e01ae9f0a5155aff3632e0bd6bb7707`
- **监听端口**：`*:9900 (0.0.0.0:9900 LISTEN)`，支持全网卡与 Tailscale
- **连通性校验**：
  * 本地回环 `http://127.0.0.1:9900/pair/whoami` $\to$ `200 OK`
  * 远程 Tailscale `http://100.75.207.88:9900/pair/whoami` $\to$ `200 OK`
  * WebSocket `auth_ack` 返回 `conversation_v1` 与 `notifications_v1`
  * UDS 通知管道 `/Users/alauda/Library/Application Support/agentmirror/notify.sock` 运行正常

### 5.2 权威交付安装包（最新基线版）
- **产物文件**：`corral-app-issue54-fixed-773237.apk`（对应 Commit `7732374cf`）
- **本地路径**：`/Users/alauda/Downloads/corral-app-issue54-fixed-773237.apk`
- **精确 SHA-256**：**`2c30b9a82ba583ce87e94b48012e089aefdaea57f6c5fa08b1e5e8deb8bf8c48`**
- **文件大小**：41,533,590 bytes
- **115 专属提取码**：**`csfkwq5ciyn60dmv0`**
- **115 通用覆盖包提取码**：**`biujbiy8kvkeg83ar`**（网盘同名覆盖文件：`/corral-app-reflow-optimized.apk`）

---

## §6 收尾与环境清理规范（Teardown & Cleanup Checklist）

本轮收尾已严格按照最高工业级标准执行现场清理：

1. **测试模拟器与测试包清理**：
   - Android Emulator 实例已通过 `adb emu kill` 安全退出，`adb devices` 确认为空；
   - 临时验证包名 `dev.agentmirror.issue54accept` 已完全卸载；
2. **测试端口与后台进程清理**：
   - 临时测试端口 `19900`、`19905`、`19909`、`9914`、`9951` 已全部释放；
   - 临时测试 fixture、mock daemon 与测试专属 tmux socket 已按精确 PID 完全终止；
   - `lsof` 验证仅有生产 `9900`（PID `58013`）在运行；
3. **分支与 Worktree 清理**：
   - 所有已验收的代码已推送到特性分支 `feat/issue50-native-gui-session-mode`（PR #53）；
   - 临时 worktree（`.worktrees/issue54-*`、`.worktrees/gui-socket-ref-fix`）已强制移除，`git worktree prune` 执行完毕；
4. **执行席位上下文重置（Agent Session Reset）**：
   - 4 个执行席位（`developer`, `tester`, `app-tester`, `sol`）已全部执行 `team-agent reset-agent <AGENT> --discard-session`；
   - 上下文历史完全清空，恢复纯净初始态；
   - 🔴 **常驻总控保护**：`leader` 核心调度席未受触碰，宿主与协作会话绝对完好；
5. **临时专家席位彻底永久移除**：
   - `opus` 席位已通过 `team-agent stop-agent` 与 `remove-agent --from-spec` 彻底从运行时和配置中永久删除。

---

## §7 安全约束（原文保留，不可弱化）

1. **凭据绝对禁现**：
   - 配对 Token、Tailscale AuthKey、私钥及会话凭据严禁在任何日志、截屏、commit 信息或对话中明文输出；
   - 生产启动参数与环境读取必须采用管道式传递，严禁 echo 或落盘；
2. **禁止写 memory**：
   - memory 系统已废止，关键技术沉淀严格写入对应项目的 skill 文件或 `docs/contracts/` 契约文档；
3. **文件传输严禁 SCP**：
   - 必须通过 Git 仓库或专用打包交付物完成，严禁 SCP；
4. **禁止全盘盲目搜索**：
   - 严禁对大目录执行无边界的 grep find，避免 I/O 阻塞；
5. **通知使用规范**：
   - 任何任务达到验收标准需通知用户时，统一直接调用全局命令 `corral-notify "<汇报正文>"`，禁止在全局系统提示词中增加广播指令，杜绝子节点广播风暴。

---

**案卷编制人**：远程Agent安卓 团队 Leader  
**案卷生效日期**：2026-10-05  
**Git 权威基线**：`baseline-20261005-native-gui-v2` @ `7732374cf` (clean)
