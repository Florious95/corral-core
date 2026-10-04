# Leader 权威交接与基线收尾案卷（2026-10-02）

> **受众**：给“刚接手、未看过本轮过程”的后继 Leader 或 compact 重启后的自己。只读本案卷及指向的文件即可完全接管工作区，无需回放历史长对话。
> **基线状态**：全功能已验收、全部已闭环代码已合入 `main` 主线（Commit `b034fff7d`），生产服务端 9900（PID `86757`）稳定常驻，执行席位已全量重置。

---

## §0 compact 后先做什么（后继接手必读）

### 0.1 一句话现状
本轮所有功能开发、紧急缺陷修复（含跨端通报的 P0 重订阅冻屏死锁）、暗色/浅色 30 套全主题气泡自适应、2000 字符长文本文件化、主题选择实时预览保留当前页、以及经典原版底栏回退已**全部通过真机/独立实测验收**，相关代码已 100% 合流并推送到 `main` 主线（Commit `b034fff7d`）；所有临时测试资源已彻底清理释放，执行席位已 `--discard-session` 重置完毕，当前**无在途阻塞任务，全系统处于就绪待命状态**。

### 0.2 开口第一句
> “报告用户：上一阶段的全部开发、测试、P0 缺陷根治、全主题消息气泡自适应、超长文本文件化、以及收尾清理已全部圆满收官！代码已全量合流至 main 主线（Commit `b034fff7d`），生产环境 9900 稳定运行（PID `86757`），全体执行席位已重置就绪，随时听候您的下一步工作指示！”

### 0.3 必读清单（按优先级）
1. **本案卷本身**：`/Volumes/nvme/Projects/远程Agent安卓/HANDOFF-LEADER-20261002.md`；
2. **多端契约完全体**：
   - 01 通知与自动推导：`docs/contracts/01-agent-notification-contract.md`
   - 02 桌面滚轮与裸输入：`docs/contracts/02-desktop-mouse-wheel-and-raw-input-contract.md`
   - 03 文件与长文本上传：`docs/contracts/03-file-upload-and-long-text-reference-contract.md`
   - 主协议权威副本：`docs/protocol.md`
3. **跨端完备交接资产包**：`/Volumes/nvme/Projects/远控-ios/android-notifications-and-pipeline-handoff/README.md`（40 文件自包含）；
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
   - 执行 `git status -s` 确认工作树处于 clean 状态，位于 `main` 分支（HEAD 为 `b034fff7d` 或其后代）；
   - 执行 `lsof -nP -iTCP:9900 -sTCP:LISTEN` 确认 PID `86757`（或最新正式 daemon）处于 LISTEN；
   - 执行 `team-agent status` 确认仅有 4 个核心席位（`developer`, `tester`, `app-tester`, `sol`）且处于 `running / idle`；
2. **第二步（先恢复守护，后推进）**：
   - 若用户安排了自动化长任务，检查周期心跳看门狗状态；当前无活动任务，守护处于待命态；
3. **第三步（恢复期间的禁令）**：
   - **绝对禁止** 在未收到用户新指示前擅自开启新分支或修改业务代码；
   - **绝对禁止** 盲目重启生产 9900 daemon；
   - **绝对禁止** 擅自重新增加已删除的 9 个临时冗余席位；
   - **绝对禁止** 重新修改 Agent CLI 对话界面的底部输入框（用户明确指示锁定在经典原版形态）；
4. **第四步（判“恢复完毕”的标准）**：
   - 工作树 clean、生产 9900 响应 200 OK、4 个核心席位处于 fresh 空闲态、案卷与代码完全对齐；
5. **第五步（恢复时发现与文档不符怎么办）**：
   - 若发现现场状态（如 PID、分支）与文档不符，**以现场客观事实为准**，先通过只读命令查清原因并向用户如实请示，严禁盲目猜想。

---

## §1 身份与不变量（操作铁律）

1. **Leader 编排铁律**：
   - **严禁亲力亲为、禁止查看代码、禁止轮询、严禁通过 tmux 查看 agent 界面**；
   - 需求分析、目标文档编写、验收标准裁定交由 Leader，定位、编码与探索交由 Teammate；
   - 通过 Team Agent 消息机制驱动协作；
2. **Teammate 模型调度标准**：
   - **`developer`**：固定开发（当前用户已升级并固化为 **`openai-codex/gpt-6.1-sol`**，Pi 驱动，`effort: xhigh`，`bypass: true`）；
   - **`sol`**：复杂根因定位与架构决策（`openai-codex/gpt-6.1-sol`，Pi 驱动，`effort: xhigh`，`bypass: true`）；
   - **`tester`**：服务端红测、回归与唯一构建（`openai-codex/gpt-6-luna`，Pi 驱动，`effort: xhigh`，`bypass: true`）；
   - **`app-tester`**：自有设备与真实 UI 验收（`openai-codex/gpt-6-luna`，Pi 驱动，`effort: xhigh`，`bypass: true`）；
   - **`opus`**：高级视觉模型（单轮派发 UI 重构后必须立即 `--discard-session` 并执行 `remove-agent --from-spec` 彻底移除）；
3. **业务与代码不变量**：
   - **经典原版底栏锁定**：Agent CLI 底部输入框及按键条保持经典原版形态，不得擅自重构；
   - **超长文本文件化门槛锁定**：`text.length >= 2000` UTF-16 字符 或 `lines >= 100` 行，禁止单次增量 100 字符的过早拦截；
   - **保活机制锁定**：解码任意有效业务帧立即清除 5 秒探活守卫，物理连接永不被盲目掐断换号；前台服务独立重建自动读配置恢复长连接；
   - **暗色模式气泡底色锁定**：`#1E1E2E` 在黑守卫前精确语义路由至 `pal.userBlockBg`，文字对比度 $\ge 4.5:1$；
   - **生产 9900 端口红线**：所有测试严格限制在隔离端口（9902/9914），绝对禁止把测试请求或调试 binary 注入生产 9900。

---

## §2 排期与封存令

### 2.1 本轮已彻底闭环完成项（全部通过真实验收）
- [x] **任务通知体系全链路（Issue #42 / PR #43）**：全局 CLI `corral-notify`（无参数自动推导身份）、UDS IPC、1000 条持久化历史、Android Heads-up 横幅、消息中心直达终端；
- [x] **前台会话秒开快照有界超时与快速自愈**：2s 重发 + 4s 硬限熔断，原子竞态消除，绕开 OkHttp 异步关闭挂死；
- [x] **前台服务配置自愈（选项一）**：Service 独立重启自动从本地 SharedPreferences 读配置拉起长连接，kill -9 重启实测 100% 连通；
- [x] **切前台探活防 5 秒误掐死保活**：解码任意有效业务帧立即清除 5s 守卫，实测持续接收 1051 个真实终端数据帧零断开、永不换代；
- [x] **常驻通知状态精准同步**：前台服务启动读取真实连接状态，处于 READY 保持“已连接”，彻底消除“正在连接…”闪烁；
- [x] **超长文本自动打包上传为文件（Issue #45 / PR #46）**：放宽至 2,000 字符 / 100 行门槛，几百字正常键盘直发，超长文本自动打包上传并注入安全单引号路径引用；
- [x] **契约完全体合流**：`01`（通知）、`02`（滚轮）、`03`（文件上传）与 `protocol.md` §8/§9 权威规范合入主线；
- [x] **P0 严重缺陷根除**：重订阅全局原子单调递增 reflow epoch 分配器上线，彻底消灭重订阅 delta 静默丢弃与视口冻屏死锁，生产 9900 热更就绪（PID `86757`）；
- [x] **底栏回退**：彻底回退有争议的重构，100% 恢复经典原版底部输入框及周边 UI；
- [x] **暗色模式发送消息气泡底色修复**：`#1E1E2E` 语义路由至 `userBlockBg`，文字对比度 4.5:1，媲美 Ghostty；
- [x] **向 iOS 团队交付完备交接资产包**：`/Volumes/nvme/Projects/远控-ios/android-notifications-and-pipeline-handoff/`（40 文件自包含对账全绿，投递送达）；
- [x] **Issue #47 消息中心层级过滤（PR #48）**：一级入口展示全局全量，二级入口严格按当前工作区目录过滤，角标与已读严格隔离，实机全量 PASS；
- [x] **终端主题选择页实时预览与当前页停留（PR #49）**：彻底移除选后自动返回，支持连续点击对比切换，顶部预览实时刷新，实机全量 PASS。

### 2.2 封存令
当前代码已达最高工业级稳定度并合入 `main`（Commit `b034fff7d`）。**正式封存当前代码状态，严禁在未获用户新需求授权时引入任何改动！**

---

## §3 P0 / 插队项复盘

1. **跨端 P0 缺陷（重订阅 delta 丢失视口死锁）**：
   - **现象**：客户端分屏或重排切换（先 unsubscribe 再 resubscribe）时，画面死锁在初始快照，后续新输出全不显示；
   - **根因**：新订阅的 `reflowGate.epoch` 从 1 计起，被连接级累积的 `staleBefore` 阈值（如 7）按过期脏帧全量丢弃；
   - **止血与根治**：服务端引入原子全局单调递增代际分配器，代际号严格高于历史阈值，不清空过滤表防旧脏帧回流；
   - **测试与生产**：tester 独立两项红测由红转绿（PASS），全量回归通过，生产 9900 平滑热更上线（PID `86757`）；
2. **底栏 UI 回退插队项**：
   - 用户明确指示不满意重构后的 Dock 样式，要求彻底回退；
   - 团队立即中止 Dock 重构分支，将代码精确切回经典原版基线（`cae23b0`），并在此基线上叠加全量功能与色板修复，圆满达成用户心意。

---

## §4 在途未收尾任务与挂起跟踪项

- **当前代码开发在途任务**：**0 项（全部收尾清零）**；
- **挂起的未来规划 Issue（仅作知识归档，不排期开发）**：
  * [corral-core #109](https://github.com/Florious95/corral-core/issues/109)：提供系统「允许后台耗电/忽略电池优化」一键跳转与提示引导（已归档，暂不适配）。

---

## §5 运维与生产状态

### 5.1 生产服务运行状态
- **常驻进程 PID**：**`86757`**
- **二进制 SHA-256**：`69c18631fa68dde4510007386075489697e2a20503e4c238a5e5b6c81b8cea1c`
- **监听端口**：`*:9900 (0.0.0.0:9900 LISTEN)`，支持全网卡与 Tailscale
- **连通性校验**：
  * 本地回环 `http://127.0.0.1:9900/pair/whoami` $\to$ `200 OK`
  * 远程 Tailscale `http://100.75.207.88:9900/pair/whoami` $\to$ `200 OK`
  * WebSocket `auth_ack`、`listing` 正常（4 个 Agent 启动器，6 个工作区，26 个会话）
  * 通知 UDS 管道 `~/Library/Application Support/agentmirror/notify.sock` 运行正常

### 5.2 权威交付安装包（最新正式版）
- **产物文件**：`corral-app-theme-picker-stay-c5ea7a0.apk`（对应 Commit `c5ea7a04a`）
- **本地路径**：`/Users/alauda/Downloads/corral-app-theme-picker-stay-c5ea7a0.apk`
- **精确 SHA-256**：**`94c0653cd55e36fe22ae5757b141b0a93a42412f0f34cfaab189c418f6ab67be`**
- **文件大小**：41,091,163 bytes
- **115 专属提取码**：**`biul9plon7nlj83ar`**
- **115 通用覆盖包提取码**：**`biul92nfs0gbr83ar`**（网盘同名覆盖文件：`/corral-app-reflow-optimized.apk`）

### 5.3 跨团队交付物
- **iOS 资产包**：`/Volumes/nvme/Projects/远控-ios/android-notifications-and-pipeline-handoff/`（40 文件自包含，包含三契约、主协议、Android/Go 源码切片、8 张 1080×2400 高清真机实况截图与 Swift 实现指南，已投递通知 iOS Leader）。

---

## §6 收尾与环境清理规范（Teardown & Cleanup Checklist）

本轮收尾已严格按照最高工业级标准执行现场清理：

1. **测试模拟器与测试包清理**：
   - Android Emulator 实例已通过标准流程安全退出，`adb devices` 为空；
   - 未残留任何无主模拟器或占用渲染资源；
2. **测试端口与后台进程清理**：
   - 临时测试端口 `9902`、`9914`、`9913` 已全部安全释放；
   - 临时创建的测试 fixture 进程已按精确 PID 退出，无遗留进程；
   - 临时测试 tmux socket 与工作区已全部删除；
   - `lsof` 验证仅有生产 `9900`（PID `86757`）在运行；
3. **PR、Issue 与分支归整**：
   - 所有已验收的代码已合并至 `main` 主线（Commit `b034fff7d`）；
   - GitHub PR #46, #48, #49 已闭环；
   - GitHub Issue #45, #47 已关闭并附带验收依据留言；
   - 临时 worktree（`/private/tmp/theme-*`）已全部清理，`git worktree prune` 执行完毕；
4. **执行席位上下文重置（Agent Session Reset）**：
   - 4 个执行席位（`developer`, `tester`, `app-tester`, `sol`）已全部执行 `team-agent reset-agent <AGENT> --discard-session`；
   - 上下文历史完全清空，恢复纯净初始态；
   - 🔴 **常驻总控保护**：`leader` 核心调度席未受触碰，宿主与协作会话绝对完好；
5. **冗余席位彻底永久移除**：
   - `opus`, `astra`, `code-auditor`, `ui-developer`, `fable`, `pi-astra`, `luna-acp`, `contract-architect`, `gemini-auditor` 已通过 `remove-agent --from-spec` 彻底从运行时和配置中永久删除，重启 Tmux 绝不再现。

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
**案卷生效日期**：2026-10-02
**Git 权威状态**：`main` @ `b034fff7d` (clean)
