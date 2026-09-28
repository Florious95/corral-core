# 知识基底 · fix-app-runtime-sa（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-app-runtime-sa
    goal: >
      两件事合并（同属 `:app` 模块运行时，按「同一 Gradle 模块同一时刻只放一席」的红线合席）：
      ①**补在屏兜底时钟泵**（阶段三复核 `w-stage3-verify` 记的 medium gap）：
      `feat-fg-service-wiring` 把时钟泵改为**单归属前台服务**（2s 一拍），
      在屏组合不再各自持有；后果是**服务被杀时即使 App 在前台也没有泵**，界面停止更新。
      接线前泵由在屏组合的 `LaunchedEffect` 驱动、前台恒有泵，所以这是功能回退。
      它踩在 004 的自检标准上——「删掉前台服务这一层，产品功能应当仍然完整，**只是后台期间体验降级**」，
      现状是**前台也降级**。修法：在屏组合检测到服务不可用时接管泵（服务恢复后让出，不得双泵重复拍），
      并配红测断言「服务不可用 + 前台在屏 ⇒ 泵仍在跑」与「服务恢复 ⇒ 不出现双泵」。
      ②**修 `docs/stage3-issue-inventory.md` 分组 E**（`app/app` 运行时，gate 复跑后剩 10 条）：
      逐条按清单处置，**修的是问题不是告警**——不许用 `@Suppress` 或改写法把告警糊过去，
      每条要能说清"这条告警指出的实际风险是什么、修完为什么风险消失了"。
      确有必须抑制的逐条给具体理由写进证据，不许写"误报"了事。
      红线：改动若使符号行为或错误面变化，**必须同步更新该符号的注释与契约标签**——
      本轮刚花整轮治「注释落后于实现」（19 包 75 条），且 `.session`/`.workspace`/`.service`
      三个包的注释在前台服务接线后刚同步过第二版，**不要造出第三版不实注释**。
      不得放宽 Lint 规则集或加 `lintOptions` 豁免（已裁定）。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd app && ./gradlew -q :app:testDebugUnitTest\"'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
      - "bash -lc 'env -u TEAM_AGENT_* bash tools/gate/run.sh'"
    deps: ["feat-fg-service-wiring", "gate-static-analysis"]
    write_scope: ["app/app/src/main/", "app/app/src/test/"]
    evidence: ".team/evidence/fix-app-runtime-sa.json"
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
