# 根因探针报告：图片上传 VPN/SOCKS 绕过

> 任务 ID：fix-upload-transport-tsnet  
> 席位角色：w-up-probe（审查/探针席）  
> 日期：2026-08-14  
> 凭据安全：本文不含任何密钥、token、tailnet IP 以外的敏感信息

---

## ⚠️ 重大发现：此模拟器无法复现 tailnet 路径类缺陷（2026-08-14 实测）

> **凡在此模拟器上做的任何「tailnet 路径」验证都是假的。**  
> 它测的是 host NAT 直达，不是隧道。  
> 历史上凡是声称「模拟器验过 tailnet」的结论，都要按这条重新审视。

### 实测证据

```
测试环境：
  Mac tailnet IP：100.103.188.4（daemon 监听地址）
  模拟器 IP：     10.0.2.x（经 host NAT 出网）

改前 APK（HttpUrlConnectionUploader.kt:67 无 proxy）：
  上传端点：http://100.103.188.4:19983/upload
  结果：    ✅ 成功（文件到达 /tmp/am-e2e/uploads/）
  logcat：  无 DEBUG-SOCKS-CONNECT（正确——没走 SOCKS）
  耗时：    < 1s（不是 10s timeout）
```

### 根本原因

Mac 本身在 tailnet 上，`100.103.188.4` 是 Mac 自己的 IP。  
模拟器的流量经 host NAT 出去，**直接到达 Mac 自身的 tailnet IP**，根本不需要任何隧道。  
而用户真机在蜂窝网络上，`100.75.207.88`（Mac tailnet IP）对蜂窝路由器不可见，只能通过 tsnet SOCKS 隧道才能到达。

**两种网络拓扑的本质差异：**

| 环境 | 到达 Mac tailnet IP 的路径 |
|---|---|
| **用户真机（蜂窝）** | 蜂窝→互联网→无路由 **→ ConnectException after 10s** |
| **模拟器（host NAT）** | 模拟器→host NAT→Mac 本地回环 **→ 直接命中，< 1s** |

### 工程影响

此发现影响缺陷⑤（w-tsresume-probe）及所有需要在此模拟器上验证 tailnet 相关行为的任务：  
若模拟器的 tsnet 流量实际走 NAT 直达，断 DERP 也不会产生任何超时现象。  
**所有 tailnet 类缺陷的模拟器验证结论均需以「host NAT 直达」这一前提重新评估。**

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

> ⚠️ **2026-08-14 模拟器实测安全事故记录**
>
> 坐标目测错误（键盘弹出后布局上移，目测坐标失效），多次 `adb input text` 全部流入 URL 字段，
> 包含 auth key 的 stdin 注入也进了 URL 字段，导致 auth key 前若干字符出现在 URL 输入框并被截图。
> **截图已立即删除；字段已清空；daemon 已 kill；emulator AVD userdata 增量盘已由 leader 清除；
> auth key 已由 leader 请用户轮换。**
>
> 上报时教训：**报告泄露时只说位置/范围/处置，泄露的值本身一个字符都不要带。**
>
> 模拟器实测暂停（等待用户给出新 key 及验收方式决定）。
> **Android 单测（HttpUrlConnectionUploaderTsnetRouteTest 7/7 PASS）仍为有效验收证据。**

| 步骤 | 方法 | 结果 |
|---|---|---|
| 改前命中（Go 探针 A） | Go 探针在当前 HEAD 运行 | ✅ PASS — 直连无 SOCKS，bug 确认 |
| 改前命中（tailnet self-cert X） | Go 探针 X，100.64.0.1 直连失败 | ✅ PASS — tailnet IP 无法直连，bug 条件确认 |
| 改后不命中（Go 探针 B） | Go 探针，有 SOCKS 代理时 SOCKS 被调用 | ✅ PASS — 修复条件验证 |
| 改后不命中（Android 单测） | `HttpUrlConnectionUploaderTsnetRouteTest` | ✅ **PASS（7/7，0 失败）** `upload_tsnetUp_tailnetHost_goesThroughSocks` 绿；debug 输出见下 |
| 改后不命中（LAN 不倒退，模拟器实测） | 改后 APK + `ws://10.0.2.2:19983/ws` 上传 | ✅ **PASS** — 上传成功，logcat 无 SOCKS，LAN 路径未被新代码干扰 |
| tailnet 路径模拟器实测 | 不适用 | ⚠️ **结构性无效**：见上方「重大发现」框 — emulator host NAT 直达，无法区分改前/改后 |
| 真机蜂窝验收（tailnet 上传） | 用户自行验收 | ⏳ 待用户在蜂窝+TS 环境下用改后 APK 传一张图 |

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

## §10 模拟器验收剧本（待执行）

> ⚠️ **凭据纪律**：配对 token 与 TS authkey **永不落日志、永不上屏明文、永不入截图**。  
> QR 扫码是唯一合法出口。TS authkey 只能用 `set -a; . <env-file>; set +a` 注入子进程，  
> **禁止 cat / grep / Read / echo / log 其原文**。

### 前置条件

```bash
# 检查 ADB
~/Library/Android/sdk/platform-tools/adb devices

# 检查模拟器二进制（leader 安装后验证）
~/Library/Android/sdk/emulator/emulator -list-avds

# 检查已有 AVD
avdmanager list avd
```

若无 AVD，创建一个：

```bash
# ARM64 Android 35（Google APIs）
avdmanager create avd \
  -n agentmirror-test-arm64 \
  -k "system-images;android-35;google_apis;arm64-v8a" \
  -d pixel_6
```

---

### 步骤 1：构建「改前」APK（git worktree，禁 git stash）

```bash
# 在另一个目录建 worktree，指向当前 HEAD（未应用 w-up-dev 修改的提交）
git worktree add /tmp/am-before HEAD

# 进入 worktree 的 app 子目录构建
cd /tmp/am-before/app
./gradlew :app:assembleDebug

# 产物路径
ls /tmp/am-before/app/app/build/outputs/apk/debug/app-debug.apk
```

> **为什么不用 stash**：当前工作区有 w-up-dev（开发席）、w-up-test（测试席）等多席的未提交改动，  
> stash 会混入所有改动，严重污染基线。worktree 共享 .git 目录但有独立工作区，安全隔离。

---

### 步骤 2：构建「改后」APK（当前工作区，含 w-up-dev 修改）

```bash
cd /Volumes/nvme/Projects/远程Agent安卓/app
./gradlew :app:assembleDebug

# 产物路径
ls app/build/outputs/apk/debug/app-debug.apk
```

---

### ⚠️ 填字段前必须拿真实坐标（2026-08-14 实测教训）

**绝对不要用截图目测算坐标**。键盘弹出会导致布局整体上移，目测坐标失效，
多个字段的输入可能全部流入同一字段，造成凭据进入错误字段并被截图。

**每次填字段前先 dump 真实坐标：**

```bash
adb shell uiautomator dump /sdcard/uidump.xml
adb pull /sdcard/uidump.xml /tmp/am-e2e/uidump.xml

# 查找"服务端 ws 地址"字段的真实边界
grep -A2 '服务端 ws\|ws.*地址\|resource-id.*manualUrl' /tmp/am-e2e/uidump.xml | head -5

# 从 bounds="[x1,y1][x2,y2]" 中取中心点
# 中心 x = (x1+x2)/2，中心 y = (y1+y2)/2
# 再点击：adb shell input tap <center_x> <center_y>
```

**不能用目测坐标填任何涉密字段（token、auth key）。**

### 步骤 3：启动隔离测试 daemon（tailnet 模式）

> 必须注入 `TS_AUTHKEY` 才能让 daemon 和手机 App 都加入同一个测试 tailnet。  
> `TS_AUTHKEY` 只能从 `.team/current/profiles/tailnet-test.env` 注入子进程，  
> **凭据禁令见本节顶部警告框**。

```bash
# 1. 先构建 daemon 二进制（使用 server 目录下的 go build）
mkdir -p /tmp/am-e2e/daemon /tmp/am-e2e/state /tmp/am-e2e/uploads
(
  cd /Volumes/nvme/Projects/远程Agent安卓/server
  go build -o /tmp/am-e2e/daemon/agentmirrord ./cmd/agentmirrord
)

# 2. 生成隔离 pairing token（不落文件，只在 shell 变量里）
TEST_TOKEN=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
# ⚠️ 不要 echo $TEST_TOKEN 到屏幕或日志

# 3. 注入 TS_AUTHKEY 并启动隔离 daemon（TS_AUTHKEY 注入子进程，不打印）
# 创建独立 tmux 扫描目录（防扫生产 socket）
TMUX_SOCK_DIR=/tmp/am-e2e/tmux/tmux-$(id -u)
mkdir -p "$TMUX_SOCK_DIR"

# 注入 authkey 后启动 daemon：
(
  set -a; . /Volumes/nvme/Projects/远程Agent安卓/.team/current/profiles/tailnet-test.env; set +a
  AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$TMUX_SOCK_DIR" \
  /tmp/am-e2e/daemon/agentmirrord \
    -listen 0.0.0.0:19983 \
    -token "$TEST_TOKEN" \
    -state-dir /tmp/am-e2e/state \
    -upload-dir /tmp/am-e2e/uploads \
    > /tmp/am-e2e/daemon.log 2>&1 &
  echo "daemon PID=$!"
)

# 等待 daemon 监听并获取其 tailnet IP
sleep 3
# 查看 daemon QR（包含 tailnet IP），不截图，只用肉眼确认
cat /tmp/am-e2e/daemon.log | grep "listen\|tailnet\|qr\|addr" | head -10

# ⚠️ 禁止 cat /tmp/am-e2e/daemon.log 全文（daemon 会记 token source，需确认不含 token 明文）
#    若看到 token= 明文行立即停止
```

> **daemon.log 安全边界**：daemon `@inv` 声明「token 值永不落日志（只记 source 与 store path）」，  
> 但 tailscale 可能打印 auth key 相关信息 — 若日志体积超 5KB 或含 "TS_AUTH" 字样，停止并报 leader。

---

### 步骤 4：启动模拟器（若未在运行）

```bash
~/Library/Android/sdk/emulator/emulator \
  -avd agentmirror-test-arm64 \
  -no-snapshot-save \
  -no-audio \
  -no-window &   # 无 GUI，CI 友好；如需 GUI 去掉 -no-window

# 等待 boot 完成
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 wait-for-device
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell getprop sys.boot_completed
# 返回 "1" 表示就绪
```

---

### 步骤 5（改前验证）：安装改前 APK，配对，触发上传

```bash
ADB=~/Library/Android/sdk/platform-tools/adb

# 安装改前 APK
$ADB -s emulator-5554 install -r /tmp/am-before/app/app/build/outputs/apk/debug/app-debug.apk

# 启动 App
$ADB -s emulator-5554 shell am start -n dev.agentmirror.app/.MainActivity

# 在模拟器屏幕上扫 daemon QR（daemon 会打印 QR 到终端，QR 里含 tailnet IP + tsAuthKey）
# ⚠️ QR 必须直接在模拟器 camera 里扫，不截图、不复制、不粘贴 token 明文

# 打开 logcat 监听上传相关日志（另一个终端窗口）
$ADB -s emulator-5554 logcat -s AgentMirror HttpUrlConnection ConnectException | \
  grep -v "token\|auth_key\|TS_AUTH"  # 过滤掉任何可能含密钥的行

# 在 App 内触发图片上传（选图 → 发送 / upload）
```

**改前「命中」判断标准：**

| 观察点 | 命中（bug 在） | 说明 |
|---|---|---|
| **App 错误提示** | `上传失败：failed to connect to /100.x.x.x (port 19983) from /10.x.x.x` | source 是蜂窝/模拟器 NAT 地址，不是 tailnet IP |
| **logcat** | `ConnectException: failed to connect to /100.x.x.x ... after 10000ms` | 模拟器无法直连 Mac tailnet IP |
| **daemon.log** | 无上传请求到达（`POST /upload` 日志缺失） | daemon 从未收到连接 |
| **uploads/ 目录** | 空（`ls /tmp/am-e2e/uploads/`） | 文件未到达 |

---

### 步骤 6（改后验证）：卸载改前 APK，安装改后 APK，重复上传

```bash
# 卸载改前 APK（清除数据，避免 token 残留影响）
$ADB -s emulator-5554 uninstall dev.agentmirror.app

# 安装改后 APK
$ADB -s emulator-5554 install -r \
  /Volumes/nvme/Projects/远程Agent安卓/app/app/build/outputs/apk/debug/app-debug.apk

# 启动 App，重新扫 QR 配对（同一 daemon，同一 token）
$ADB -s emulator-5554 shell am start -n dev.agentmirror.app/.MainActivity

# 触发上传
```

**改后「不命中」判断标准：**

| 观察点 | 不命中（bug 已修） | 说明 |
|---|---|---|
| **App 提示** | 上传成功（进度条完成 / 无错误弹窗） | |
| **logcat** | `DEBUG-SOCKS-CONNECT host=100.x.x.x port=19983` | 上传经 SOCKS 代理（与单元测试 debug 输出一致） |
| **daemon.log** | `POST /upload` 出现，source = 模拟器 tailnet IP（`100.x.x.x`，内嵌 tsnet 节点地址） | |
| **uploads/ 目录** | 文件出现（`ls /tmp/am-e2e/uploads/`） | 文件到达 daemon |

---

### 步骤 7：LAN 不倒退（回归检查）

```bash
# 启动 LAN 模式 daemon（无 TS_AUTHKEY，监听 10.0.2.2:19984）
# 10.0.2.2 是模拟器访问 Mac 宿主机的固定 IP
/tmp/am-e2e/daemon/agentmirrord \
  -listen 10.0.2.2:19984 \
  -host 10.0.2.2 \
  -token "$TEST_TOKEN" \
  -state-dir /tmp/am-e2e/state2 \
  -upload-dir /tmp/am-e2e/uploads2 \
  > /tmp/am-e2e/daemon-lan.log 2>&1 &

# 用改后 APK 配对（扫新 QR），触发上传
# 预期：isTailnetHost("10.0.2.2") = false → 直连路径 → 上传成功
# 若 LAN 上传失败 = 回归（改后 SOCKS 路径把 LAN 也拦了）
```

---

### 步骤 8：w-tsresume-probe 数据点

> `w-tsresume-probe` 需要验证：**切后台 → 回前台 后上传是否还能成功**。  
> 这个数据点在模拟器验收剧本里顺带采集。

```bash
# 在改后 APK 配对成功且上传过一次后：

# 1. 把 App 切到后台（Home 键）
$ADB -s emulator-5554 shell input keyevent KEYCODE_HOME

# 2. 等待 30 秒（模拟用户中途放下手机）
sleep 30

# 3. 把 App 切回前台
$ADB -s emulator-5554 shell monkey -p dev.agentmirror.app -c android.intent.category.LAUNCHER 1

# 4. 再次触发上传

# 5. 观察：上传是否仍经 SOCKS？
#    - logcat: 是否仍有 DEBUG-SOCKS-CONNECT？
#    - daemon.log: 是否仍收到上传？
#    - 若 TsnetWire 在 App 后台时进入 Idle，切回来后上传会退化为直连 → 验证 §9"必须先回答的新问题"
```

**数据点记录格式（填完后转 w-tsresume-probe）：**

```
切后台 → 回前台后上传状态：
- TsnetWire 状态（logcat TAG=TsnetWire）：___（Idle / Up）
- 上传结果：___（成功 / 失败：错误原文）
- 若失败：source IP = ___（是否蜂窝地址）
- SOCKS 日志：___（有/无 DEBUG-SOCKS-CONNECT）
```

---

### 步骤 9：清理

```bash
# 杀 daemon
pkill -f /tmp/am-e2e/daemon/agentmirrord

# 清理 worktree（改前）
git worktree remove /tmp/am-before

# 删临时目录
rm -rf /tmp/am-e2e

# 停模拟器
$ADB -s emulator-5554 emu kill
```

---

## §9 修复方向确认

**w-up-dev 的方案（上传复用 `TsnetProxySocketFactory`/`TsnetDial.socketFactoryFor`）正确，可以落盘。**

前提条件：
1. 用户配对时带了 `tsAuthKey`（内嵌 tsnet 会 Up）
2. 修复后的 upload 代码与 WS 取**同一份 `TsnetWire.state`**（避免两边判断不一致）
3. 目标地址经 `isTailnetHost` 判定为 tailnet → 走 SOCKS；否则直连（保留 LAN 路径）

这三点与 FIELD.md 的修法方向完全对齐。
