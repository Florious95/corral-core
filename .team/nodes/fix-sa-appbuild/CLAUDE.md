# 知识基底 · fix-sa-appbuild（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-sa-appbuild
    goal: >
      修复 `docs/stage3-issue-inventory.md` 分组 F 的 14 条 Android Lint 问题
      （`app/app` 构建配置：依赖版本与工程卫生）。
      逐条处置：依赖版本升级须**确认兼容性**（升完 `:app:testDebugUnitTest` 与 `:terminal:test` 必须仍绿），
      工程卫生项按 Lint 建议修正。
      红线：**不得为了消告警而放宽 Lint 规则集或加 `lintOptions` 豁免**（默认规则集不裁剪已裁定）；
      确有必须豁免的逐条给具体理由，写进 `tools/gate/README.md` 的豁免表，一条豁免一行理由。
      **不得升级到未验证的大版本**——若某条 Lint 建议的升级跨越主版本且存在破坏性变更风险，
      记进证据的 `deferred` 数组并说明风险，交 leader 排期，不要赌。
      注意与其他席位的写入面隔离：**不要动 `app/app/src/main/` 下任何源码或 manifest**
      （那是运行时组与 `w-fg-wiring` 的地盘），本条只管构建配置与依赖声明。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd app && ./gradlew -q :app:testDebugUnitTest\"'"
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd app && ./gradlew -q :terminal:test\"'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
    deps: ["gate-static-analysis"]
    write_scope: ["app/build.gradle.kts", "app/app/build.gradle.kts", "app/terminal/build.gradle.kts", "app/settings.gradle.kts", "app/gradle/", "app/gradle.properties", "tools/gate/README.md"]
    evidence: ".team/evidence/fix-sa-appbuild.json"
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
