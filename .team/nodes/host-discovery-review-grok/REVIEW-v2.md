# 主机自动发现方案：v2 复审（安全 / 协议 / 兼容）

席位：`host-discovery-review-grok`。日期：2026-09-05。  
对照本席 v1 的 B1–B10 与收敛 S1–S6。未读另一席本轮复审，未与作者协商，未改方案/产品。未编译、未跑设备、未扫 tailnet、未碰生产/凭据。  
`EVIDENCE.md` 只作 v1 历史摘要，不作为当前方案。源码基线仍 `corral-core@eaaa7d47` + `corral-serve@911dd941`。

## 裁决

**fail**（设计阶段仍不可派 owning PR）

B1–B10 在修订文本里已经按操作数闭合，HMAC 证明范围也写对了（只覆盖该次 identify HTTP，不是后续 WS 所有权）。需求分歧按裁定处理：只入 TS 可发现**候选主机**，绑定仍要主机 token；`whoami` 本身没有把 token 送出去，也没有单独构成已绑定写入。

仍不可派的原因只有修订**新引入**且可写出反例的缺口：无主机记录时 TS `whoami` 的端口未定义，与「缺 port 不能构成候选」合在一起，S1 的「先入 TS 列出候选主机」在字面上发不出请求。这不是 PKI、不是无限兼容、也不是实验未跑。

设计阶段即使日后改成 pass，也不等于功能/设备/性能通过。§11 三条与 §10 未跑项保持未验证。

## 输入核验

修订归档提交：`6a69fa12db34c7563ac469bfe3af6acce2b4f7e2`。  
`shasum -a 256 -c .team/stable-pr/host-auto-discovery/REVIEW-INPUT-v2.sha256`：八项 OK。

| SHA-256 | 文件 |
|---|---|
| `6db18d8adb70c52fa3e72f7bdef81a8b621a204743eaa2e2fb423be2bc236a93` | `requirement-base/entries/101-主机自动发现与TS优先路由.md` |
| `365e123ef003776330bbb39b64191786c1fbeb6fd03b6b359088026a6b91f603` | `GOAL-DRAFT.md` |
| `2837544831579977157b3b1fce2aa968bebf39a29e58d1401a1a79f8d1c9bc1c` | `DESIGN.md` |
| `14feb8085de4e0833e2c299c692dcb27f94f377be2d485469e3fb62c3c444740` | `REVIEW-SYNTHESIS.md` |
| `3b18964e1450f2ecdbea06bdddd737dbb3e74bbef0d560593365fd033454b04d` | `EXPLORATION.md` |
| `9bf935acdf50cc124b1794493e0cc6cacef4c6fd8f5c499e688d1ccc4ca6ec8d` | `CAPABILITY-EVIDENCE.md` |
| `c7446890729ac9b9e34d6ca818a9be33d4f60fd8b59783c8b66c652a6dc76a07` | `REVIEW-IMPLEMENTATION.md`（v1 实现审查，本轮输入） |
| `835e5e1a1087ba2a4af3183e052bf162c810ff176a2cfd02315deb41e6590249` | 本席 `REVIEW.md`（v1） |

## HMAC 证明范围（修订后）

§3.1 / §3.3 现与 v1 结论一致，不再把 HMAC 写成后续连接所有权：

- 证明：持同一主机 token 的响应方，对本次 `nonce` 与客户端 `dest_ip` 算出 MAC；客户端用**原始拨号字面**比 `bound`。
- 不证明：随后 WS 与这次 HTTP 同一 TCP/进程/所有者。
- 关掉的新增分裂面：HTTP/WS 重定向、DNS 二次解析（相对旧「对 QR 地址喷 token」是新面）。
- 不升级 PKI 的残余：identify 成功到 WS 之间的 DHCP/ARP 换主，与今日明文 `ws://` 同阶。

`bound_ip` 两种结果都等于通过校验后的 `dest_ip`，主路径要求 accepted `conn.LocalAddr()` IPv4 等于 `dest_ip`，替代路径要求 `dest_ip ∈ DetectAddresses 可用 IPv4 ∪ tsnet Up/WithTailnet 的 100.x`。禁止 listener 地址、禁止只比端口。继电器两种改写（声称自己 / 改写成真机 IP）在客户端 `bound==拨号字面` 下失败。

## v1 B1–B10 闭合表

| ID | 状态 | 修订位置 | 仍需的具体反例（owning PR 落实，不是再开方案项） |
|---|---|---|---|
| B1 重定向分裂 token | **闭合** | §3.3 `followRedirects=false`；3xx=失败；WS 同纪律；只原始字面 IPv4 | 307/308/301 到真机后：无 `create(广告者)`、无 `AuthFrame` |
| B2 `resolve(): String?` / 单 URL 退避 | **闭合** | §4 废弃 String?；CM 唯一 `create`；失败黑名单本代次并推进；auth 拒绝 STOPPED | identify 200 + WS 升级失败 → 同代次下一候选；每字面至多一次 `create`；pump 线程无同步 IO |
| B3 TS 预算饿死 LAN / `whenSettled` | **闭合** | §4.5 8s 硬上限、TS 证明 ≤4s 必须开 LAN 槽、identify 并发 ≤4、删 `whenSettled` | 黑洞 100.x ×N + 可达 LAN：8s 内 LAN READY；last-good=100.x 且 TS Starting：同样 LAN READY |
| B4 32/8 盲截漏主机 | **闭合** | §5.1 已知 `ts_node_id` 全表先匹配；StableID 序+cursor 旋转窗口；in-flight≤8 不是全局砍 | >256 peer、目标在序末、有 `ts_node_id` 必须命中；第 9/33 在后续代次出现 |
| B5 404/「无端点」过宽 | **闭合** | §3.5 四谓词全真：`host_id==null` ∧ `scanned_primary`/`persisted_legacy` ∧ 字面精确相等 ∧ 原始 POST **恰好 404** | 发现/last-good/candidates/whoami 的 404、主 url 超时/3xx/5xx：无 token；写入 `host_id` 后白名单关 |
| B6 LocalAddr 来源 | **闭合** | §3.3 唯一来源 accepted `net.Conn.LocalAddr()` / `LocalAddrContextKey`；禁 `ln.Addr()` | 夹具 listener=`0.0.0.0:9900`、conn.LocalAddr=拨号 IP 才过；`bound=0.0.0.0`/空失败 |
| B7 替代绑定无 MAC 字节 | **闭合** | §3.3 两种路径 `bound_ip=dest_ip`；100.x 必须来自 tsnet 状态 | LocalAddr=100.x 与 LocalAddr=loopback 两套向量；继电器 dest_ip 两种改写不得 auth |
| B8 port/ts_node_id 冷启 | **闭合** | §6.2 键表含 `port`/`ts_node_id`/`name`/`scan_hints`；`load` 允许 URL-less | READY 前杀进程仍能用 `host_id+port+ts_node_id` 发现；`clear()` 无残留 |
| B9 无 host_id 时 MAC 操作数 | **闭合** | §3.3 先验响应 `host_id` 的 MAC，通过后才写 prefs | 旧 QR×新 daemon 学会 id；坏 MAC 不写 `host_id` |
| B10 NSD hostname / DNS | **闭合** | §3.3 发现层丢 hostname/IPv6；只用 `InetAddress.hostAddress` 字面 IPv4 | NSD hostname 不得进入 identify/WS URL |

B3 残余（非阻断）：墙钟到期句只写「cancel 未完成的 TS 证明，尚未试 LAN 则留 LAN 槽」。实现不得把 8s 做成连**已开始的 LAN WS** 一并 abort。用 §10.1「黑洞 TS + LAN」夹具卡住即可，不必再改证明模型。

## S1–S6

| 项 | 状态 | 说明 |
|---|---|---|
| S1 无扫码发现入口 | **未闭合** | 流程（列举→选主机≠路径→输入主机 token→identify）已写清，且不是免鉴权。缺省端口见下方新阻断。 |
| S2 完整性与退化 | **闭合** | 已知 ID 全表匹配；cursor 续扫；静态提示≠自主发现 PASS（§5.3、§11）。 |
| S3 异步拨号与预算 | **闭合** | 代次状态机、单 create、LAN 槽、pump 不阻塞、无 `whenSettled`。 |
| S4 证明与兼容 | **闭合** | 字面地址、禁重定向/DNS、LocalAddr、dest_ip MAC、先验后写、遗留仅确切 404+来源+未升级身份。 |
| S5 数据与 READY | **闭合** | 冷启字段、READY 原子发布 live 上传、TS key 变不拆链。 |
| S6 验收与交付 | **闭合（设计阶段）** | 17 项全名、前台五项、CONTRACT/envcheck、签名/覆盖已映射；删「先合」；冻结后可 push/开 PR，merge/生产未授权。未跑≠通过。 |

## 新阻断（修订引入）

### B11. 无主机记录时 TS `whoami` 没有端口，S1 发不出请求

- **依据**：`DESIGN.md` §2.2 要对 peer 的 `IPv4:port` 做 `GET /pair/whoami`。`PeerSnapshot` 行是 StableID/online/IPv4，无端口（§5.1、§7）。§7：「缺 port 则该来源不能单独构成候选」。未绑定、无 QR、无 last-good 时没有「记录中的 port」。响应体里的 `"port":9900` 是**已经打到某端口之后**才看得到的字段，不能当第一跳。
- **反例**：新装只填 TS auth key，本机 Up，tailnet 上唯一 AgentMirror 监听默认 `0.0.0.0:9900`（基线 `ListenAddr`）。按 §7 字面：Peer 只有 IP → 不能构成候选 → 零 `whoami` → 主机列表空。用户无法进入「选择主机 → 输入主机 token」。这正是裁定要保留的 101 路径，不是免鉴权 SSO。
- **影响**：不泄露 token；把 S1 做成空操作。实现者若猜 9900 可能碰巧能用，但方案自己禁止猜（缺 port 即丢弃）。
- **最小修正**（一句即可）：无主机记录时，TS `whoami`/`identify` 的端口=**9900**（与 daemon 默认监听一致）；`whoami` 响应 `port` 只允许补到**同一字面 IPv4** 上，不得改 IP。非默认端口靠 NSD/QR/`port` 字段/手填，不得扫 `100.64/10`。
- **应补验证**：无 QR、无 last-good、只入 TS、daemon `:9900`：必须发出 `GET http://<peerIPv4>:9900/pair/whoami`；列表出现该 `host_id` 后才收 token；证明前零 `AuthFrame`。

## `whoami` 有没有扩大 token / 身份写入边界

**没有扩大 token 上行。** GET 无 token、无 MAC；§3.5 把 `whoami` 命中地址排除在遗留直 auth 之外；空 token 不得 identify/WS。

**没有单独构成已绑定写入。** §2.2：未选主机或未通过 identify 前不写已绑定、不进工作区。选错伪主机并输入**该伪机自己的** token，与扫攻击者 QR 同阶（明文 WS 固有面，不升 PKI）。选真 `host_id`、identify 打到伪地址：无 MAC，不发 token。同一 `host_id` 把真机 IP 与伪 IP 合并成一行时，逐地址 identify，伪地址失败、真地址才过。

落实时必须钉死（否则会变成写入扩大，现文本已禁止）：

1. `whoami` 客户端与 identify 同一纪律：`followRedirects=false`、只字面 IPv4、3xx=不是主机。§2.2 把 3xx 列为「不是主机」，但 §3.2 没写死 OkHttp 默认跟随；若跟随 307，列表会把真机 `host_id` 挂到广告者 IP 上（可用性/展示撒谎，**在 identify 仍禁重定向时不泄露 token**）。
2. 绑定尝试中「用户所选 `host_id`」当作本地 id：identify 请求带上，响应必须逐字节相同；不得用另一地址的 whoami/identify 响应 id 覆盖选择。
3. whoami 不得写 prefs 的 `token`/`host_id`。无扫码路径在 identify **且**本次 `auth` 成功前不得把记录标成已绑定。

## 实验失败语义（可执行，不能冒充 PASS）

| 实验 | 失败时必须做 | 不得做 |
|---|---|---|
| Android `PeerSnapshot`/`Status` | 无 TS whoami/精确 ID；已绑定可对 QR/`lastTsUrl` 字面地址 identify | 扫 `100.64/10`；把静态提示成功写成自主发现 TS PASS |
| NSD + 组播锁 | 停窗口、释放锁；LAN 只剩 last-good/QR 提示+identify | 把 mDNS 当 TS 发现；静态提示冒充自主发现 LAN PASS |
| tsnet accepted LocalAddr ≠ 100.x | 走已冻结的 dest_ip 替代 MAC | 只比端口；现场改弱 msg |

隔离节点，禁止扫用户 tailnet。未跑保持未验证。

## owning PR 必须落地的测试（未跑，不得标 PASS）

安全（日志只记 IP/HTTP 码/generation，禁止 token）：

1. 伪 DNS-SD / 伪 peer / 伪 whoami：选择真 `host_id` 时零 `AuthFrame`；证明前零 `create`。
2. identify 与 whoami 的 307/308/301：无 token；whoami 不把 `Location` 写成候选 IP。
3. 继电器 dest_ip 两种改写、`bound=0.0.0.0`、只比端口：失败。
4. 遗留 404 四谓词：仅 `scanned_primary`/`persisted_legacy` 真 404 允许一帧 auth；其它来源 404/超时/5xx 无 token；写入 `host_id` 后关闭。
5. 无本地 `host_id`：响应 id 验 MAC 后才写；坏 MAC 不写。
6. TS key ↔ 主机 token 失败；identify 后 `/upload` 无 Bearer 仍 401。
7. 无扫码只入 TS（补 B11 后）：`:9900` whoami 列出主机，选主机后才收 token。

择路 / 数据：

8. identify 过、WS 失败：同代次下一候选；每字面一次 `create`；过期 `auth_ack`（generation 不匹配）不得发布 READY，cancel 必须关掉上一代 in-flight WS。
9. TS 黑洞 + LAN 槽；last-good=100.x + TS Starting。
10. `ts_node_id` 全表命中；cursor 续扫第 9/33。
11. URL-less 冷启 `host_id+port+ts_node_id`；`clear()`；同主机 TS key 变不 `releaseManager`。
12. 同一 Session VM，LAN→TS 后上传 HTTP host = 当前 WS host。
13. PeerLines/NSD 关闭：静态提示可连则连；自主发现项不得 PASS。

回归映射用方案 §10.2–10.5 的既有证据入口；缺跑保持未验证。性能仍先 `envcheck.sh --gate`，禁止 null 旧 baseline JSON。

## 本席未做

未改 DESIGN/GOAL/产品，未催作者返修，未读另一席 v2 复审。产物仅本文件。等两份复审齐后由 leader 统一处理。
