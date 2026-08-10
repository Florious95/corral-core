---
name: w-archwiki-ktroot
role: archwiki 扫描根缺陷窄修（terminal 模块对判据完全不可见）
provider: claude_code
auth_mode: compatible_api
permission_mode: auto_approve
profile: worker-api
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
  - provider_builtin
---

你承办一处**判据基础设施缺陷的窄修**。**一次性席位，交件即退役。**

## 缺陷（由 `w-doc-kt-terminal` 施工中用阳性对照实证）

`tools/archwiki/build_wiki.py` 的 `KT_SEARCH = ("app/app/src/main/java",)`，
`_find_kotlin_roots()` 的兜底也只找 `*/src/main/java`。
而 `:terminal` 模块的源码在 **`app/terminal/src/main/kotlin/`** —— 路径段是 `kotlin` 不是 `java`。

后果：**整个 `dev.agentmirror.terminal` 模块对全部判据不可见**，T1-2 / T3-1 / T3-2 / T3-3 / T3-4
在这个包上全是**空通过**。实证：`w-doc-kt-terminal` 故意删掉 `TerminalEmulator.feed` 的 `@err` 之后，
`--check --strict-t3 --pkg dev.agentmirror.terminal` **仍然 exit 0、四项全 PASS**。
它已用 checker 自带的 `testdata/contract-incomplete` 反向验证判据本身在可扫描包上确实会红
（精确指向 `contract.go:4` 缺 @err/@inv、`Incomplete.kt:3` 缺 @post），故根因 100% 是扫描根。

这个模块是自研终端内核（需求 R-002，压着 GPL 隔离红线），**从未被任何一条判据覆盖过**——
此前报告里"Kotlin 9 包均有模块 doc"的那个 9 里根本没有它。

## 要做的事

**一、先写红测（顺序不许倒，本工程判据改动的准入纪律）**
在 `tools/archwiki/testdata/` 下新建 fixture，形状照既有 mini-repo：
- 一个**多模块**布局：`app/app/src/main/java/...`（现有形态）+ `app/<mod>/src/main/kotlin/...`（新形态）；
- `src/main/kotlin` 下放一个**带残缺 `@contract`** 的符号 ⇒ **修复前必然是空通过（exit 0），修复后必须红**。
  **这一格就是本次修复的红测**，证据里要写明它修复前后的 rc 变化。只写"修完会红"不算证明。
- 另配必绿格：`src/main/kotlin` 下的符号标注完整 ⇒ 修复后仍 exit 0（防止你改成"凡 kotlin 目录一律红"）。
全部挂进 `tools/archwiki/test_check.py`。

**二、再改实现**
让 Kotlin 源码根发现同时覆盖 `src/main/java` 与 `src/main/kotlin`，且**跨模块**
（不只是 `app/app`，还有 `app/terminal` 以及将来可能新增的模块）。
做法要稳：优先按"扫 `app/*/src/main/{java,kotlin}`"的形状发现，而不是把某个模块名写死。
`KT_SEARCH` 常量与 `_find_kotlin_roots()` 的 docstring 一并更新，别留下与实现不符的注释
（本轮整件事就是在治这个，工装自己更不能犯）。

**三、跑真仓库并如实报告新增违规**
修好后 `dev.agentmirror.terminal` 会第一次进入判据视野，**很可能立刻报出违规**
（T3-1 缺 doc / T3-3 契约不全 / T3-4 漂移都有可能）。这是**预期结果，不是你的错**，
**不许为了让全仓库好看而放宽判据或给该模块加豁免**。
你要做的是：
- 跑 `--check`（报告模式）与 `--check --strict-t3`，把该模块新暴露的违规**逐条列进
  `docs/wiki/t3-report.md`**（生成物会自动更新，确认它确实出现了）；
- 在证据里给出"该模块新增违规条数与分布"，这是交给 leader 排后续施工的依据。

**注意**：`w-doc-kt-terminal` 已经在该模块补了 10 个 `@contract` 与 1 条改写，那些是它的成果，
**你不要动 `app/terminal/` 下任何文件**。修复后若该模块仍有违规，由它复跑收口，不归你。

## 验收

- `python3 -m unittest discover -s tools/archwiki -p "test_*.py"` —— 全绿，新增用例确认真在跑
- `python3 tools/archwiki/build_wiki.py --check` —— **允许因 terminal 模块首次纳入而报出违规**，
  但既有 9 个 Go 包 + 9 个 app Kotlin 包的判据结论**不得被打坏**（对照修复前的结论逐项比）
- 生成物**幂等**：连跑两次 `git diff` 为空

## 红线

- 写入范围严格限于 `tools/archwiki/`。**不要动 `app/` 或 `server/` 下任何文件。**
- 不得为让真仓库好看而放宽判据、加豁免或白名单。
- 禁 git commit / push（leader 收口）。
- 绝不触碰生产 daemon（pid 3393，`:9900`）与用户真实 tmux；测试带 `env -u TEAM_AGENT_*` 前缀。

## 交件

`.team/evidence/archwiki-ktroot.json`：`status` 只允许 `pass`/`red`/`blocked`，
带 `tests`（argv+rc 原文）、`changes`、`fixture_rc_before_after`（那格必红 fixture 修复前 exit 0 / 修复后非 0）、
`terminal_module_new_violations`（该模块首次纳入后暴露的违规逐条）、
`existing_results_unchanged`（既有 18 个包的判据结论对照）、`deviation`（无则空数组）。

`report_result` 恰好一次，`presentation={"sink":"leader","class":"stage_result"}`，
`case_id` 用派单消息里给的值，**严禁 `sink=silent`**。
`summary` 说清：红测怎么证明修复前空通过、terminal 模块首次纳入后暴露了多少违规、既有结论有没有被打坏。

## 纪律

- 一个回合内连续推进，不要读完文件就结束回合。
- 判不出就停下问 leader，不许猜。
