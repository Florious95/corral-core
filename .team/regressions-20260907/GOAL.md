# 目标：恢复 Agent 工作状态与会话名称的可信展示

- 用户启动授权：2026-09-07，要求直接完成；基本工作节点 Pi 驱动 Luna，高级节点 Codex 驱动 Astra。
- 编排基线：主仓 main `1f170c6223f78a27934f208c8e411a5ed3185794`。主工作树存在既有未提交改动（含 `tools/nodeprobe/src/classify.rs`），禁止 reset/clean/stash 或夹带。此 SHA 不是已证明的线上版本。
- 历史手验参考：daemon `33b4c481e7545e1194935fd9ea373ed035463bea` / nodeprobe `f629d775`；App PR76 `596fe65179e73210699f3e84c40168c930bbbbca`。均是 09-06 坐标，首轮必须核今天现场，不能照抄当现行真相。

## 五项用户事实与需求（完整冻结，Pi 不是 Grok）

| ID | 用户事实 | 必须恢复的可见行为 | 当前责任口径 |
|---|---|---|---|
| S1 | Grok 在工作桌面可见、手机不可见 | App 正确展示 Grok 工作动态，完成后停止 | App 回退；仍核实际输入帧，不假设根因 |
| S2 | Codex 是否在工作显示错误 | Codex 空闲→工作→空闲准确自动更新 | 责任端与引入 PR 待证据 |
| N1 | Codex 名称显示 `node`，之前尝试解决 | 显示能辨识会话的正确名称，不能用运行时 comm/随意编号顶替 | 责任端与引入 PR 待证据 |
| S3 | Pi（口述「派」）以前 App 有工作动态，现在桌面和 App 都无 | 服务端正确输出 Pi 工作态，两端正常展示 | 用户裁定服务端回退，不得覆盖 S1 |
| N2 | Pi 曾有一段时间名字正确，现在全是 `node` | 恢复 Pi 正确会话名，多会话不混同 | 用户新增服务端回退；不得混作 N1 |

用户要求：找出每项命中的仓库、PR URL、引入 commit/parent，回退造成回归的引入点；不能靠无关补偿 PR 盖过去。若最终证据仅为部署版本/接线落后，明确写“部署/接线问题，尚无代码回归 PR 证据”，不得硬指 PR86。

## 已调通 MVP / 单一实现权威

必读 `/Users/alauda/.agents/skills/tmux-node-activity/SKILL.md`。用户明确它是已经调好的 MVP，应复用、对齐，不重新发明。

- 唯一判定实现 `nodeprobe`，安装 `/Users/alauda/.local/bin/nodeprobe`。
- 全局 Pi extension `/Users/alauda/.pi/agent/extensions/nodeprobe-pi-activity.js`。
- canonical corpus：本仓 `tools/nodeprobe/fixtures/titles.tsv`、`providers.tsv`。
- 四轴 `provider/activity/session_name/health` 独立，health 不折叠进 activity；unknown 不当 idle/normal。
- 报告 envelope schema=1；Pi lifecycle channel schema=2，不能混淆。
- Pi 工作状态来自官方 lifecycle channel：agent_start/tool start→working，agent_settled→idle；静态 π 标题不能判活。
- Pi 名称来自官方标题或 channel；一致合并，缺失/冲突按 skill，不猜。
- 非 Pi 复用各 CLI 已验收规则；Codex 10 帧 spinner 不可扩成全部盲文。

## 2026-09-07 安全插队（覆盖此前安装探测器可安全观察的假设）

Astra 源码发现：实际安装 f629 的 `src/lib.rs:226–260` 定义 capture_pane，probe 每 pane 调 `capture-pane -S -30`。因此 **skill 的只读结构契约不等于当前旧 binary 的实际行为**；只过滤最终 JSON、只挑本队名称都不能消除正文读取。

- 禁止本轮再向真实、默认共享或其他团队 socket 调这枚旧 probe，包括自检、判活、heartbeat。
- f629 A/B 仅能在本席自建、物理独立、只有合成会话的隔离 socket 执行。
- 本队 heartbeat 已卸载且 wrapper 加了拒绝运行闸；只能在无正文读取的新产物通过验收后恢复 trial/install。
- 未停生产服务，未声称其内部旧 probe 调用已经消失。安全链修复与产物更新进入本任务，不能用“心跳停了”冒充产品安全验收。
- 新探测产物的验收必须证明实际 tmux 调用无 capture-pane、进程快照无 argv；源码检查 + 隔离命令观测/禁止调用反控，不只看 JSON 字段。继续其他隔离复现与单一源码路线裁定，不为此停任务。

## 首轮必须闭合的三个假设

1. **版本没有更新**：运行进程、加载二进制与磁盘路径/sha/源码提交是什么？历史 PR HEAD、最新源、构建产物、已安装文件、已运行进程是否同一条谱系？扩展/corpus 同查。
2. **接线未更新**：serve 实际调用哪个 nodeprobe/参数/环境/corpus/schema；是否仍用旧路径或缺 Pi extension；实际 WS 字段是否保留四轴？
3. **多源漂移**：skill→nodeprobe→serve 适配→App 投影是否出现第二份分类器、重复名称解析、health/activity 混用、session_name 丢失或 window_name=node 覆盖？只用证据归因。

## 范围与非目标

- 先只读审计与复现，再由 leader 冻结责任 PR 集合及写界。首轮两审计席不写产品；现已进入 `DECISION.md` 准入阶段，新写手只能按该裁定和 Astra `PR-TASKS.md` 的对应任务施工、更新 owning PR。不换任何全局 binary/extension。
- 不重做已手验：收藏三面离线投影、全 UID socket 枚举、普通 shell 过滤、Ctrl+C 成 zsh 残留身份、schema 空列表修复。它们仍是兼容回归门。
- 不改图标、布局、发现链、tailnet 身份、不做性能 A/B；功能状态更新延迟仍遵守 061 的 ≤5s，不冒充性能 PASS。
- 不碰其他团队/工区、不影响现有未提交改动；不恢复或重用旧 Grok/Qwen 席。

## 可观察验收

1. 分别用真实 Grok、Pi、Codex 做空闲→工作→空闲完整循环；probe 四轴、serve 同 ref 实际 Level2、客户端 UI/动态证据对应。≤5s 自动反映，不靠退出重进；未知不假装空闲。合成帧只能验投影，不能代替真实 CLI 全链。
2. Pi/Codex 多个已知不同名称（含中文）在列表、在线收藏、会话标题一致；任务运行/结束、重订阅、App 回前台后不掉回 node。显示名不改变身份键或收藏引用。Codex 具体正确名字来源由历史与现有契约核清，不套用 Pi 或 Claude 规则。
3. 改探测/服务端时用同一受控 daemon、同一组会话夹具、同一个 listing 请求对比新旧条数与 ref 集合；不得空列表、丢在跑 Agent、增加普通 bash/zsh。更新生产前后亦核实际 listing 与监听，不拿 cargo/test 绿代替。
4. 名称用 UI 树具体文字断言，动画用连续帧/录屏加语义状态；保留改前红、改后绿与不倒退证据。测试禁止缓存。
5. 每条结果注明 source SHA、artifact SHA、环境、命令、退出码/实际执行数、失败名集合、证据路径。作者不自验；独立验收一个出口，不做双审。

## 交付与流程

PR-centric：审计/复现→一次 Astra 契约与因果分组裁定→在命中的 owning PR 撤回引入点并最小修复→独立 Luna 实测→leader 核证。不同根因分 PR，同一根因接口与调用方一并修，不旁路补丁。源码/远端构建产物走 Git。

阶段一仅调查；完整五项都有事实/未知分类才划修复包。后续任务书应编译 basegen/架构影响闭包；使用独立 wiki-tooling，不复制其实现。archwiki 与 CI 按影响范围验，不擅自把工具不可判标绿。

不可逆边界：当前不 merge/关 Issue/部署。需要线上变更时 leader 先明确候选和证据，再按用户授权及 `tools/swap-prod-daemon.sh` 备份→安全继承配置→替换→监听/listing核验；席位禁止碰生产。不得用以前错误的回滚循环顶替定位。

## 安全与沟通

不读 pane 正文、真实对话、进程 argv、凭据；只读 nodeprobe 安全结构输出。任何 `.team/**/profiles/*.env` 和 provider-env 禁读，尤其 tailnet-test.env。不得打印生产日志/配对 token。生产 listing 若缺安全认证路径，只报该缺口并继续隔离可执行验证，不能自行读凭据。

临时文件只写各自 `.team/nodes/<本席>/tmp/`；隔离 socket 必须短、预建、自证，不动用户 tmux。不本机 Go/Gradle/Rust 编译，构建与大测 Grok Bot（先读对应技能）。正常仅中文 report_result 一次+落盘产物；只有真实阻塞/编排调整允许一封说明，不发进度、不跨席聊天、不私开子团队。
