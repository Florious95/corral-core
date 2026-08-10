# 知识基底 · perf-thresholds-enforce（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: perf-thresholds-enforce
    goal: >
      把 test-api-user-scenarios-perf 的基线转成硬门限断言（门限由 leader 2026-08-10 依基线定，
      留 ~3x 余量，避免机器抖动误红；基线见 e2e/artifacts/test-api-user-scenarios-perf/baseline.json）：
      pair_to_first_listing p95 ≤ 5ms；subscribe_first_frame p95 ≤ 400ms；output_end_to_end
      p95 ≤ 150ms；scrollback_page p95 ≤ 150ms；reconnect_recovery p95 ≤ 400ms。
      静默经济三态：零连接 CPU ≤ 0.5% 且子进程派生 = 0；已连接零订阅 CPU ≤ 5%；已连接单订阅
      CPU ≤ 5% 且子进程 ≤ 4；任一态 10 分钟窗口 RSS 净增 ≤ 20MiB。
      同时补齐基线里没测出数的两项：large_output 吞吐与 upload 耗时（当前 p50/p95 为 null）。
      超门限必须 exit 非 0 并打印实测值与门限值对比，不得静默通过。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash e2e/api-user-scenarios.sh'"
      - "bash -lc 'python3 -c \"import json;d=json.load(open(\\\"e2e/artifacts/test-api-user-scenarios-perf/baseline.json\\\"));assert d[\\\"performance\\\"][\\\"hard_numeric_thresholds\\\"],\\\"门限未落盘\\\"\"'"
    deps: ["test-api-user-scenarios-perf"]
    write_scope: ["e2e/api-user-scenarios.sh", "e2e/artifacts/test-api-user-scenarios-perf/", ".team/evidence/perf-thresholds-enforce.json"]
    evidence: ".team/evidence/perf-thresholds-enforce.json"
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
