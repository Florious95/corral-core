# 知识基底 · stage4-b3-host-t（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: stage4-b3-host-t
    goal: >
      阶段四 B3 批次：宿主 T 对账。零设备依赖，独立隔离 daemon 端口。
      按 docs/stage4-execution-plan.md §1 执行 C4、C5(H 面)、D1–D4 共 6 条用例。
      隔离铁律：自建 TMUX_TMPDIR + 高端口 daemon（≥19983），绝不触碰生产 daemon（pid 3393，:9900）
      与用户真实 tmux。测试一律 env -u TEAM_AGENT_*。
      每用例逐条判定落 REPORT，收尾自证零残留（lsof + 进程表）。
    acceptance:
      - "bash -lc 'test -s e2e/artifacts/stage4-execution/REPORT.md'"
    deps: ["plan-stage4-execution"]
    write_scope: ["e2e/artifacts/stage4-execution/"]
    evidence: ".team/evidence/stage4-b3-host-t.json"
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
