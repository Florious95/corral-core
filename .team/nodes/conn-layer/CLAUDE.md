# 知识基底 · conn-layer（系统编译产物）

## 0. 任务（taskbook.yaml#conn-layer）
- 目标：安卓连接层（`:app` 内 `conn/` 包，纯 JVM 可测）：WS 客户端、指数退避自动重连、重连即重新 auth+subscribe（快照重放）、协议帧编解码（控制帧 JSON + 二进制流帧）。本层**不持久任何会话状态**（004 无状态铁律）。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :app:testDebugUnitTest --tests "*Conn*"'`
- 写范围：`app/app/src/main/java/**/conn/`、`app/app/src/test/`、`app/app/build.gradle.kts`（仅加依赖）。红线：不动 UI 包；不引入 GPL 依赖。

## 1. 架构基
- **协议唯一权威：`docs/protocol.md`（v1，已定稿）**——生命周期 §3、控制帧 §4、两级模型与聚合 §5、二进制帧 §6（scrollback 回复 12 字节头 [req_id:4BE][from_line:4BE 有符号][line_count:4BE 无符号]）、枚举 §7。实现前通读。
- **契约夹具**：`server/internal/protocol/testdata/`（14 JSON + 3 bin）是契约一部分——你的编解码单测必须消费同一份夹具做字节级断言（相对路径 `../server/internal/protocol/testdata/`），这是拦协议漂移的机制（013）。
- 选型：WS 客户端 OkHttp（Apache-2.0，Android 事实标准）；JSON 用 kotlinx-serialization-json（Apache-2.0）。二进制帧手写解析（几十行，别引库）。
- 分层：`FrameCodec`（纯函数编解码）/ `Connection`（单条 WS 生命周期）/ `ConnectionManager`（重连策略+订阅簿记：重连后自动重放 auth+全部活跃 subscribe；listing seq 不连续→自动重新 list，见 §4.2）。上层（UI/service）只见 Flow/回调，不见 WS 细节。
- 重连退避：指数 1s 起、上限 30s、抖动；网络可达性变化立即重试（Android 侧钩子留接口，JVM 测试用假时钟）。

## 2. 现场基
- `:app` Kotlin 2.2.0 / AGP 8.13.0；JVM 单测跑 testDebugUnitTest（无模拟器）。构建一律 `bash -lc`。
- WS 传输在单测中用假 WebSocket（接口抽象）测状态机；OkHttp 真连接归 e2e，不在本任务测试面。
- server 端 ws-api 尚未实现——你面向协议文档与夹具开发，不依赖真服务端。

## 3. 需求基（指针）
1. requirement-base/entries/003-对话体验四标准.md（发送必达/状态零丢失的客户端义务）
2. requirement-base/entries/004-后台策略-无状态免疫.md（本层无状态的理由）
3. requirement-base/entries/013-测试体系与回归门禁.md（契约测试层）

## 4. 经验基
- 红测先行：坏帧/截断/未知 type/版本不匹配/seq 跳变各一条红测；夹具字节级往返断言。
- 静默失效猎杀：每个发送路径必须让调用方可判定结果（input 以 input_ack 完结，超时=明确失败）。
- 注释红线照旧（KDoc 首句职责）；测试净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
