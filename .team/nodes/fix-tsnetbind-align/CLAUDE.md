# 知识基底 · fix-tsnetbind-align（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-tsnetbind-align
    goal: >
      修复 `docs/stage3-issue-inventory.md` 分组 G 的 `Aligned16KB`（3 条为同一 AAR 的重复告警）：
      `app/app/libs/tsnetbind.aar` 内的 `jni/arm64-v8a/libgojni.so` **未按 16KB 对齐**。
      **这是 P1 且是上架硬性要求**：Android 15+ 对未 16KB 对齐的 native 库强制拒绝，
      不修则 App 无法在 Android 15 及以上正常分发/运行——不是告警洁癖，是发布阻断项。
      修法：在 `tools/tsnetbind` 的 gomobile 构建链里加 16KB 对齐
      （Go 1.22+ 的 gomobile 可经链接器参数指定 max-page-size；具体手段由你按实测确定），
      重新产出 AAR 并替换 `app/app/libs/tsnetbind.aar`。
      **阳性对照（必做）**：不许只看 Lint 不再报。要用客观工具直接验证 `.so` 的段对齐
      （如 `objdump -p` / `readelf -l` 读 LOAD 段的 align 值，或 `zipalign -c -P 16 -v`），
      给出**修复前与修复后的实测数字对比**——"Lint 不报了"可能只是产物没被重新扫描。
      同时确认替换后 tsnet 功能未坏：TS 链相关测试与 e2e 必须仍绿。
      红线：不得用给 Lint 加豁免的方式"解决"（那会把上架阻断项藏起来）；
      重建 AAR 的构建步骤必须**可复现**并写进 `tools/tsnetbind/README`（或等价文档），
      不许只在本机手工产出一个二进制就交件——那样下次没人能重建。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd app && ./gradlew -q :app:testDebugUnitTest\"'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
    deps: ["gate-static-analysis"]
    write_scope: ["tools/tsnetbind/", "app/app/libs/"]
    evidence: ".team/evidence/fix-tsnetbind-align.json"
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
