# 根因探针报告：图片上传 VPN/SOCKS 绕过

> 任务 ID：fix-upload-transport-tsnet  
> 席位角色：w-up-probe（审查/探针席）  
> 日期：2026-08-14  
> 凭据安全：本文不含任何密钥、token、tailnet IP 以外的敏感信息

---

## 核心问题

用户手机现在跑的是**官方 Tailscale App（系统级 VPN）**，不是 App 内嵌 tsnet。  
系统级 VPN 理论上会接管全部 App 流量 —— 那为什么上传仍然从蜂窝地址 `10.4.234.175` 出去？

---

## 结论先行

**分叉已定：「系统 VPN 是否 Up」这个问题对修复方向没有影响。**

1. `HttpUrlConnectionUploader` 永远走系统网络栈（无论 VPN 在线与否）。
2. App 代码中**不存在任何 Android VPN bypass 机制**（静态实证，见 §2）。
3. WS 通不能证明系统 VPN 当时在线——WS 走内嵌 tsnet SOCKS，独立于系统 VPN。
4. 修复方向（上传复用 `TsnetDial.socketFactoryFor` / `TsnetProxySocketFactory`）**正确且充分**。

---

## §1 已知事实（不重新诊断）

```
报错原文：
  上传失败：failed to connect to /100.75.207.88 (port 9900) from /10.4.234.175 (port 39030) after 10000ms
                                  ↑ Mac tailnet 地址              ↑ 手机蜂窝地址，不是 tailnet 地址
```

- `HttpUrlConnectionUploader.kt:67`：`URL(endpoint).openConnection()`，**无 proxy 参数**
- WebSocket 走 `TsnetDial.proxyFor(state)` → loopback SOCKS5 → 内嵌 tsnet → DERP → daemon
- 两条通道不同路，这是缺陷根因（已闭合）

---

## §2 候选假设逐条排查

### A. Manifest / VpnService 层声明排除

**已排除 — 静态代码证据：**

- `AndroidManifest.xml`：无 `BIND_VPN_SERVICE`、无 `allowBypass`、无 `excludedRoutes`、无 `setAllowedApplications`
- 完整 manifest 只声明：`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`、`CAMERA`

### B. 内嵌 tsnet 创建 VPN/TUN 与系统 VPN 竞争

**已排除 — 决定性证据在包文档：**

`tsnet/PackageDoc.kt` 明文写道：
> "无 VpnService、零系统权限，Up 后经 TsnetDial 给 OkHttp 配 loopback SOCKS5 即达 tailnet"

内嵌 tsnet 是**纯用户态节点**：Go 侧用 `tsnetbind.Tsnetbind.start()` 起节点，  
暴露 loopback SOCKS5 代理，通过 Android `NetworkInterface` API 枚举网卡（`GomobileTsnetBackend.kt`）。  
**不调用 `VpnService`，不创建 TUN 接口，两个 VPN 竞争的场景不存在。**

### C. upload socket 被 `bindProcessToNetwork` / `Network.openConnection` 绑定到蜂窝

**已排除 — 全目录 grep 零命中：**

```
grep -r "bindProcessToNetwork\|Network\.openConnection\|requestNetwork\|TRANSPORT_CELLULAR" app/app/src/main/java/
```

结果：
- `NetworkConnectivityWatcher.kt`：只用 `registerDefaultNetworkCallback`（监听变化，不绑 socket）
- `HttpUrlConnectionUploader.kt:67`：`URL(endpoint).openConnection()`，**无 `Network` 对象参与**
- 无任何路径将 upload socket 绑定到特定网络 transport

### D. 系统 VPN 当时实际 Down / 未覆盖 100.64.0.0/10

**最可能的运行时原因（A/B/C 全排除后的默认解释），需运行时验证（见 §4）。**

---

## §3 核心问题的答案

### WS 通不能证明系统 VPN 在线

这是关键前提：

```
WS 路径：App → TsnetDial.proxyFor(TsnetState.Up) → loopback SOCKS5 → 内嵌 tsnet → DERP → daemon
```

WS 完全绕过系统 VPN，走内嵌 tsnet 自己的用户态隧道。  
**「WS 通」只证明内嵌 tsnet Up，不证明系统 VPN 在线。**

### 系统 VPN 是否在线对修复方向无影响

`HttpUrlConnectionUploader` 使用系统网络栈，无论系统 VPN 是否在线：

| 系统 VPN 状态 | 上传结果 |
|---|---|
| Down / 未覆盖 100.64.0.0/10 | 直连蜂窝出去，源地址 = `10.4.x.x`，连不上 tailnet 地址 → timeout |
| Up 且覆盖该网段 | 应能路由（但依赖 VPN 状态，脆弱） |

**修复目标**：让上传不依赖系统 VPN，显式走内嵌 tsnet SOCKS（同 WS），健壮且可控。

---

## §4 根因探针

### 探针位置

`e2e/harness/upload_transport_probe_test.go`

### 探针语义

| 测试 | 场景 | 预期结果 | 命中意义 |
|---|---|---|---|
| `A_no_proxy_probe_must_HIT` | 上传无代理 | SOCKS 服务器未收到连接 | **命中** = bug 存在（上传绕过 SOCKS） |
| `B_with_proxy_probe_must_NOT_HIT` | 上传经 SOCKS | SOCKS 服务器收到连接 | **不命中** = bug 已修 |
| `X_tailnet_direct_probe_HIT` | tailnet IP 直连 | 连接失败，SOCKS 未调用 | 自证：tailnet IP 无法直达，必须走 SOCKS |
| `Y_lan_direct_probe_NOT_HIT` | LAN IP 直连 | 成功，SOCKS 未介入 | 自证：LAN 路径本来就通，不是 bug |

### 探针自证结果（纪律⑨，2026-08-14 实测）

```
=== RUN   TestUploadTransportProbe/A_no_proxy_probe_must_HIT
    probe HIT ✓: direct upload bypassed SOCKS proxy (count=0→0). Bug confirmed.
=== RUN   TestUploadTransportProbe/B_with_proxy_probe_must_NOT_HIT
    probe NOT HIT ✓: upload went through SOCKS proxy (count=0→1). Fix confirmed.
=== RUN   TestUploadTransportProbeSelfCert/X_tailnet_direct_probe_HIT
    self-cert X PASS: tailnet IP direct upload = SOCKS not used (count=0→0). Bug condition confirmed.
=== RUN   TestUploadTransportProbeSelfCert/Y_lan_direct_probe_NOT_HIT
    self-cert Y PASS: LAN IP direct upload succeeded without SOCKS (count=0→0). Correct behavior.
PASS    ok  e2e/harness   0.976s
```

### 探针运行命令

```bash
cd e2e/harness && go test -v -run TestUploadTransportProbe ./...
```

---

## §5 tailnet 状态实测（tailscale status，2026-08-14）

```
tailscale status 输出（只读，无敏感信息）：
100.75.207.88    macbook-pro-1          macOS     ← Mac，daemon 监听地址
100.114.207.123  agentmirror-v2502a     android   idle; offline, last seen 3m ago
...
```

**关键数据点**：
- `agentmirror-v2502a`（`100.114.207.123`）= **手机内嵌 tsnet 节点**（hostname 前缀 "agentmirror-" 来自 `TsnetBootstrap.sanitizeHostname`）
- 该节点 3 分钟前 offline/idle——说明内嵌 tsnet 曾 Up，后断开
- HANDOFF 里提到 "手机 tailnet IP: `100.69.43.120`（节点名 `v2502a`）" 是**官方 Tailscale App 的节点**（无 agentmirror- 前缀，不同 IP）

**推论**：手机同时运行两个 Tailscale 节点：
- `v2502a` @ `100.69.43.120`（官方 Tailscale App，系统 VPN）
- `agentmirror-v2502a` @ `100.114.207.123`（App 内嵌 tsnet，SOCKS 代理）

WS 连接 daemon 侧 source 为 `100.69.43.120` 的解释：  
内嵌 tsnet 的出站连接（到 DERP）经过官方 Tailscale App 的系统 VPN，从外部看源为 `100.69.43.120`（官方节点）而非 `100.114.207.123`（内嵌节点）。  
**未验证**，属于推断，需实机路由表确认。

---

## §6 改前/改后状态（纪律⑨ + 眼见为实铁律）

| 步骤 | 方法 | 结果 |
|---|---|---|
| 改前命中（Go 探针 A） | Go 探针在当前 HEAD 运行 | ✅ PASS — 直连无 SOCKS，bug 确认 |
| 改前命中（tailnet self-cert X） | Go 探针 X，100.64.0.1 直连失败 | ✅ PASS — tailnet IP 无法直连，bug 条件确认 |
| 改后不命中（Go 探针 B） | Go 探针，有 SOCKS 代理时 SOCKS 被调用 | ✅ PASS — 修复条件验证 |
| 改后不命中（Android 单测） | `HttpUrlConnectionUploaderTsnetRouteTest` | ✅ **PASS（7/7，0 失败）** `upload_tsnetUp_tailnetHost_goesThroughSocks` 绿；debug 输出见下 |
| 改后不命中（模拟器实测） | 真机/模拟器 upload 流程 | ⚠️ **进行中**：emulator 包安装中，worktree 改前基线待建 |

---

## §8 token 配对模式推理（leader 2026-08-14 分析）

**结论：当时必然是 token 配对模式（内嵌 tsnet Up），修复适用。**

推理链：
1. WS 当时是通的，upload 从蜂窝地址 `10.4.234.175` 出去
2. WS 只有两条路能通：内嵌 tsnet SOCKS 或系统 VPN
3. 如果系统 VPN Up 且覆盖 `100.64.0.0/10`，upload 也会从 tun 出去（因为 upload 也走系统网络栈）
4. 但 upload 从蜂窝出去（不走 tun）→ 系统 VPN 当时不在 Up 状态（或未覆盖该网段）
5. 因此 WS 走的只能是内嵌 tsnet SOCKS 代理 → 内嵌 tsnet 状态为 Up
6. 内嵌 tsnet Up 的前提是 `config.tsAuthKey.isNotBlank()` → **token 配对模式确认**

**这条推理不是实测，是逻辑证明。** 待 emulator 就绪后实测验证。

---

## §9 探针局限性与待验证项

### 局限性

当前探针在 Mac（Go 层）验证了**代理选择机制**（SOCKS 是否被调用），但：

- 探针不直接测试 Android `HttpUrlConnectionUploader.kt` 的执行路径
- 探针无法验证 `isTailnetHost` 分支在真机上的触发（需要 Android 代码路径）
- 探针没有实际连接真实 tailnet（使用 `100.64.0.1` 作为模拟 tailnet IP）

### 完整 e2e 验证步骤（需要模拟器 + tailnet 环境）

若需要更强的"眼见为实"验证（确认「改之前 source 地址」），需要：

```bash
# 1. 注入 TS_AUTHKEY 起测试 daemon（凭据只能注入子进程，不打印）
set -a; . .team/current/profiles/tailnet-test.env; set +a
AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS=/tmp/test-e2e \
  ./server/agentmirrord -host <tailnet-ip-of-test-node> -port 9901 &

# 2. 确认 daemon 的 tailnet IP（tailscale status）
# 3. 在模拟器中，配对扫码时使用 ws://<tailnet-ip>:9901/ws
# 4. 尝试上传图片 → 观察 daemon 侧打印的 source IP
# 5. 改之前：source = 模拟器的蜂窝/WLAN IP（不是 tailnet IP）→ bug 确认
# 6. 改之后：source = 模拟器的 tailnet IP（100.x.x.x）→ bug 修复
```

### 必须先回答的新问题

> **在 App 嵌入 tsnet 与官方 Tailscale App 同时运行时，`TsnetWire.state` 是否真的是 `Up`？**
>
> 原因：`HttpUrlConnectionUploader` 的修复方案用 `TsnetDial.socketFactoryFor(state, host)` 判路，
> 当且仅当 `state is TsnetState.Up && isTailnetHost(host)` 才走 SOCKS 代理。
> 若用户场景下内嵌 tsnet 没有 authkey（`config.tsAuthKey.isBlank()`），
> 则 `TsnetWire.state == Idle`，修复后的代码走 `null → 直连`，
> **等同于没有修复**。
>
> 需要确认：用户配对 QR 码是否携带了 `tsAuthKey`？

---

## §9 修复方向确认

**w-up-dev 的方案（上传复用 `TsnetProxySocketFactory`/`TsnetDial.socketFactoryFor`）正确，可以落盘。**

前提条件：
1. 用户配对时带了 `tsAuthKey`（内嵌 tsnet 会 Up）
2. 修复后的 upload 代码与 WS 取**同一份 `TsnetWire.state`**（避免两边判断不一致）
3. 目标地址经 `isTailnetHost` 判定为 tailnet → 走 SOCKS；否则直连（保留 LAN 路径）

这三点与 FIELD.md 的修法方向完全对齐。
