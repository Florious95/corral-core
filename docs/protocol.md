# agentmirror 传输协议（WebSocket）v1

> 状态：协议契约 v1（protocol-spec 任务产出）。本文档是**人读规范**；`server/internal/protocol/`
> 是机器可校验的 Go 参考实现，二者以本文为准，不一致时以本文为准并修 Go 实现。
>
> 需求出处：`requirement-base/entries/003`（四标准）、`005`（resize）、`006`（秒开+滚动）、
> `008`（状态五值+隔离铁律）、`011`（技术路线裁定：WS 传输）。

## 0. 术语

| 术语 | 含义 |
|---|---|
| C / S | 客户端（Android App）/ 服务端（agentmirrord） |
| 会话（session） | 一台 tmux 会话中的一个被镜像 pane（一个 Agent CLI） |
| 工作区（workspace） | 按 cwd 聚合的会话组，一级分组键（requirement 002） |
| ref | 服务端分配的会话引用，客户端用它寻址 subscribe/input/scrollback/resize；**非**展示用 name |
| 控制帧 | WebSocket **text** 消息，JSON，携带协议元信息（v + type + payload） |
| 流帧 | WebSocket **binary** 消息，携带原始终端字节流（ANSI/VT），**不经 JSON 转义** |

## 1. 传输模型

单条 WebSocket 连接同时承载两类消息：

- **text** 消息 = 一个 JSON 控制帧（见 §4）。终端字节流**永不**进入 JSON。
- **binary** 消息 = 一个二进制流帧（见 §6），帧头内嵌会话 ref，因此一条连接可多路复用多个会话镜像。

连接 URL：`ws://<host>:<port>/ws`（路径由 ws-api 任务最终裁定；端口见
`internal/config`）。控制帧与流帧交错到达，互不阻塞。

## 2. 版本

- 当前协议版本 `v = 1`（`protocol.Version`）。
- 每个 JSON 控制帧的 `v` 字段、每个二进制流帧的版本字节必须等于双方协商的版本。
- **版本不匹配**：S 发送 `error` 帧（`code: unsupported_version`）后关闭连接。
- 未知 **type** 是错误；未知 **JSON 字段**被忽略（向前兼容，§4.1）。
- 新增帧类型 / 新增可选字段是**增量变更**，不 bump 版本；删除/重定义字段是破坏性变更，必须 bump。
- 二进制流帧另有独立版本字节（现等于 `v`），仅当二进制帧格式本身需修复时才独立 bump。

## 3. 生命周期

```
C ── auth ────────────▶ S       握手：携带配对 token（011 路线 a）
C ◀── auth_ack ─────── S       ok，或拒绝+reason（拒绝后 S 立即关闭连接）
   （此后 S 只接受已认证连接，未认证操作回 error: unauthorized）

C ── list ────────────▶ S       拉取完整两级列表
C ◀── listing ──────── S       全量快照（req_id 对应）

S ── list_delta ──────▶ C       会话新增/消失/状态变化主动推送（无轮询）
C ── subscribe ───────▶ S       订阅一个会话镜像
C ◀── [binary] snapshot S       首帧全屏快照（capture-pane -e，含颜色）
C ◀── [binary] delta ── S       后续增量字节流（pipe-pane）
C ── input ───────────▶ S       整条文本注入
C ◀── input_ack ─────── S       必达回执（成功/失败+原因）
C ── scrollback ──────▶ S       按行区间拉历史
C ◀── [binary] scrollback S     一页历史（ANSI 字节）
C ── resize ──────────▶ S       上报手机行列数（CLI 自重画）
C ── unsubscribe ─────▶ S       停止镜像
   （任一方向均可先关闭连接；关闭即隐式退订全部会话）
```

**重连语义（requirement 004 无状态铁律）**：S **不**保存客户端会话状态。重连 =
重新 `auth` + 重新 `subscribe` = 重新 `snapshot`（当前屏重放）。不存在"消息丢了"；
链路的唯一状态就是主机 tmux 这个事实源。S 允许 C 用新连接代替旧连接，无需显式断开。

## 4. 控制帧

每个控制帧是一个 JSON 对象：

```jsonc
{
  "v": 1,              // 协议版本
  "type": "list",      // 帧类型判别符
  "payload": { ... }   // 类型专用负载；不得包含终端字节
}
```

### 4.1 帧类型表

| type | 方向 | 语义 | payload 必填字段 |
|---|---|---|---|
| `auth` | C→S | 配对握手 | `token`（写用，**不回显、不落日志**） |
| `auth_ack` | S→C | 握手裁决 | `ok`; 拒绝时 `reason` |
| `list` | C→S | 请求全量列表 | `req_id` (≥1) |
| `listing` | S→C | 全量列表回复 | `req_id`、`seq`、`workspaces[]` |
| `list_delta` | S→C | 列表增量推送 | `seq` + 四组字段（见 §5.2） |
| `subscribe` | C→S | 订阅会话镜像 | `ref`、`rows`、`cols` |
| `unsubscribe` | C→S | 停止镜像（幂等） | `ref` |
| `input` | C→S | 整条文本注入 | `req_id`、`ref`、`text` |
| `input_ack` | S→C | 注入回执（必达） | `req_id`、`ok`; 失败时 `reason` |
| `scrollback` | C→S | 按行区间拉历史 | `req_id`、`ref`、`from_line`、`count` |
| `resize` | C→S | 上报行列数 | `ref`、`rows`、`cols` |
| `error` | S→C | 协议级错误 | `code`、`reason` |

**前向兼容**：客户端/服务端在信封与 payload 中遇到未知字段**必须忽略**（不报错）。
未知 `type` 必须报错（`error`）。`payload` 可省略，此时按零值校验（缺必填字段即错误）。

### 4.2 帧详情

**auth**（C→S）——token 一次性上行，任何回复不回显，服务端日志**禁止**出现 token。

**auth_ack**（S→C）——`ok:true` 通过；`ok:false` 携带 `reason`，S 随后**立即关闭连接**。
C 可把"auth 后立即断开"视作拒绝。

**list / listing**——`listing.req_id` 对应 `list.req_id`。`listing.seq` 单调递增（≥1）。
若 `list_delta` 先于 `listing` 到达，或 `list_delta.seq` 不连续于 C 上次见过的 `seq`，
C **必须**重新 `list` 拉全量（无状态恢复）。

**subscribe**——成功后 S 先发一个二进制 `snapshot`，再流 `delta`。`rows/cols` 为 C 初始终端
尺寸，S 以此 resize 底层面板（requirement 005）。订阅失败以 `error` 帧告知（如
`session_not_found`）。重复 subscribe 同一 ref 幂等：重放 snapshot + 重流（重连语义）。
**unsubscribe** 幂等；退订未订阅的会话不算错误；连接关闭即全部退订。

**input**——整条文本一次性注入并回车（send-keys 语义，非逐键，requirement 003）。
`text` 为空 = 仅回车，允许。S **必须**回 `input_ack`（成功/失败+原因），杜绝"发了没反应"。

**input_ack**——`req_id` 对应 `input.req_id`。`ok:true` 表示字节已进面板；`ok:false` 必须携带
`reason`，枚举见 §7.2。`reason` 存在当且仅当 `ok:false`（一字段一义）。

**scrollback**——`from_line` 按 tmux capture-pane 语义寻址：0=当前屏顶行，负值=屏上历史。
`count≥1`。S 收敛到可用范围，并在二进制 `scrollback` 回复中报告实际区间（见 §6.3）。

**resize**——上报手机行列数；S resize 底层窗口（grouped session + `window-size latest`，
谁最近操作听谁的，requirement 005）。只作用于已订阅会话。

**error**——协议级失败（坏帧、未知类型、缺会话、版本不支持、内部错误）。`code` 枚举见 §7.1。

## 5. 两级分组模型与列表增量

### 5.1 全量列表（listing）

```jsonc
{
  "v": 1, "type": "listing",
  "payload": {
    "req_id": 7, "seq": 42,
    "workspaces": [
      {
        "cwd": "/proj/a", "session_count": 2, "aggregate_state": "blocked",
        "sessions": [
          {"ref": "s1", "name": "claude", "cwd": "/proj/a", "state": "working", "rows": 40, "cols": 100},
          {"ref": "s2", "name": "codex",  "cwd": "/proj/a", "state": "blocked",  "rows": 24, "cols": 80}
        ]
      },
      { "cwd": "/proj/b", "session_count": 1, "aggregate_state": "unknown",
        "sessions": [
          {"ref": "s3", "name": "claude", "cwd": "/proj/b", "state": "unknown", "rows": 30, "cols": 90}
        ]
      }
    ]
  }
}
```

一级 = cwd（聚合键），二级 = 会话。`session.name` 是展示标签，**不参与分组**
（requirement 002 反面教材：herdr 平铺）。`ref` 与 `name` 分离：ref 是寻址键，name 可重名。

### 5.2 聚合状态规则（服务端权威计算，leader 裁定已固化）

`workspace.aggregate_state` **由服务端计算并下发**，客户端只渲染、不重算。规则：

1. 取该 cwd 下所有会话中 **注意力优先级最高** 者的状态。
2. 优先级表（**由高到低**，此语义 = 注意力需求排序：谁最需要人看谁）：

   ```
   blocked > done > working > idle
   ```

3. **unknown 不计入聚合**：仅自身显示 unknown，不参与取最高；满足 008 状态/镜像解耦。
4. 工作区所有会话均 unknown 时，聚合为 `unknown`。

未来新增状态按"注意力需求"原则插入本表；**本优先级表为协议正文**，bump 才可改。

### 5.3 增量（list_delta）

```jsonc
{
  "v": 1, "type": "list_delta",
  "payload": {
    "seq": 43,
    "added_sessions":     [ {会话} ],       // 新增会话，含完整当前值
    "removed_refs":       [ "s1" ],         // 消失的 ref 列表
    "changed_sessions":   [ {会话} ],       // 状态/尺寸变化，按 replace 应用
    "changed_workspaces": [ {工作区} ]      // 工作区聚合/计数变化
  }
}
```

四组字段在同一 delta 内**两两不相交**（一个会话每 delta 只出现在一组）。`seq` 单调递增。
工作区聚合由 S 重算（规则唯一来源在 S），delta 只推 `changed_workspaces`（其中 `sessions`
可省略，只携带 cwd/count/aggregate_state 语义）。

## 6. 二进制流帧

终端字节流以原始 ANSI/VT 字节承载，**绝不 JSON 转义**。一条 binary 消息 = 一帧。

### 6.1 布局

```
偏移   长度   内容
0-1    2      magic "RA"（两字节）
2      1      version（= 1）
3      1      kind（见 §6.2）
4      1      reflen（0..255，会话 ref 字节长度）
5      5+reflen  ref（UTF-8）
5+reflen  ...  payload（kind 相关）
```

- magic 与 version 在最外层：解码器先验 magic/version，再信任何字节。
- `reflen=0` 非法（空 ref）。
- ref 上限 255 字节；单帧 payload 上限 1 MiB（`protocol.BinaryMaxPayloadLen`）。

### 6.2 kind

| kind | 方向 | 语义 |
|---|---|---|
| `1` snapshot | S→C | subscribe 生效后首帧全屏（capture-pane -e，含颜色转义） |
| `2` delta | S→C | 一段增量终端字节（pipe-pane），追加到当前屏 |
| `3` scrollback | S→C | 一页历史（capture-pane -S），回答 scrollback 请求 |

### 6.3 scrollback 回复载荷

kind=3 时 payload 头部为 **12 字节元数据头**，描述**服务端实际返回的行区间**，
随后是 ANSI 字节：

```
[req_id: 4BE 无符号][from_line: 4BE 有符号][line_count: 4BE 无符号][ANSI 字节流]
```

- `req_id` 对应请求（≥1）。
- `from_line` / `line_count` 为**服务端收敛后的实际区间**（tmux capture-pane 语义：
  0=当前屏顶，负值=屏上历史）。请求越界时 S 收敛到可用范围并在此**如实报告实际区间**，
  客户端据此锚定本地滚动视口——无此元数据则客户端无法定位收敛后的页锚点，历史拼接会错位。

例如请求 `from_line=-300, count=100` 而 tmux 仅有 50 行历史，回复为
`from_line=-100, line_count=50`。

## 7. 枚举

### 7.1 状态（AgentState，五值闭集）

`working` / `idle` / `blocked` / `done` / `unknown`（requirement 008）。

- `unknown` 是**一等公民值**，不是错误；任何状态解析失败降级为它。
- **状态只出现在控制帧**（listing / list_delta），**永不进入二进制镜像通道**——
  状态层与镜像层严格解耦（008 隔离铁律）：状态判不出不影响镜像与输入。

### 7.2 error 帧 code（ErrorCode）

| code | 语义 |
|---|---|
| `unauthorized` | 未认证即操作 |
| `bad_frame` | 控制帧无法解析 |
| `unsupported_version` | 版本不匹配（随后关闭） |
| `unsupported_type` | 未知帧类型 |
| `session_not_found` | ref 无对应存活会话 |
| `internal` | 服务端内部错误 |

### 7.3 input_ack 失败 reason（InputFailReason，ok:false 时必带）

| reason | 语义 |
|---|---|
| `session_not_found` | 目标会话已不存在 |
| `not_subscribed` | 未订阅该会话即注入 |
| `inject_failed` | tmux send-keys 被拒 |
| `too_large` | 文本超服务端大小上限 |
| `internal` | 服务端内部错误 |

## 8. 图片上传（HTTP，同端口）

- 图片走 **multipart HTTP 端点**，不走 WebSocket：`POST /upload`（同服务端口）。
- 成功：S 将文件落盘主机，返回 JSON `{"path": "/绝对/路径"}`（`protocol.UploadResp`）。
- C 再将该 `path` 作为 `input.text` 注入，CLI 原生吃图片路径（requirement 003 图片管线；
  **不涉及任何多模态 API**）。

## 9. 安全与日志

- 配对 token 只在 `auth` 上行一次；**任何回复不回显、任何日志不出现**（011 路线 a）。
- 未认证连接的任何操作一律 `error: unauthorized`。
- 服务端日志不得记录帧 payload 中的敏感内容。

## 10. 参考实现与契约夹具

- `server/internal/protocol/version.go` — 版本常量
- `server/internal/protocol/frametype.go` — 帧类型判别符
- `server/internal/protocol/frames.go` — 帧结构与 Envelope/Typed
- `server/internal/protocol/json.go` — JSON 编解码（MarshalFrame/UnmarshalFrame）
- `server/internal/protocol/binary.go` — 二进制流帧编解码（EncodeBinary/DecodeBinary）
- `server/internal/protocol/state.go` / `errors.go` — 状态枚举与错误码
- `server/internal/protocol/*_test.go` — 帧往返与红测（红测先行）

### 10.1 契约夹具（testdata/）——协议的一部分

`server/internal/protocol/testdata/` 是**契约的一部分**（leader 裁定，计入验收）：

- `*.json` — 每种控制帧类型一个 golden 样本（v1 线上字节）。
- `*.bin` — 三种二进制流帧各一个样本；`*.bin.txt` 为字节注解。
- 客户端（Kotlin conn-layer）与 Go 实现**消费同一份夹具**做编解码断言，拦协议漂移。
- 本仓库往返单测（golden_test.go）要求每个样本**字节级稳定**（decode→re-encode 不变）。
- **未经版本 bump 不得增删、重命名或重排这些文件**；新增帧类型必须同步补样本。
