# 主机自动发现能力补证

> 只读源码/已安装依赖阅读，未编译、未生成或替换 AAR，未启动发现/扫描、未碰 tailnet/生产。证据基线：`corral-core@eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`、`corral-serve@911dd94176a34e11f66b61b15b191216463c48cf`。

## 1. 精确依赖与产物

|对象|事实|
|---|---|
|服务端 Go|`server/go.mod:1-9` 锁 `tailscale.com v1.102.2`、`github.com/coder/websocket v1.8.14`、qrcode；`server/go.sum:237-238` 的 Tailscale h1=`K0TJMOFv0F9aJDSjM/C2uVtrwnLn+ek22c42x61FXeA=`。公开 tag `v1.102.2` 指向 commit `eb67e5dcbe145d63e1128b9b4b630f8a82da101f`。|
|gomobile 工具模块|`tools/tsnetbind/go.mod:1-5,42,57` 同锁 `tailscale.com v1.102.2`，`golang.org/x/mobile v0.0.0-20260803200217-62cee1672c8e`（commit 前缀 `62cee1672c8e`，go.sum h1=`hi1b7Lmv+xvnrjeEv8BoDmU7H/FAOuH7hEHSvjMl2VM=`）。|
|Android 依赖|`app/app/build.gradle.kts:63-66,109-145`：minSdk 26；Compose/Kotlin serialization/OkHttp/CameraX/ZXing/当前本地 `tsnetbind.aar`。exact head 没有提交 Gradle dependency lockfile，版本由该 build 文件显式声明。|
|AAR|`app/app/libs/tsnetbind.aar`（由 exact head 追出，当前仅 `jni/arm64-v8a/libgojni.so`、`classes.jar`、manifest）；`tsnetbind-sources.jar` 的生成 Java API 见本目录 `tmp/capability/generated-all.java:1-133`。|

## 2. upstream tsnet/LocalClient：有 peer 清单，但不是应用服务发现

- `tailscale.com@v1.102.2/tsnet/tsnet.go:356-366`：`Server.Dial`；`:411-425`：`Server.LocalClient() (*local.Client,error)`，若未启动会先启动。
- `tsnet.go:535-584`：`Server.Up(ctx) (*ipnstate.Status,error)` 等到 `ipn.Running`，调用 `lc.Status(ctx)`，返回完整 `Status`；成功还要求至少一个 `TailscaleIPs`，并清空已持久化 ServeConfig/Service advertisements。**Up 返回的是本机入网状态，不是目标服务可达证明。**
- `client/local/local.go:767-787`：`LocalClient.Status(ctx)` 返回完整 `*ipnstate.Status`；`StatusWithoutPeers` 明确去掉 peer 信息。`client/local/local.go:331-344,384-399`：`WhoIs` 可按 IP/IP:port 或 NodeKey 查 peer 所属，未找到返回 `ErrPeerNotFound`。
- `ipn/ipnstate/ipnstate.go:31-86,200-207`：`Status` 有本机 `TailscaleIPs`/`Self`、`Peer map[key.NodePublic]*PeerStatus`，并提供排序的 `Status.Peers()`。
- `ipn/ipnstate/ipnstate.go:233-319`：`PeerStatus` 字段包括 `ID`（StableNodeID）、`NodeID`、`PublicKey`、`HostName`、`DNSName`、`TailscaleIPs`、`Addrs`、`CurAddr`、`Relay`、`Online`、`PeerAPIURL`、`Capabilities`/`CapMap` 等。`HostName` 注释明确“不一定唯一”；`Online` 是控制面在线状态，不是 9900/TCP 或 AgentMirror 服务状态。
- `client/tailscale/apitype/apitype.go:35-43`：`WhoIsResponse` 返回 `tailcfg.Node`、`UserProfile`、`CapMap`。`tailcfg/tailcfg.go:352-430` 的 `Node` 有 `StableID`、MagicDNS `Name`、`Addresses`、`Endpoints`、`Online`；`tailcfg/tailcfg.go:865-940` 的 Hostinfo 含 `Services`。`tailcfg/tailcfg_view.go:601-618` 暴露 `HostinfoView.Services()`，其语义是“该机器广告的服务”，不是本产品 WebSocket 已监听。
- `tsnet/tsnet.go:1835-1885` 有 `Server.ListenService`，但要求 Service 名、tagged node、管理员/ACL approval，且端口须符合 Service 定义；当前项目没有调用它。`Server.Up` 清空 service advertisements 的事实也使它不能作为当前 AgentMirror 标记。

**结论**：v1.102.2 能列出 TS peer 及其地址/节点身份，也能通过 `WhoIs` 获得节点与 Hostinfo service 元数据；只能证明“这是一个 tailnet peer/控制面记录及可能的网络地址”。不能区分“正在提供 AgentMirror”与“仅网络可达”的 peer，仍需要产品自己的非秘密身份响应/认证。`PeerAPIURL` 是 Tailscale PeerAPI，不是 AgentMirror `/ws`。

## 3. 当前 Go/gomobile 包装器实际暴露面

- `corral-core@eaaa...:tools/tsnetbind/tsnetbind.go:29-45,59-80`：`Node` 内部保存 `*tsnet.Server`，`Start` 创建 `Server`，调用 `srv.Up(ctx)` 但丢弃返回的 `*ipnstate.Status`，随后只调用 `srv.Loopback()`，保存并返回代理地址/凭据。
- `tools/tsnetbind/tsnetbind.go:83-114`：导出的仅 `ProxyAddr()`、`ProxyCred()`、`Close()`、静态 `Start(...)`、`SetInterfaceProvider(...)`；没有 `LocalClient`、`Status`、`Peer`、`WhoIs`、`ListenService`。
- exact AAR 生成源码 `tmp/capability/generated-all.java:13-58,69-109`：Java `Node` 仅 `close/proxyAddr/proxyCred`；`Tsnetbind` 仅 `setInterfaceProvider` 与 `start`。AAR 不是 Maven 依赖，`app/app/build.gradle.kts:136-140` 从本地文件引用。
- `tools/tsnetbind/build.sh:1-31`：`gomobile bind -target="$ABIS" -androidapi 26`，默认 `ABIS=android/arm64`，输出覆盖 `app/app/libs/tsnetbind.aar`；缺 `gomobile/gobind` 时分别 `go install ...@latest`（工具版本因此不是完全由 go.mod 锁定），并保留 16KB ELF 对齐 ldflags。
- `corral-serve@911...:internal/tsnetd/tsnetd.go:60-71,97-177,230-262`：服务端 `Group` 把 `*tsnet.Server` 私有化；`Group.Up` 只返回本机 tailnet IPv4，`ListenTailnet` 只按端口建立监听，没有 peer 清单接口。`cmd/agentmirrord/main.go:181-216` 只消费该本机 IP 并合并进 QR candidates。

**扩展边界（事实+建议分开）**：若方案要在 Android 取得 peer 清单，当前必须扩展 `tools/tsnetbind` 的 Go 导出 API，再重新 `gomobile bind` 生成 AAR；应导出扁平、受控的基本类型/结构，而不是直接把 `ipnstate.Status` 作为 Android API（当前包装器注释 `tsnetbind.go:4-7` 已约束 gomobile API 形状）。具体字段、刷新时机、容量上限 = 方案设计，不能从现有 AAR 假定。

## 4. Android NSD/mDNS 与 LAN 组播边界

- exact client tree 对 `NsdManager`、`NsdServiceInfo`、mDNS/Bonjour/zeroconf 没有引用；现有 `multicast` 仅是网卡能力字段：`app/.../GomobileTsnetBackend.kt:99`、`TsnetInterfaceCodec.kt:29-64`。服务端 Go direct requires 也没有 mDNS/zeroconf 包。
- 已安装 `/Users/alauda/Library/Android/sdk/platforms/android-36/android.jar`（`javap` 结果落在 `tmp/capability/nsd-javap.txt:1-53`）显示平台 `NsdManager` API（API 16 起）有 `discoverServices`、`registerService`、`resolveService`；`NsdServiceInfo` 可返回 service name/type、host/host addresses、port、attributes。minSdk 26 因而能调用平台 API，不需新增 Maven 依赖。
- `app/app/src/main/AndroidManifest.xml:29-40` 当前仅声明 INTERNET、ACCESS_NETWORK_STATE、前台服务/通知、CAMERA；没有 `CHANGE_WIFI_MULTICAST_STATE`/`ACCESS_WIFI_STATE`，也没有现有 `WifiManager.MulticastLock`。若方案直接收发 LAN 组播，权限/锁及不同设备行为需专门验证；本轮不假称已可用。
- `NsdManager`/mDNS 是同链路 DNS-SD 发现：可作为 LAN 候选来源，但不会穿过 Tailscale WireGuard/userspace netstack 到另一网段。现有 `NetworkConnectivityWatcher.kt:61-82` 只监听默认网络 `onAvailable` 并转发重连，丢弃 `Network`，不是发现器。TS peer 清单与 LAN NSD 是两条独立来源，不能互相替代。

## 5. 服务端身份、持久状态和旧 v1 数据

- `corral-serve@911...:internal/pairing/token.go:22-60,63-108,111-137`：自动 token 是 16 bytes/128 bit base32，持久于 `<UserConfigDir>/agentmirror/token`，写入 0600 且临时文件改名；重启复用。显式 `config.Token` 不持久化。`internal/config/config.go:34-46,65-70` 只有 token、QR host override、TS auth key，没有 host identity。
- `cmd/agentmirrord/main.go:383-390` 的 hostname 只是 `os.Hostname()`，传给 `tsnet.Server.Hostname`；Tailscale `PeerStatus.HostName` 又明确可能不唯一。TS `StableID` 只在该 TS 节点控制面状态可见时存在，不能自动绑定同一 daemon 的 LAN 地址。
- 服务端 `internal/pairing/qr.go:17-41` 的 v1 payload 真实字段是 `v,url,token,ts_authkey,candidates`，无 `host_id`/签名；客户端 `PairingModels.kt:29-65` 同构。`QrPayloadParser.kt:30-91` 允许未知字段、缺 `ts_authkey` 按空处理；测试 `QrPayloadParserTest.kt:32-56,81-108` 固化了这些旧扫码兼容事实。
- 已配对 Android 存储真实键/迁移在 `PairingConfigStore.kt:46-155`：`url`+`token` 成对；`ts_authkey_encrypted` 为 Keystore AES-GCM，旧明文 `ts_authkey` 读取后迁移，失败则整份配置失效不降级。成功后仍只存一个 `PairingConfig(url,token,tsAuthKey)`，没有 candidates 持久化。旧 QR 的候选自动逐地址发送由 `PairingViewModel.kt:360-467` 实现。
- 现有 `AuthFrame` 只有 token：`core-protocol/.../Frames.kt:146-168`；服务端 `internal/api/ws_handler.go:53-70` 直接 token 校验。主机 token 可稳定地绑定“已认证服务端”，但不能在未验证的发现地址上先发送 token；当前资料不足以从 token 反推出安全公共 host identity。

## 6. 方案边界与未决验证

1. **已确认可复用**：TS `Status.Peer`/`WhoIs` 做 TS 节点与地址元数据，服务端现有 LAN `DetectAddresses`/TS 自身 IP，Android 平台 NSD 做 LAN 候选；但候选必须经过产品 host identity 证明后才可进入主机 token Auth。
2. **不可由现状推出**：Tailscale peer metadata 不证明 AgentMirror 端口；NSD 不跨 TS；`TS Up` 不证明目标主机可达；`HostName`/MagicDNS/StableID 不自动等于 daemon host identity；AAR 目前不能取得 peer 清单。
3. **最小后续实验（本轮未运行）**：在隔离测试节点上仅调用新增包装器的 `LocalClient.Status`/`WhoIs`，观察目标 TS IP 是否映射到 `StableID/DNSName/Hostinfo`。结果 A（有 peer）只证明 TS 节点映射，结果 B（缺失/过期/无权限）要求继续走产品身份发现；两种结果都不能证明 9900 AgentMirror 服务，证据缺口仍是非秘密 app-level identity handshake/service proof。
4. **安全裁定前置**：公共 `host_id` 的来源/轮换、发现广播字段、是否使用 TS peer `StableID`、LAN 身份证明载体、旧 v1 单 URL 兼容策略均保持 unknown；不可把未验证地址变成无条件发 token 的依据。
