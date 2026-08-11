# 知识基底 · fix-dogfood-lock-zh（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-dogfood-lock-zh
    goal: >
      兑现 017 R-6（当期**锁中文**并在 README 明示；抽取翻译后置）——dogfood 缺陷 D-12，至今未开工。
      现状：产品界面全中文，但仓库文档从未明示"当期锁定中文、国际化后置"这一裁定。
      本产品 Apache-2.0 全开源（见 008），外部读者无从得知这是**有意为之的当期边界**而非疏漏，
      也不知道翻译抽取属已裁的后置项而非欢迎 PR 的开放任务。
      范围：根 README.md 与 server/README.md（以及 app 侧若有面向用户的说明文档）明确写出——
      ① 当期界面语言锁定中文；
      ② 国际化与翻译抽取为**已裁定的后置项**（出处 requirement-base/entries/017-场景审计八项裁定.md 的 R-6），
      不是待办疏漏；
      ③ 对外部贡献者说明当前不接受翻译 PR 的原因与将来开放的条件。
      措辞要让不了解本项目决策史的人一眼看懂，不要只写"锁中文"三个字。
      红线：**只改文档，不动任何代码与界面文案**；不得把后置项写成"计划中"或"即将支持"——
      那正是本轮在治的形态⑦（把未来效果写成现在式），需求基裁的是**后置**，不是承诺。
      引用需求条目时写成可验证的形状（真实文件路径），让 T3-2 的引用真实性判据能验。
    acceptance:
      - "bash -lc 'grep -q 中文 README.md'"
      - "bash -lc 'grep -q 017 README.md'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
    deps: []
    write_scope: ["README.md", "server/README.md", "docs/"]
    evidence: ".team/evidence/fix-dogfood-lock-zh.json"
    contention: impl
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：
- 正向依赖（你消费的契约，只读）：无
- **反向依赖（波及面=回归自查范围）**：无

### 闭包架构卡内联（职责/导出面/依赖边）

（无卡命中——报 leader）

## 3. 需求基
- goal 引用条目：requirement-base/entries/008*, requirement-base/entries/017*
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
