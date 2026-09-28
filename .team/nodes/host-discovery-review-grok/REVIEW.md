# 主机自动发现方案：独立审查（安全 / 协议 / 兼容）

席位：`host-discovery-review-grok`（独立方案安全审查）。日期：2026-09-05。  
未读 Luna 审查结果，未与设计作者协商，未改方案或产品。未编译、未跑设备、未扫 tailnet、未碰生产/凭据、未提交。

## 裁决

**fail**

不是「有 HMAC 所以安全」的签字，也不是把明文 WS 的固有风险扩成 PKI。  
方案把发现与鉴权拆开、identify 请求不含 token、伪广告没有密钥就算不出 MAC，这条方向成立。但按冻结文本实现时，存在**可写出请求/响应序列的新增 token 泄露或未经证明放行**，以及择路/持久化与用户契约无法闭合的状态机。这些必须在派 owning PR 前改方案，不能留给实现者临场发明。

未跑的有界实验（PeerLines / NSD / tsnet `LocalAddr` 实测）不足以单独把全案打成 unjudgeable；它们是实现期装置，下面已与「派单前必须写死的失败语义」分开。

## 输入核验

对 `.team/stable-pr/host-auto-discovery/REVIEW-INPUT.sha256` 执行 `shasum -a 256 -c`，六份全部 OK：

| SHA-256 | 文件 |
|---|---|
| `6db18d8adb70c52fa3e72f7bdef81a8b621a204743eaa2e2fb423be2bc236a93` | `requirement-base/entries/101-主机自动发现与TS优先路由.md` |
| `43bd62b31497b82752d00950f004555c79c3fec479a042095cfe5c4fd6b36bc9` | `GOAL-DRAFT.md` |
| `aba82112585c7108c610cc033ea09e9c5b7d2d3eee2355db4f45e4796198ef00` | `DESIGN.md` |
| `3b18964e1450f2ecdbea06bdddd737dbb3e74bbef0d560593365fd033454b04d` | `EXPLORATION.md` |
| `9bf935acdf50cc124b1794493e0cc6cacef4c6fd8f5c499e688d1ccc4ca6ec8d` | `CAPABILITY-EVIDENCE.md` |
| `a0e1fea8fd2b45fc30d6f2cf416612635e3deb70284de9f5e01aa4b4f768637c` | `EVIDENCE.md` |

源码事实只取基线对象 `corral-core@eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`、`corral-serve@911dd94176a34e11f66b61b15b191216463c48cf`，不以 dirty `main` 当产品。

## HMAC + LocalAddr 实际证明了什么

选定 MAC（`DESIGN.md` §3）：

```
key = UTF-8(pairing token)
msg = "agentmirror-identify-v1" || 0x1f || host_id || 0x1f || nonce_hex || 0x1f || bound_ip || 0x1f || bound_port
```

随后另开一条 TCP 做现有 `ws://…/ws` + `AuthFrame(token)`（`Connection.kt` `onOpen` 立刻发 token；`ws_handler.go` `handleAuth` 只验 token，identify 不置 `authed`）。

**一次成功 identify 证明的是：**

1. 响应方能用与客户端相同的主机 token 算出 MAC（知道共享秘密，或等价地能向知道秘密的真机问到 MAC）。
2. MAC 覆盖了客户端这次选的 nonce（重放旧响应会因 nonce 不同失败）。
3. **若且仅若**客户端拿「自己发起 identify 时的候选字面 IPv4:port」去比 `bound`，且服务端 `bound` 来自**这条 HTTP TCP 的 `conn.LocalAddr()`**（不是 listener 广告地址），则响应方当时认为自己的本端地址等于客户端想打的地址。

**它不证明、也不能单独当作证明的：**

- 随后那条 WebSocket 与 identify 是同一条 TCP、同一个进程、同一套 path 路由。identify 不在服务端创建会话绑定；`/ws` 仍然是「谁有 token 谁就能 auth」。
- 客户端最终 `create(wsUrl)` 的目标等于被证明的目标。这是客户端纪律，不是 MAC 的性质。OkHttp 默认跟随重定向时，这两件事会分裂（B1）。
- `http.Server` 的 listener 地址。基线 LAN 监听默认 `0.0.0.0:9900`（`internal/config/config.go`），tsnet `Listen("tcp", ":"+port)` 的 `listener.Addr()` 在无 IP 时是 `":9900"`（`tsnet.go` `listenAddr`）。若实现误用 listener 地址当 `bound`，比较恒失败，或被改成「只比端口」而放行继电器。
- TS Up、端口开、DNS-SD TXT/`host_id`、`PeerStatus.Online`、自报身份。这些最多产生候选。

对「伪发现、无 token」：攻击者算不出 MAC → 不建 WS。这条成立，前提是客户端在 `transport.create` 之前就丢弃。  
对「同网卡 ARP/L2 打到真 IP」：与今日明文 `ws://` 相同，不在本需求用 PKI 收口。  
**本方案相对旧「对 candidates 直接喷 token」真正多出来的风险，集中在：证明与持 token 的 WS 不是同一条连接，以及 404 降级把「未证明」又放回 WS。**

---

## 阻断清单（派实现前必须改方案）

### B1. identify HTTP 跟随重定向 → 证明地址与 WS 地址分裂，token 发给未证明端

- **依据**：`DESIGN.md` §2.2 / §4.2 要求对「同一字面 URL」先 identify 再 WS；§9 PR B 写 identify 用 OkHttp。基线 `OkHttpClient.Builder()` 默认 `followRedirects=true`（307/308 保留 POST）。方案未禁止重定向、未规定比较的是原始候选还是最终 URL。
- **反例**：
  1. 伪 DNS-SD/伪 peer 广告 `A=192.168.1.99:9900`（攻击者），TXT/`host_id` 抄真机。
  2. 客户端 `POST http://192.168.1.99:9900/pair/identify` `{"v":1,"host_id":"H","nonce":"N"}`。
  3. 攻击者 `307 Location: http://192.168.1.10:9900/pair/identify`（真机）。
  4. OkHttp 把同一 body POST 到真机。真机 `bound=192.168.1.10:9900`，MAC 用真 token 计算。
  5. 若客户端用**最终 URL**比 `bound`：通过。若随后 WS 仍打**原始候选** `ws://192.168.1.99:9900/ws`：`onOpen` 把 token 发给攻击者。
  6. 若 WS 也跟随到真机：这次碰巧安全，但「字面 URL」已无定义，下一种 307 目标可再被换成攻击者。
- **影响**：这是自动发现**新增**的 token 泄露，不是明文 WS 固有面。旧客户端喷 token 至少打的是 QR 里的地址；这里证明的是真机、token 却可以交给广告者。
- **最小修正**：identify 客户端 `followRedirects=false`（3xx = 该候选失败）。`bound` 与后续 WS 只对**原始候选字面 IPv4:port**。禁止把 `Location` 当作已证明地址。
- **应补验证**：307/308/301 到真机后，断言无 `AuthFrame`、无 `transport.create(原始A)`；日志只记候选 IP 与 HTTP 码，不含 token。

### B2. `resolve(): String?` 与「identify → WS auth → 第一路 READY」不闭合

- **依据**：`DESIGN.md` §2.4 注入 `DialTarget.resolve(): String?`，null 则本轮不建 `Connection`；§4.4 又写对每个候选 `identify → WS auth`，一路 READY 即停。基线 `ConnectionManager.attemptConnect`（`:667-674`）`create(config.url)` 后立刻 auth；失败走 `scheduleReconnect` 对**同一** `ConnectionConfig.url` 指数退避（`:763-839`）。`ConnectionConfig.url` 是构造期 `val`。
- **反例 A（卡死、饿死 LAN）**：TS 候选 `T1` identify 200+MAC 过（HTTP 可达），WS upgrade 失败（只放行 HTTP、或中间盒）。`resolve()` 若在 identify 成功后返回 `T1`，`attemptConnect` 建 WS 失败 → 退避 → 下次 `resolve()` 又返回同一 `T1` → LAN 永不试。用户契约「TS 不可用则局域网」被破坏；工作区空，但 LAN 本可用。
- **反例 B（双连）**：若 `resolve()` 内部已经 `create`+auth 等到 READY，再返回 URL，则 `attemptConnect` 会再 `create` 一次，token 再发一遍，READY 不抢占也失效。
- **影响**：不是风格问题。按现码接缝实现，候选遍历在 WS 失败时不存在。
- **最小修正**：把「一轮 attemptConnect」定义成有界状态机，而不是一个 `String?`：
  - identify 失败：丢弃，试下一个；
  - identify 通过：只对**该字面 URL** `create` 一次 WS；
  - WS 非 `auth_ack.ok=false` 的失败：记入本轮黑名单，继续下一候选，**禁止**把该 URL 写进 `config.url` 当退避唯一目标；
  - `auth_ack.ok=false`：整轮 `STOPPED`（凭据，不换候选喷 token）；
  - 第一路 READY：停。`resolve()` 若保留，只能返回「本轮尚未试的下一目标」或改成异步回调，且不得在 `AppClockPump.pump` 线程上同步跑满发现预算。
- **应补验证**：T1 identify 过、WS 失败、T2/LAN identify+WS 成功；断言只对 T1/T2 各 `create` 一次、token 只出现在证明后的 WS；`pump` 在发现期间仍能推进输入超时。

### B3. TS 优先预算与 8s 墙钟自相矛盾，可饿死 LAN

- **依据**：`DESIGN.md` §5.3：每候选 identify 2s；先全部 TS 再 LAN；一轮 `resolve()` 约 8s。§2.1：TS 候选 ≤8。§7：`PersistentConnection` 不等 TS 再允许 LAN。基线相反：`PersistentConnection.kt` 对 100.x 在 `TsnetState.Up` 前 `whenSettled` 才 `start`（可等到 TS Error/60s 量级）。
- **反例**：TS Up，PeerLines 给出 8 个 `online=1` 的无关 peer（控制面在线 ≠ 9900）。串行 identify 8×2s=16s > 8s。若硬切 8s：LAN 本轮零尝试，进入指数退避，局域网可用的工作区仍空。若坚持「全部 TS 再 LAN」：墙钟被自己作废。若 last-good 是 100.x 且残留 `whenSettled`：LAN 被 TS Starting 再饿一轮。
- **影响**：直接打穿 101 路由表「TS 否、LAN 是 → 用局域网」和「TS 入网失败不阻止 LAN」。这是新择路引入的可用性/契约洞，不是旧明文风险。
- **最小修正**：
  1. 写死墙钟为硬上限，并**预留 LAN 槽**（例如 TS identify 并行、总 TS 预算 ≤4s，剩余给 LAN；或 TS 与 LAN 并行 identify，READY 时 TS 成功优先于 LAN 成功，但 LAN 不得排在墙钟之后）。
  2. 明确：即使 last-good/QR 提示是 100.x，`TsnetState` 非 Up 也必须同时跑 LAN 候选，禁止 `whenSettled` 门闩。
- **应补验证**：8 个黑洞 100.x + 一个可达 LAN；墙钟内 LAN READY、徽标 `局域网`、无 TS 失败文案。last-good=100.x 且 TS Starting：同样 LAN READY。

### B4. 32 peer / 8 候选截断未规定选择函数，可丢掉真实主机

- **依据**：`DESIGN.md` §2.1 / §5.2：`PeerLines` 上限 32 行；TS 候选 ≤8；有 `ts_node_id` 则精确过滤，否则对 `online=1` 有界 identify。Go `map` 遍历顺序不确定。`PeerStatus.Online` 不是 9900 服务（能力补证 §2）。
- **反例 1**：QR 有 `ts_node_id=S`。包装器先把 `Status.Peer` 截成 32 行再交给 Java，S 不在这 32 行里 → 本轮零 TS 候选。主机在 tailnet 上可达。
- **反例 2**：无 `ts_node_id`（QR 打印时 TS 未 Up，§8.4 不重打码）。>8 个 online peer，真实 daemon 排第 9 → 只证明前 8 个无关节点，MAC 全失败，若 LAN 也不在本网段则工作区空。
- **影响**：101「先入 TS 后发现 TS 相关 IP」在中大型 tailnet 上可稳定失败。截断若发生在 `ts_node_id` 过滤之前，连「精确过滤」都是假的。
- **最小修正**：`PeerLines` 在 Go 侧对**完整** `Status.Peer` 先按 `ts_node_id` 取值；命中则只输出该节点 IPv4，不受 32 行影响。其余行再截 32。客户端 8 候选顺序必须稳定且**先放入** `ts_node_id` 命中、`lastTsUrl`、QR 100.x 提示，再补其它 online。禁止「截断后再找 S」。
- **应补验证**：夹具 Status 含 >32 peer，目标放在 map 迭代末尾；有 `ts_node_id` 时必须证明到该 IP。无 `ts_node_id`、>8 online：文档化「可能漏」或改为先 last-good/QR 再填满 8，测试断言 QR 100.x 不被挤出。

### B5. 旧 daemon「404/无端点」白名单语义过宽 → 未证明放行

- **依据**：`DESIGN.md` §6：新 App + 旧 QR/已存 prefs，identify `404/无端点` 时**仅扫码主 `url`** 可直接 WS auth。基线 `Handler()` 只有 `/ws`、`/upload`（`server.go:249-253`），旧二进制对该 POST 确为 404。方案把「无端点」与 404 并列，未定义超时、RST、405、3xx、HTML 404、以及是否允许 last-good/发现候选走同一降级。
- **反例 A（发现候选误放行）**：已存 `legacyBootstrapUrl=ws://192.168.1.10:9900/ws`。攻击者 NSD 广告另一地址 `192.168.1.99`。若实现把任意候选的 404 当成「旧服务」：对该地址 `create`+`AuthFrame(token)`。这是新自动发现把旧「喷 token」又打开。
- **反例 B（超时当无端点）**：主 url 上攻击者丢弃 POST，客户端 2s 超时，若把超时算「无端点」则跳过 HMAC 直接 WS，token 仍给该 IP。相对「必须先证明」是未经证明放行。
- **反例 C（学到 host_id 之后）**：新 daemon 已写入 `host_id`，之后该端口被非本服务占用并 404。若仍走白名单，token 发给无关 HTTP 服务的 WS 升级。
- **影响**：白名单若只作用于「尚无 `host_id` 的那一条扫码/已存主 url、且仅 HTTP 404/405」，则与旧客户端对该 url 发 token **同阶**，不是新泄露。一旦「无端点」或候选集合被写松，就是新泄露。
- **最小修正**：遗留直连 WS 的充要条件全部写死：`host_id==null` ∧ 字面 URL 等于 `legacyBootstrapUrl` ∧ identify 对该 URL 的**原始**请求（无重定向）得到 HTTP 404 或 405 ∧ 不得用于 last-good、QR `candidates`、NSD、PeerLines。超时/RST/3xx/2xx-坏 MAC = 失败，不降级。一旦 `host_id` 非空，永久关闭该白名单。
- **应补验证**：伪造候选 404 无 `AuthFrame`；主 url 超时无 `AuthFrame`；主 url 真 404 才允许一帧 auth；写入 `host_id` 后再 404 不得 auth。

### B6. `bound` 的 LocalAddr 来源与失败关闭未钉死

- **依据**：`DESIGN.md` §2.2「本条连接的 LocalAddr」。Go 1.x `http.LocalAddrContextKey` 在 `net/http/server.go` `conn.serve` 里是 `c.rwc.LocalAddr()`（已接受连接），**不是** `ln.Addr()`。基线 LAN `0.0.0.0:9900`；tsnet 无 IP 的 listener 字符串是 `":9900"`。gVisor `gonet.TCPConn.LocalAddr()` 在握手完成前可 nil（`wgengine/netstack/netstack.go` 注释）。
- **反例**：实现用 `ln.Addr()` → `bound=0.0.0.0:9900` 或 `:9900`。客户端拨 `192.168.1.10:9900`，比较失败，TS/LAN 全不可用（假失败）。若有人把「未指定 IP」改成只比 port：继电器 `A` 转发到真机，真机 `bound_port=9900`，客户端拨 `A` 也是 9900 → MAC 过 → token 给继电器。`DESIGN.md` §3 继电器反例依赖「继电器 IP ≠ 真机 IP」被这步拆掉。
- **影响**：不是 PKI 问题；是绑定操作数选错会把方案自己的继电器证明作废。
- **最小修正**：协议写死 `bound_ip` = 已接受 `net.Conn.LocalAddr()` 的 IPv4（Go：`Request.Context()` 的 `LocalAddrContextKey`，且禁止用 listener 地址回退）。`unspecified / loopback / nil / 非 IPv4` → 该次 identify 失败关闭，不得改成只比端口。TS 路径若实测不是 100.x，走 B7，不得在 B6 上放宽。
- **应补验证**：单元夹具 listener=`0.0.0.0:9900`、conn.LocalAddr=`192.168.1.10:9900`，客户端拨后者才过；`bound=0.0.0.0` 或空 IP 必须失败。

### B7. tsnet LocalAddr 替代绑定没有 MAC 格式，存在「放弃地址绑定」的实现路径

- **依据**：`DESIGN.md` §11.3：若 userspace 监听器 LocalAddr ≠ 客户端拨的 100.x，「改用本机地址集合包含拨号 IP」，「不能放弃绑定」。未改 §3 的 `msg` 字段，未规定集合来源（`DetectAddresses` 看不见 userspace 100.x，100.x 来自 `group.Up`/`st.TailscaleIPs`，`probe.go` `WithTailnet`）。
- **反例（弱实现）**：LocalAddr=`127.0.0.1:9900` 时，服务端仍按 LocalAddr 签，客户端改成「端口相同且集合非空即过」。继电器把 identify 转到真机，真机集合含自己的 100.x，不含继电器；但弱实现已不再检查拨号 IP 是否在集合里。
- **正确替代（必须写进 §3，不能只写 §11）**：identify 请求增加客户端声称的 `dest_ip`（=本次拨号字面 IPv4）。服务端校验 `dest_ip ∈ (LAN DetectAddresses ∪ TailscaleIPv4)`，再用 **`dest_ip` 而不是错误的 LocalAddr** 进入现有 MAC 字符串。客户端仍要求 `bound == 自己拨的字面 IP`。继电器声称 `dest_ip=自己` → 不在真机集合 → 4xx；改写 `dest_ip=真机` → 客户端 `bound≠拨号` → 丢弃。
- **影响**：实验本身可在实现期做（隔离节点，禁止扫用户 tailnet）。**MAC 在两种结果下的字节定义必须现在冻结**，否则 PR 会发明弱绑定。
- **应补验证**：同一组 HMAC 向量覆盖 LocalAddr=100.x 与 LocalAddr=loopback+集合含 dest_ip；继电器 dest_ip 两种改写都不得 WS auth。

### B8. `host_id` / `port` / `ts_node_id` 持久化不闭合

- **依据**：`DESIGN.md` §4.1 QR 有 `port`、`ts_node_id`；§4.3 `PairingConfig` 只有 `hostId/token/tsAuthKey/legacyBootstrapUrl/lastTsUrl/lastLanUrl`，无 `port`、无 `ts_node_id`。基线 `PairingConfigStore.load`：`url` 缺失直接 `null`（`:67-68`）。新解析允许「合法 `host_id` 则 `url` 可空」（§4.1）。PeerLines 只有 IP，构造 `ip:port` 需要端口。
- **反例**：扫新码（`host_id`+`port`+`ts_node_id`，url 空或被当成未信任丢弃）→ 落盘进工作区 → 进程被杀（尚未 READY，无 last-good）→ `load()` 无 url 视为未绑定，或有 `host_id` 无 port：PeerLines 的 IP 无法构成候选，NSD 未开窗口时 LAN 也没有。`ts_node_id` 丢失后落入 B4 的 8 候选抽签。
- **影响**：覆盖安装/冷启动后「已绑定主机」契约不闭合；与「回写 `url` 给旧 App」也不对称——旧 App 还要求 url，新 App 自己却可能读不出发现所需字段。
- **最小修正**：prefs 增加 `port`、`ts_node_id`（及可选展示 `name`）；`clear()` 删除。`load()` 合法条件改为：`token` 非空且（`host_id` 合法或 `legacyBootstrapUrl`/`url` 合法）。`url` 仍每次 READY 回写，供降级 PR74。QR 提示地址可存为未信任 hint，不得当 `legacyBootstrapUrl`。
- **应补验证**：无 READY 杀进程再启动仍能用 `host_id+port+ts_node_id` 发现；旧 prefs 仅 url+token 走 §6；`clear()` 后无新键残留。

### B9. 无本地 `host_id` 时 MAC 的 `host_id` 操作数未定义

- **依据**：§4.2 请求 `host_id` 可选；成功响含服务端 `host_id`；校验顺序「本地尚无则在 MAC 通过后写入」。§3 `msg` 含 `host_id`。未写：客户端用空、用请求值、还是用响应值算 MAC。
- **反例 A**：新 App + 旧 QR（无 `host_id`）打新 daemon。服务端用自身 `host_id` 算 MAC。客户端用空 `host_id` 校验 → 恒失败 → 不能「学会 host_id」（§6 该行作废），又因 HTTP 200 不是 404，不能走遗留 WS → 完全连不上。
- **反例 B**：先把响应 `host_id` 写入再验 MAC。攻击者 200 `{host_id: evil, bound: 拨号IP, mac: 垃圾}`。MAC 失败不发 token，但 prefs 已被写成 evil，之后 NSD/Peer 按错误 id 过滤，真机再也匹配不到（身份 DoS）。
- **最小修正**：本地无 `host_id` 时，MAC 的 `host_id` 操作数 = 响应 `host_id`（须合法 charset/长度），**先**常数时间比 MAC 与 `bound`，**再**写入。本地已有 `host_id` 时响应必须逐字节相同，否则与 `unknown_host` 同失败。
- **应补验证**：旧 QR×新 daemon 学会 `host_id` 并 READY；坏 MAC 不得写入 `host_id`。

### B10. 候选必须是字面 IPv4，NSD hostname 禁止进入会做 DNS 的客户端

- **依据**：§5.3「分类只按字面 IPv4，不 DNS」；§5.1 用 `NsdManager.resolveService`。Android `NsdServiceInfo` 可带 hostname。OkHttp 对非字面 host 会解析 DNS。
- **反例**：NSD 给出 `host=agentmirror.local`（或攻击者 TXT 指向可变 A 记录）。identify 的 DNS 结果为真机（MAC/`bound` 按真机 IP 过），随后 WS 再解析得到攻击者 IP → 与 B1 同类分裂。
- **最小修正**：发现层只输出已是 IPv4 字面的候选（NSD：`InetAddress.hostAddress` 且 `TsnetDial` 同类解析成功；hostname/IPv6 丢弃）。identify 与 WS URL 只嵌入该字面 IP。
- **应补验证**：夹具 NSD hostname 不得 `create`；只有 v4 字面进入 identify。

---

## 非阻断清单

### N1. 「无扫码、无旧地址、只先入 TS」不能绑定 —— 不是 101 的有效缺口

- **事实**：HMAC 与 WS auth 的密钥是主机 token。PeerLines 只能列出 peer IP，不能代替主机 token（能力补证 §2、§6）。无 `host_id` 且无 url 时 §4.1 直接解析失败。
- **对 101**：最终有效需求 1/3/4 是「绑定主机+主机 token」「扫码给出主机与 token」「TS token ≠ 主机 token」。只填 TS auth key 入网后可以发现地址，但不能完成绑定。这是凭据分离，不应扩成「免主机 token 的 tailnet 单点登录」。
- **张力**：手填「只有 token、主机靠发现」也被解析规则拒绝；若产品以后要这条，才需要「无 url/host_id 落盘 + 用 token 在有界候选上学习 host_id」。本次不新增该需求。
- **应补验证**：TS key 当主机 token → identify/WS 失败；主机 token 当 TS key → 不能 Up。不测「只入 TS 就进工作区」。

### N2. PeerLines / NSD 实测失败时，QR/last-good 退化 ≠ 「自主发现 TS」

- **依据**：§11.1–2。QR 仍带 `url`/`candidates`（给旧 App，新 App 当未信任提示）。
- **影响**：退化路径能连的是码上已印的地址，不是「入 TS 后发现当前 100.x」。主机换了 CGNAT 地址且 PeerLines 不可用时，远程-only 用户工作区空。这符合「两路不可用则空」，但不得把该退化写成 101 第 2 条已满足。
- **处理**：实验可延后到实现期（隔离节点，禁止扫用户 tailnet）。GOAL 里「从自动发现结果建立 TS 连接」在实验未过前不得标 PASS。LAN 不受 PeerLines 失败影响。

### N3. identify 与 WS 之间的 DHCP/ARP 换主

- **序列**：identify 对 `192.168.1.10` 成功 → 短窗口内该 IP 改绑到攻击者 → WS auth 把 token 发给新所有者。
- **归类**：与今日「扫码后过一会儿再连明文 ws://该 IP」同阶；方案已用短超时约束。不升级为 PKI。B1 的重定向不是这一类，必须单独修。

### N4. 旧 App 仍对 `candidates` 喷 token

- 方案明确不改 PR74。这是旧客户端固有面。新客户端测试必须锁死「candidates 不 `create`」。

### N5. 学到 `host_id` 后把服务端降回旧二进制

- 白名单已关，identify 404，新 App 不能连旧 daemon。未在 101 要求。文档一句即可。

### N6. identify 速率/body 上限只有原则句

- HMAC-SHA256 成本低。实现给死数字（如 body ≤ 1KiB、每 IP 每秒 N 次）并单测即可，不挡方案方向。

### N7. 成功 identify 不等于登录

- 方案已写：不加 `authed`、`/upload` 仍要 Bearer、WS 仍要 auth。保留测试即可。

### N8. 架构工具目录缺失、17 项 UI/性能/覆盖安装

- 探索已记 unknown。不得报架构 PASS，也不得把未跑真机金标准标 PASS。与本安全审查正交。

### N9. 预存在 tsnet 换 key 同名冲突

- 方案正确划出本功能不修。不要绑 hostname。

---

## 用户契约核对（不新增需求）

| 问项 | 结论 |
|---|---|
| 绑定界面无链接、只主机+token | 方案意图满足（删 PairingViewModel 喷地址/下拉/`入网失败：`）。取决于 B2/B8 实现后冷启动仍有主机记录。 |
| 先入 TS 能否发现再绑定 | 有主机 token + `ts_node_id` 或有界 peer identify 时，设计上可以。只入 TS 无主机 token：不能绑（N1）。PeerLines 失败：不能发现**新** 100.x（N2）。截断未修时中大型 tailnet 可发现失败（B4）。 |
| 32/8 硬截断 | 未定义选择函数时会漏真实主机（B4）。有 `ts_node_id` 也必须先过滤再截断。 |
| 发现失败退化 QR/last-good | 有扫过的码或曾经 READY 时可能仍能连；不满足「入网即发现当前 TS 地址」。不得当 PASS。 |
| TS 优先是否饿死 LAN | 按现文本会（B3），且与基线 `whenSettled` 叠加更糟。 |
| `String?` + 异步 WS/READY | 不能正确遍历（B2）。 |
| `host_id/port/ts_node_id` 与旧版 | 服务端 `host_id` 文件与 token 分寿命：合理。客户端缺 `port`/`ts_node_id`、`load` 仍强依赖 url：不闭合（B8）。旧 App + 新 QR 靠必填 `url`：成立。新 App + 旧 QR 依赖 B5/B9。 |
| 右上角实际路径、不抢占、两路空且无提示 | 接缝选择对（只改 `attemptConnect`、READY 前台不重拨、徽标只读 READY）。Workspace 现码仍有「重连中…/连接已关闭」（`WorkspaceScreen.kt`），方案要求删掉——实现项，不是安全阻断。 |
| 上传跟随 | 基线 `SessionViewModel.baseUrl` 构造期快照；方案改为读 live `uploadBaseUrl`。必须做，否则换路后上传打旧 host；属兼容闭合，随 PR 验收，不单开安全阻断。 |

## 可延后实验 vs 派实现前必须解决

**派 owning PR 前必须写进 DESIGN（本次 fail 的原因）**：B1–B10。尤其 B1/B5/B6/B7 的操作数（原始 URL、404 谓词、LocalAddr API、两种 MAC 消息）。

**实现期隔离实验，禁止扫用户 tailnet、禁止本机编译产品、禁止动生产：**

1. Android 进程内 `PeerLines`/`LocalClient.Status`  
2. `NsdManager` + 组播锁在发现窗口能否解析 `_agentmirror._tcp`  
3. tsnet 已接受连接 `LocalAddr()` 是否为客户端拨的 100.x（夹具覆盖两种结果；失败走已冻结的 B7 消息，不得现场改弱）

实验失败只启动方案已写的退化，并把 GOAL「TS 自主发现」保持未通过；不回头改用户需求，也不许用退化冒充第 2 条已交付。

## 论证边界（即使将来改成可派）

- 本审查**没有**跑 HMAC 向量、没有跑设备、没有证明 PeerLines/NSD/LocalAddr 在 Android/tsnet 上的实测值。
- 未声称会话秒开、17 项 UI、覆盖安装 PASS。
- 未把「明文 WS + 共享 token」升级为需 TLS/PKI；L2 打到真 IP 仍与今日相同。
- 服务端 identify 不替代 WS auth：这是正确的最小范围。安全性质是**客户端在 `transport.create` 前完成证明**，必须靠测试钉死，不能靠「有 `/pair/identify` 路由」。

## 修完后仍必须保留的测试（未跑，不得标 PASS）

安全（日志只记操作数，禁止 token）：

1. 伪 DNS-SD / 伪 peer：identify 失败，零 `transport.create`、零 `AuthFrame` 字节。  
2. 重放 MAC（旧 nonce）：失败。  
3. 继电器 / 307 到真机：失败且无 token 上行（B1）。  
4. `bound` ≠ 拨号字面 IP、`bound` 为 `0.0.0.0`/空：失败。  
5. TS Up、端口开、TXT 自报 `host_id` 单独出现：不发 token。  
6. 旧 `candidates`、last-good、发现候选 404：不发 token；仅无 `host_id` 的 `legacyBootstrapUrl` 真 404 才允许一帧 auth。  
7. identify 后 `/upload` 无 Bearer 仍 401；WS 无 auth 不 `authed`。  
8. 主机 token / TS key 互换失败。  
9. 无本地 `host_id`：用响应 `host_id` 验 MAC 才写入；坏 MAC 不写。

择路 / 兼容：

10. 四格状态 + READY 不抢占 + last-good=100.x 时 LAN 不被 `whenSettled` 堵住。  
11. identify 过、WS 失败，下一候选仍被尝试（B2）。  
12. >32 peer 时 `ts_node_id` 仍命中。  
13. 无 READY 冷启动仍有 `host_id+port+ts_node_id`。  
14. 旧 v1 QR × 新 daemon 学会 `host_id`；新 QR × 旧 App 仍用 `url`。  
15. 上传读 live base，与当前 READY 的 SOCKS/直拨一致。  
16. 发现无 2s 常驻扫描子进程；候选有界。

真机金标准与 17 项仍是后续验收，不在本稿。

## 本席未做

未写产品、未编译、未跑模拟器/真机、未扫 tailnet、未操作生产、未催设计作者返修。产物仅本文件。
