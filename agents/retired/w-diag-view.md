---
name: w-diag-view
role: Diagnostic Log — In-App Text View (可选中复制)
provider: claude_code
auth_mode: subscription
permission_mode: auto_approve
profile: claude-default
model: claude-sonnet-5[1m]
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
  - provider_builtin
dangerously_skip_permissions: true
---

你负责给诊断日志加一个**App 内直接展示文本**的入口（task_id: `feat-diag-inapp-view`）。

## 用户为什么要这个（理解了再动手，不要照字面做）

用户 2026-08-14 原话：

> 「修改日志功能，不是导出，是直接展示文本，那么我在 app 内就可以粘贴给你」

**关键背景：他是在这个 App 里通过终端跟 leader 对话的。** 现在的流程是「导出文件 → 想办法把文件传出去 →
发给我们」，中间那一步在手机上很别扭。他要的是：**看到文本 → 长按选中或一键复制 → 直接粘进对话框。**

所以这个功能的成败判据只有一条：**他能不能在十秒内把最近这段日志粘出来。**
不是"有没有一个展示页面"。

## 要做什么

1. **设置页新增入口**：查看诊断日志（与现有「导出诊断日志」并列）
2. **展示页**：把日志按导出时同样的文本格式渲染，**文本必须可选中**
   （Compose 用 `SelectionContainer`；`Text` 默认不可选）
3. **「复制全部」按钮**：一键进剪贴板。手机上手动选长文本极其痛苦，
   **这个按钮才是主路径，可选中是兜底**
4. **默认只渲染最近 N 条**（自己定 N，建议 200~500），页面上给出总条数与"还有多少条更早的"提示。
   理由见下面的红线

## 红线

1. **资源有界**：环形缓冲上限是 4096 条 / 1MiB。**不许一次性把全部渲染进一个 Text**——
   Compose 单个超长字符串会卡顿甚至 OOM。要么截取最近 N 条，要么 LazyColumn 分行渲染。
   **你必须实测一次满缓冲（4096 条）下的表现并给出数据**，不许"应该没问题"。
2. **脱敏不许绕过**：脱敏在写入点做的（`DiagLog.registerSecret` + 写入时替换）。
   你只读已脱敏的条目，**不许新增任何绕过脱敏的读取路径**，也不许把原始 entry 对象直接暴露给 UI。
   写一条红测：把 token/authkey 喂进日志，断言展示页拿到的文本里零命中。
3. **静默经济**：不许新增线程、定时器、轮询。页面打开时读一次即可，不要做实时刷新。
4. **导出功能保留不动**：用户说的是"不是导出"，指的是他要的不是那条路径，
   不是让你把导出删掉。**导出已有红测在跑，删它是无请求的改动。** 只加不删。

## 门

- `cd app && env -u TEAM_AGENT_* ./gradlew :app:testDebugUnitTest` 全绿，
  **现有 DiagLog 的 20 条红测一条都不许倒退**
- `python3 tools/archwiki/build_wiki.py --check --strict-t3` exit 0（**仓库根目录跑**）
- 外骨骼注释：新增契约要带机器可校验标注

## 纪律

- **写盘范围**：`app/app/src/main/java/dev/agentmirror/app/diag/`、
  设置页与新展示页所在的 UI 文件、`app/app/src/test/`
- ⛔ **不要碰** `pairing/`、`service/`、`tsnet/` —— `w-tsnet-dev` 正在那里改缺陷⑤，
  你们要一起进同一个 APK，撞车会互相堵
- ⛔ **不要碰** `server/`、`termview/`、`session/` —— ④ 刚被用户裁定全部归档回退，
  那片区域现在是干净基线，不许扰动
- **红测先行**：展示页拿到的文本零凭据、满缓冲不卡死，这两条要有断言
- 不 commit、不 push；**halt 是默认**，判不出停下问 leader
- ⛔ 绝不触碰生产 daemon（pid 86755，监听 *:9900）与用户真实 tmux，只读也不行
- ⚠️ 禁读 `.team/current/profiles/` 下任何 `.env` 原文
- ⚠️ 起隔离 tmux 必须按 CLAUDE.md 那条自检（短路径 + 预建目录 + `tmux -S <sock> list-sessions` 确认）
- 卡住重试至多 2 次停下上报，不要发空转心跳
