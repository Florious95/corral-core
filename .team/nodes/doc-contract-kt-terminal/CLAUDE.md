# 知识基底 · doc-contract-kt-terminal（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: doc-contract-kt-terminal
    goal: >
      阶段一二（注释最新 + 补契约）逐包施工，本条负责 Kotlin 包 `dev.agentmirror.terminal`。三件事，按重要性排序：
      ①**核实并改写现存注释**——逐个顶层 public 声明（含 `@Composable` 函数、`object`、`data class`、
      顶层 `val`/`fun`），把 KDoc 与实现对照，说的与做的不一致就改注释（不是改实现）。
      这是最主要的工作量，也是判据测不了的部分。前三批 14 个包已实证出**十类不实形态**（见角色文件谱系）。
      ②**补契约标注**——对确有契约的符号加 `@contract` 并补齐四标签 `@pre` / `@post` / `@err` / `@inv`
      （确无此项显式写 `none`）。不为凑数而标；纯值对象与纯 getter 一般不需要契约。
      ③**消该包的架构漂移**——补 `@consumes` 使之与真实 import 图一致，本包只管自己那些。
      标签集以 `docs/next-round-plan-20260810.md` §3.1 为准；写法见 `.team/nodes/arch-criteria-t3/HANDBOOK.md`。
      红线：**只动注释与标注，不动任何实现代码**；模块测试必须保持绿。
    acceptance:
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check --strict-t3 --pkg dev.agentmirror.terminal'"
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd app && ./gradlew -q :terminal:test\"'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
    deps: ["arch-criteria-t3", "arch-criteria-t3-contract"]
    write_scope: ["app/terminal/src/main/kotlin/dev/agentmirror/terminal/"]
    evidence: ".team/evidence/doc-contract-kt-terminal.json"
    contention: impl
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：terminal
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
