# 知识基底 · feat-input-keys-server（R-1 特殊键服务端全链）

## 0. 任务（taskbook.yaml#feat-input-keys-server）
- 裁定权威：requirement-base/entries/017 R-1/R-2 原文——先读。总图纸 docs/scenario-coverage.md B5/B4 行。
- 目标（契约先行，按此顺序施工）：
  1. **docs/protocol.md**：input 帧加可选 `keys` 字段——字符串数组，闭集 `esc/ctrl_c/tab/up/down/left/right`；不 bump 协议版本（前向兼容增量：不发 keys 的旧客户端行为不变）。keys 与 text 的互斥/共存语义你定夺并写死在文档（建议：一帧只允许其一，两者都有判协议错误——简单可判定）。同时在多行注入节标注 R-2 退化风险：CLI 不支持 ?2004 时括号粘贴退化为逐行执行。
  2. **protocol 包**：Input.Keys 模型+闭集校验；黄金夹具 testdata/input_keys.json（命名循既有 input.json；Kotlin ConnCodecTest 消费同一目录做字节级断言——夹具是跨语言契约，字段名/顺序定了就冻结）。
  3. **bridge 包**：命名键注入——send-keys **非 -l** 命名键映射（esc→Escape、ctrl_c→C-c、tab→Tab、up→Up…）；与既有 Inject 同款决定性 ack 语义（003 发送必达：成功/失败可判定）。
  4. **ws_handler.go** handleInput 接 keys 分支，input_ack 语义不变——**放最后一步**（互斥约束见 §1）。
- R-2 服务端不新增代码：bridge.go:101 paste-buffer -d -p 路径已存在且 TestInjectMultiline 有测。
- 验收：`bash -lc 'cd server && go test ./internal/protocol/... ./internal/bridge/... ./internal/api/...'`。红测先行：夹具解码红→模型落地绿；bridge 假 tmux 断言命名键 argv 精确形状。

## 1. 现场基
- protocol/frames.go:136 Input 结构；golden_test.go 夹具机制（testdata/*.json 每帧一份）。
- bridge/bridge.go:67-101 Inject（send-keys -l 单行 / load-buffer+paste-buffer -d -p 多行）；runTmux 假件与测试形状照既有。
- api/ws_handler.go:116 handleInput。
- **同包并行约束**：test-aggregate-status 席位同期在 internal/api 新增纯 _test.go。你动 ws_handler.go 前先确认包可编译；每次落盘保持 protocol/bridge/api 三包全部可编译+测试可跑（共享编译单元纪律，前科：conn 互阻）。
- App 侧（键条 UI+conn 消费夹具）是另一任务 feat-input-keys-app，你**不动 app/**。

## 2. 需求基（指针）
1. requirement-base/entries/017（R-1/R-2 裁定原文）
2. requirement-base/entries/003（发送必达——keys 也要决定性 ack）
3. docs/protocol.md（协议唯一权威；你改它但只增不破坏：既有夹具字节一个不动）

## 3. 经验基
- 契约先行：先 protocol.md+夹具，再代码；红测先行；净化前缀 env -u TEAM_AGENT_*；bridge 测试只用自建隔离 socket；交件前 tools/gate/run.sh 全量门自查。
