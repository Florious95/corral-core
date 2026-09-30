# PERF14：分页不得先抓全历史

用户验收范围为性能 #14–#17 与会话完整性 #10。本格只修 corral-serve #14，长期缺陷，不是新回归。A6 等待期间继续独立工作；不得把本格当作 #16 已完成。

## 冻结输入与所有权

- 开发：multisocket-luna，Pi / openai-codex/gpt-5.6-luna / xhigh。PR23 先保持冻结，本格全新独立 serve clone，禁止共写旧树。
- 独立判据/审查：perf-triage-astra，Codex / gpt-6-astra / medium。优先保留 A6 统一审查接棒，当前做有界 #14 判据核对。
- 固定 base：b98504e742ed1e7c7475767c512934b07eac592b。新 owning 分支 fix/scrollback-page-metadata，PR base main；不吸收未验 PR20，不改 PR22/23，不并 main。
- 事实输入：.team/nodes/perf-triage-astra/RESULT.md 中 #14 调用链、风险与测试设计。先核本格 base 与 S 相关路径差量；不照抄旧行号。用既有 basegen 生成影响闭包。

## 唯一行为目标

请求 P 行历史，不再先 capture 全历史计算总行数。用一次有界 metadata 查询读取实际 history_size/pane_height，再 capture 目标页；保留协议 Ref/ReqID/FromLine/LineCount 与 App 分页锚点、空历史兼容。

最小产品范围：bridge metadata 原语与 API scrollbackRange/handleScrollback。禁止顺便改恢复、目录协调、resize、缓存、协议或预取策略。metadata/capture 非原子边界明确，不虚称输出竞态已消除。

2026-09-08 正文计行裁定：同路径单行丢失与尾空行丢失纳入 #14。只去 capture 最后一个分隔 LF，非空无 LF 正文计 1，保留实际尾空行。H=0 纯历史继续 FromLine=0/LineCount=1/Data=LF 占位且不捕获屏幕。独立覆盖无终止 LF、多尾空行与真实空行；不全局改变其他 countLines 调用者语义，不修改协议。

## 验收清单

1. 固定 base 的生产 handler 路径具名红：请求阶段存在无界 capture；候选同 oracle 绿，最多一次目标范围 capture 和一次常量 metadata 查询。记录参数和 captured bytes，不能只数 mock 函数调用。
2. 独立数值区间 oracle：越顶、跨顶、历史与屏幕边界、H=0、尾空白行、FromLine/Count 极值、实际高度与 catalog 不同、metadata 失败。中间计算避免溢出；不得擅自编码 LineCount=0。
3. 真实隔离 tmux 的静态页面逐行匹配，并保留头布局、alternate-screen 和相关原有场景。tmux 不可用/skip/零匹配不可放行。
4. 固定 P、不同 H 的捕获字节/调用范围证明工作量有界；性能耗时必须另过环境闸及既有同批 A/B，不能把结构改进冒称手机提速。
5. 下游真实 App 分页及原用户“秒开无空白”最终组合仍需验，不以 Go 绿代表全部验收。

## 执行与交付

开发直接最小实施，独立判据同时准备，在具名红测上汇合。Go/Gradle 本机禁止编译；hosted Go -race -count=1，Gradle --rerun-tasks。先一次静态编译面核对，再准备单一 hosted workflow：固定 base 红、精确候选 patch/tree 绿、相关兼容场景与原始回执。只允许 connector 追加 workflow，避免 OAuth workflow scope 重试。产品验过后按正常单项 commit 推同 owning PR；未验补丁明确冻结，不造绿。

已有基底红、量具失败和不同源码身份不得覆盖。遇到结果失败一次汇总后修复，禁止削弱 oracle 或循环重跑。作者正常 report_result 一次；仅真实编排接口缺口允许消息，必须包含所需文件路径/身份/最小动作。不轮询 CI、席位或文件。不操作生产9900、用户AVD、真实会话正文/输入。临时数据仅本席目录。

## 已证验证依赖

run34182140489 与 run34183410019 相关 WebSocket 兼容场景命中 b985 已有 metrics/closeReason 竞态；这些修复归 PR20。停止相同旧竞态基底的重复运行。固定原 b985 具名红证据；新验证树从 serve1fa3b651c613f7b973b5187429d8e9e50d9d3d45 加同一 #14 patch 独立构建，记录精确两 source/patch/tested-tree。原14根判据不删减，bridge未执行不能算通过。owning产品仍基于b985，不复制PR20修复，不partial merge，不提升全局基线；组合证据与standalone失败分列。
