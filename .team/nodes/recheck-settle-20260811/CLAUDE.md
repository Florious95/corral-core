# 知识基底 · recheck-settle-20260811（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: recheck-settle-20260811
    goal: >
      结账（只复跑不修）：工作区悬着 54 项未提交改动，来自 6 个案子——4 案席位自报 pass
      （fix-dogfood-pairing-ux / fix-dogfood-upload-media / fix-dogfood-term-ux / fix-recovery-baseline）、
      2 案被清场杀在半路（fix-upload-auth 是 P0 安全：POST /upload 曾零鉴权；perf-thresholds-enforce）。
      逐案原样复跑该案 taskbook acceptance 的 argv，并为每次测量配阳性对照（解析测试结果 XML/输出，
      确认用例数非零、本轮新增红测文件确实被执行且计数非零——"exit 0"本身不算证据，
      gradle -q 配 UP-TO-DATE 可以让没跑测试也退 0）。再逐案核对 goal 里的声明是否真落地
      （例：fix-recovery-baseline 要求解除 TestDiscoveryRecoveryReachesConnectedClientFromStartFailure
      的 t.Skip；fix-dogfood-pairing-ux 要求 D-14 的设置页重配入口可达、且原先谎称"设置里有重配按钮"
      的注释已改真；fix-upload-auth 要求 docs/protocol.md 先有该端点鉴权契约再实现，且含 D-13 上传目录上限）。
      产出 .team/recheck-20260811/verdict.json（每案 status pass/red/blocked + tests 的 argv 与 rc 原文
      + positive_control 计数 + goal 逐条落地核对结论 + 建议的窄提交文件分组）与同目录 VERDICT.md 人读版；
      同时把每案判定写回 .team/evidence/ 下该案的证据文件。
      硬红线：只复跑不修——测试红了不许改测试、不许改产品代码、不许放宽 acceptance，
      差口原样记进 verdict.json 的 gaps 数组（后续转缺陷条目排进阶段三）。不许 git commit/push（leader 收口）。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd server && go test -count=1 ./internal/api/... ./internal/pairing/...\"'"
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd app && ./gradlew -q :app:testDebugUnitTest :terminal:test\"'"
      - "bash -lc 'python3 -c \"import json;d=json.load(open(\\\".team/recheck-20260811/verdict.json\\\"));c=d[\\\"cases\\\"];assert len(c)>=6;assert all(x[\\\"tests\\\"] for x in c);assert d[\\\"positive_control\\\"][\\\"app_tests_run\\\"]>0 and d[\\\"positive_control\\\"][\\\"terminal_tests_run\\\"]>0\"'"
    deps: []
    write_scope: [".team/recheck-20260811/", ".team/evidence/"]
    evidence: ".team/evidence/recheck-settle-20260811.json"
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
