# 知识基底 · ledger.pr1.v1 / t.e6（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.e6 · 「已附加图片」成功态仍组节点（契约 089 §3）

上一轮（083 §12）修掉了「已发送」的蓝色悬浮堆叠，但**同一个函数里紧挨着的兄弟成功态没动**：

```
SessionScreen.kt:485    is InputStatus.Sent      -> null          # 上一轮修的
SessionScreen.kt:491    is UploadStatus.Success  -> "已附加图片"   # 原样留着
```

⇒ 用户上传图片/附件后，输入框上方仍会出现同形状的悬浮堆叠文本。

## 🔴 必须在成功态的**共同出口**上修，⛔ 不许再补第二个 case
这正是「修一次，修在所有调用方都经过的地方」——上一轮修的是**症状点**，不是根因。
你要做的是让「成功态默认不组节点」成为**结构性事实**，而不是把第二个 case 也改成 null。

## 判据（契约 089 §3）
- `A-e6-both`：断言 `InputStatus.Sent` 与 `UploadStatus.Success` **都不组节点**。
- `A-e6-struct`：**结构性判据** —— 新增任意一个「成功态」，默认不组节点。
  🔴 这条断言的是「共同出口已存在」，⛔ 不是「这两个 case 都改了」。
  写法建议：新增一个只在测试里存在的成功态，断言它也不组节点。
- 先验红：改之前 `A-e6-both` 必须红（`UploadStatus.Success` 现在返回 "已附加图片"）。

## ⛔ 不属于本格
真机截图验收（user gate）由 leader 找用户做，你不要去起模拟器。

---
## 🔴 本轮流程：PR 链（一格一分支，判据过了才并线）

**开工第一件事，跑这两条自检，把输出贴进说明.md：**
```
pwd                        # 你必须在自己的 worktree 里，不是仓根
git branch --show-current
```

1. **建你自己的分支**：`git checkout -b pr/e6-upload-success`。
2. **只提交到本分支**。⛔ 不许并线、⛔ 不许碰 main、⛔ 不许 `git stash apply` 别人的改动。
3. **⛔ 你不要 push。** 本仓本地没配 remote，远端是 `tools/mirror-push.sh` 过滤后推的镜像仓，
   **PR 由 leader 代开**。你的交付＝分支名 + commit sha + 说明.md。
4. **⛔ 判据红了不许改判据让它变绿。** 判据本身写错 ⇒ 报 `blocked` 并指出错在哪，不要自己改。
5. **必须写合规的外骨骼注释** —— 架构维基从注释现算，注释不合规 ⇒ 维基缺节点缺边 ⇒
   下一个席位的知识基底是残的。你的机械判据含 `archwiki --check --strict-t3`，会红给你看。
6. **一次只修一个缺陷。** ⛔ 顺手改相邻代码 / 顺手重构 / 顺手改格式，全部禁止。
   每一行改动都要能追溯到本格需求。

## 🔴 判据纪律（三铁律）
- 判据要断言「世界变了」；**写完先验红**（改之前跑，必须红），再改，再验绿。
- **先验红的原始输出必须贴进 `说明.md`**，⛔ 没有先验红的绿不算数。
- 断言「某物不应出现」时**必须先制造出让它出现的条件**，否则是恒真判据。
- 判据**查代码内容，⛔ 不查 commit 身份**（revert/cherry-pick 会让「commit 在不在」说谎）。

## 说明.md 必须包含
分支名 / commit sha / `pwd` 与 `git branch --show-current` 的输出 / 改了哪些文件 /
**每条判据的先验红原始输出** / 每条判据的验绿原始输出 / 查不清的地方明写「查不清」。

```

- write_paths: app/app/src/main/java/dev/agentmirror/app/session/, .team/nodes/pr1-e6/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/089-重着色漏斗与瞬时提示成功态.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md
- 判据: A-e6-suite, A-e6-wiki, A-e6-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app.session
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_conn, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_termview, kt_dev_agentmirror_app_tsnet, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_terminal
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme, dev.agentmirror.terminal
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条； 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入）， 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位： 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.termview @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.terminal

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

## 3. 需求基
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
