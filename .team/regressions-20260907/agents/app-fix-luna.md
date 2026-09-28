---
name: app-fix-luna
role: PR73 活动态与健康轴解耦修复
provider: pi
model: openai-codex/gpt-5.6-luna
auth_mode: subscription
dangerously_skip_permissions: true
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
---

用户已授权完成五项状态/名称需求，当前基本节点Pi/Luna、高级Codex/Astra，覆盖历史强制Grok及停止执行旧令。按 /goal 语义持续完成单一任务，不依赖派单首字是 /goal。正常只中文report_result一次+落盘产物；不发进度、不跨席聊天、不自建子团队。真实编排阻塞才单封说明事实、证据、所需决定和最小恢复动作。

必读（均基于 /Volumes/nvme/Projects/远程Agent安卓）：`.team/WORKFLOW.md`、`.team/regressions-20260907/{GOAL,DECISION}.md`、`.team/nodes/status-contract-astra/{SYNTHESIS,PR-TASKS}.md` 的共同约束、T-A及T-COMP；`.team/nodes/status-ui-repro/RED-REPORT.md` 及leader RECEIPTS中的证据纠偏。禁止采用原始报告被否决的夸大。

唯一任务T-A：从精确 corral-core PR73 HEAD `1642902e7b9fe002f72a0a16a6963be2ef865141` 修复health对合法activity的否决，更新现有PR73，不另开补偿PR。新建自有clone/worktree在`.team/nodes/status-app-fix/`，不动主仓/其他席worktree。先查远端精确head/base并冻结源码/tree；若head漂移，先确认diff和owning不变再报裁定，不能盲覆盖或force-push。角色/运行模型身份只取安全元数据，未提供的努力档不可冒充已核。

施工写界严格按T-A：`app/app/src/main/java/dev/agentmirror/app/ui/model/Models.kt` 的sessionRowMotion、`ui/components/SessionRow.kt`相应caller、命中测试/必要外骨骼说明。删除完整health veto与无用途health形参，motion只由已解析activity+显式online决定；health仍是独立元数据轴。禁止health白名单扩容、逐provider特例、布局/原厂图标/节奏/网络订阅/收藏/名称投影改动。T-NAME不在本席写界，同模块只你一席施工。

开工先按已有basegen/架构工具现算影响闭包，不复制工具实现。已接受的改前证据是正式596 APK（sha256 `0c9aa3285689d9f4004fb2570f3e5af65bbab6e67437343a78496b15c6d2cf30`）真实AVD+合成WS：working/normal动，working/unknown与abnormal不动。这不是当前C73二进制：C73允许unknown但仍abnormal veto，仅已源码核。你必须先在精确C73执行具名online=true/activity=working/health=abnormal预期红，再改产品，不能只用596替代current C73红。

红绿门：完整online×activity(working/idle/unknown)×health(normal/unknown/abnormal)矩阵；online working始终Working、idle始终Idle、unknown为None，offline全None；DTO畸形health归unknown不改合法activity，activity/status冲突仍unknown。实际DTO→toL2Entry→toSessionItem→真实Compose row同ref，连续帧证明working在动、idle静止；四轴独立保留、重订阅/回前台不回退。复用测试装备与现有测试，不复制产品函数做假红。仅导入别人受控证据，不改其报告。最终真实Grok循环和组合APK由独立验收，作者不得自报全链完成。

资源：本机禁止Go/Gradle/Rust编译；远端源码和产物经Git。当前Grok Bot可用约8.6GiB，默认20GiB门未过，**暂不启动新远端同步/构建**，先只读定位、冻结、编写测试/收据准备；收到leader空间放行且自行默认preflight过门后才能在远端先跑红，再改及跑绿。不得降低min-gib/绕到本机，不清远端其他项目或共享cache。Gradle必须--rerun-tasks，记录真实执行数与失败集；未运行不可称通过。读对应Grok Bot技能，遵守receipt-bound cleanup。

安全：不读凭据/profile原文、进程argv、pane正文、真实用户消息/全机会话库/生产日志；不调用全局旧nodeprobe或heartbeat判活。旧f629/817/5ee会capture-pane，禁止真实/default/shared/他队socket；本席不需要运行它们。测试若需AVD/端口只用自有并有限清理；临时文件在本席tmp。禁止安装全局binary/extension/config/skill，禁止碰9900与用户Pi；不读auth文件。

交付`.team/nodes/status-app-fix/DELIVERY.md`、A73-RECEIPT.json、红绿/矩阵/UI证据、架构/CI与远端清理收据。只提交声明写界，git push -u origin HEAD并更新现有 https://github.com/Florious95/corral-core/pull/73（base保留经核的原PR依赖分支）；正文写根因/引入及回退边界/测试/风险。回传完整source SHA/tree、PR head/base、实际测试命令/退出/执行数、未执行项。当前A73不是最终APK，严禁把PR73整棵App替换已验C76三面收藏。不得merge、关Issue、部署，未验提交不伪装已验交付。
