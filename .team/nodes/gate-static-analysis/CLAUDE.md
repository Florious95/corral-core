# 知识基底 · gate-static-analysis（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: gate-static-analysis
    goal: >
      阶段三第一步——**把隐含问题一次性全部暴露出来并立账**，本条只负责"暴露与立账 + 门禁接入"，
      修复由后续按账施工（避免边扫边修导致清单永远不收敛）。
      背景：`tools/gate/run.sh` 最后一次运行是 **2026-08-09 18:47**（`tools/gate/gate-report.json` mtime），
      此后两天的全部改动**没过过门**——这是"隐含问题从不被发现"的直接原因。
      三件事：
      ①**接入静态分析**（`docs/next-round-plan-20260810.md` §3.3 已裁）：Go 侧 `go vet`（已有）+
      **staticcheck**（BSD-3，许可与 Apache-2.0 兼容，符合 008）；Android 侧 **Android Lint**
      （AGP 自带，零新依赖）。**不引 detekt**（需加插件、收益与 Lint 重叠）。三者接进 `tools/gate/run.sh`
      的对应面，成为门禁的一部分。
      ②**把 gate 加进每条 acceptance 的收口位**——taskbook 里凡涉及代码改动的条目，
      acceptance 末尾补一条全量门；本条先在 `tools/gate/README.md` 写明该约定并给出标准 argv，
      具体条目的批量补写由 leader 收口时做。
      ③**全量跑一遍并立账**：`tools/gate/run.sh` 三面 + `archwiki --check`（含 T3 全套）+
      新接的静态分析，把**所有** issue 逐条落进 `docs/stage3-issue-inventory.md`
      （字段：来源工具 / 文件:行 / 规则 id / 原文 / 初判严重度 / 建议归属包）。
      红线：**本条只暴露不修复**——发现问题原样记账，不许顺手改产品代码（顺手改会让"这一批到底发现了多少"
      永远算不清）；工具自身的接入改动除外。
      **不许为了让门禁变绿而降低规则集**：staticcheck 与 Lint 的默认规则集不得裁剪，
      确有必须豁免的（如生成物目录）逐条给理由写进 README。
      **阳性对照**：接入后必须自证工具真在跑——故意引入一个必然被 staticcheck 报的构造
      （如未使用的变量）确认它真报、再撤销；Android Lint 同法。空清单不算健康，
      扫出 0 条要说明是"真干净"还是"没扫到"。
      注意 gate 有**用例数棘轮**，本轮 19 包大量注释改动可能撞红，如撞红照实记账、不许绕过棘轮。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash tools/gate/run.sh'"
      - "bash -lc 'test -s docs/stage3-issue-inventory.md'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
    deps: []
    write_scope: ["tools/gate/", "docs/stage3-issue-inventory.md", "app/app/build.gradle.kts", "app/build.gradle.kts", "server/"]
    evidence: ".team/evidence/gate-static-analysis.json"
    contention: impl
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：
- 正向依赖（你消费的契约，只读）：无
- **反向依赖（波及面=回归自查范围）**：无

### 闭包架构卡内联（职责/导出面/依赖边）

（无卡命中——报 leader）

## 3. 需求基
- goal 引用条目：（goal 无编号引用）
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
