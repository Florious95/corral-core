# 远程Agent安卓 — leader 换手交接（2026-09-07）

本文件覆盖旧 HANDOFF-current 的 09-06 状态。用户明确要求「现在开始交接，接下来会有其他 Agent 来代替你」，随后要求「关闭一下多余的席位」。已完成指定团队的多余席位清理；没有为最新 App 问题新派单、改码或新开 Issue/PR。

## §0 接手先做什么

**现状一句话：生产已更新并由用户手验确认 Pi 名字/工作态正确；当前只处理 Codex 在 App 内名称错误、三处工作态不一致，服务端与 Pi 冻结。新一轮 App 复现尚未派出。**

开口第一句建议：
> 我接手后只处理 Codex 在 App 三处的名称与工作态分叉，Pi 和当前服务端不动；先核真实 APK 与同一 ref 的三处表现，不把构建通过当手机已修。

### 必读清单（按优先级，不通读历史大树）

1. 本文件，尤其 §2「用户最新事实与待确认项」、§4.1「尚未派出的 App 任务」、§5「当前生产与不可重跑的脚本」。
2. `/Volumes/nvme/Projects/远程Agent安卓/.team/TASKS.md` — 当前工作状态；`/Volumes/nvme/Projects/远程Agent安卓/.team/WORKFLOW.md` — 当前流程与模型。
3. `/Volumes/nvme/Projects/远程Agent安卓/.team/regressions-20260907/GOAL.md` 与 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/status-contract-astra/PR-TASKS.md` 的 T-A/T-NAME/T-COMP；最新用户反馈覆盖过时排期，不重做首轮审查。
4. `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/status-final-accept/ACCEPTANCE.md` — 唯一独立验收报告，**不是 PASS**；注意下文区分报告与用户后来手验。
5. App 交付：公开 GitHub `Florious95/corral-core` 的 `NAME-A-DELIVERY.md@02915145cc8d5f1836c3b04bdd5ad72d5304adeb`；原作者上下文在保留的 `app-fix-luna`。不要猜不存在的根目录报告副本。
6. `/Users/alauda/.pi/agent/AGENTS.md`、`/Volumes/nvme/Projects/远程Agent安卓/AGENTS.md`。缓存中的强制 Grok 规则已经被用户撤销；看 §1。**不读/写 memory。**

### 恢复工作流程

1. **先核对，不先干预。** 在工作区执行只读检查：
   ```sh
   cd /Volumes/nvme/Projects/远程Agent安卓
   git rev-parse HEAD
   git diff --cached --name-only
   lsof -nP -iTCP:9900 -sTCP:LISTEN -Fpn
   team-agent --version
   team-agent status --workspace /Volumes/nvme/Projects/远程Agent安卓 --team regressions-20260907 --json
   ```
   写前主仓为 `main@c38ed21ab9f4f94a26bba8ac10369cade620abef`，暂存区空；本次交接随后只提交文档，最终 docs commit 不自引用进本文。生产应为 PID `92596` / `*:9900`，身份见 §5。生命周期查询的 `agents` 键清理后仅 `app-fix-luna`；不要用 `worker_state`、`last_output_at` 或 `stale_last_output/stuck` 判活。
2. **恢复守护的例外：不要启动旧 heartbeat。** 它是因全局 f629 会 capture-pane 而有意卸载并加拒绝闸，不是 compact 丢失。当前生产使用私有新 N，但全局 N 仍旧版，不能因此恢复旧 heartbeat/watchdog。没有需要复活的旧 ledger、后台构建或等待中的 CI。
3. **接管本队，不重建全队。** 本队为 `regressions-20260907`；只剩 App 席。新 leader 若需要绑定，先读 team-agent skill/公开 help，在自己的 leader pane 按 `claim-leader --workspace ... --team regressions-20260907 --confirm` 公共接口执行。若 App 席实际窗口缺失，用 `start-agent app-fix-luna` 保留会话；不要默认 `reset-agent`、`add-agent --force` 或 `restart --allow-fresh` 丢上下文。CLI 返回结构化 action 时遵循公开 action，不读框架内部状态/代码。状态曾同时显示 `team:"leader"`、`ready:false` 和 agents，不能只看前两个字段判断席位不存在。
4. **恢复期间禁令：** 不重启/回退生产、不换 APK、不恢复已删席位、不清理工作树/磁盘、不读生产 argv/token/日志、不启动全局旧 probe、不补投旧任务。当前没有新的 App 派单，不能声称写手已经在查。
5. **恢复完成标准：** 已确认当前 S/N/A 身份、仅保留的 App 席、用户最新 App-only 范围与 APK 待确认问题；知道待办 #113/#117 未完成。随后按 §4.1 向原 App 席派一次有界任务即可，不再综合/双审。
6. **发现不一致：** 现场身份优先；先记录具体差异并确认归属，不能为了对上本文而杀进程、reset 分支或换包。手机安装身份尚未答复时明确未知，可以并行做私有 AVD 对照，不能假定用户没装新包，也不能假定已装。

## §1 身份与不变量

- leader 负责目标/判据/归属、客观核证与授权运维。代码探索、根因定位、复现/修复由执行席做；不再把大树、整份日志/截图流倒进 leader 上下文。
- 基本工作节点：**Pi / `openai-codex/gpt-5.6-luna`**。高级节点：**原生 Codex / `gpt-6-astra`**。用户已撤销强制 Grok Build `grok-4.6`；不自动操作其他团队或更改 leader 模型。Pi role 不加 `provider_builtin`；`dangerously_skip_permissions: true`。保留 App 席即上述 Pi/Luna 配置，不声称从注册信息核出了实时模型调用。
- 当前采用 `stable-pr-workflow`，不是旧 ledger-run。用户反复要求收敛：不新增调查/综合/交接席，不双审，不一份结果改写多轮收据。工作状态只更新 `.team/TASKS.md`，证据引用现有执行产物；本交接是用户明确要求的换手例外。
- **真实复现 → 对应 Issue/owning PR → 最小修复 → 同条件回归。** 未复现不猜修；不要把多个未知根因硬并为“重复代码”。先追到同一 ref 的原始数据、解析、状态/名称投影、渲染的真实分叉。复用已有 helper/类型，不给三处各加 provider/health 特例，也不造通用状态框架。内层绿/白点与外层动画可以有不同视觉形式，必须共用一致的名称/活动语义；不是要求重写页面或强行共用一个UI组件。
- 构建/测试远端；**本机禁止 Go/Gradle/Rust 编译**。现在用 GitHub-hosted CI，源码/产物经 Git/GitHub 交付；Go `-count=1`，Gradle `--rerun-tasks`。Robolectric/CI 绿不是实际 APK/真实 CLI/UI 验收。性能 A/B 本轮不做、不阻塞、不写 PASS。
- 所有产品写手独立绝对 clone/worktree；写/提交/push 前 `rev-parse --show-toplevel` 必须严格匹配，不能让 `git -C` 向上找到主仓就当成功。同一 App 模块只有一个写手。主仓 main 没合这批 PR，不能读 main 产品源码推断线上实现。
- commit 无需再问，但只提交声明范围；不 `git add -A`，不夹带旧脏改，不写 `Co-Authored-By: Claude`。当前**没有 merge/关 Issue 授权**。用户已授权一次生产手验更新且已完成；最新 App-only 范围下不要再动已验服务端。
- 正常派单用 `.team/ta send <完整目标> ...`，带 intent；`report_result` 一次+落盘。只有真实编排/安全阻塞才消息，勿催进度；send 成功不 poll。每个对方每日最多10个往返，不能换名字规避。已清理的 ui/probe/Astra/chain 不再索料。
- 想使用 ArchWiki 时读 `/Users/alauda/.agents/skills/archwiki/SKILL.md` 及其 `references/METHOD.md`；本轮最后只加载了 SKILL，尚未为新 App 任务运行工具。必须用实际适配入口和真实扫描范围，partial/scan0 不是 PASS，不修框架/改判据造绿。

## §2 用户最新事实与待确认项（优先于旧报告）

### 原话与裁定

用户在生产更新后说：
> 「pi工作状态和名字都正确了。但是Codex的名字和工作状态都是错误的。但是服务端这方面没有问题，因为我的桌面端能够看到它准确的名字和工作状态。就是APP的问题。」

又提供同一 App 内的对照：
> 对话界面收藏按钮展开后，同一 Codex 的节点前是绿点（在工作）；外部会话列表与外部收藏列表却没有“在工作”的动态效果。

并批评不同位置不同处理、重复代码、契约未收束，以及“心里虚拟地完成，真实世界没有完成”。

当前事实：
- **Pi（派，不是 Grok）名字与工作态已被用户真机确认正确。** 不再计入本轮待修，不回退其实现。
- **Codex 桌面名字/工作态正确，手机不正确。** 当前服务端先冻结，只处理 App；不是已钉死某个函数的源码根因。
- **Codex 对话内收藏绿点、外部两类列表不动** 是最重要的新复现线索。最终要同一 ref/同一时间/同一真实 Codex 对比三处，不是各自用合成样本宣布 PASS。
- 用户本轮没有给新的 Grok 真机结论，既不偷换成 Pi，也不擅自将其标通过或混开新修复包。

### 尚待回答的问题

上任已问、用户还未回答：
> 手机是否已覆盖安装 `NAME-A-04bfde8` 这个 APK？

所有近期包 package/versionCode/versionName 相同（1 / 0.1.0），**当前正在手机上运行的 APK 身份未核实**。新 APK 已生成、复制 Downloads 不等于手机已装；同时，不能以“可能没装”否定用户的真实故障。私有 AVD 可先做参考/精确候选有界对照，区分交付未生效与候选确有分叉。别反复追问同一句，更不能没确认就先修想象中的代码。

## §3 插队项与流程教训

### 本次用户手验覆盖原排期

原全量独立验收已交 `FAIL_WITH_UNJUDGEABLE_GATES`；随后用户直接手验提供了更强的 Pi 通过、Codex App-only 三处不一致事实。**新 App 复现优先于旧“再查服务端/再写综合”的排期**。旧未闭门留存，不用它们把用户当前问题拖回全系统调查。无新 P0 级别擅自定级，当前阻塞见 §4。

### 必须避免重复的错误

1. **派单 ≠ 已实跑。** 上任曾把“验收席已启动/已派单”说得像正在全链验收；后来才核到隔离 PID9242/19090。该隔离服务已清理，不能再把它当当前生产。
2. **隔离环境 ≠ 用户手机环境。** 独立席用19090，用户手机用9900。用户明确「直接更新来验收」后，才实际切生产到92596。切换事实已核，仍不等于所有功能过。
3. **NoEnter 曾陷无效定位循环，已彻底收口，勿再查。** 原唯一超时是测试把 PTY CR 当成必须 LF，且 mirror 等待丢弃先到的 ack。test-only `d1360c489eab629987b0dbe6a7051ec82882c546` / `2607711c794ceed2ae377e06bf5f95d7b25174b4` 修正后，真实 focused+全API/protocol通过；已接回 PR19。不是产品 mirror 回归。旧 `TestInject` 曾被误称通过，实际 no tests to run，已撤回。
4. **工作树误操作历史。** ui-repro 相对路径曾在主仓误造提交13376e0d67b93c54942bc8b3cb875b72531c8a28并自行update-ref回7989d3330d268cb8d47825dd7fc30897ed4fd0ea；已停止进一步补偿，旧脏改仍在。不要清理遗留 nested worktree/.team 目录来“收尾”。
5. **远端清理越界历史。** bot-space误把共享库存当owner，清了6个unit（合计7792KiB）。只有受管文件7/7哈希保留，不是完整workspace/cache/input可恢复。已上报、停止远端清理，不再为此调查/扫描/等回信。原案见 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/status-bot-space/SPACE-RECEIPT.md`。
6. **网络错误可泄露临时签名URL。** 一次 gh artifact TLS错误输出带出短效URL，已一行上报并收紧：只输出错误类别/退出，不打印原始URL，不因泄露停工或删证据。

## §4 未完成任务与保留能力

### §4.1 第一项：Codex App 三处不一致（尚未派单）

- todo：#113 `in_progress`（当前换手）；#117 `pending`，题为“修复 Codex 的 App 名称与三处状态分叉”。#112 是上一轮开发/CI/产物阶段完成，不是最终功能验收完成；#115 NoEnter、#116 发布/clean构建已闭。
- 拟执行者（**唯一保留席位**）：`/Volumes/nvme/Projects/远程Agent安卓::regressions-20260907/app-fix-luna`。它已完成上轮 APK，**没有收到这次新任务**。原 role 文件可能仍写旧 T-A-only；新任务正文须明确接替旧范围，模型不变，无需为了改任务重启丢上下文。
- 第一动作：恢复核对后用 `.team/ta send` 向该完整目标发一次任务：核当前APK身份；以精确候选/参考、自有AVD和同一WS/ref复现内收藏点与外会话/收藏两表的名称、working→idle差异；沿真实数据→DTO→投影→渲染找到首个分叉。服务端/Pi冻结；未复现不改产品、不新建Issue；复现后再归 owning PR73/89，不能先编引入PR。
- 判做完：当前候选的真实红（或证明确为交付身份问题）及同 ref 三处的具体字段/调用路径；若需改码，复用既有统一语义入口，最小差量，actual APK 三处同一真实Codex的名字/工作-结束同步证据，不以三套独立测试代替。不要新造状态框架或分别加health/provider特例。
- 合法未知：手机当前APK身份待用户回答；这不挡自有AVD对照。无新开发常驻进程/任务PID可查，进度只能在实际派单之后按对应report_result/产物判断；不能把“保留的席位存在”当已在处理。

### §4.2 当前 App 与 owning PR 身份

- App候选构建 source：`04bfde8d161c244c9dfced3b5ab90d22d1460d86`，tree `6121f88087b80ba1014d843fcba13a3528598002`。
- PR89：`https://github.com/Florious95/corral-core/pull/89`，最近已核文档头 `02915145cc8d5f1836c3b04bdd5ad72d5304adeb`，base `base/status-name-a0@e42619b31803c28fec940f085d6897241ca7b9b5`。当前需施工时重核远端head，不能盲推；04bf相对前一已验ccd仅CI差量，不是新的产品实现。
- A0 是公开已验 C76 `596fe65179e73210699f3e84c40168c930bbbbca` 加 PR73 精确活动差量，不是整棵PR73覆盖C76。PR73最近已核head `e7e7779085facbcc44d44520888100211da388d2`，产品/测试源 `cfb8d32a11cd3a97d2a096d57b09d7bc184924d9`，base C70 `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`。
- APK（本机文件身份本次重新核过）：`/Users/alauda/Downloads/AgentMirror-Three-Surface-NAME-A-04bfde8-signed.apk`；35610017 bytes，SHA256 `bd4ababfcec3301c19ced02350ff2193f183311f9ec0bdd455145deab3b3e861`。原交付副本：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/name-app/tmp/name-a-release-download/AgentMirror-Three-Surface-NAME-A-04bfde8-signed.apk`。
- GitHub draft prerelease `name-a-04bfde8d-api35`，release383926908/asset548427762，API asset摘要与本地一致；未正式发布。实际构建 run `34098991746`，head `04bfde8d161c244c9dfced3b5ab90d22d1460d86`，hosted成功；作者收据75 tests/57 assemble tasks。此前 run34096661280 的75/0/0/0与41实跑task由leader读日志核过。
- 参考：`/Users/alauda/Downloads/AgentMirror-Three-Surface-596fe6517-signed.apk`，SHA256 `0c9aa3285689d9f4004fb2570f3e5af65bbab6e67437343a78496b15c6d2cf30`。两包accepted signer `ea427eb4e14f95654a66802b6558fbbf6f93f1ca69d8117795fb7cef376cb13b`、dev.agentmirror.app、versionCode1/versionName0.1.0/min26/target35；独立席已做自有AVD覆盖安装，**不是用户手机安装证明**。

### §4.3 唯一独立验收报告（历史证据，不扩大其结论）

- 负责人 `status-final-accept-luna` 已关闭，report `res_0386d4d42db8`；报告 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/status-final-accept/ACCEPTANCE.md` 与 `acceptance-evidence.json`。
- 其报告：真实三CLI来源 S→N→WS活动、部分真实Pi名称、最终artifact G-SAFE、主要AVD投影有证；Codex官方双中文名装置没给出要求样本，Pi/Grok缺同刻AVD帧，完整多socket/退出残留等独立兼容未闭；N/Go strictT3仍partial/scan0。**没有最终PASS。**
- 其称离线收藏冷启退node为FAIL；这个观察保留，是否命中本轮冻结的“在线名称/离线历史投影”契约需核定，不在未比契约前自动扩写迁移或追加新PR。用户后来确认Pi通过、Codex App-only优先，覆盖旧Pi未知状态。
- 隔离S PID9242/19090、真实CLI与AVD emulator-5562已由该席报告清理。本轮写前已核生产是92596，而非9242。QA曾用 `/tmp/sg-status-final-accept` socket，不应照抄到新测试；本项目要求项目内自有临时目录与短socket自检。

### §4.4 可延后/禁止恢复的旁支

- 全部 PR 仍未merge/关Issue。历史自动发现/TS端到端、性能采样器、远端磁盘等不因换手复活。
- 旧 NoEnter 已闭，不做第N轮“可能是某层”归因。原 S/N 作者、审查、空间席已收工且已清理，不再索料。
- 原 core#87 误开的灯修复已关闭，不复活其错误 Grok/Pi 归因。用户已验的C76三面显隐、全部socket/普通shell/退出残留能力必须保留，不能拿当前Codex问题回退它们。

## §5 运维、席位与现场证据

### 当前生产（本次换手前重新核过）

- PID **92596**，`lsof -nP -iTCP:9900 -sTCP:LISTEN -Fpn` 返回 `p92596 / n*:9900`。
- 运行二进制：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/_driver/deploy-status-20260907-183711/inputs/agentmirrord`；31143522 bytes，SHA256 `1c9a07af18e0b0cd530ed1f50e0a8ec22c2a7405933dd2dfa8d11a14b5d3100f`。`server/agentmirrord` 原子更新为相同字节，旧mapped artifact未覆盖。
- S source `0f9010ce335833790c08431bee778b7ca98e2822`，tree `7758dd60d6aab573e83824349ed12fb2153ad1fd`，clean VCS。Git carrier `befa2534f1cc9486a1e3e4490aee3f888a673ebc` parent即S，`artifacts/agentmirrord-darwin-arm64` blob `4e9f3f46960c855e9ca29244b01bfa049346073d`；远端ref/对象与CI字节算出的blob已核一致。消息曾误抄tree后半段，以此/API/DELIVERY为准。
- S保留S-COMP `7e55502f0a0c9eda77c3f7c791b36d3432024979` + PR6 `40496820b05be6b29ec439de5ad1dc196a773c9f` + PR19 `5668ca102aee17cc6a431b1f4e5689e061edfa4d` 的命名/测试差量。Darwin34104886796、Ubuntu34104897413同S SHA均success；leader核Darwin原始JSON188兼容pass+专用GSAFE另1pass。PR19 run34103044843已核成功。
- 私有N：同一部署目录 `inputs/nodeprobe`，885504 bytes，SHA256 `e5667b9ebe931de7805a87538e03b9a6ee1fb9cb9250c25282f8b054268226b5`。载体 `corral-core@4619b561ddbb8e931fbf17b280cd69edad52f58d`；实际N构建checkout `6e47021a7e3502843d490a2eeb0342c78c0231f3`，公开同树源 `4f77e3bdc2df08c81b5f50c13050cbddc7053b62`，tree `047bfb0539047f592ef64d4d8bf9dfaba4278e20`。
- **全局 `/Users/alauda/.local/bin/nodeprobe` 没替换**：600112 bytes，仍 `d60cacc86f5ec432ddf8562782b7eb483e46c8b17e45a43d6c48f7f69ec7497f`（旧f629，会capture-pane）。不能因为看到全局旧N就说生产没更新，更不能用它判活。

新S的显式、非敏感配置（由部署脚本设入子进程）：

| 变量 | 值 |
|---|---|
| AGENTMIRROR_NODEPROBE_BIN | `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/_driver/deploy-status-20260907-183711/inputs/nodeprobe` |
| NODEPROBE_FIXTURES | `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/_driver/deploy-status-20260907-183711/inputs/titles.tsv`，SHA256 `cff45d25492fdfe9689330c630c80bad20a1f27243e5aae1d93bc57de0a22b58` |
| NODEPROBE_PROVIDERS | `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/_driver/deploy-status-20260907-183711/inputs/providers.tsv`，SHA256 `c68f50115b33ae6a6806b463cc27c71d8bbfc92273435a533502e9bb84b8b522` |
| AGENTMIRROR_NODEPROBE_PI_EXTENSION | `/Users/alauda/.pi/agent/extensions/nodeprobe-pi-activity.js`，3572 bytes，SHA256 `51ffcad3f68ac22330d98ba0240b81f41b210939709a91637c917e1555494c27` |
| NODEPROBE_PI_ACTIVITY_DIR | `/Users/alauda/.local/state/nodeprobe/pi-activity` |

**不要默认启动新S却遗漏以上绑定。** 主仓 `tools/nodeprobe/fixtures/titles.tsv` 仍旧 `c0ec2de2e9aae61c200e1fd65e9865664bd4bcddc4a17361c1eb6bb2673e91ae`，不是新N的语料。

部署收据/备份：
- `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/_driver/deploy-status-20260907-183711/receipt.json`，仅含我方生成的非敏感路径/hash/状态，**没有argv或token**；`backup/` 有旧root daemon、旧mapped daemon与旧global N。
- 旧mapped `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/server-enum-hand/bin/agentmirrord-33b4c48-darwin-arm64` 仍为 `5dabb1709ffcda94bb71f273496756849150531f7379b7c78fa1605065e00452`，未覆盖。
- 一次性 `switch.py` 沿现有swap脚本的opaque参数内存管道继承，仅改可执行入口并绑定私有N；监听失败时在内存参数尚在时恢复，本次成功未恢复。**不要复跑它：receipt仍钉旧PID63994，是一次性部署脚本。**
- `tools/swap-prod-daemon.sh` 原版会cp覆盖当前mapped不可变产物，再执行原argv[0]且不保留上述私有N环境，**也不能在此布局直接照抄执行**。现在只修App，无理由重启生产；未来确需换S，先核当时PID/路径并沿受控内存参数继承重新安排，不先杀进程后手拼启动命令。
- 没有既有安全生产authenticated-listing入口；自动同ref计数未跑，不能读token补洞。用户Pi手验成功证明手机实际功能可用，但不是程序化全UID列表差量门的PASS。

### 席位清理（用户最新要求，已核）

只操作本队 `regressions-20260907`，**保留 `app-fix-luna`**；已关闭：bot-space-luna、chain-audit-luna、contract-astra、probe-fix-luna、status-final-accept-luna、ui-repro-luna。

`team-agent remove-agent` 首次分别要求force/from-spec，已按工具返回的exact action执行，其余用已确认的 `--from-spec --confirm --force`。六席均返回removed，随后canonical status的agents键仅app-fix-luna；生产92596仍在。未删工作树/分支/产物。收据：`/Volumes/nvme/Projects/远程Agent安卓/.team/artifacts/seat-cleanup-20260907.json`。保留席位注册provider=pi/status=running，**这不代表有新任务在执行**。不自动复活任何已删角色。

### 外部/资源

- Grok Bot 20GiB门此前未过，全部远端清理/重复preflight已停；GitHub hosted已解决构建路径，不等远端磁盘。未知12.31GiB unit没有授权，未动。
- GitHub既有SSH可用于普通push；gh读取存在席位间TLS差异。用现有通路有界观察，禁止补scope/换身份/改代理/重复失败端点。先前leader代收过具体run，不搭新观察系统、不为docs头重编已验产物。
- 框架维护通道：`/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader`；之前磁盘归属/越界告知msg_d13dfe6c66a8、msg_759c004ab618已投（当时未attached），不等回复、不为其取证。只有真实框架缺陷才一封事实+路径，报完继续自己的产品任务。

## §6 安全约束（原文保留，不弱化）

- 「禁止写 memory(memory 系统已废止);关键知识只沉淀到对应项目的 skill 文件。」本文件是用户明确要求的交接，不是memory系统。
- 「禁止用 AskUserQuestion 工具问用户;一两句话能说清的直接在对话里问,别铺陈。」
- 「密钥只存在于 `.team/current/profiles/*.env`，任何席位禁止读其原文。」
- 「`.team/current/profiles/tailnet-test.env` 全员禁读（含 leader）。里面是用户 tailnet 的 auth key，只能通过 `TS_AUTHKEY` 环境变量注入测试节点，任何形式的 cat/grep/plist/Read 都禁止。取值只用 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。」本次App任务不需要该文件。
- 「查任何配置前先想凭据」；禁止无过滤 `ps aux`、`tail .team/logs/agentmirrord-prod.log`；「Shadowrocket 的偏好 plist 与 `tailscale_keys.bin` 列入禁读。」不读 `.cursor/mcp.json`、`.grok/config.toml` 等未知配置原文来审查脏改。
- 「凭据已泄露 ≠ 停工」：只做「一行上报（不复述泄露的值）、就地收紧做法、继续干活」；不等新key、不删本地产物当作风险消除。gh/curl错误也不得原样输出带签名URL。
- 「席位不许写 `/tmp` 或任何项目外路径，临时文件写 `.team/nodes/<格>/tmp/`。」新的私有socket需短路径预建且自检真实socket归属，不能以“我用了-S”推断隔离；不要复制旧QA的/tmp路径。
- 「给席位发消息只走 `team-agent send`，禁 tmux `send-keys`。」不操纵用户真实pane/其他团队；自有测试fixture输入与被测采集须分开，不能用测试名义控制工作席。
- 不读真实pane正文、进程argv、用户会话库/真实消息、生产日志或token。旧f629/817/5ee只可在自有合成隔离fixture做拒绝反控，不可用于真实/default/shared/他队socket。全局旧N不能用于判活。
- 单独的生产运维例外仅为已有的opaque启动参数内存管道继承：不输出、不落盘、不提取token用于别的目的，不把例外扩大成凭据取证。用户手机不由席位操控；AVD只本席拥有实例。
- 「不写 `Co-Authored-By: Claude`。」主仓已有大量M/D（含配置、历史ledger/role、CLAUDE.md、tools/nodeprobe/src/classify.rs、旧daemon文件），不读其敏感内容、不restore/reset/clean/夹带。只有本次声明的handoff/TASKS/清理收据入文档提交。
- 「禁止为框架队取证。唯一配合的事项是：换用他们发布的新基础设施。」不私改其skill/引擎；不因其问题停产品，不人肉无限消息往返。当前不要把ready/worker_state显示当新框架调查任务。

---

后继开口第一句：**「Pi和当前服务端保持不动；我只处理Codex在App三处的名称/工作态分叉，先核真实APK与同ref证据，不把未派单或CI通过说成已经修好。」**
