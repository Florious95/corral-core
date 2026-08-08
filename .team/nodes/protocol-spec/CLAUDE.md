# 知识基底 · protocol-spec（系统编译产物）——契约级任务

## 0. 任务（taskbook.yaml#protocol-spec，contention: contract）
- 目标：产出 WS 协议契约：`docs/protocol.md`（人读规范）+ `server/internal/protocol/` Go 类型与编解码 + 单测。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/server && go test ./internal/protocol/...'`
- 写范围：`server/internal/protocol/`、`docs/protocol.md`。红线见下。
- **契约级流程**：本任务产物是全工程的接口契约，leader 将逐条审后定稿；若你在设计中遇到需求维基未覆盖的政策抉择，send 给 leader 裁定，不要自行拍板。

## 1. 架构基（协议必须覆盖的能力面，全部由需求推导）
- 传输：单 WebSocket 连接。**JSON 控制帧 + 二进制流帧**（终端字节流不经 JSON 转义；二进制帧头含会话引用）。协议带版本字段，帧类型可扩展。
- 能力清单（括号内为需求出处，条目见第 3 节指针）：
  1. 配对鉴权握手：token 校验，未配对即断（011 路线a；token 不回显不落日志）
  2. 工作区列表：按 cwd 聚合的两级模型——工作目录→{会话数, 聚合状态}；会话→{名称, 状态, 尺寸}（002）
  3. 列表增量推送：会话新增/消失/状态变化主动推（001 舰队场景，避免轮询）
  4. 会话订阅：订阅即回首帧快照（capture-pane 全屏含颜色），随后二进制增量流（006 秒开）
  5. scrollback 分页拉取：按行区间请求历史（006 本地滚动）
  6. 输入注入：整条文本一次注入；**必须有可判定回执**（成功/失败+原因）（003 发送必达）
  7. resize：客户端行列数上报（005）
  8. agent 状态字段：working/idle/blocked/done/**unknown** 五值；状态通道与镜像通道解耦，unknown 不得影响镜像（008 隔离铁律）
  9. 图片上传：multipart HTTP 端点（同端口），落盘主机后返回绝对路径，客户端再作为文本注入（003）
  10. 重连语义：重新握手+重新订阅=重放当前快照，服务端无客户端会话状态（004 无状态铁律）
- Go 类型放 `internal/protocol`，只定义帧结构与编解码 + 帧往返（marshal/unmarshal round-trip）单测；不实现服务逻辑（那是 ws-api 任务）。

## 2. 现场基
- server/ 骨架已就位（internal/protocol/doc.go 占位注释在），go1.26.1。
- 尽量零第三方依赖（标准库 encoding/json 足够；WS 库由 ws-api 任务选型引入）。

## 3. 需求基（指针，按序读）
1. requirement-base/entries/002-两级分组模型.md
2. requirement-base/entries/003-对话体验四标准.md
3. requirement-base/entries/006-秒开与本地滚动.md
4. requirement-base/entries/008-生产级定位与开源许可.md（状态五值+隔离）
5. requirement-base/entries/005-自适应-让CLI自己重画.md（resize 语义）
6. requirement-base/entries/011-技术路线裁定.md（协议行）
（均位于 /Volumes/nvme/Projects/远程Agent安卓/）

## 4. 经验基
- 静默失效猎杀原则：返回值必须让调用方可判定实际发生了什么；一个字段禁止承载两件事（bool 压缩三态=信息丢失）；"已生效但核不了"必须与"未生效"可区分。
- 红测先行：先写帧编解码失败/缺字段/未知类型的红测。
- 测试净化前缀：`env -u TEAM_AGENT_WORKSPACE -u TEAM_AGENT_ID -u TEAM_AGENT_OWNER_TEAM_ID -u TEAM_AGENT_AGENT_ID go test ...`
- 注释红线：每个帧类型注释写清语义与触发方向（C→S / S→C）。

## 5. 沉淀区（唯一允许你追加写入的区域）
