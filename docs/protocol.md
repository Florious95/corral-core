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

### 2.1 QR 配对载荷（onboarding payload，task fix-pairing-candidates）

配对 onboarding 的载体是终端打印的二维码（requirement 011 路线 (a)），其内容是
**单行 JSON**（服务端 `internal/pairing` 生成、App `pairing` 包解析）。字段名是 wire
契约，不得改名；未知字段按 §4.1 前向兼容忽略：

```jsonc
{
  "v": 1,            // 载荷 schema 版本（当前恒 1；未知版本 App 拒绝并提示）
  "url": "ws://192.168.31.116:9900/ws",  // 主选 WebSocket 端点
  "token": "…",      // 配对 token：扫码即走 §3 auth 上行；QR 是其合法出口之一（§9）
  "ts_authkey": "",  // Tailscale auth key（可选，task feat-ts-wire；语义见下）
  "candidates": [    // 可选：同一主机的全部候选 ws URL（含主选 url）
    "ws://192.168.31.116:9900/ws",
    "ws://10.20.55.20:9900/ws",
    "ws://100.101.2.3:9900/ws"
  ]
}
```

- **`candidates` 是可选字段（0..n）**：同一主机的多网卡/多可达地址（fix-pairing-candidates
  P0：多真实网卡下哪个地址对端可达机器不可判定，产品把候选全集给出逐试，不赌单一主选）。
  **缺省或空数组 = 无候选，行为与旧版完全一致**——前向兼容增量，**不 bump 版本**；
  旧 App 扫含 candidates 的新 QR、新 App 扫无 candidates 的旧 QR，都只试 `url`，行为不变。
- **candidates 是同一主机的多地址，不是多主机档案**（017 R-3 后置：多主机支持走设置页
  重配单档覆盖，本字段不承担多主机语义）。
- 服务端生成：`candidates` = 全部可达地址的 ws URL（LAN + tailnet，按检测顺序；
  **不含 loopback**——对手机不可达，仅作主选最后兜底；主选本身为 loopback 的降级
  场景 candidates 为空，App 仅试主选，旧版行为不变）。
- App 消费（配对失败即候选逐试）：
  1. 主选 `url` 优先试；失败（拨号失败/不可达/超时）且 `candidates` 非空时自动逐试——
     按数组顺序，跳过与已试相同、空、或非 ws 的项，每候选 **3s 超时**；
  2. 任一候选 READY 即配对成功（持久化该候选 url + token）；
  3. 全部候选失败才落最终失败态，并展示候选列表供一键重试（每项可点，失败可见 003）；
  4. 手填表单地址支持从候选下拉选。
- 解析宽容：`candidates` 中非 ws URL / 空项**跳过不报错**；`candidates` 类型错误（非数组）
  视为无候选——坏候选不拖垮整个 QR，主选 `url` 仍可配对。

**Host discovery extension (v1, additive fields)**:

A new QR may carry `host_id`, `port`, `ts_node_id`, and display-only `name`; `url` may be
empty when the host is to be found on TS/LAN. `host_id` identifies the daemon, not a path,
and `name` is never an identity check. An unbound client first performs public discovery:
`GET /pair/whoami` contains no token and is only a candidate listing. It then proves a
literal IPv4 endpoint with `POST /pair/identify` before creating a WebSocket. The identify
body is `{"v":1,"host_id":...,"nonce":...,"dest_ip":...}` and the response is bound to the
same literal `dest_ip`; redirects, DNS names, IPv6, and `100.64/10` scans are rejected.
Only after a valid HMAC proof may the existing `auth` frame carry the host token. The
first TS-only probe uses port `9900`; a non-default port comes only from a host record, QR,
NSD resolution, or last-good hint. `whoami` and identify never persist credentials.
The server computes `HMAC-SHA256(pairing_token,
"agentmirror-identify-v1" || 0x1f || host_id || 0x1f || nonce_hex || 0x1f || dest_ip ||
0x1f || bound_port)` and returns lowercase hex `mac` plus `bound="ip:port"`. Clients
verify `host_id`, `bound`, and the MAC before writing a host record. A `404` legacy fallback
is allowed only for the exact scanned primary/persisted legacy URL, with no local `host_id`,
and never for discovered, NSD, last-good, or QR candidate hints.

**`ts_authkey` 语义（task feat-ts-wire，requirement 011 预授权分发裁定）**：

- 服务端通过环境变量 `TS_AUTHKEY` 配置 TS authkey 时，daemon 内嵌
  tsnet 节点真实起网，并把**同一 authkey** 原样写入本字段——预授权分发：App 扫码
  即拿到入网凭证，扫一次码同时完成「配对 + 加入 tailnet」，用户零额外操作（011
  路线 (a) 单一 App 原则）。未配置时字段为空串（与历史 QR 字节兼容，不 bump 版本）。
  因 daemon 与 App 会先后注册两个节点，此 key 必须允许至少两次使用；建议使用短期、
  预授权且由 ACL/tag 限权的 reusable key，配对完成后立即吊销。
- 服务端起网成功后，tailnet 地址（100.64.0.0/10）以 ws URL 形式**追加进
  `candidates`**（tsnet 用户态节点无本机网卡，接口探测看不见它，必须由 tsnet
  状态显式注入）；guide 明文区照常列出 tailnet 地址，**但绝不明文打印 authkey**。
- App 消费：`ts_authkey` 非空 → 在 App 进程内起 tsnet 用户态节点（gomobile 绑定，
  无 VpnService）；节点 Up 后，**仅目标 host 落在 100.64.0.0/10 的拨号**经 tsnet
  loopback SOCKS5 通道，其余地址（LAN 等）一律直拨——按地址选路，不引入全局代理，
  无 authkey 时保持历史直拨路径；携带 authkey 且节点仍在 Starting 时，tailnet 首拨
  等待节点进入 Up/Error，LAN 候选仍立即直拨（不把起网窗口误判成连接失败）。
- 手填通道：配对页保留 authkey 输入框，手填 key 与扫码 key 走同一起网入口。
- **安全红线（同 token §9 级）**：authkey 不落日志、不进错误文案、不在 UI 明文
  回显（输入框为密文态）；QR 是它唯一的分发出口；App 侧随配对配置持久化（与
  token 配置一起管理，但磁盘值由 Android Keystore AES-GCM 加密）。密钥不得通过
  argv flag 传入（进程列表/shell history 可见），只允许 `TS_AUTHKEY` 环境变量。

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
C ── input ───────────▶ S       整条文本/命名键注入
C ◀── input_ack ─────── S       必达回执（成功/失败+原因）
C ── scrollback ──────▶ S       按行区间拉历史
C ◀── [binary] scrollback S     一页历史（ANSI 字节）
C ── resize ──────────▶ S       上报手机行列数（CLI 自重画）
C ◀── [binary] snapshot S       resize 生效后补发全屏快照（清残影，fix-term-residuals）
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
| `input` | C→S | 整条文本/命名键注入 | `req_id`、`ref`；`text` 与 `keys` 至多其一 |
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

**input.keys（R-1 快捷键条，017 裁定；可选字段，前向兼容增量，不 bump 版本）**——
`input` 帧新增可选 `keys`：字符串数组，闭集 `esc` / `ctrl_c` / `tab` / `up` / `down` /
`left` / `right`（对应 tmux 命名键 Escape / C-c / Tab / Up / Down / Left / Right）。
**`text` 与 `keys` 互斥**：一帧至多携带其一——两者都有判协议错误（帧校验失败，S 回
`error: bad_frame`，不执行注入、不回 `input_ack`）；两者皆无 = 仅回车（既有语义不变）。
`keys` 注入**不附加回车**：快捷键条语义是"按一下那个键"，Esc/Ctrl-C/Tab/方向键后再补
Enter 可能误触发 CLI 确认——这与 `text` 的"注入+回车"本质不同。旧客户端只发 `text`
不发 `keys`，行为完全不变（send-keys 仍走既有路径）。

**多行文本与 R-2 退化风险**——含换行的 `text` 由 S 走既有 `paste-buffer -p` 括号粘贴路径
整段注入 + Enter，**App 不拆分**（R-2 裁定，017）。退化风险：目标 CLI 不支持 `?2004`
（DECSET 括号粘贴模式）时，终端退化为**逐行执行**，多行内容可能被 CLI 逐行当作命令执行。
此行为由 CLI 自身是否声明 `?2004` 决定，S 侧以 R-2 已测路径（`TestInjectMultiline`）为准；
在支持括号粘贴的 CLI（Claude Code 等）上无感。

**input_ack**——`req_id` 对应 `input.req_id`。`ok:true` 表示字节已进面板；`ok:false` 必须携带
`reason`，枚举见 §7.2。`reason` 存在当且仅当 `ok:false`（一字段一义）。

**scrollback**——`from_line` 按 tmux capture-pane 语义寻址：0=当前屏顶行，负值=屏上历史。
`count≥1`。S 收敛到可用范围，并在二进制 `scrollback` 回复中报告实际区间（见 §6.3）。

**resize**——上报手机行列数；S resize 底层窗口（grouped session + `window-size latest`，
谁最近操作听谁的，requirement 005）。只作用于已订阅会话。resize 成功应用后 S **必须**
向该连接补发一帧二进制 `snapshot`（语义同 subscribe 首帧：C 清屏重建，见 §6.2）。
理由（fix-term-residuals）：SIGWINCH 后 CLI 的重画只以 `delta` 叠加在 C 的旧几何网格上，
旧提示符残影无法确定性清除；快照重放是唯一收敛点。补发的 `snapshot` 同时充当事实回执，
仍然没有独立的 resize ack 帧；未订阅会话的 resize 仍是 no-op、无任何回复。

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
| `1` snapshot | S→C | 全屏快照（capture-pane -e，含颜色转义）：subscribe 生效后首帧；resize 生效后补发。C 收到即清屏重建。S 裁去尾部空行并在字节尾追加游标重锚序列（CUP，1 基），使 C 重放后游标与面板真游标一致——否则随后不带绝对定位的 delta（如 bash SIGWINCH 重绘）会落在快照末尾行（残影根因之二，fix-term-residuals） |
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
- 鉴权：请求必须携带标准 HTTP 头 `Authorization: Bearer <pairing-token>`；服务端用与
  WebSocket `auth` 握手相同的 `TokenValidator` 校验。缺失或错误凭据均返回 HTTP 401，
  JSON 正文包含稳定的 `code: "unauthorized"` 与非空 `reason`；响应和日志不得回显 token。
- 成功：S 将文件落盘主机，返回 JSON `{"path": "/绝对/路径"}`（`protocol.UploadResp`）。
- 资源上限：单文件默认不超过 20 MiB，上传目录内的常规文件总量硬上限为 1 GiB；本次写入
  会越过目录上限时返回 HTTP 507，JSON `code` 为 `storage_limit_exceeded` 并携带非空
  `reason`。服务端不自动删除自定义目录中的既有文件，由用户清理后重试。
- C 再将该 `path` 作为 `input.text` 注入，CLI 原生吃图片路径（requirement 003 图片管线；
  **不涉及任何多模态 API**）。

## 9. 安全与日志

- 配对 token 只在 `auth` 上行一次；**任何回复不回显、任何日志不出现**（011 路线 a）。
- 未认证连接的任何操作一律 `error: unauthorized`。
- 服务端日志不得记录帧 payload 中的敏感内容。

### 9.1 token 生命周期与全量吊销

- 默认 token 由 daemon 生成并以 `0600` 保存到系统用户配置目录的 `agentmirror/token`，重启复用；
  停止 daemon、删除该文件并重启会生成新 token，从而让全部旧 App 配置在下一次认证时失效。
- 显式 `-token` / `AGENTMIRROR_TOKEN` 不写入 token 文件且优先级更高；轮换时必须改为新值并重启，
  只删除自动 token 文件不能吊销仍在使用的显式值。
- 轮换后 App 必须重新扫描新 QR；单档客户端在新配对成功后覆盖旧配置。token 文件内容、显式值和
  QR 均不得进入日志或截图。

## 10. 参考实现与契约夹具

- `server/internal/protocol/version.go` — 版本常量
- `server/internal/protocol/frametype.go` — 帧类型判别符
- `server/internal/protocol/frames.go` — 帧结构与 Envelope/Typed
- `server/internal/protocol/json.go` — JSON 编解码（MarshalFrame/UnmarshalFrame）
- `server/internal/protocol/binary.go` — 二进制流帧编解码（EncodeBinary/DecodeBinary）
- `server/internal/protocol/state.go` / `errors.go` — 状态枚举与错误码
- `server/internal/pairing/qr.go` — QR 配对载荷（§2.1）：`Payload`/`NewPayload`/`Marshal`
- `server/internal/protocol/*_test.go` — 帧往返与红测（红测先行）

### 10.1 契约夹具（testdata/）——协议的一部分

`server/internal/protocol/testdata/` 是**契约的一部分**（leader 裁定，计入验收）：

- `*.json` — 每种控制帧类型一个 golden 样本（v1 线上字节）；`input` 有 text 与 keys
  两个变体样本（`input.json` / `input_keys.json`，字段名与顺序冻结）。
- `*.bin` — 三种二进制流帧各一个样本；`*.bin.txt` 为字节注解。
- 客户端（Kotlin conn-layer）与 Go 实现**消费同一份夹具**做编解码断言，拦协议漂移。
- 本仓库往返单测（golden_test.go）要求每个样本**字节级稳定**（decode→re-encode 不变）。
- **未经版本 bump 不得增删、重命名或重排这些文件**；新增帧类型必须同步补样本。
