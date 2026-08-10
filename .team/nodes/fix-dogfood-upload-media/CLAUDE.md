# 知识基底 · fix-dogfood-upload-media（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-dogfood-upload-media
    goal: >
      dogfood 缺陷波次二（P1，来源 e2e/artifacts/dogfood/REPORT.md）：
      D-03 上传文件名传的是 MediaStore 数字 ID、扩展名全丢（SessionScreen.kt 用 uri.lastPathSegment），
      需取真实 displayName/MIME 推导扩展名；D-02 「+」菜单只有相册，017 R-8 承诺的拍照直传缺席；
      D-01 相机权限二次拒绝后按钮静默无响应（红线5 失败可见：必须给出可见原因与去设置的引导）。
      红测先行：文件名推导单测（含无扩展名/中文名/重名）、权限拒绝态 UI 断言。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd app && ./gradlew -q :app:testDebugUnitTest\"'"
    deps: ["test-app-dogfood"]
    write_scope: ["app/app/src/main/java/", "app/app/src/test/", ".team/evidence/fix-dogfood-upload-media.json"]
    evidence: ".team/evidence/fix-dogfood-upload-media.json"
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
