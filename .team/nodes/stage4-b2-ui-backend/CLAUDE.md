# 知识基底 · stage4-b2-ui-backend（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: stage4-b2-ui-backend
    goal: >
      阶段四 B2 批次：模拟器 UI 自动化·后台/通知/视觉。独占 :app 编译 + 独占模拟器。
      按 docs/stage4-execution-plan.md §1 执行 A10、A11、A12 + B1–B8 + C1、C2、C3、C5(U)、C6(菜单) + D5
      共 22 条用例（A10a 已随 R-004 撤销）。
      需先 assembleDebug 装最新 APK（含 fix-upload-bearer 修复）。
      每用例逐条：结构断言 + 截图 + 阳性对照 + 失败四归因。
      截图落 e2e/artifacts/stage4-execution/；判定结果逐条落 REPORT-B2.md。
    acceptance:
      - "bash -lc 'test -s e2e/artifacts/stage4-execution/REPORT-B2.md'"
    deps: ["stage4-b1-ui-firsttouch"]
    write_scope: ["e2e/artifacts/stage4-execution/"]
    evidence: ".team/evidence/stage4-b2-ui-backend.json"
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
- .team/nodes/stage4-b2-ui-backend/FIELD.md（先完整读；含真机实证/失败现场/裁定）
