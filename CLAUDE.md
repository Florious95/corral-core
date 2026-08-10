# 远程Agent安卓（暂名）——工程编排约定

手机远程操控主机 tmux 中大量 Agent CLI 的开源产品（Apache 2.0）。
产品需求的唯一权威是 `requirement-base/`（先撞库再问用户）；任务状态的唯一权威是
`taskbook.yaml` + `.team/evidence/`。本工程按 taskbook-orchestration skill 运行。

## 目录地图

- `requirement-base/` — 需求维基（INDEX 索引 / entries 条目 / REVISIONS 修订记录），只增不改
- `taskbook.yaml` — 任务书（五栏+争议度）
- `agents/` — 席位角色文件（retired/ 为退役归档）
- `.team/evidence/` — 任务证据 JSON（状态唯一来源）
- 产品代码目录在架构裁定后建立

## 席位与模型

- teammate 一律第三方 API（compatible_api profile）；难点模块可开 Fable 5 短命席位（一次性，交件即退役）
- **通道判据（2026-08-10 用户复申，违者即浪费）**：默认 `provider: claude_code` +
  `auth_mode: compatible_api` + `profile: worker-api`（模型即 `deepseek-v4-flash[1m]`，角色文件**不写 model**）。
  升级 codex `gpt-5.6-sol` 仅两种情形：① taskbook 该条 `contention: contract`；
  ② 同一任务自动返工达上限（开顾问席熔断）。派单必须打印通道判据一行，跑偏一眼可见。
  实证教训：2026-08-10 我在派单模板里写死 `provider: codex`，全天所有席位都是 sol，又慢又拖沓且烧订阅额度
  ——这是决策错误不是配置意外，详见 `docs/next-round-plan-20260810.md` §4。
- **困难问题通道**（用户裁定 2026-08-10）：codex provider + `gpt-5.6-sol` 模型
  （profile `codex-default`，订阅额度已重置，**额度解限**——不受下条轮次/墙钟约束，
  但工程纪律「红测先行/隔离/basegen/交件即退役」照常）。Fable 5/Anthropic 订阅通道不再开发发用。
- **全自动编排**（用户裁定 2026-08-10）：席位间自行交接下一棒（含知识基底指针），期间不向
  leader 发消息；一般问题直投**裁定席 `adjudicator`**（codex gpt-5.6-sol，常驻，具 leader 同等
  裁定/验收/派单/commit 能力，章程见 `agents/adjudicator.md`）；leader 只收四类：用户指令、
  裁定席升级件（契约级/对外交付/通道变更/连环故障）、看门狗 escalation、框架直报回执。
  **2026-08-10 终形态（框架 leader 指导落地）**：框架 leader 绑定已转移至常驻 LLM-leader pane
  （tmux 窗口 llm-leader，codex gpt-5.6-sol 经 claim-leader 持有，owner_epoch 2，开机简报
  `.team/llm-leader-boot.md`）——report_result/abnormal_exit/五类关键消息全部注入它，
  由它独立完成裁定+验收+派单+值守，原 worker 裁定席由它承接后退役。人工侧（本会话）
  唯一接口=轮询 `.team/escalations-for-human.md`（四类升级件：对外交付/契约级需用户/
  通道额度/超自愈连环故障），零其他信息到达。
- **Anthropic 订阅席位用法铁律**（用户两次裁定 2026-08-10，额度见底实证；违者即浪费）：
  ①**一次性投喂**：派单一条消息给全流程、方向、需求、验收标准，之后不追加轮次；
  ②**汇报即关**：席位 report 完成的当刻 stop-agent 关闭，不做小修小改的往返轮次——
  小修小改一律另派第三方席位或留 leader 验收裁定；
  ③**杂务外包**：Fable 5 开发席必须配低成本第三方 teammate 承接跑命令类杂务
  （跑测试/起环境/截图/e2e 复跑），Fable 5 上下文只花在设计与写码上；
  ④席内扩案（新雷/新层缺陷）不得原席续干，leader 拆案改派第三方席位；
  ⑤墙钟超 2 小时未交件必须主动审视拆案。开发常规通道永远是第三方 API
- 密钥只存在于 `.team/current/profiles/*.env`，**任何席位（含 leader）禁止读其原文**；
  诊断只用 `team-agent profile show <name> --workspace . --json`
- **席位恢复纪律**（A-24 实证，2026-08-09）：席位恢复失败达 2 轮（自动恢复/start-agent/reset 任意组合）
  即弃 id——remove 归档后换**处女 id** add-agent 重建带案重派，不再消耗轮次；死 id 的 runtime 残留
  （provider-config/env/events）保留供框架取证。停摆检测与自动探针由 `.team/watchdog.py` 值守（三条件+预算 2）
- **派单必写 intent，否则该席对看门狗全盲**（2026-08-11 两次实证，同一个洞栽两回）：
  `.team/watchdog.py` 的 `inflight_seats()` 以 `.team/evidence/<task>.intent.json` 存在且对应
  `<task>.json` 未落盘来判"在途"。**人工派单漏写 intent ⇒ 该席位永远进不了停摆计数**
  （`turnend`/`still`/`idle` 三个计数器压根不为它创建），卡死多久都没人发现。
  铁律：`add-agent` + `send` 的同一批操作里**必写** intent，字段
  `{task_id, dispatched_to, base, case_id, at, note}`；`case_id` 取 `send --json` 返回的 `message_id`。
  两次实证：①2026-08-11 00:0x 手工派两席后全盲；②同日 03:4x 一晚派 11 席一个 intent 都没写，
  `w-doc-tsnetd` 卡死无人知，看门狗采样里跟的还是两个早已退役的席位。
- **判活性只看 pane 尾栏真值位，不看框架状态**（2026-08-11 实证）：框架对卡死 35 分钟的席位
  持续报 `worker_state=BUSY` 且 `last_output_at` 还在跳。Claude Code pane 回合进行中尾栏含
  `esc to interrupt`，回合结束则无——这是布尔真值位，能把"在长思考"与"已收工"分开，
  已落地为 watchdog 的 T5 判据（连续 2 采样无标记即出针，约 4 分钟）。
- **派单必经净化包装器 `.team/ta`**（2026-08-10 人工侧通道级裁定，实证在脚本头注释）：codex 席位
  在自身 shell 执行 `team-agent` 时会被 codex 注入其托管的死代理（`ec2-13-213-89-27…:8443`，
  实测不可达），launcher 把它快照进新席位启动串 ⇒ **凡 codex 席位派出的席位全生命周期零 token**
  （屏幕显示 Working 是假活）。所有 `add-agent/start-agent/reset-agent` 一律走 `.team/ta <子命令>`。
- **新席位核真活性**：`status=工作` 不算数——必须取 `~/.codex/sessions/<当日>/` 最新 rollout jsonl，
  确认其中出现 `reasoning`/`custom_tool_call` 记录，才算这个席位真的接通了模型。
- **给席位发消息只走 `team-agent send`**，严禁 tmux `send-keys` 敲键盘（实证：键入文本会与框架注入
  消息拼接成一条，并触发 steer 打断）。leader 的合法寻址是 `<team>/leader` 或
  `<workspace>::<team>/leader`，裸 `leader` 会被拒（`state did not contain the requested team/name tuple`）。

## 工程红线

- 代码必须带外骨骼注释（机器可校验标注），架构维基从代码现算，禁止人工另维护架构文档
- halt 是默认：缺字段、判不出 ⇒ 停下问，绝不猜
- 契约级议题（见 requirement-base/INDEX.md 未决议题表）定夺前，相关模块不施工

## 工程常识红线（2026-08-09 用户回炉裁定后增设；所有基底模板继承，验收必查）

任何**面向用户或常驻运行**的交付物，功能验收之外必须自证以下工程卫生——缺一项即不合格，
不因"验收命令绿了"豁免：
1. **静默经济**：常驻进程空闲（零客户端/零订阅）时 CPU 趋近 0、无固定频率的子进程派生；
   已连接但用户无操作时同样必须有界——CPU 与子进程派生不得仅因舰队规模线性常烧
   （2026-08-10 裁定席升级、leader 裁定入线）。交付须分别量测三态：零连接、已连接零订阅、
   已连接单订阅；
2. **进程卫生**：单实例守卫；自身与测试脚本退出后零孤儿进程/零残留监听端口；
3. **资源有界**：内存/磁盘（日志、上传目录）增长有界或有轮转说明；
4. **可达性常识**：对外广播的地址/端口必须是对端真实可达的（虚拟网卡/回环/link-local 排除）；
5. **失败可见**：用户任何动作在有限时间内必得可见结果（成功或带原因的失败），静默等待即缺陷。
