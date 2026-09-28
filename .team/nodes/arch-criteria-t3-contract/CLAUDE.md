# 知识基底 · arch-criteria-t3-contract（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: arch-criteria-t3-contract
    goal: >
      阶段二（补契约）的验收定义先行，与 arch-criteria-t3 同一逻辑：判据不先立，
      补契约就只能靠席位自报。落地两条新判据，编号续 T3 系列（T2-x 已被既有 FUTURE_CRITERIA 占用）：
      T3-3 契约标签完备——凡标了 @contract 的符号，四标签 @pre / @post / @err / @inv 必须齐全，
      允许显式写 none（表示"确无此项"），但不许缺项；缺项即"契约半成品"，比没有契约更坏，
      因为它让读者以为契约已定。
      T3-4 跨层声明一致——@consumes 声明的包必须真在该包的 import 图里；反之，跨层 import 了
      却没声明的判架构漂移。这条让架构维基能从代码现算真依赖，而不是只统计 import。
      标签集以 docs/next-round-plan-20260810.md §3.1 为准（@contract / @pre / @post / @inv /
      @err / @consumes / @produces），本工程自定，不套用任何 Rust 工程的既有标注标准。
      Go 写在 doc 注释里，Kotlin 写同名 KDoc 标签。
      准入纪律与分级开关全部沿用 arch-criteria-t3 既有约定：每条判据先配必红 fixture 再配必绿
      阳性对照（四格齐才准入，防"没扫到"被当成"干净"），挂进 test_check.py；
      默认报告模式不改退出码，--strict-t3 计入退出码，--pkg 单包硬判供阶段二逐包收口。
      复用 arch-criteria-t3 已建成的 _all_comment_lines 字符串感知提取器，不另起炉灶。
      边界诚实（承 arch-criteria-t3 的教训，不得重犯）：本条只验标签的完备性与声明一致性，
      **不验契约内容是否描述正确**——"@post 写的是不是真的"属语义事实，静态判据判不了，
      那一面由用例覆盖。报告与 HANDBOOK 必须明写这条边界，不许暗示有它不具备的保护力。
      对真仓库跑一次报告模式，清单落 docs/wiki/t3-report.md 的新增两节
      （阳性对照：当前全仓库尚未标注 @contract，故 T3-3 违规数很可能为 0——**这个 0 必须自证是
      "真没有标注"而不是"判据扫不到"**，做法是造一个带残缺 @contract 的 fixture 与真仓库同法扫描，
      并在报告里给出"扫描到的 @contract 符号总数"这一覆盖量数字）。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* python3 -m unittest discover -s tools/archwiki -p \"test_*.py\"'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
      - "bash -lc 'grep -q \"T3-3\" docs/wiki/t3-report.md && grep -q \"T3-4\" docs/wiki/t3-report.md'"
    deps: ["arch-criteria-t3"]
    write_scope: ["tools/archwiki/", "docs/wiki/t3-report.md"]
    evidence: ".team/evidence/arch-criteria-t3-contract.json"
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
