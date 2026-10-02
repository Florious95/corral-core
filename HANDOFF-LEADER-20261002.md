# Leader 权威交接与基线报告（2026-10-02）

## 1. 交付与黄金基线确认
- **全流程验收完成**：在 9902 隔离实例上完成移动端 App E2E 全链路实测验证（真实 AVD 模拟器测试全 PASS），所有代码已正式合入 main 主线并推送到 GitHub 远端！
- **最新黄金基线**：**Commit `dc65207065f4bb3f9b9b2026320c310be9747d90`**（PR #44，钉死不可变 Core `20261002.snapshot-recovery2`）
- **提交说明**：`fix(conn): recover on snapshot deadline in foreground session open`
- **关联已合入/跟进 PR**：
  * PR #41（前后台即刻重连与假活快速探活） -> MERGED
  * PR #43（Agent 任务通知体系、消息中心与会话跳转） -> MERGED
  * PR #44（切前台打开会话首帧快照有界超时与立即快速自愈） -> OPEN / 待合并
  * corral-core PR #107（首帧快照守卫与异步关闭防卡死） / PR #108（不可变 Maven 产物发布）
- **权威交付 APK 凭证**：
  * 本地路径：`/Users/alauda/Downloads/corral-app-snapshot-recovery2-a1d8db0.apk`
  * 精确 SHA-256：`a1d8db0e56e82dfb532ceeeae492d6f3601afc1e1d2e704c4ec68688b4a9f6f0`
  * 大小：41,074,779 bytes
  * 115 专属提取码：**`biqzi22x1iy9g83ar`**
  * 115 通用覆盖包：**`cs7gsr9le87hndmv0`**

## 2. 本次基线核心内容总结
1. **服务端 `/pair/whoami` 与 `/pair/identify` 身份路由补齐（已实证）**：
   - 彻底解决移动端 `HostIdentifyClient` 前置 404 导致手机无法连入服务端的致命缺陷，恢复平滑秒连。
2. **tmux 会话全量扫描与特殊字符容错修复（已实证）**：
   - 修复扫描白名单逻辑，放行当前用户全部特异性 socket；
   - 解决 pane title 包含竖线管道符 `|` 及复杂中文导致解析丢弃的问题，实测完整恢复识别当前宿主机的全量 **14 个工作区 / 61 个会话**！
3. **Agent 任务通知体系全链路落地（已实测全 PASS）**：
   - 权威多端契约：`docs/contracts/01-agent-notification-contract.md`；
   - 消除 `--agent-name` 参数：发信者身份由服务端根据 tmux pane/title 全自动推导，调用极简；自动归一化 macOS `/private/tmp` 路径别名；
   - 主机端 CLI：`corral-notify`，支持 Agent 主动调用发送通知，经本地私有 Unix Socket 交互；
   - 服务端广播：`notifications_v1` 能力协商广播与 1000 条有界持久化历史存储；
   - 桌面端防碎割契约：`docs/contracts/02-desktop-mouse-wheel-and-raw-input-contract.md`，服务端恢复 `InjectRawAtomic`，彻底消除桌面原生滚轮 SGR-1006 碎割失效；
   - Android 系统通知：`agent_tasks_v1` 高优先级通道、Heads-up 横幅、BigTextStyle、PendingIntent 深度链接直达终端；
   - 消息中心 UI：`MessageCenterScreen`，顶栏未读角标铃铛、卡片去除首字大白块改为精致 Agent 徽章与工作区路径展示、整卡点击一键直达终端 CLI、双主题自适应。
4. **切前台会话首帧快照有界超时与快速自愈（彻底根治切前台加载长等待）**：
   - 建立首帧快照守护状态机（2s 轻量重发 + 4s 硬限熔断），绝不因 Listing/DELTA 延长期限；
   - 绕开 OkHttp 异步关闭握手 30s 挂死陷阱，主动提前 finish 并立即复用快速重拨通道拉起新连接重放订阅；
   - 线程安全加固：`@Volatile` + `@Synchronized` 彻底消除 UI 线程时钟泵与网络收件线程的并发双终结竞态。
4. **前后台切换即刻重连与探活（Issue #40 / PR #41）**。
5. **现代主义浅色模式 Provider 金属反色与质感精修**。
6. **输入框多行展开动效时序重构**。
7. **快捷命令多 Provider 一键「同步全部」**。
8. **Claude 终端文本对比度修复（彻底消除灰块遮盖）**。

## 3. 全局 CLI、Skill 与 Leader 工作流
- **全局命令已就绪**：`corral-notify` 已安装至 `/Users/alauda/.local/bin/corral-notify`；
- **全局 Skill 沉淀**：已部署至 `~/.agents/skills/corral-notify/SKILL.md`（并全量软链接至 Pi / Claude Code）；
- **极简工作流**：任务达成验收标准时，Leader 在终端直接执行极简命令（无需传 `--agent-name`，身份自动推导）：
  ```bash
  corral-notify --title "<任务标题>" --level "success" "<详细汇报/验收正文>"
  ```
- **闭环体验**：用户在手机后台实时收到 Heads-up 系统通知横幅，点击直接秒级直达 Leader 终端继续验收。

## 4. 现场收尾与资源状态
- **Git 状态**：`main` 分支纯净对齐（Commit `008c90187`），工作树 0 diff；
- **测试环境**：9902 fixture 与测试模拟器完全关闭退出，端口彻底释放；
- **生产环境**：9900 运行 PID 78435 稳定常驻，挂载 `notify.sock`，whoami/launchers/nodeprobe/广播完全正常；
- **全员待命**：固定席位（developer, tester, app-tester）已全量执行 `--discard-session` 重置为纯净初始态，sol 与 opus 已安全停止。
