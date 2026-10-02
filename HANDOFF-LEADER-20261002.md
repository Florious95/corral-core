# Leader 权威交接与基线报告（2026-10-02）

## 1. 交付与黄金基线确认
- **全流程验收完成**：在 9902/9914 隔离实例上完成移动端 App E2E 全链路实测验证（真实 AVD 模拟器测试全 PASS：超长文本大段粘贴自动打包为 UTF-8 文件上传并安全注入路径引用、上传失败完整保留用户草稿、短文本实时直通保持 100% 完好；底层 WS 保活层 PASS），服务端相关契约完全体（01/02/03及protocol.md §8/§9）已全量正式落盘合流，终端底部 1:1 现代主义 Dock 与 2,000 字符长文本文件化已完成终极官方合流出包！
- **最新黄金基线**：**Commit `1b4b28f31cdfaf67ae1b0523f477c57b27dceaa9`**（PR #46，完美合并 Terminal Modernist Dock 1:1 与 2,000 字符长文本文件化，叠加在保活黄金基线 `f8896d0` 之上）
- **提交说明**：`feat(session): unify modernist dock 1:1 with relaxed 2000-unit file upload threshold`
- **关联已合入/跟进 PR**：
  * PR #41（前后台即刻重连与假活快速探活） -> MERGED
  * PR #43（Agent 任务通知体系、消息中心与会话跳转） -> MERGED
  * PR #44（切前台会话首帧快照自愈 + 前台服务配置自愈 + 探活防误掐死 + 通知状态对齐） -> 已验证
  * PR #46（Issue #45 超长文本自动打包上传为文件 + 终端底部 Modernist Dock 1:1） -> 终极合流官方候选 `1b4b28f31`
  * corral-core PR #107（Commit `028030795`） / PR #108（版本 `20261002.background-liveness1`）
- **权威交付 APK 凭证（终极双合流正式版）**：
  * 本地路径：`/Users/alauda/Downloads/corral-app-final-dock-1b4b28f.apk`
  * 精确 SHA-256：`96a6f571a580266ad2faa36c364e0eb7d72c1c1b37097f7766a27f9b05f56cd3`
  * 大小：41,140,315 bytes
  * 115 专属提取码：**`e5dd6425m7sodzw2p`**
  * 115 通用覆盖包：**`ak66q0zstp223htb5`**

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
5. **后台保活、前台服务配置自愈与防误掐死（彻底消除常驻通知卡连接与无端重拨）**：
   - **前台服务配置自愈**：在没有 Activity 时，Service 独立被系统拉起自动从 `SharedPreferences` 读配置拉起长连接，真机 kill -9 重启实测 100% 连通（`accept-000006`）；
   - **彻底消除 5 秒探活误掐死**：解码任意有效业务帧（快照/DELTA/level2/heartbeat）立即清除探活守卫，模拟器实测持续收发 1051 个真实 binary 帧零断开，永不换代；
   - **常驻通知状态对齐**：`onStartCommand` 读取真实连接状态，处于 READY 保持“已连接”，彻底消除“正在连接…”无端闪烁。
6. **超长文本自动打包上传与文件引用（Issue #45 / PR #46 方案 A）**：
   - **单次大批量粘贴自动锁定**：当单次插入/粘贴字符增量超过 100 字符时，立刻锁定为大文本草稿模式，彻底掐死向终端的实时击键泄漏（模拟器剪贴板实测 pane diff 严格为 0）；
   - **无感文件打包上传**：点击发送时，自动将超长文本转换为带有时间戳的 UTF-8 文本文件（如 `upload-text-*.txt`），通过 `POST /upload` 管道上传至宿主机；
   - **安全路径引用注入**：上传成功后，向终端 CLI 注入带引号的绝对路径（如 `"/path/to/upload-text-*.txt"`），输入框安全清空；
   - **草稿保护与短文兼容**：上传失败时完整保留用户草稿并轻量提示；短文本（<100字符）依然享受 100% 极速实时直通体验，两者互不干扰。
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
