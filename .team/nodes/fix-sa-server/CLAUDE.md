# 知识基底 · fix-sa-server（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-sa-server
    goal: >
      修复 `docs/stage3-issue-inventory.md` 分组 A / B / C / D 的全部 9 条 staticcheck 问题
      （`internal/api` 6 + `internal/api` 测试辅助 1 + `internal/agentstate` 1 + `cmd/agentmirrord` 1）。
      逐条按清单原文处置：**能修则修，不能修则给出不修的技术理由并在清单里标注**——
      staticcheck 存在少量在本工程语境下不适用的规则，但**默认规则集不得裁剪**（已裁），
      个别不适用项只允许用最小范围的行内 `//lint:ignore <规则> <理由>` 且**理由必须具体**，
      不许写"误报"三个字了事；用了几条、各是什么理由，逐条写进证据。
      红线：**修的是问题不是告警**——不许用改签名、加空引用、`_ = x` 之类的手法把告警糊过去；
      若某条 staticcheck 告警指向的是真实死代码（如本轮已记录的 `internal/bridge` 四个零消费导出符号），
      **不要顺手删**，那超出本条范围，记进证据的 `out_of_scope` 数组交 leader 排期。
      改动若使某个符号的行为或错误面发生变化，**必须同步更新该符号的注释与 `@err` 契约标签**——
      本轮刚花整轮治"注释落后于实现"，不许在修 lint 时当场制造新的。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd server && go test -count=1 ./...\"'"
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd server && go vet ./...\"'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
    deps: ["gate-static-analysis"]
    write_scope: ["server/"]
    evidence: ".team/evidence/fix-sa-server.json"
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
