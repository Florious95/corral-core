# 知识基底 · plan-stage4-execution（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: plan-stage4-execution
    goal: >
      阶段四（把用例设计里的用例全部跑一遍）的**执行方案先行**，本条**只产出文档、不跑任何用例、
      不改任何代码**——用例执行等阶段三收口后另派。
      输入：`e2e/artifacts/dogfood/TESTPLAN.md` 的 41 条用例、`docs/perf-scenarios.md` 的 A–F 六组性能场景、
      `docs/next-round-plan-20260810.md` §3.5 的三通道分工、`requirement-base/entries/016-生产级验收定义修正.md`
      （真机验收权威性）与 `013-测试体系与回归门禁.md`（五层测试体系）。
      产出 `docs/stage4-execution-plan.md`，必须回答清楚：
      ①**逐条用例的通道归属**——模拟器 UI 自动化 / API 与 instrumentation（仅 TS 网络）/ 真机（交付后用户验）。
      用户裁定原文：「模拟器能测的全测；测不到测不了的才用 API 模拟用户场景」，
      **UI 必须测**，走 API 只针对 TS 网络（tailnet 在模拟器里不好测）。别把 UI 划进 API 通道偷懒。
      ②**已知不可用面**：相机与 Extended Controls 的窗口寻址在本机模拟器上已实证不可用
      （2026-08-10 为此空转八代），相关用例直接划真机通道，不要再在模拟器上硬撞。
      ③**每条用例的判定方式**：uiautomator 结构断言写什么、截图截哪一屏（018 要求 leader 逐图目检）、
      失败如何归因（013 的失败四归因）。
      ④**执行顺序与批次切分**：受「同一 Gradle 模块同一时刻只放一席」约束（本轮实证，已入 CLAUDE.md），
      UI 自动化批次不能与 `:app` 施工席并行；给出可操作的排期形状。
      ⑤**阳性对照方案**：每类判定配一个必然非空的对照（例如故意让断言目标缺失、确认用例真的会红），
      防止"没测到"被当成"通过"——本轮已三次栽在这个坑上（T3-2 抓不住原型、gradle UP-TO-DATE 假绿、
      terminal 模块整体不在扫描根）。
      ⑥**性能场景的接入点**：A–F 六组里哪些进本轮、哪些后置，F1 终端滚动帧率必须进
      （`docs/round-findings-20260811.md` P-3 已裁：先量 F1 再决定要不要接脏区局部重绘）。
      红线：**不许在本条里跑用例或改代码**；不许把"可自动化度"当成覆盖优先级
      （016 明裁：自动化必要非充分、验收权在真机、可自动化度不决定覆盖优先级）。
    acceptance:
      - "bash -lc 'test -s docs/stage4-execution-plan.md'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
    deps: []
    write_scope: ["docs/stage4-execution-plan.md"]
    evidence: ".team/evidence/plan-stage4-execution.json"
    contention: impl
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：
- 正向依赖（你消费的契约，只读）：无
- **反向依赖（波及面=回归自查范围）**：无

### 闭包架构卡内联（职责/导出面/依赖边）

（无卡命中——报 leader）

## 3. 需求基
- goal 引用条目：requirement-base/entries/016*
- librarian 撞库：无回执文件（leader 未查或无命中）
- 修订记录 requirement-base/REVISIONS.md 必读（被推翻结论不回改条目）

## 4. 经验基（通用纪律+先例）
- 红测先行；每次落盘保持整模块可编译（共享编译单元互阻三次实案）；编译被他人半成品阻断→直接 send 文件主人（附文件+行号+错误原文），主人最高优先修复回执，不经 leader
- 测试净化前缀 env -u TEAM_AGENT_*；tmux 只用自建隔离 socket；杀进程只 scoped kill 自己命名空间（w-fix-statewire 险案）
- 派单通道净化：所有 Team Agent CLI 调用统一走仓库包装器 .team/ta，尤其 add-agent/start-agent/reset-agent；禁止手写 env -u 前缀或直接调用 team-agent，否则 Codex 托管代理会被快照进新席启动串，形成零 token 假 BUSY
- A-31 开工核真：Codex 新席必须在对应 ~/.codex/sessions 当日 JSONL 出现 reasoning 或 custom_tool_call；Working/BUSY、pane 存在、命令 exit 0 均不算真活性
- 代码必须带注释（设计决策写为什么）；禁止 git push；本地不 commit；report_result 恰好一次带 tests

## 5. 现场基（leader 手填取证素材——唯一手填合法区）
- 无现场素材文件
