# 知识基底 · perf-ts-link-guidance（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: perf-ts-link-guidance
    goal: >
      TS 链路性能指导（顾问席，只出指导不施工）。用户 2026-08-13 裁定：
      「在 v6 版本优化性能，无论是服务端还是 app」「性能优化要有基线和指标」
      「核心是优化 tailscale 的链路」「我在本地局域网很流畅……基于 ts 就有（闪烁），
      你们复现不了也是因为网络好」。
      该裁定改变了一整类问题的定性：**闪烁不是渲染缺陷，是网络症状**——
      局域网下中间状态一闪而过，TS 下用户看见每一个中间状态。
      本任务不改任何代码，产出 docs/ts-link-perf-guidance.md：
      §1 链路诊断（直连 vs DERP 中继，这是性价比最高的一问）
      §2 基线与指标定义（含棘轮、出处可比性、NOT_MEASURED 默认失败）
      §3 优化方向（按 收益/风险/验证难度 排序，分「只依赖自己」与「依赖对端」）
      §4 第一步做什么（一两小时能出第一个数）
    acceptance:
      - "产出 docs/ts-link-perf-guidance.md，四节齐全"
      - "§1 给出直连/中继的判定结论 + 命令原始输出（两端各判一次）"
      - "§2 每个指标写清：对应用户哪个体感 / 探针在哪 / 局域网与 TS 两组基线 / 棘轮判据"
      - "零代码改动：git diff 中不得出现 app/ server/ test/ 下任何文件"
    deps: []
    write_scope: ["docs/"]
    evidence: ".team/evidence/perf-ts-link-guidance.json"
    contention: none
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
