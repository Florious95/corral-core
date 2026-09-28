# 知识基底 · test-api-user-scenarios-perf（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: test-api-user-scenarios-perf
    goal: >
      用户裁定 2026-08-10：**由 API 组成用户场景的测试，且必须包含性能测试**。建立一套完全经
      程序接口（WS 协议 + HTTP 上传口 + 配对口）驱动的端到端场景套件，不经任何 UI 路径
      （不点界面、不扫码、不做窗口寻址），把 016 首触九步与 017 当期承诺翻译成可重复跑的
      API 剧本：配对建立→工作区/会话两级列举→会话订阅（快照+增量流+scrollback 分页）→
      输入注入→输出回流→resize→图片上传→断线重连续订→状态字段流转。每个场景断言的是
      用户可见结果（拿到什么、多久拿到、失败有没有原因），不是内部实现。
      **性能面必须同场量测并落盘基线 JSON**：配对到首帧会话列表的时延、订阅首帧时延、
      输出流端到端时延（p50/p95）、大输出吞吐、scrollback 分页时延、上传耗时/大小、
      重连恢复时延；以及工程常识红线 1 要求的静默经济三态 CPU 与内存（零连接/已连接零订阅/
      已连接单订阅），并记录 daemon 常驻内存与子进程派生计数。本轮**先建套件与基线**：
      门限值不得自行拍脑袋，基线跑出来后连同建议门限写进证据，由用户或裁定席定门限后再转为
      硬断言。红线：自建隔离 daemon + 高端口，绝不触碰生产 daemon（:9900）与用户真实 tmux；
      配对 token 不落日志不上屏；跑完零残留进程/端口/临时目录。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash e2e/api-user-scenarios.sh'"
      - "bash -lc 'python3 -m json.tool e2e/artifacts/test-api-user-scenarios-perf/baseline.json >/dev/null'"
      - "bash -lc 'test -s e2e/artifacts/test-api-user-scenarios-perf/REPORT.md'"
    deps: ["protocol-spec"]
    write_scope: ["e2e/api-user-scenarios.sh", "e2e/artifacts/test-api-user-scenarios-perf/", ".team/evidence/test-api-user-scenarios-perf.json"]
    evidence: ".team/evidence/test-api-user-scenarios-perf.json"
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
