# 知识基底 · audit-prod-daemon-lifecycle（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: audit-prod-daemon-lifecycle
    goal: >
      调查 2026-08-10 01:2x 前生产 daemon PID 46081 无声退出事件：在不触碰当前生产 PID 3393、
      不读取密钥/profile 原文、不把 token/authkey 上屏落盘的前提下，对齐旧进程约 3.5h 生命周期、
      各席位/测试脚本活动、系统退出证据与 daemon 自身 panic/主动退出路径，给出 product|environment|
      unknown 三选一且逐证据可复核的结论。若属 product，只提交后续 fix 案五栏提案，不顺手修产品；
      若属 environment，仅在裁定台账追加一行。同步加固值守面：生产启动必须经日志落盘脚本，
      watchdog 对 :9900 监听、进程与日志接管做只读存活探针，异常只写 watchdog-escalation.log，
      不自动重启、不发信号。红线：生产 daemon 与用户真实 tmux 禁触。
    acceptance:
      - "bash -lc 'test -s e2e/artifacts/audit-prod-daemon-lifecycle/REPORT.md && python3 -m json.tool .team/evidence/audit-prod-daemon-lifecycle.json >/dev/null'"
      - "bash -lc 'python3 -m py_compile .team/watchdog.py && bash -n .team/prod-daemon-launch.sh'"
      - "bash -lc 'test -s e2e/artifacts/audit-prod-daemon-lifecycle/prod-guard-selftest.log'"
    deps: ["fix-daemon-idle-cpu"]
    write_scope: [".team/watchdog.py", ".team/prod-daemon-launch.sh", ".team/adjudicator/log.md", "e2e/artifacts/audit-prod-daemon-lifecycle/", ".team/evidence/audit-prod-daemon-lifecycle.json"]
    evidence: ".team/evidence/audit-prod-daemon-lifecycle.json"
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
- librarian 撞库回执：.team/nodes/audit-prod-daemon-lifecycle/LIBRARIAN.md（先完整读）
- 修订记录 requirement-base/REVISIONS.md 必读（被推翻结论不回改条目）

## 4. 经验基（通用纪律+先例）
- 红测先行；每次落盘保持整模块可编译（共享编译单元互阻三次实案）；编译被他人半成品阻断→直接 send 文件主人（附文件+行号+错误原文），主人最高优先修复回执，不经 leader
- 测试净化前缀 env -u TEAM_AGENT_*；tmux 只用自建隔离 socket；杀进程只 scoped kill 自己命名空间（w-fix-statewire 险案）
- 派单通道净化：所有 Team Agent CLI 调用统一走仓库包装器 .team/ta，尤其 add-agent/start-agent/reset-agent；禁止手写 env -u 前缀或直接调用 team-agent，否则 Codex 托管代理会被快照进新席启动串，形成零 token 假 BUSY
- A-31 开工核真：Codex 新席必须在对应 ~/.codex/sessions 当日 JSONL 出现 reasoning 或 custom_tool_call；Working/BUSY、pane 存在、命令 exit 0 均不算真活性
- 代码必须带注释（设计决策写为什么）；禁止 git push；本地不 commit；report_result 恰好一次带 tests

## 5. 现场基（leader 手填取证素材——唯一手填合法区）
- .team/nodes/audit-prod-daemon-lifecycle/FIELD.md（先完整读；含真机实证/失败现场/裁定）
