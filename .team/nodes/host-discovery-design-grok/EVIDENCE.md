# 主机自动发现方案：证据索引

席位：`host-discovery-design-grok`。只读基线对象 + 已有摸底。未编译、未跑设备、未扫 tailnet、未改产品。

## 基线

- core：`eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`
- serve：`911dd94176a34e11f66b61b15b191216463c48cf`
- 需求：`requirement-base/entries/101-主机自动发现与TS优先路由.md`
- 摸底：`.team/nodes/host-discovery-explore-luna/EXPLORATION.md`、`CAPABILITY-EVIDENCE.md`
- 方案：`.team/stable-pr/host-auto-discovery/DESIGN.md`

## 事实（源码）

- `Connection.onOpen` 立即 `AuthFrame(token)` → 身份证明必须发生在 `transport.create` 之前。
- `Handler()` 仅 `/ws`、`/upload`；upload 要 Bearer token，不能当发现通道。
- `attemptConnect` 固定 `config.url`；`start`/`pump`/`onNetworkAvailable`/前台 RECONNECTING 都进这里；READY 前台不重建。
- QR v1 无 `host_id`；解析器未知字段忽略、非 v1 拒绝、无 url 拒绝。
- `PairingConfig` 只有 url+token+tsAuthKey；candidates 不持久化；旧 VM 会对候选发 token。
- AAR 无 peer API；upstream tsnet v1.102.2 有 `LocalClient.Status`/`Peer`。
- 客户端无 NSD 引用；平台 `NsdManager` 存在；清单无组播权限。
- 服务端有 key 时 `group.Up` 失败即进程退出。
- `SessionViewModel` 上传基址是构造期快照，不跟随后续拨号。
- `ConnectionPath.label` 现为 `LAN`/`tailnet`。
- 架构工具目录缺失：unknown，不报 PASS。

## 设计决定（见 DESIGN.md）

- 发现：LAN = DNS-SD `_agentmirror._tcp` + NSD；TS = `PeerLines` 有界清单。
- 身份：`POST /pair/identify` HMAC-SHA256(token, 域分隔消息含 host_id/nonce/bound)；通过后才 WS auth。
- QR 保持 v=1 加可选字段；旧主 url 保留给旧 App。
- 配对成功 = 主机记录落盘，不拨号。
- 择路只在 `DialTarget` → `attemptConnect`；不抢占 READY。
- 上传读 live `ServiceWire.uploadBaseUrl`。
- 新 App 禁止旧 candidates 喷 token；旧 daemon 仅扫码主 url 遗留白名单。
- 不上 PKI、不改业务帧、不用 ListenService。

## 有界未知（未跑）

1. Android 内 `PeerLines`/`Status` 是否可用。
2. NSD + MulticastLock 实机/模拟器行为。
3. tsnet listener `LocalAddr` 是否等于客户端拨的 100.x。
4. 架构工具路径仍缺。
