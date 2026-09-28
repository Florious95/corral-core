# 知识基底 · feat-input-keys-app（R-1 特殊键 App 侧 + R-2 多行不拆分）

## 0. 任务（taskbook.yaml#feat-input-keys-app）
- 裁定权威：requirement-base/entries/017 R-1/R-2；服务端契约已冻结，见 .team/evidence/feat-input-keys-server.json 与 docs/protocol.md input.keys 节——先读这两处。
- 目标：
  1. **conn 层**：Input 帧模型加 keys 字段（闭集 esc/ctrl_c/tab/up/down/left/right；text/keys 一帧至多其一；keys 不附加回车）。消费共享黄金夹具 server/internal/protocol/testdata/input_keys.json 做字节级断言（ConnCodecTest/ConnTestHelpers 是既有形状，照抄消费方式）。
  2. **会话页键条 UI**：输入条上方快捷键条，最小集 Esc / Ctrl-C / Tab / ↑ ↓ ← →（017 原文顺序）。点按即发 keys 帧（走既有 input→input_ack 决定性链路，003 发送必达：ack 失败要可见）。
  3. **R-2 多行粘贴**：输入含 \n 的文本**不拆分**、整段一条 input.text 发送（服务端 paste-buffer -p 括号粘贴路径处理）——检查现有发送路径没有拆行为多次 send 的逻辑，有则删除，无则加锁定测试防回归。
- 红测先行：VM 层断言点按键条→发出的帧 keys 字段正确且无 text；多行文本→单帧不拆分。
- 验收：`bash -lc 'cd app && ./gradlew -q :app:testDebugUnitTest'`（全量，不只 --tests 过滤——你动 conn 公共模型，须证明全套无回归）。

## 1. 现场基
- conn 包：ConnCodecTest.kt / ConnTestHelpers.kt（夹具消费机制，candidates 路径列表已含 server testdata）。Input 帧 Kotlin 模型在 conn 包（grep "input" 找到帧序列化处）。
- 会话页：session/SessionScreen.kt（输入条现场）+ SessionViewModel（发送逻辑、input_ack 处理既有形状）。
- **并行环境（app 模块当前四席，编译单元互阻高危——刚发生一起实案）**：w-test-appseams 写 app/src/test（接缝测试）+可能触碰 workspace/StateBadge；w-fix-wswire 写 workspace/ + AgentMirrorApp.kt；w-fix-pairpump 写 pairing/。你只动 session/ 与 conn/ 及其测试，**与三席文件零交集**；每次落盘保持 :app 整模块可编译（半成品绝不落盘——先在内存写完整再存）；若 go/gradle 编译错在你没写的文件，报 leader 勿自修。
- 键条 UI 风格：匹配既有 Compose 代码（Theme.kt/现有组件密度），锁中文文案（R-6 当期裁定），键条按钮加 contentDescription（R-7 顺带）。

## 2. 需求基（指针）
1. requirement-base/entries/017（R-1 最小键集/R-2 不拆分——裁定原文）
2. requirement-base/entries/003（发送必达：keys 帧同样要决定性 ack 可见失败）
3. docs/protocol.md input.keys 节（契约唯一权威，字段语义以它为准）

## 3. 经验基
- 契约消费不复制：夹具字节级断言防两端漂移；红测先行；代码必须有注释；交件前全量 :app:testDebugUnitTest + tools/gate/run.sh 自查；净化前缀 env -u TEAM_AGENT_*。
