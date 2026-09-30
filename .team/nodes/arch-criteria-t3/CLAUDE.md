# 知识基底 · arch-criteria-t3（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: arch-criteria-t3
    goal: >
      阶段一的验收定义先行：把"注释最新/契约齐"变成机器判据，否则该阶段只能靠席位自报。
      命名裁定（leader 2026-08-11）：tools/archwiki/build_wiki.py 里 T2-1/T2-2 已被 FUTURE_CRITERIA
      占用（零消费者包 / 孤儿子图）、T2-3 被 parse_exoskeleton_fences 预留，故新判据另开 T3 系列，
      不得覆盖既有编号。落地两条：
      T3-1 符号级 doc 覆盖——非测试导出符号（Go 顶层导出 decl / Kotlin 顶层 public 声明）必须有紧邻 doc/KDoc；
      T3-2 引用真实性——doc 与外骨骼标签文本里提到的符号名、仓库文件路径、CLI flag 必须在仓库中真实存在；
      这条专抓"注释谎称设置里有重配按钮"那类谎报注释，是本轮"注释即契约"能否成立的关键。
      准入纪律（沿用本文件既有约定）：每条判据必须先配红测 fixture 才准入——testdata/ 下各建至少一个
      必红 mini-repo，另各配一个必绿的阳性对照 fixture（防"没扫到"被当成"干净"），全部挂进 test_check.py。
      分级开关：默认报告模式（列清单、不改变退出码，因真仓库 18 包尚未刷注释、此刻必然大面积违规），
      --strict-t3 才计入退出码，--pkg 包名 支持单包硬判——阶段一逐包收口时用它做每包 acceptance。
      对真仓库跑一次报告模式，清单落 docs/wiki/t3-report.md（阳性对照：清单必须非空，空即判工具没真扫）。
      红线：既有 T1-1/T1-2 必须保持绿；不得为了让真仓库好看而放宽判据。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* python3 -m unittest discover -s tools/archwiki -p \"test_*.py\"'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
      - "bash -lc 'test -s docs/wiki/t3-report.md && grep -q \"T3-1\" docs/wiki/t3-report.md && grep -q \"T3-2\" docs/wiki/t3-report.md'"
    deps: []
    write_scope: ["tools/archwiki/", "docs/wiki/t3-report.md"]
    evidence: ".team/evidence/arch-criteria-t3.json"
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
