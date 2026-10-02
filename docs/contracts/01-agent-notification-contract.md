# 01 · Agent 主动任务通知契约（notifications_v1）

> 状态：**Issue #42 架构提案，尚未实现**。实现发布后，声明 `notifications_v1` 能力即承诺遵守本文。
> 基础协议：[WebSocket v1](../protocol.md)。本契约是能力协商保护的增量扩展，不修改终端 binary 格式、不把全局 `v` 改成 2。
> MUST / MUST NOT / SHOULD 分别表示必须 / 禁止 / 应当。Android、iOS、Desktop 使用同一字段和恢复语义，不另造平台私有协议。

## 1. 范围、信任和成功的含义

- **唯一发布入口是 Agent 主动执行 CLI**；不监听 Agent 最终输出、不读取完成词、不安装自动 Hook、不从进程退出推断任务完成。
- 链路：`corral-notify → 本机私有 IPC → daemon 持久化 → 所有已认证且协商通知能力的在线 WS 客户端`。
- V1 覆盖：在线实时消息 + 重连/首次连接后的历史拉取 + 本地消息中心 + 平台允许时的系统通知。
- V1 **不包含 APNs/FCM、不保证杀进程/断网/系统冻结时收到系统通知**。iOS 后台暂停 WS 的客户端属于离线；下次连接补历史，不能冒称 background push。
- CLI 成功只表示 daemon **已持久化接受**；不表示任一客户端在线、收到、已读或已弹出系统通知。
- 网络送达为实时最佳努力 + 保留窗口内可补拉；客户端必须幂等。不是无限期 exactly-once 投递。
- 通知是独立业务事件，不等于终端快照、Agent 状态或自动任务终结判据。

## 2. 标识、记录与编码

### 2.1 NotificationRecord

下表字段在已接受记录中全部出现；可空字段编码为 JSON `null`，不是空串。记录创建后不可修改。

| 字段 | 类型 / 范围 | 语义与来源 |
|---|---|---|
| `id` | string，标准小写 UUID v4 | daemon 生成的通知身份；同一次接受/幂等重试保持不变 |
| `host_id` | string，8–64 个 ASCII 字母/数字/`-`/`_` | 持久主机身份；不得用 IP、主机展示名或配对 token 代替 |
| `stream_id` | string，UUID v4 | 通知历史世代；daemon 正常重启不变，显式重建通知存储后才变化 |
| `seq` | string，十进制 uint64，1 起，无前导零 | 本 stream 的严格递增提交序号；**不得复用 listing / L2 seq** |
| `timestamp` | string，UTC RFC3339，固定毫秒与 `Z` | daemon 提交时间，如 `2026-10-01T01:00:00.123Z`；用于展示，不用于排序/去重 |
| `title` | string，1–256 UTF-8 bytes，非全空白 | 请求提供；缺省为 `Agent 任务通知` |
| `body` | string，1–16,384 UTF-8 bytes，非全空白 | Agent 原始消息正文；保留换行/空格，禁止静默截断 |
| `session_ref` | string（1–255 UTF-8 bytes）或 null | 服务端规范化的 opaque 会话引用；**不是**展示名，也不一定是 `%140` 这样的裸 pane id |
| `session_instance` | string（1–128 个 ASCII 字母/数字/`-`/`_`）或 null | 服务端会话 incarnation；历史链接的复用保护，见 §8 |
| `workspace` | string（≤4,096 UTF-8 bytes）或 null | 发布时对应 pane 的 cwd，展示快照；不是跳转寻址键 |
| `agent_name` | string（1–128 UTF-8 bytes）或 null | 服务端根据已识别 pane 的会话名称投影推导；Leader 统一为 `Leader`，调用方不可覆盖；只供展示 |
| `level` | `info` / `success` / `warning` / `error` | Agent 显式严重程度；缺省 `success`，不意味着 daemon 判定任务真的完成 |

约束：
- `session_ref` 与 `session_instance` 必须同时为 null，或同时非空。
- title/body/agent_name 为普通文本：必须是合法 UTF-8；允许 body 内 `\n` / `\r` / `\t`，拒绝其他 C0 控制字符与 NUL。不得把正文当 HTML、终端 ANSI 或可执行命令。
- opaque `session_ref` 按现有协议保留其合法分隔字符（包括 `\u001f`），不能套用正文的控制字符限制；NUL 禁止。
- seq/cursor 的整数用**字符串**，避免 JavaScript Number 精度损失；比较必须按数值，禁止字典序排序。`"0"` 仅用于空历史/cursor，事件 seq 永不为 0。
- host_id/stream_id/id 是不同概念。客户端记录主键为 `(host_id, id)`，顺序键为 `(host_id, stream_id, seq)`。
- 同 id 重传必须保持所有记录字段一致；不同内容同 id 属协议损坏，客户端不得再提醒。
- 未知 JSON 字段按基础协议忽略；未知 level/非法记录拒绝进入业务存储并报告解码异常，不触发系统提醒。

### 2.2 实时事件

Frame Kind：**JSON 控制帧**；WebSocket opcode：**0x1 Text**；type：`notification`；方向：S→C。

```json
{
  "v": 1,
  "type": "notification",
  "payload": {
    "id": "720a303f-c883-445f-a3df-c316d901a065",
    "host_id": "host_0123456789abcdef",
    "stream_id": "4e4ca540-df59-4b79-a285-7d1f7523c7ab",
    "seq": "42",
    "timestamp": "2026-10-01T01:00:00.123Z",
    "title": "验收完成",
    "body": "Issue #42 的契约与验收方案已完成。\n详见报告。",
    "session_ref": "/tmp/tmux-501/team\u001f%3",
    "session_instance": "pane-instance-example-01",
    "workspace": "/work/project",
    "agent_name": "Sol",
    "level": "success"
  }
}
```

- 不增加 binary Kind、私有 WS opcode 或终端 ANSI 通知标记。
- 单个通知/历史响应 text message 上限 **128 KiB（序列化后的 UTF-8 bytes）**。校验原始正文上限不代替校验完整 wire 长度。
- `notification` 仅表示提交后的实时发布。历史只能用 §4 的 `notifications_page`，两者提醒策略不同。

## 3. 能力协商与兼容性

客户端 `auth.payload` 增加可选字段：

```json
{"v":1,"type":"auth","payload":{"token":"EXAMPLE-ONLY","capabilities":["notifications_v1"]}}
```

服务器成功 `auth_ack.payload`：

```json
{
  "v": 1,
  "type": "auth_ack",
  "payload": {
    "ok": true,
    "capabilities": ["notifications_v1"],
    "notification_state": {
      "host_id": "host_0123456789abcdef",
      "stream_id": "4e4ca540-df59-4b79-a285-7d1f7523c7ab",
      "head_seq": "41",
      "retained_from_seq": "1"
    }
  }
}
```

- `capabilities`为至多32个字符串的数组，每个1–64个ASCII字母/数字/`_`/`-`/`.`；非数组/非字符串/超限是invalid_field。它为双方支持能力的交集，不得仅返回服务端全部能力。未知能力字符串忽略、重复项去重。
- 对未声明capabilities的旧客户端保留原auth_ack字段集合，不附加notification_state；空交集可返回空capabilities或省略。不要为了协议扩展无必要改变旧golden/旧握手字节。
- auth 不带 capabilities = 不支持；auth_ack 不带 capabilities = 旧服务端/能力未启用。新客户端此时禁止发新通知请求，只保留本地旧消息供阅读并展示“服务端不支持通知”。
- 只有 token 通过认证且交集含 `notifications_v1` 的连接，才可收到新通知帧、请求历史、使用本契约的 guarded session link。
- auth_ack 必须先入发送队列，随后才允许 notification/page；`notification_state` 在协商成功时必有，数据来源不能信任 CLI。
- notification_state 的 head 是握手时观察值，**不是**历史已经同步成功的游标。
- 未认证请求沿用 `error: unauthorized`；未协商通知能力的请求沿用 `error: unsupported_type`，禁止向该连接返回新通知类型。
- 新服务端+旧客户端：终端功能不变，绝不盲发 notification。新客户端+旧服务端：正常终端功能、无通知能力。无需同时升级所有设备。
- 存储初始化失败时不得声明该能力或假装返回空历史；保留终端服务，明确报告通知功能不可用。

## 4. 历史拉取、分页与 live/sync 竞争

### 4.1 游标

```json
{"stream_id":"4e4ca540-df59-4b79-a285-7d1f7523c7ab","seq":"41"}
```

cursor 是**已完成同步/连续处理到的位置**，不是“见过的最大实时 seq”。客户端不得因为先收到 seq=45，就跳过尚未收到的 42–44。

### 4.2 首页请求

C→S，type=`notifications_sync`，opcode=0x1：

```json
{"v":1,"type":"notifications_sync","payload":{"req_id":7,"cursor":{"stream_id":"4e4ca540-df59-4b79-a285-7d1f7523c7ab","seq":"41"},"page_size":50}}
```

- req_id：1..2,147,483,647 的 JSON 整数，连接内相关请求未完成前不得复用。
- cursor 缺省/null：首次 bootstrap；非空：增量同步。
- page_size：缺省 50，范围 1..100；这是每页**条数上限**，服务端还按 128 KiB wire 上限缩小本页。
- 服务端在首次请求时捕获有限、不可变的历史视图，冻结该次同步的 host/stream、保留下界 L 与 head H。后续并发通知不进入该视图，由 live 或下一次 sync 补充。

### 4.3 成功响应和下一页

S→C，type=`notifications_page`：

```json
{
  "v": 1,
  "type": "notifications_page",
  "payload": {
    "req_id": 7,
    "ok": true,
    "host_id": "host_0123456789abcdef",
    "stream_id": "4e4ca540-df59-4b79-a285-7d1f7523c7ab",
    "snapshot_cursor": {"stream_id":"4e4ca540-df59-4b79-a285-7d1f7523c7ab","seq":"43"},
    "retained_from_seq": "1",
    "reset_reason": null,
    "order": "asc",
    "items": [{
      "id":"720a303f-c883-445f-a3df-c316d901a065",
      "host_id":"host_0123456789abcdef",
      "stream_id":"4e4ca540-df59-4b79-a285-7d1f7523c7ab",
      "seq":"42",
      "timestamp":"2026-10-01T01:00:00.123Z",
      "title":"验收完成",
      "body":"Issue #42 的契约与验收方案已完成。\n详见报告。",
      "session_ref":"/tmp/tmux-501/team\u001f%3",
      "session_instance":"pane-instance-example-01",
      "workspace":"/work/project",
      "agent_name":"Sol",
      "level":"success"
    }],
    "next_page_token": "opaque-example-token",
    "resume_cursor": null
  }
}
```

上例视图 head=43，本页返回42，下一页仍有43；非终页 `items` 必须非空，且每项为完整 NotificationRecord。继续请求：

```json
{"v":1,"type":"notifications_sync","payload":{"req_id":8,"page_token":"opaque-example-token"}}
```

- page_token 为服务端 opaque 值，最多 256 ASCII bytes；只能用于生成它的同一认证连接、同一同步视图。客户端不得解析/拼接。
- 继续请求只能携带 req_id/page_token，不再同时携带 cursor/page_size。
- 每连接至多一个活动历史视图；新的首页请求使旧 token 作废。重复使用当前有效 token可幂等重传该页，直到客户端以响应给出的 next token 前进。
- 视图生命周期限于该连接/该次同步完成；资源必须有界，最多引用保留窗口内的记录，不持有全局发布锁、不复制无限历史。
- 最后页 `next_page_token=null`，`resume_cursor=snapshot_cursor`；非最后页 resume_cursor=null。完整同步结束后才把 H 提交为已同步游标。最后token的应答丢失时仍允许同连接重取相同终页；服务端可只缓存最后应答，释放整个视图，直到新首页请求/断线使该token作废。
- 无新增记录/空历史仍返回一个终页；此时 items=[] 合法。空历史 head_seq="0"、retained_from_seq="1"。
- page之间可穿插 live notification 与终端帧；同一同步视图的元数据、记录内容不得变。客户端按 `(host_id,id)` 合并，不能把历史再次提醒。
- 客户端每次只拉一页；先持久化该页，再申请下一页，不一次塞满现有 WS send queue，不阻塞终端首帧。

### 4.4 Bootstrap / 过期 / 重置

| 条件 | reset_reason | 返回集合 / 顺序 |
|---|---|---|
| cursor null | `bootstrap` | 捕获的全部保留记录，按 seq **desc**，先展示最新 |
| cursor.stream_id != 当前 stream | `stream_reset` | 同上，重新建立当前 stream 基线 |
| 同 stream，cursor.seq < L-1 | `retention_gap` | 同上；明确告知缺失记录已超出保留窗口 |
| 同 stream，L-1 ≤ cursor.seq ≤ H | null | `(cursor.seq,H]`，按 seq **asc** |
| cursor.seq > H | 失败 `invalid_cursor` | 不静默回退或伪称同步成功 |

- 客户端仅在 bootstrap/reset 全部分页完成后替换该主机旧 stream/窗口的缓存基线；失败/断线不得先清空旧消息造成白屏。替换必须保留此次同步期间已收到的当前stream、seq>H的live记录，不能把未包含于历史视图的新通知删掉。
- 同步期间收到的 live 可入本地库/提醒，但不能抢先把同步游标推进到 H 之后。完成后折叠已存储的连续 live；有序号洞则再 sync。
- 中途断线以先前已确认的游标重来，重复历史去重；不能把 page_token 持久化成跨连接游标。
- retention_gap/stream_reset 必须成为可见的历史完整性状态；V1 不承诺恢复已被裁剪或显式删除的服务器历史。

### 4.5 失败响应

只对已协商能力、req_id 可解析的请求：

```json
{"v":1,"type":"notifications_page","payload":{"req_id":8,"ok":false,"error":{"code":"invalid_page_token","reason":"history view is no longer available"}}}
```

通知分页错误 code：`invalid_request`、`invalid_cursor`、`invalid_page_token`、`store_unavailable`、`internal`。失败无 items/resume_cursor；不得推进游标。envelope/req_id 本身无法解析时沿用基础 `error`，不伪造相关 req_id。

## 5. 服务器保存与发布语义

- 持久保存**最近最多 1,000 条**，并把通知存储的序列化有效载荷限制在 **16 MiB**；任一上限触发时裁剪最旧记录。不增加后台 TTL 定时器，不保存无限消息。
- stream_id、最新 seq、记录以及保留范围必须在一个一致的提交中持久化。正常 daemon 重启保留全部身份和游标；不得 seq 归零而 stream_id 不变。更换stream必须重建认证连接/关闭原协商连接，使新auth_ack声明新世代；禁止同一认证source下悄悄改stream。
- 单次记录需先验证并序列化成功，再提交。持久化提交成功才可返回 CLI 2xx、才可进入实时广播。磁盘满/提交失败禁止虚报 accepted。
- 推荐用现有 Go 标准库实现小型原子 JSON snapshot（私有 stateDir 内，临时文件→sync→rename→父目录sync）；实现可替换，但耐久/恢复/上限契约不变。无需为低频主动任务消息添加数据库依赖。
- 初始无文件是新 stream；损坏文件不是正常空历史。禁止悄悄删除损坏存储并重新宣布成功，应停用通知能力并给出可判错误，终端功能仍可继续。
- IPC提交须串行分配 seq；广播按提交顺序进行。通知 seq 必须独立于 listing/L2/terminal counter。
- 广播不得让一条慢 WS 阻塞 daemon 发布锁、CLI 或其他客户端。对单连接有界排队；实时通知无法排队时终止该慢连接，由其重连补拉。禁止静默丢弃后仍假装保持完整实时流。
- 历史分页使用既有一写者机制/有界队列；不能在全局锁中调用当前阻塞式 sendMsg，也不能直接绕过 WS writer 并发 Write。
- 无在线客户端时仍保存通知；没有轮询通知文件、自动进程 Hook或维持额外通知专用 WS 的要求。
- 主机身份复用既有持久 host_id；若实现基线没有，应在相同私有 stateDir 新增独立持久 host-id。不能把 stream_id 兼任 host_id，删除历史不等于换一台主机。

## 6. 本机 CLI ↔ daemon IPC

### 6.1 选择与安全边界

**选择：HTTP/JSON over Unix Domain Socket。** 一套标准 HTTP 编解码，但不是新公开 TCP API。

- daemon 私有 listener：`<resolved stateDir>/notify.sock`；HTTP route：`POST /v1/notifications`。
- 禁止挂到 LAN/tailnet 的公开 `api.Server.Handler()`；禁止从 WS 发 notification 当作发布请求；客户端没有远程发布权限。
- 当前 host 为 Unix/tmux（macOS/Linux）。Desktop作为客户端不受此限制；Windows 主机 daemon 的 IPC 另立契约，不偷偷回退到 0.0.0.0 HTTP。
- socket 所在目录必须由 daemon UID 拥有并为 0700；socket 0600，存储/临时文件0600。仅同一 OS 用户的 Agent 能发布，不携带/暴露配对 token。
- 必须先获得现有 daemon 单实例锁再建 socket；不得无条件 unlink 可用的另一 daemon socket或非 socket文件。自定义路径仍须满足私有父目录约束，路径超过平台 AF_UNIX 上限明确失败。
- daemon 可提供 `--notify-socket`；CLI `--socket` > `CORRAL_NOTIFY_SOCKET` > 同一 stateDir 的 notify.sock。stateDir沿用现有解析（含 `AGENTMIRROR_STATE_DIR`），不引入另一套默认配置根。

### 6.2 请求

Content-Type=`application/json`，完整 HTTP body≤128 KiB：

```json
{
  "request_id": "4f14e231-8132-4672-86a6-e0ae7a22d05a",
  "title": "验收完成",
  "body": "检查全部通过。\n报告已落盘。",
  "level": "success",
  "session_ref": null,
  "tmux_context": {"socket":"/tmp/tmux-501/team","pane":"%3"}
}
```

- request_id 必须为 UUID v4，由 CLI 在本次调用开始时生成；它是提交幂等键，**不是**通知 id。
- title/level/session_ref/tmux_context 可省略/null，视为未提供；title与level取已声明缺省值，session_ref为空时才允许自动上下文推断。显式空串不等同未提供，必须拒绝。body与request_id必须提供非空合法值。server-owned id/host_id/stream_id/seq/timestamp/workspace/agent_name 禁止由请求覆盖。
- 显式 session_ref 优先，若同时有 tmux_context不得改绑其他会话。
- 自动上下文仅用于确定发信 pane：由 TMUX/TMUX_PANE 得到 socket+pane，再由 server既有 catalog/discovery映射为 canonical ref；它不决定任务何时完成。
- lookup受现有 discovery/白名单/fixture范围约束；无缓存时可一次有界按需查找，不新增周期扫描。
- 显式 `--session` / `CORRAL_SESSION_REF` 无法解析到可证明的当前会话，返回422 `session_not_found`，不得猜第一个同名Agent/同编号pane。
- 仅自动tmux推断失败：允许接受**无链接**消息，响应带 `warnings:["session_unresolved"]`，CLI必须显示警告。不让路由推断失败吞掉完成文本。
- workspace/agent_name取发布时catalog；不是实时数据。会话名复用已有 window/title 展示名投影，无法得到有效名称时回退 tmux Session；Leader 角色统一显示 `Leader`。无会话时workspace和agent_name均为null，不接受调用者自报身份。

### 6.3 幂等、成功与失败

首接受返回 HTTP201；命中同请求重试返回HTTP200：

```json
{
  "accepted": true,
  "request_id": "4f14e231-8132-4672-86a6-e0ae7a22d05a",
  "deduplicated": false,
  "notification": {"id":"54b36a82-4c5e-4331-8fc6-52e130036f56","host_id":"host_0123456789abcdef","stream_id":"4e4ca540-df59-4b79-a285-7d1f7523c7ab","seq":"44","timestamp":"2026-10-01T01:00:02.123Z","title":"验收完成","body":"检查全部通过。\n报告已落盘。","session_ref":null,"session_instance":null,"workspace":null,"agent_name":"Sol","level":"success"},
  "warnings": ["session_unresolved"]
}
```

- 幂等范围：当前 stream 的保留记录窗口。服务端须同时保留request_id与规范化发布请求的指纹；重试同请求返回原记录，不分配新seq、不再广播。相同request_id但不同发布请求返回409 `idempotency_conflict`。
- 指纹依据发布意图字段（含显式绑定/自动上下文及解析后的缺省值），不是会变化的server catalog展示字段。重复请求返回原metadata，不重新改写正文或workspace。
- 不承诺在记录已裁剪/历史已重建后永久识别旧幂等键；过期键不能作为无限期 exactly-once保证。
- 错误格式：`{"accepted":false,"outcome":"not_accepted","request_id":"…","error":{"code":"invalid_request","reason":"body must not be empty"}}`；body尚不可解析时request_id可省略。
- `accepted:false` 是未获得接受确认，不独自证明无提交。错误必须带 `outcome:not_accepted|unknown`；例如rename后耐久确认失败/内部处理无法排除提交时用unknown，CLI exit=5并要求同key核对，不能断言未发送。
- HTTP400 `invalid_request` /415 `unsupported_media_type` /413 `payload_too_large` /422 `session_not_found` /409 `idempotency_conflict` /503 `store_unavailable` /500 `internal`；明确拒绝用not_accepted，不确定提交的500/503用unknown。
- server/CLI日志不得记录title/body/token；诊断仅记录请求/通知id、seq、结果code、长度和耗时。

## 7. CLI 外部命令契约

```text
corral-notify "消息正文" [--session <opaque-ref>] [--title <标题>]
              [--level info|success|warning|error]
              [--socket <path>] [--request-id <uuid>]
corral-notify --stdin [上述可选参数]
```

- 正文恰好一个位置参数，或 `--stdin` 读取UTF-8正文；两者不可同时使用。消息空/全空白/超限必须非零退出，不拆成多条、不默默截断。可选参数支持在正文前或正文后；`--`可保护以`-`开头的正文。不得因Go标准flag遇到首个位置参数停止解析而忽略示例中的后置参数。
- `--help`是独立用法请求：打印帮助并退出0，不产生通知；此时不是发布成功回执。敏感正文优先用stdin，避免argv/进程列表和shell history泄漏。
- session来源：`--session` > `CORRAL_SESSION_REF` > 可用的TMUX/TMUX_PANE自动上下文 > 无会话。
- TMUX上下文必须正确处理socket路径，不能按空格拆分；解析TMUX中尾部pid/session两个逗号字段，不能假设路径不含逗号。macOS 的 `/private/tmp` 与 `/tmp` socket 别名须解析到同一个 catalog ref；持久化和下发使用 catalog 中的权威 ref。
- 缺省level=success，title=`Agent 任务通知`。不读取剪贴板、Agent日志或“最后一轮回复”。
- `--request-id` 用于**同一发布意图**的有意识重试；缺省每次主动调用生成新key。不得为所有Agent/所有任务复用一个常量key。
- 总IPC deadline **3s**；不得自动重试为新key、静默启动daemon、无限等待。超时/响应截断若请求可能已经提交，必须判 `outcome_unknown`。
- 成功stdout为一个JSON对象，至少含accepted/id/host_id/stream_id/seq/request_id/deduplicated；不回显完整正文。warnings明确写stderr。错误stderr含稳定code、reason、request_id及是否结果未知，stdout为空。

| exit | 含义 |
|---:|---|
| 0 | daemon确认已耐久接受（或同key已接受），不表示手机已提醒 |
| 2 | 用法/本地正文校验错误 |
| 3 | 无法连接daemon，确认未发出请求 |
| 4 | daemon明确拒绝/提交前存储失败，outcome=not_accepted |
| 5 | 可能已提交但未取得完整确认；outcome_unknown，用同request_id重试核对 |

Agent 示例（主动调用，不做Hook）：

```bash
corral-notify 'Issue #42 验收完成，报告已落盘。' --title '验收完成'
printf '第一项已完成\n第二项仍需用户确认\n' | corral-notify --stdin --level warning
```

## 8. 会话链接：必须防跨主机与 ref 复用

- 点击链接只能作用于已经配对、身份匹配的host_id；不得凭URI里的IP/URL自动替换配对配置。若当前服务器未协商notifications_v1，缓存消息仍可读，但不得把历史guard链接降级成普通subscribe。
- 当前基础服务端ref由tmux socket+pane id构造；pane id在另一个socket、tmux重启后均可能复用。**历史ref非空不等于历史那个Agent仍活着。**
- 链接记录必须带服务端opaque `session_instance`：同一tmux pane incarnation跨daemon正常重启保持一致；tmux重启/换pane incarnation即改变。不得仅用展示名、裸pane id或不持久的随机catalog编号。
- 建议复用现有socket/pane解析，结合可验证的tmux server/socket incarnation与pane进程生命周期证据生成稳定marker；客户端不解析marker。不能证明绑定时按§6处理显式失败/自动无链接。
- 通知跳转后的Subscribe增加可选 `expected_session_instance`。带该字段时，server必须在resize/attach/snapshot之前校验当前ref的instance；不匹配按已有 `error.code=session_not_found` 拒绝，无终端/尺寸副作用。
- 此种错误增加可选 `ref` 字段供新版客户端匹配当前通知跳转；旧解码器忽略额外字段。reason仅人读，不当路由判据。
- 新客户端须把expected_instance随订阅意图一起保存，并在断线重放时继续发送；不能重连后悄悄去掉保护转连同ref的新pane。

```json
{"v":1,"type":"subscribe","payload":{"ref":"/tmp/tmux-501/team\u001f%3","rows":40,"cols":120,"expected_session_instance":"pane-instance-example-01"}}
```

拒绝该链接的既有error扩展示例：

```json
{"v":1,"type":"error","payload":{"code":"session_not_found","reason":"notification session unavailable","ref":"/tmp/tmux-501/team\u001f%3"}}
```
- 关闭/失效会话：消息仍可完整阅读，显示“会话已结束或已变化”，返回该通知详情；禁止自动新建Agent、猜另一个会话或永久停留在空终端加载页。
- 无链接的通知点击打开详情，不尝试虚构session。

## 9. 跨平台消费、已读与系统提醒

- 每个平台中央通知存储订阅连接事件，不依赖某个消息中心页面/Session VM存活。
- 先落库去重，再发UI变化/系统提醒；保存正文、source身份、sync游标及本设备read状态。
- `notification` 中该连接首次有效live可触发一次系统提醒；`notifications_page` **只补历史，不逐条声音/震动/Heads-up**。提醒资格要求当前已认证source/stream匹配，seq大于本连接auth_ack的head_seq，且该id没有提醒claim；重复live不重复提醒。
- 数据去重不等于提醒claim：新live若与history竞态、记录先被history入库，仍可按上述规则尝试一次live提醒；历史入库本身绝不触发。不能简单用“本次INSERT是否新增”作为唯一OS提醒条件。
- OS提醒是平台副作用，数据库与OS不能原子提交。推荐耐久记录一次提醒claim后执行，保证最多一次尝试；崩溃/权限/DND/系统策略可能使提醒未显示，历史仍可读。不得宣称OS exactly-once或显示回执。
- 已读仅本设备本地状态；V1没有跨设备mark-read广播、服务器已读账本、删除/编辑通知或推送receipt。
- 收到消息本身不得强制跳转或打断当前终端；只有用户点击才导航。
- 客户端正文缓存总量也应限制为最多1000条/16MiB，按source保留当前窗口和有限旧来源条目；当前单主机产品不要求永久保存多个主机的无限归档。游标/read元数据按source隔离。本地缓存/正文应在系统私有、不备份区域。offline仍能读已缓存正文；源身份不匹配时不得跨主机打开相同ref。
- Android实现需求：独立HIGH channel，POST_NOTIFICATIONS运行时授权，普通VIBRATE权限，BigTextStyle，唯一PendingIntent身份，冷/热启动都能消费；细则见架构文档。
- iOS实现需求：相同记录/游标/去重、本地UNNotification和授权、userInfo只含稳定source/id。无APNs不承诺后台/杀进程送达。Desktop同样只有在线进程可实时接收。
- 平台声音、振动、Heads-up受用户权限、channel设置、DND、锁屏、通知冷却策略制约，不能用最高优先级或full-screen intent绕过。

## 10. 协议夹具与共同验收

实现时新增独立扩展夹具到 `docs/contracts/fixtures/notifications_v1/`，Go/Core/Swift/Desktop消费同一份：auth带能力、ack能力交集、linked/unlinked event、bootstrap、增量分页、continuation、空历史、retention_gap、stream_reset、失败页、guarded subscribe。不得改写旧golden。

JSON对象key顺序不影响wire语义；fixture中的UTF-8正文、换行、opaque ref、seq字符串必须精确。现有Go canonical golden可继续字节往返，各平台必须语义一致，不要求Swift/Javascript用同一key序列化顺序。

最低共同验收：
1. Agent未主动调用时零通知；两个并行Agent仅按各自调用发布，不能靠最终输出Hook重复生成。
2. 新客户端收到同一id的在线事件；未协商/未认证客户端收不到新通知/历史正文；新旧服务端/客户端兼容不破坏终端。
3. 无客户端仍接受并耐久保存；daemon重启不丢stream/seq；磁盘失败非零/无accepted；损坏存储不伪空。
4. CLI超时后的同key重试只生成一条；不同内容同key冲突；窗口外幂等限制有说明。
5. live先到、历史分页后到、断线重来、retention gap、stream重置均无漏游标/重复声音；满窗口之外明确不可恢复。
6. 一慢客户端不能拖住其他客户端/CLI；历史拉取不阻塞终端首帧/正文接收，不引入周期子进程扫描。
7. UTF-8多行正文在消息中心完整显示；超限明确拒绝；拒绝权限仍有历史；冷/热点击跳转正确host和pane。
8. 两个socket各有%0、关闭pane、同路径tmux重启复用%0、daemon正常重启四类身份场景分别验证；历史通知不得打开错误Agent。

> 本契约不得以“编译通过”“CLI exit=0”替代真正的在线广播、系统通知和点击路由验收。
