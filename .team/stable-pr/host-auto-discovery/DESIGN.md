# 主机自动发现：选定方案

日期：2026-09-05；本版为第一轮 FAIL 审查后的统一返修（S1–S6）。基线：`corral-core@eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`（PR #74）+ `corral-serve@911dd94176a34e11f66b61b15b191216463c48cf`（PR #7）。需求正本 `requirement-base/entries/101-主机自动发现与TS优先路由.md`；行为裁定只读 `DECISIONS.md`。摸底复用 `.team/nodes/host-discovery-explore-luna/{EXPLORATION,CAPABILITY-EVIDENCE}.md`。失败审查归档于提交 `6528ecaf9a27867f44971404e5b42100a3a5bfc2`（失败证据，不是通过）。

本文冻结可实施选择。事实、设计决定、未跑实验分开写。当前方案阶段不改产品。leader 冻结后 owning 分支允许提交、push、开 PR；merge 与生产仍未授权。本稿不自报复审 PASS。

## 0. 一句话结论

绑定的是**主机**及其**主机 token**，不是 URL。未绑定：先入 TS（失败静默）并在 LAN/TS 上发现**候选主机**→用户选择主机（不是路径）→输入主机 token→HMAC 证明后再 WS `auth`。已绑定：内部发现该主机的字面 IPv4 候选，同样先证明再拨号。发现与 `whoami` 不含 token，不能当 SSO。HMAC 只证明**这一次 identify HTTP** 的对端当时知道 token 且承认客户端声称的目的地址；它不是后续 WS 的同所有者密码学证明。客户端纪律：无重定向、无 DNS、只对原始字面 IPv4:port 建恰好一条 WS。择路是异步状态机：一轮一代次、硬预算、LAN 槽、单 `create`、失败推进、READY 原子发布。资源限制约束并发/窗口/缓存并**继续扫**，已知 TS ID 先全表匹配。PeerLines/NSD 失败时静态提示仍可连，但自主发现验收不得标通过。

## 1. 已冻结的用户行为（不再提问）

- 界面没有 URL/IP/端口/路径选择。未绑定可展示**主机列表**供选择；已绑定不再选路。TS auth key 只入网，不能替代主机 token，也不能免鉴权绑定。
- 多途径自主发现：至少 TS 与局域网。先入 TS 后应能发现 TS 上的候选主机（公开 `host_id`/`name`），再收主机 token。
- 首次连接和每次重连优先 TS；当前连接能用就保持；TS 恢复不抢占仍可用的 LAN。
- TS 入网失败不提示，不阻止 LAN。两路都不可用时工作区为空，无弹窗、错误条、提示文案。
- 右上角只显示实际使用的 `TS` 或 `局域网`。
- 输入校验和鉴权不取消。TS Up ≠ 目标服务可达。mDNS 不跨 TS。

“可用”= 该路径已通过 identify 并且 **这一次** WS `auth` 成功。节点 Up、端口开、自报 `host_id`、`whoami` 都不是可用。

## 2. 绑定与发现入口（S1）

两条入口，都要主机 token 才能绑定。不是免鉴权 SSO。

### 2.1 扫码（已有 token）

QR 给出 `host_id` + 主机 token + 可选 TS key + `port` + 可选 `ts_node_id`。落盘主机记录后进工作区，由 §4 状态机连接。UI 不展示 url/candidates。

### 2.2 无扫码：发现主机 → 选择主机 → 输入 token → 验证绑定

前置：用户可先填 TS auth key 入网（失败静默，不挡 LAN 发现）。无 TS key 时只做 LAN 发现。

1. **发现候选主机**（公开、可伪造、不含 token）  
   - LAN：DNS-SD `_agentmirror._tcp`，TXT `id=<host_id>`，实例名=`host_id`，端口=监听端口，可选展示名。  
   - TS：本机 `TsnetState.Up` 后，对 peer 的字面 IPv4 做 `GET /pair/whoami`（无 token）。**引导端口**见下段，不是实现者现场猜值。200 且字段合法则视为一台 AgentMirror 主机；非本服务/超时/RST/3xx/4xx/5xx 不是主机。  
   - 同一 `host_id` 的多地址合并成**一行主机**，不展示 IP，不让用户选 TS/LAN。

   **TS 首次发现的端口（R1 / B11）**：无主机记录、无 QR/`port` 字段、无 NSD 解析端口、无 last-good 时，whoami 只打 **产品协议默认端口 9900**（与基线 daemon `ListenAddr` 默认 `0.0.0.0:9900` 一致，`internal/config/config.go`）。已有可信来源给出的端口继续按既定候选规则使用，优先级：当前主机记录 `port` → QR `port`/主 url 的 port → NSD `resolveService` 端口 → 否则 9900。`whoami` 响应里的 `port` 只允许附着**本次请求的原始字面 IPv4**，不得改 IP、不得当成新主机地址；该字段仍是不可信元数据，后续 identify 仍走 §3。非默认监听端口只能经 NSD、QR 或已有主机记录获得。不恢复用户手填端口/链接，不扫全端口，不扫 `100.64/10`。默认 9900 引导**只覆盖**「peer 上 AgentMirror 正在默认端口监听」；改了 `-listen`/`AGENTMIRROR_LISTEN` 且无 QR/NSD/记录时，TS-only 列表可以找不到该主机——如实范围，不宣称能推导任意服务端口。
2. **展示/选择主机**：列表项 = 展示名（仅展示，不匹配）+ `host_id`。多台则必选其一；一台也可确认。可手填 `host_id`（daemon 指引印刷），输入校验：charset/长度。
3. **输入主机 token**：必填，空 token 不得绑定、不得 identify、不得 WS `auth`。TS key 不得填进该框当主机 token。
4. **验证绑定**：对所选 `host_id` 的候选字面地址走 §3 identify；通过后才允许对该**同一字面地址** `create` WS 并 `auth`。identify 失败不发 token，换该主机的下一地址；地址用尽则仍停在绑定页（校验/鉴权失败可见），不把“发现失败”写成已绑定。

未选主机或未通过证明前：不写已绑定记录、不进工作区当已配对。已绑定之后的重连静默规则见 §4.7。

`whoami` 可被伪广告。选错主机并输入**该伪主机自己的** token，等于扫了攻击者的码，与今日扫码威胁同阶。选真 `host_id` 却把 token 打到伪地址：HMAC 失败，不发 token。

## 3. 证明与兼容（S4）

### 3.1 证明了什么、没证明什么

选定仍用同端口 HTTP，因为：旧 Handler 只有 `/ws`、`/upload`，对未知路径 POST 为 **404**（兼容谓词的真实依据）；无 token 的主机列举需要 `whoami`；现 `Connection.onOpen` 会立刻 `AuthFrame(token)`，证明必须发生在 `transport.create` 之前。

不上 PKI。不把“禁止改业务帧 / 必须独立 HTTP”写成用户硬要求；本版仍选 HTTP 是因为它同时承担 404 探测与无 token 列举。若未来用同一条 WS 先 identify 再 auth，必须另证旧 daemon 兼容，且不得弱化地址绑定。

一次成功 identify **只**证明：响应方能用同一主机 token 算出覆盖本次 `nonce` 与客户端 `dest_ip` 的 MAC。它**不**证明随后 WS 与这次 HTTP 是同一 TCP、同一进程、同一所有者。后续 WS 的安全靠客户端纪律（§3.3）和 WS `auth`，不靠把 HMAC 夸成连接所有权。

残余：identify 成功后、WS 前的 DHCP/ARP 换主，与今日“扫码后过一会儿连明文 ws://该 IP”同阶，不在本需求用 PKI 收口。HTTP **重定向**和 **DNS 二次解析**是本方案相对旧喷 token **新增**的分裂面，必须关掉（§3.3），不是 L2 固有面。

### 3.2 `GET /pair/whoami`（无 token，可伪造）

200：

```json
{"v":1,"host_id":"<self>","name":"<hostname>","port":9900}
```

无 MAC、无 token。客户端与 identify 同一纪律：只字面 IPv4、`followRedirects=false`、3xx = 不是主机，禁止把 `Location` 写成候选 IP。响应 `port` 若采用，只附着本次 GET 的原始字面 IPv4，不得改 IP。4xx/5xx/非 JSON = 该地址不是可展示主机。body ≤ 1KiB；每 IP ≤ 5 次/秒（与 identify 共用计数）。不加 `authed`、不唤醒 listing。whoami 不得写 prefs 的 `token`/`host_id`。

### 3.3 `POST /pair/identify`（无 token）

客户端对 `http://<dest_ip>:<port>/pair/identify`：

- 只嵌入**原始候选字面 IPv4**。hostname、IPv6、需 DNS 的名字在发现层丢弃（NSD 只用 `InetAddress.hostAddress` 且通过与 `TsnetDial.isTailnetHost` 同类的字面 IPv4 解析）。
- OkHttp `followRedirects=false`；3xx = 该候选失败。禁止把 `Location` 当已证明地址。WS 客户端同样禁止跟随重定向、禁止对非字面 host 建连。
- 请求：

```json
{"v":1,"host_id":"<optional>","nonce":"<32 hex>","dest_ip":"<本次拨号字面 IPv4>"}
```

`nonce`：16 字节 CSPRNG。`dest_ip` 必填。本地已有 `host_id` 则带上。

服务端：

1. 校验 `nonce`、`dest_ip`（必须是指定 IPv4，非 unspecified/loopback）。
2. 若请求带 `host_id` 且与本机不等：与未知主机相同失败（不泄漏 token 是否本会成功）。
3. **`bound` 来源（唯一）**：已接受连接 `net.Conn.LocalAddr()`。Go：`http.Request.Context()` 的 `http.LocalAddrContextKey` 断言为 `net.Addr`。**禁止**用 `listener.Addr()` / `ln.Addr()` 回退。
4. 令 `local` = 该 LocalAddr 的 IPv4（无法取得则为无效）。
5. **MAC 的 `bound_ip` 字节**（两种结果都必须实现，夹具覆盖，不得现场改弱）：
   - **主路径**：`local` 是指定、非 loopback 的 IPv4。若 `local != dest_ip` → 失败。`bound_ip = dest_ip`（与 local 相同）。
   - **TS 替代路径**：`local` 为 nil / unspecified / loopback / 非 IPv4。若 `dest_ip` 不属于 `DetectAddresses()` 的可用 IPv4 ∪ 本进程 `group.Up`/`WithTailnet` 注入的 Tailscale IPv4 → 失败。`bound_ip = dest_ip`（**不是**错误的 LocalAddr）。`DetectAddresses` 看不见 userspace 100.x，100.x 必须来自 tsnet 状态，不得只用 NIC 表。
6. 无效 `local` 不得改成“只比端口”。

MAC：

```
key  = UTF-8(pairing token)
msg  = "agentmirror-identify-v1" || 0x1f || mac_host_id || 0x1f || nonce_hex || 0x1f || bound_ip || 0x1f || bound_port
mac  = HMAC-SHA256(key, msg)
wire = lowercase hex(mac)
```

`mac_host_id` = 本机 `host_id`（始终用服务端自己的值入 MAC）。`bound_port` = 十进制监听端口。比较常数时间。响应无 token。

200：

```json
{"v":1,"host_id":"<self>","name":"<hostname>","bound":"<bound_ip>:<bound_port>","mac":"<64 hex>"}
```

失败：4xx + `code`（`unknown_host` / `bad_nonce` / `bad_dest` / `bad_request`），无 MAC。body ≤ 1KiB；每 IP ≤ 5 次/秒。

**客户端校验顺序（无本地 `host_id`）**：HTTP 200 → `host_id` charset/长度合法 → `bound` 的 IP:port 等于原始拨号字面 → 用**响应** `host_id` 作 `mac_host_id` 验 MAC → **通过后**才写入 prefs。坏 MAC / bound 不符：**不得**写入 `host_id`。

**有本地 `host_id`**：响应 `host_id` 必须逐字节相同，否则与 `unknown_host` 同失败；MAC 用本地值。

identify 成功 ≠ 登录：不加 `authed`，`/upload` 仍要 Bearer，WS 仍要 `auth`。

### 3.4 反例

| 反例 | 期望 | 机制 |
|---|---|---|
| 伪 DNS-SD/伪 peer，无 token | 零 `create`、零 `AuthFrame` | 无 MAC |
| 307/308/301 到真机再 WS 打原始广告者 | 3xx 即失败；无 token | `followRedirects=false`；WS 只打原始字面 |
| DNS 分裂：identify 解析到真机、WS 再解析到攻击者 | 发现层无 hostname | 只字面 IPv4 |
| 重放 MAC | 失败 | 新 nonce |
| 继电器：转发 identify，WS 打继电器 | 失败 | `dest_ip`∈真机集合且 `bound==拨号字面`；两种改写都失败 |
| `bound=0.0.0.0` / 空 / 只比端口 | 失败 | 禁止 listener 地址与端口放宽 |
| 自报 `host_id` / TS Up / 端口开 / `whoami` | 不发 token | 无 MAC 或未 auth |
| 伪造候选 404、last-good 404、candidates 404 | 不发 token | 遗留谓词不含这些来源 |
| 主 url 超时/RST/3xx/5xx 当“无端点” | 不发 token | 仅确切 404 |
| 已有 `host_id` 后再 404 | 不发 token | 白名单永久关 |
| 无本地 `host_id` 时先写响应再验 MAC | 禁止 | 先验后写 |
| TS key ↔ 主机 token | 失败 | 用途隔离 |
| identify 当登录 | `/upload` 401；WS 未 auth 不 `authed` | |

### 3.5 遗留直连 WS（从窄，仅 404）

基线 `Handler()` 仅注册 `/ws`、`/upload`，旧二进制对 `POST /pair/identify` 为 **404**。不把 405 或其它状态列入，除非以后另有真实兼容事实。

**充要条件（全真才允许对该字面地址发恰好一帧 `AuthFrame`，且不再 identify 成功）：**

1. 本地 `host_id == null`（身份未升级）；
2. 来源是 `scanned_primary`（本轮扫码主 `url`）或 `persisted_legacy`（冷启已存主 `url`/`legacyBootstrapUrl`，即旧客户端当初持久化的那一个）；
3. 字面 IPv4:port **精确等于**该主 URL 的 host:port（规范化后）；
4. 对该 URL 的 **原始** identify POST（无重定向）HTTP 状态码 **恰好 404**。

超时、RST、DNS 失败、3xx、2xx 坏 MAC、5xx、空响应、HTML、连接被重置 = 失败，不降级。  
`discovered` / `last-good` / QR `candidates` / NSD / PeerLines / `whoami` 命中地址：**永不**走遗留直 auth。  
一旦写入非空 `host_id`，白名单永久关闭。  
旧 App + 新 QR 仍会按 PR74 对 `url`+`candidates` 喷 token：这是**旧客户端固有面**，本功能不改 PR74，不得写成全客户端安全不变量。

## 4. 异步候选 → 证明 → WS/auth → READY（S3）

### 4.1 为什么废弃 `resolve(): String?`

基线 `attemptConnect` 对**一个** `config.url` `create` 后 auth；失败则对同一 URL 退避。同步 `resolve()` 若在 identify 后返回字符串，WS 失败会饿死其余候选；若在内部已经 `create`，会双连。pump 线程不可跑满发现预算（基线输入超时与 `pump` 同节奏）。

### 4.2 单一建连责任方

`ConnectionManager` 是唯一调用 `transportFactory.create` 的地方。`HostDialCoordinator`（app 模块）只产出「请对此外面字面 URL create」的请求，自己不建 WS、不发 token。

### 4.3 代次

`generation` 单调递增，在 `start`、掉线后的新一轮、`onNetworkAvailable` 于非 READY、`onForegroundResume` 于 RECONNECTING 时 +1。过期回调、identify 完成、WS `onClosed` 若 `generation` 不匹配则丢弃。`cancel(generation)`：停 NSD、取消 in-flight HTTP、不 `create`、不把结果发布为 READY。READY 期间不因 TS Up 开新代次。

新代次 `begin` 才读取磁盘上的**最新待用 TS key** 并（若非空）`ensureStarted`；READY 中途改 key 不在本代次重启 TS（§6.3）。删除 `PersistentConnection` 的 `whenSettled` 门闩：`TsnetState` 非 Up 时本代次跳过 TS 类候选，**同时**跑 LAN；last-good/QR 即使是 100.x 也不等待 TS。过期代次不得把旧 key 写回磁盘或抢占新 READY。

### 4.4 状态机

```
begin(generation)
  → Discovering     启动 NSD 窗口；若 TS Up 取 Peer 快照（已知 ID 全表命中）
  → Proving         identify 并发 ≤4；TS/LAN 都可证明，不串行耗尽 TS 才开始 LAN
  → DialingWS       至多一个 create；优先已证明的 TS，否则已证明的 LAN（LAN 槽已到期或 TS 证明已空）
  → Authenticating  现 Connection onOpen → AuthFrame
  → Ready           原子发布后 cancel 同代次其余工作
  失败推进          identify 失败 / 3xx / WS 非 auth 拒绝 → 本代次黑名单该字面地址 → 下一已证明或继续发现
  AuthRejected      auth_ack.ok=false → STOPPED（凭据；不换候选喷 token）
  Exhausted         代次硬预算到且无 READY → RECONNECTING 退避；cursor 留下一轮
```

`attemptConnect` 只 `setState(CONNECTING)` 并 `coordinator.begin`，**立即返回**。`pump(nowMs)` 只做：输入超时、代次 deadline、到期退避再 `begin`。禁止在 pump 里同步 identify/NSD/`Status()`。

### 4.5 硬预算与 LAN 槽

单代次墙钟 **8s**（硬上限，到点 cancel 未完成的 TS 证明并若尚未试 LAN 则只留 LAN 槽）。  
TS 证明预算 **≤4s**：4s 时若还没有 TS 已证明在等拨号，必须开始（或已经在跑）LAN identify。  
每 identify **2s**；每 WS 连接超时 **3s**（本路径不得用默认 ~10s）。  
并发 identify **≤4**；in-flight WS **=1**。  
黑名单仅本 `generation`。  
下一退避代次**继续**未完成的公平扫描，不从固定“前 8 个”重来以至于永远漏第 9 个。

优先序（证明可以并行，**拨号**串行）：已证明 TS 队列非空则拨 TS；否则在（LAN 已证明 且（TS 证明空闲或 TS 预算到或墙钟逼近））时拨 LAN。禁止“先串行 8×2s TS 再 LAN”。

### 4.6 READY 原子发布（S5）

第一路 `auth_ack.ok=true` 时，在 UI/上传观察到 READY **之前**同一回调内写入：

- 当前字面 ws endpoint
- `ConnectionPath`（`TS` / `局域网`，由这次 `create` 的选路记录）
- `ServiceWire.uploadBaseUrl = deriveUploadBase(该 endpoint)`
- `last_ts_url` 或 `last_lan_url`
- 回写 prefs `url`（供降级 PR74 App）

之后才 `setState(READY)`。现存 `SessionViewModel` 上传必须经 **live provider** 读 `ServiceWire.uploadBaseUrl`（禁止构造期 `val baseUrl` 快照）。非 READY 上传失败（会话内失败，不是工作区提示）。`recordConnectionPath` 发生在 `create` 时，徽标仍只在 READY 展示。

last-good 回写 **不得** `releaseManager`。

### 4.7 工作区静默

已绑定且非 `STOPPED`（auth 拒绝）时：CONNECTING/RECONNECTING 工作区空白，无「连接中/重连中/连接已关闭」横幅或空态说明。`STOPPED`（token 无效）允许原“需重新配对”语义。无 tmux 会话的 READY 空态保留。

## 5. 完整性：已知 ID、公平续扫、退化不是 PASS（S2）

### 5.1 Peer 快照

`PeerLines`/`PeerSnapshot` 在 Go 侧读**完整** `Status.Peer`：

- 若客户端传入已知 `ts_node_id`：先在全表按 StableID 取值，命中则只输出该节点 IPv4，**不受**窗口大小影响。
- 其余：按 StableID 字典序排序后做**旋转窗口**（单次快照最多 256 行给 Java，gomobile 字符串）。窗口是并发/内存界限，不是“第 257 个永远消失”。客户端持有 `cursor`（StableID），下一代次从下一 ID 续扫。
- 禁止先截 32 再查找目标。禁止 `map` 随机迭代当选择函数。
- `Online` 只影响 whoami/identify 顺序（在线优先），不是服务证明。

本轮拨号工作集：在过滤到**当前目标 `host_id`**（已绑定）或列举阶段之后，同时证明中的地址 ≤8。这是 in-flight 上限，不是全局盲截。已知目标的 `ts_node_id` IP、`lastTsUrl`、QR 100.x 提示**先进入**工作集，不得被其它 online peer 挤出。

### 5.2 NSD

发现窗口内持续 `discover`+`resolve`，并发 resolve ≤4。匹配当前 `host_id` 的实例不得因“已收 8 条”丢弃。窗口结束停止组播锁。跨代次用有界缓存（host_id → 字面地址，条数上限，例如 32 条主机记录）作提示，仍要 identify。

### 5.3 退化

PeerLines 或 NSD **未验证或失败**：

- 已绑定且有 QR/`last-*`：这些字面地址可作**未信任提示**并 identify，保留连接能力。
- 无扫码、无 last-good、只入了 TS：若 whoami 也失败，主机列表可空；用户仍可手填 `host_id`（若有）。不得扫 `100.64/10`。
- **不得**把“静态提示重证成功”写成 101「自主发现 TS/LAN」通过。GOAL/验收里对应项保持未通过，直到隔离实验证明 PeerLines/NSD 真的列出了码上没有的当前地址。

## 6. 数据字段与冷启（S5）

### 6.1 QR `v=1` 加可选字段

旧 App 忽略未知键，仍要合法主 `url`。新服务端必须继续写 `url`。

| 字段 | 约束 |
|---|---|
| `v` | 1 |
| `url` | 旧 App 必填；新 App 未信任提示，来源不是 `scanned_primary` 遗留以外的拨号许可 |
| `token` | 非空，主机 token |
| `ts_authkey` | 可空 |
| `candidates` | 可空；新 App 永不直 auth |
| `host_id` | 新服务端必填：16 字节 CSPRNG，base32 无填充 |
| `port` | 监听端口，十进制 |
| `ts_node_id` | 可选，`Status.Self.ID`；TS 未 Up 时省略，不重打 QR |
| `name` | 可选，`os.Hostname()`，**只展示** |

解析：`v==1` 且 token 非空；合法 `host_id` 则 `url` 可空；无 `host_id` 则必须合法 `url`。

服务端 `host_id` 文件：`pairing.TokenDir()/host_id`，与 token 分寿命，0600，改名落盘。

### 6.2 客户端 prefs

| 键 | 作用 |
|---|---|
| `token` | 主机 token |
| `host_id` | 公开定位符 |
| `port` | 构造 `ip:port` |
| `ts_node_id` | 全表匹配 |
| `name` | 展示，不匹配 |
| `ts_authkey_encrypted` | 现 Keystore AES-GCM；失败整份失效 |
| `legacy_bootstrap_url` | 仅旧主 URL，遗留谓词用 |
| `url` | 每次 READY 回写当前 ws，供降级 PR74 |
| `last_ts_url` / `last_lan_url` | 提示，须重证 |
| `scan_hints` | QR url/candidates 原文，未信任 |

`load()` 合法：`token` 非空 **且**（合法 `host_id` **或** 合法 `legacy_bootstrap_url`/`url`）。URL-less 新绑定在首次 READY 前杀进程：有 `host_id+port(+ts_node_id)` 仍能发现。`clear()` 删除上表全部新键与旧键。

QR 提示不得写成 `legacy_bootstrap_url`。

### 6.3 重建边界

| 变化 | `releaseManager` | 其它 |
|---|---|---|
| `host_id` 或主机 token 变 | 是（身份变，单档覆盖） | 显式更换身份：可 `releaseManager` 并停旧 TS；新代次 |
| 同主机重复扫码，token 同 | 否 | 更新 port / `ts_node_id` / hints / name |
| last-good / `url` 回写 | 否 | READY 不闪断 |
| 同主机、主机 token 未变，仅 TS key 变 | 否 | **READY 期间只持久化最新待用 key**（磁盘最新覆盖先前待用值）。不调用会 `stop` 旧 manager 的 `TsnetWire.ensureStarted`（基线 `TsnetWire.kt:84-115` 换 key 会停旧节点），不后台立即重启 TS 后端，不拆现有 TS 或 LAN socket。当前连接自然结束、进入**下一次连接代次**时才 `ensureStarted(最新待用 key)`。TS Starting 仍不得挡 LAN。过期代次回调不得复活旧 key、不得抢占新 READY。与上一行身份重建分开 |
| TS key 从有到无或反向，身份不变 | 否 | 同上：READY 只改待用值；下一代次才按有/无 key 择路 |

## 7. 发现传输细节

LAN 广告：`_agentmirror._tcp`，TXT 无 token。小型 DNS-SD（默认 `hashicorp/mdns` 或同等）。不用 `ListenService`。清单：`CHANGE_WIFI_MULTICAST_STATE`（及若需要的 `ACCESS_WIFI_STATE`），MulticastLock 仅窗口内。

TS：gomobile 扁平字符串。`PeerSnapshot(knownId, cursor) -> lines, nextCursor`。已知 ID 全表命中。客户端 whoami/identify 的端口按 §2.2：有记录/QR/NSD 则用该来源；无主机记录且无已知端口时引导 **9900**，不是缺 port 即丢弃、也不是现场猜值。

分类：字面 `100.64/10` = TS，其余可用 IPv4 = LAN。无 loopback/link-local/RFC2544/IPv6。

服务端启动：LAN 先 `Serve` 并印 QR；TS `Up` 失败只记内部日志（不含 key），进程不退出；成功后再 `ListenTailnet` 同 handler。DNS-SD 随 LAN，与 TS 无关。

## 8. 共享入口

| 入口 | 本方案 |
|---|---|
| `ConnectionManager.attemptConnect` | 开代次，异步 coordinator；唯一 `create` |
| `pump` / 网络 / 前台 RECONNECTING | 不阻塞 IO；READY 前台仍不重建 |
| `PersistentConnection` | 无 `whenSettled`。`ensureStarted` 只在**连接代次开始**且存在待用 TS key 时调用；READY 中途改 key 只写盘，不在此入口重启 TS |
| `ServiceWire.setConfig` | 仅身份变重建 |
| 配对 UI | 主机列表+选择+token+可选 TS key；无 URL/候选下拉/TS 失败文案 |
| `PairingViewModel` 喷 candidates | 删除 |
| `WorkspaceScreen` | 已绑定非 STOPPED 且非 READY：空白无提示 |
| 上传 | live `uploadBaseUrl` |
| `OkHttpWebSocketTransport` | 字面 URL；本路径 `followRedirects=false`；选路记录保持 |

## 9. 跨仓 PR 与交付边界（S6）

不改 PR #72/#73/#74 与 serve PR #7。从上述 SHA 开 owning 分支。

**阶段：**

- 现在：不改产品。
- leader 冻结本方案后：owning 分支允许提交、push、开远端 PR（流程已授权）。
- **仍无** merge、release、替换生产 binary、重启生产的授权。

**不要写「PR A 先合」。** 契约依赖：客户端 identify/`whoami`/QR 字段依赖服务端同一协议；成对验收（同一协议版本的两端 CI + 兼容矩阵）才能判功能完成。允许两仓各自开 PR；只合其一不足以宣称自动发现交付。只上服务端：旧 App 仍用 url。只上客户端：新 App 对旧 daemon 仅 §3.5 遗留主 url。`:core-protocol` 无新业务帧，不进回滚单元。Web 不做发现。

架构工具目录缺失保持 unknown，不扩治理，不报 PASS。

## 10. 验收矩阵（复用既有证据入口；未跑 = 未验证）

不抄长日志、不编造阈值、不用 `.team/perf/baseline-20260822.json` 的 null/`INCONCLUSIVE` 当门。

### 10.1 本功能行为（实现期装置，本稿未跑）

| 场景 | 断言 | 装置 |
|---|---|---|
| 无扫码：TS key 入网后列出候选主机，选择后才收 token | 证明前零 `AuthFrame`；多 peer 含非 AgentMirror 不得当目标 | 隔离测试节点 + 假 whoami；禁止扫用户 tailnet |
| **TS-only 新装（R1）**：无 QR、无 last-good、无主机记录、仅 TS key；peer 混合默认 9900 AgentMirror 与非本服务 | 必须实际发出 `GET http://<peerIPv4>:9900/pair/whoami`；列表出现该 `host_id`；选主机后才收主机 token；证明前零 `AuthFrame`。不扫其它端口、不扫 `100.64/10` | 隔离测试节点；目标 daemon 监听 9900；禁止用户 tailnet |
| **TS READY 改 TS key（R2）** | 改 key 期间 `TsnetWire.stop`/`close`/新 `AuthFrame` 增量 = 0；现 socket 与徽标保持；下一次自然断线进入新代次后才 `ensureStarted(最后一把 key)`，仍 TS 优先择路 | 可控夹具；记 stop/close/AuthFrame 计数 |
| **LAN READY 改 TS key（R2）** | 同上增量 = 0，不抢占 LAN socket；下一次断线后才应用最后一把 key，按 TS 优先（TS 可用则下一 READY 为 TS） | 同上 |
| 仅一台/多台主机 | 多台必须选择；主机选择≠路径选择 | 同上 |
| 四格路由 + 不抢占 | 与需求 101 表一致；徽标只 READY | 可控双监听夹具 |
| TS 黑洞×N + 可达 LAN | 8s 内 LAN READY，徽标局域网，无 TS 失败文案 | 黑洞 100.x + LAN |
| last-good=100.x 且 TS Starting | LAN READY，无 `whenSettled` 等待 | 状态注入 |
| identify 过、WS 失败 | 同代次试下一候选；每字面地址至多一次 `create` | 假 HTTP 200 + WS 拒绝升级 |
| >256 peer、目标在序末、有 `ts_node_id` | 必须命中该 IP | 夹具 Status |
| 未知目标公平续扫 | 第 9/第 33 在后续代次出现，不永久消失 | 夹具 + cursor |
| URL-less 冷启 | READY 前杀进程仍有 host_id+port+ts_node_id | prefs 单测 |
| 307 到真机 | 无 `create(原始广告者)`、无 token | 假 307 |
| 遗留 404 谓词 | 仅 scanned_primary/persisted_legacy 真 404；其它 404/超时/5xx 无 token；写入 host_id 后关闭 | 假 Handler |
| 无本地 host_id | 响应 id 验 MAC 后才写；坏 MAC 不写 | 向量 |
| 上传 | 同一 Session VM，LAN→TS 后 HTTP host=当前 WS host | live provider |
| PeerLines/NSD 关 | 静态提示可连则连；自主发现项不得 PASS | 特性开关 |

### 10.2 原 17 项 UI（完整名字）

证据正本：`.team/stable-pr/external-session-status-ui/FINAL-COMPOSE-VERDICT.md`（§5 与 instrumentation 17/17）；同名清单也在 `.team/stable-pr/foreground-reconnect/instrumentation-summary.txt` 与 `FINAL-VERDICT.md` Fresh device wave。

本任务回归：在新绑定/发现路径可达的前提下，对 **同一 17 个 androidTest 名字** 再跑，缺一不可；未跑保持未验证。

1. `dev.agentmirror.app.session.MobileSessionFixtureBackInstrumentedTest#fixtureKeycodeBackReturnsFromHotkeysWithoutFinishingActivity`
2. `dev.agentmirror.app.session.SessionDockInstrumentedTest#scaffoldHasNoIndependentTerminalDockDivider`
3. `dev.agentmirror.app.session.SessionDockInstrumentedTest#favoriteSwitchPreservesScrollModeAndExpandedInputThenSendsAndCollapses`
4. `dev.agentmirror.app.session.SessionDockInstrumentedTest#productionSessionRouteKeepsGlobalFavoriteViewportDockAndInputAcrossTwoSwitches`
5. `dev.agentmirror.app.session.SessionRouteImeBodyInstrumentedTest#productionSessionRouteKeepsSameTerminalBodyThroughImeFocusAndBack`
6. `dev.agentmirror.app.session.SessionRouteOverlayInstrumentedTest#productionSessionRouteRapidDispatcherBackPopsHostExactlyOnce`
7. `dev.agentmirror.app.session.SessionRouteOverlayInstrumentedTest#productionSessionRouteBackFromViewOverlayReturnsDefaultBeforeHost`
8. `dev.agentmirror.app.session.SessionRouteOverlayInstrumentedTest#productionSessionRouteFocusedBackCollapsesThenDefaultBackReachesHost`
9. `dev.agentmirror.app.session.SessionRouteOverlayInstrumentedTest#productionSessionRouteBackFromHotkeysReturnsDefaultBeforeHost`
10. `dev.agentmirror.app.session.SessionRouteOverlayInstrumentedTest#productionSessionRouteViewOverlayScrollsToLastRowAndSelects`
11. `dev.agentmirror.app.session.SessionRouteOverlayInstrumentedTest#productionSessionRouteRestoresFavoriteAnchorAfterViewOverlay`
12. `dev.agentmirror.app.session.SessionRouteOverlayInstrumentedTest#productionSessionRouteBackFromSessionsReturnsDefaultBeforeHost`
13. `dev.agentmirror.app.session.SessionRouteOverlayInstrumentedTest#productionSessionRouteViewMenuOpensSelectsAndDismissesProtocolList`
14. `dev.agentmirror.app.workspace.ExternalSessionListGestureTest#listShortClickAndLongPressFavoriteWithoutStarOrForbiddenActions`
15. `dev.agentmirror.app.workspace.ExternalSessionListGestureTest#favoriteOfflineDoesNotNavigateAndLongPressOnlyUnfavorites`
16. `dev.agentmirror.app.workspace.ExternalSessionListGestureTest#workingLampAnimatesAndListsShareTitlePathHeight`
17. `dev.agentmirror.app.workspace.PiGrokLampAndroidTest#finalLiveCodexRowUsesBrandAndNativeSpinnerCycleFailClosed`

### 10.3 前台恢复

正本：`.team/stable-pr/foreground-reconnect/FINAL-VERDICT.md` Controlled lifecycle。

| 项 | 必须保持 | 本功能故意不同 |
|---|---|---|
| READY 前台边 | 同一 socket，不新 `accepted/open`；listing/level2 刷新 | 无 |
| 断连/退避回前台 | 恰好一次新 open/auth，不等 `onAvailable` | 新代次走 coordinator，仍禁止重连风暴 |
| 快速重复 start | 新 open delta=1 | 无 |
| 二级菜单保留 | `快捷键/查看/会话`、终端、输入 | 无 |
| 静默过期无内部红条 | 无 `二级状态已停更` 等诊断条 | 两路不可达时 **不再**展示 PR74 所见「重连中…/正在重连…」；那是 101 静默空工作区，不是前台契约回退 |

未按该夹具重跑前，前台项未验证。

### 10.4 性能

正本：`.team/nodes/input-full-auto/perf-design/CONTRACT.md`。前置：`sh tools/perfbase/envcheck.sh --gate`（不达标 = 不可判）。门：同批 A/B/A/B，三夹具四段，`B/A ≤ 1.10`；A 绑定 `baseline-20260822-release` 与参考 APK md5 `0907d6881bb1e034ef33a49f89afaa44`。缺事件/样本/身份 = 不可判。**禁止**用 null 旧 baseline JSON 当通过。发现不得在 READY 会话上抢占重拨；冷启已绑定路径含发现预算，须实测，未测不得 PASS。

### 10.5 签名与覆盖安装

- 签名：`.team/stable-pr/foreground-reconnect/SIGNING-RECEIPT.md`（证书 `ea427eb4e14f95654a66802b6558fbbf6f93f1ca69d8117795fb7cef376cb13b`，产品 ZIP 非签名项不变）。
- 覆盖安装：`.team/stable-pr/foreground-reconnect/UPDATE-INSTALL-RECEIPT.md`（`install -r`，同包名/uid/dataDir/signer，无 `-d`/卸载/`pm clear`）。
本功能 APK 须保持同一 signer 身份可覆盖安装；未跑覆盖则该项未验证。

## 11. 仍需有界验证（实现期；失败语义已定义）

禁止扫用户 tailnet、本机编译产品、动生产/凭据。

1. Android 内 `LocalClient.Status`/`PeerSnapshot` 是否返回 peer IPv4。失败 → 无 TS whoami/精确 ID 发现；静态提示可连；**自主发现 TS 不得 PASS**。
2. `NsdManager`+组播锁能否解析 `_agentmirror._tcp`。失败 → LAN 静态提示；**自主发现 LAN 不得 PASS**。
3. tsnet 已接受连接 LocalAddr 是否为客户端拨的 100.x。两种结果都走已冻结的 §3.3 MAC，不得改成只比端口。

架构工具路径仍缺：unknown。

## 12. 源码坐标（基线对象）

客户端 `eaaa7d47…`：`pairing/*`，`ConnectionManager.attemptConnect`，`Connection.onOpen`→`AuthFrame`，`PersistentConnection.whenSettled`，`TsnetDial`，`ServiceWire`，`SessionViewModel.baseUrl` 快照，`WorkspaceScreen` 横幅，`OkHttpWebSocketTransport` 默认 client，`tools/tsnetbind`。

服务端 `911dd941…`：`Handler()` 仅 `/ws` `/upload`；`handleAuth`；`pairing/{qr,token,probe}`；`cmd/agentmirrord` Up 失败退出；`tsnetd.Group`。

协议：`docs/protocol.md` §2.1、§3、§8、§9。

## 13. S1–S6 / 原 B 编号处置

| ID | 处置 | 位置 |
|---|---|---|
| S1 / Luna B1 | 闭合：whoami 列举 → 选主机 → 输入 token → identify。Grok N1 的“TS key≠主机 token”保留，不排除无扫码入口 | §2 |
| v2 B11 / Luna R1 | 无记录无已知端口时 TS whoami 引导 9900；响应 port 只附着原始字面 IPv4；非默认端口靠 NSD/QR/记录；覆盖范围如实；夹具必须真 GET `:9900` | §2.2、§3.2、§7、§10.1 |
| S2 / Luna B2 B5 / Grok B4 N2 | 已知 ID 全表先匹配；窗口/并发/cursor 续扫；退化≠自主发现 PASS | §5、§11 |
| S3 / Luna B3 / Grok B2 B3 | 异步代次状态机；单 create；LAN 槽；无 pump 阻塞；删 whenSettled | §4 |
| S4 / Luna B6 / Grok B1 B5 B6 B7 B9 B10 | 字面地址、禁重定向/DNS、LocalAddr API、dest_ip MAC、先验后写 host_id、遗留仅确切 404+来源+未升级身份 | §3 |
| S5 / Luna B2 B4 / Grok B8 | port/ts_node_id/name 冷启；READY 原子发布；live 上传 | §4.6、§6 |
| v2 Luna R2 | 同主机同 token 仅改 TS key：READY 只持久化待用 key，不 `ensureStarted`/不拆 socket；下一代次才应用；与身份重建分开。TS/LAN READY 各一条时序夹具 | §4.3、§6.3、§8、§10.1 |
| S6 / Luna B7 B8 | 17 项全名、前台五项、CONTRACT/envcheck、签名/覆盖映射；删“先合”；冻结后可 push/开 PR，merge/生产未授权 | §9、§10 |
| Grok N3 | 两连接间 ARP/DHCP：与今日明文 WS 同阶，不升级 PKI；重定向另案已关 | §3.1 |
| Grok N4 | 旧 App 喷 candidates：旧客户端例外 | §3.5 |
| Grok N5 | 学到 host_id 后降回旧 daemon：白名单已关，不在 101 | §3.5 |
| Grok N6 | body 1KiB、5/s | §3.2–3.3 |
| Grok N7 | identify≠登录 | §3.3 |
| Grok N8 / Luna N4 | 17 项/性能/覆盖未跑 | §10、§11 |
| Grok N9 | tsnet 同名冲突本功能不修 | 不变 |
| Luna N1–N3 | PeerLines/NSD/LocalAddr 实验期；失败语义已写 | §11 |

仍需实验：§11 三条 + §10 所有“本稿未跑/未验证”行。不得把本文当复审 PASS。
