# 自建 DERP（广州）· 部署件

> ## ✅ 已部署并验证（2026-08-14 01:40）
> ```
>                         min      avg      max
> 部署前（洛杉矶中继）     394.3   1221.0   2298.4 ms
> 部署后（广州中继）        32.6    122.7    344.1 ms
>                         ──────────────────────────
>                          12×      10×      6.7×
> ```
> `tailscale status` → `relay "gz"`；`netcheck` → `Nearest DERP: Guangzhou 50.2ms`（lax 184.4ms）。
> 原始快照：`e2e/artifacts/cellular-ts-baseline/snapshot-04-gz-derp.txt`
>
> **实际落地与本文预案的三处差异（后继照这里，不照下文原稿）**：
> 1. **端口是 8444 不是 8443** —— 8443 被那台机器上的 nginx 占用。
> 2. **证书用 `CertName` 指纹绑定，不是 `InsecureForTests`** ——
>    derper 启动时会自己打印正确的 DERPMap 片段（含 `sha256-raw:` 指纹），照抄即可。
>    这比 `InsecureForTests` 好：自签也有防中间人能力。
> 3. **derper 需要两个额外 flag**：`-c <配置路径>`（新版必填，存节点私钥）
>    与 `-http-port -1`（否则它抢 :80，被 nginx 占着会启动失败）。
> 4. 最后一段延迟是用户自己调端口后收敛的（部署时曾短暂 `no-derp-connection`）。


> 2026-08-14 leader 编写。**用户明早回四个问题后即可执行。**
> 目标：把 Tailscale 中继从洛杉矶（184ms）搬到广州（同省），让手机 ↔ Mac 的 RTT
> 从 390–960ms 降到几十毫秒量级。

---

## 〇、为什么是这条路（结论先行）

今晚实测把其它路全排除了：

| 方案 | 结论 | 依据 |
|---|---|---|
| 打洞直连 | **结构上不可能** | `MappingVariesByDestIP: true`（对称 NAT）。NAT 在楼里/运营商那层，用户公寓是网线直入、**没有光猫可改桥接**，小米路由 WAN 口是 `10.0.0.122` 私网 |
| 路由器端口映射 / UPnP | **不够** | UPnP 已开且 `PortMapping: UPnP, NAT-PMP, PCP` 三种全可用，但只能在小米这一层开洞，外面那层开不了 |
| IPv6 | **拿不到** | `IPv6: no, but OS has support`；路由器「未检测到IPv6信息」，楼里网络不下发 |
| 关掉代理 | **无关** | Shadowrocket 完全退出后仍 `MappingVariesByDestIP: true`、仍中继 |
| **自建国内 DERP** | **可行，且是唯一剩下的** | 广州腾讯云 `43.136.53.247` 有公网 IP，用户在深圳，同省 |

公共 DERP 实测（Mac 侧，2026-08-14 01:02）：
`lax 184ms / sfo 188ms / sea 189ms / hkg 217ms / sin 288ms` ——
**这台机器的国际线路整体很差，连香港都 217ms。** 域内自建是数量级的改善。

---

## 一、执行前必须拿到的四件事（阻塞项）

| # | 事项 | 为什么阻塞 | 谁能给 |
|---|---|---|---|
| ~~1~~ | ~~SSH 接入~~ **已解决** | 密钥 `/Users/alauda/Documents/code/安卓claude_code_开源框架/guangzhou.pem`；**端口是 `52222` 不是 22**（skill 文件里写的 22 是错的，见 §六） | ✅ 2026-08-14 已连通 |
| 2 | **Tailscale ACL 写权限** | 自建 DERP 必须在 tailnet ACL 加 `derpMap`，那是管理台网页操作。**我们存的 `tskey-auth-` 不够——authkey 只能让节点加入，不能改 ACL** | 用户给 `tskey-api-` 开头的 API key（带 ACL 写），或明早自己贴 JSON |
| 3 | **腾讯云安全组开两个端口** | derper 需要 TCP（HTTPS）+ **UDP 3478**（STUN）。默认安全组只开 22/443 | 用户在腾讯云控制台开 |
| 4 | **端口与证书方案确认** | `:443` 被 `claude-chat.service` 占用，且 skill 红线明写不可改。见下 §2 | 用户拍板 |

### 关于 #4 的两个选项

**A. 域名 + Let's Encrypt（推荐）**
用户有 `team-agent.net`。加一条 `derp.team-agent.net` → `43.136.53.247`，
derper 用 `-certmode letsencrypt` 自动签发。
- 优点：标准做法，客户端零特殊配置
- 注意：域名指向大陆 IP 涉及备案；**非标端口一般不触发**，但这是用户自己的合规判断

**B. IP + 自签（不需要域名）**
derper 用 `-certmode manual`，derpMap 里给该节点标 `"InsecureForTests": true`。
- **安全上可接受**：DERP 只中转**已被 WireGuard 端到端加密**的包，它读不到内容；
  TLS 只是外层防护。这也是 Tailscale 自己文档承认的用法。
- 优点：不碰域名和备案

---

## 二、部署步骤（拿到上面四件事后执行）

### 2.1 交叉编译 derper（在 Mac 上做，不占那台机器的内存）

那台机器只有 1.9GB RAM 且**没有 swap**，已经跑着 node + claude 子进程（峰值 200–400MB）。
**不要在上面装 Go 编译**——用 Mac 交叉编译后 scp 过去。

**连接参数（2026-08-14 实测确认）**：

```bash
KEY=/tmp/guangzhou.pem   # 从 /Users/alauda/Documents/code/安卓claude_code_开源框架/guangzhou.pem 拷来，chmod 600
SSH="ssh -p 52222 -i $KEY ubuntu@43.136.53.247"     # ← 端口 52222，不是 22
SCP="scp -P 52222 -i $KEY"                          # ← scp 用大写 -P
```

机器实测：`x86_64` / 2 核 / 1963MB 内存（可用 1329MB）/ 未装 tailscale 与 derper。

```bash
# Mac 上交叉编译（不占那台机器的内存）
GOOS=linux GOARCH=amd64 go install tailscale.com/cmd/derper@latest
$SCP "$(go env GOPATH)/bin/linux_amd64/derper" ubuntu@43.136.53.247:/tmp/derper
$SSH 'sudo install -m755 /tmp/derper /usr/local/bin/derper && rm /tmp/derper'
```

### 2.2 systemd unit

参照那台机器已有的 `claude-chat.service` 风格（它用 `AmbientCapabilities` 而不是 setcap，
因为 node 升级会丢 cap——同样的道理这里不需要，8443 不是特权端口）。

```ini
# /etc/systemd/system/derper.service
[Unit]
Description=Tailscale DERP relay (Guangzhou)
After=network-online.target
Wants=network-online.target

[Service]
User=derper
Group=derper
# 方案 A（域名 + Let's Encrypt）：
ExecStart=/usr/local/bin/derper -hostname derp.team-agent.net -a :8443 -stun -stun-port 3478 -certmode letsencrypt -certdir /var/lib/derper/certs
# 方案 B（IP + 自签）：把上一行换成
# ExecStart=/usr/local/bin/derper -hostname 43.136.53.247 -a :8443 -stun -stun-port 3478 -certmode manual -certdir /var/lib/derper/certs
Restart=on-failure
RestartSec=3
StartLimitBurst=5
StartLimitIntervalSec=60
StateDirectory=derper
ProtectSystem=strict
ProtectHome=true
NoNewPrivileges=true
ReadWritePaths=/var/lib/derper

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd -r -s /usr/sbin/nologin derper 2>/dev/null || true
sudo mkdir -p /var/lib/derper/certs && sudo chown -R derper:derper /var/lib/derper
sudo systemctl daemon-reload && sudo systemctl enable --now derper
sudo systemctl status derper
```

⚠️ **方案 A 的 Let's Encrypt 走的是 TLS-ALPN-01 挑战，需要 :443 可达** ——
而 :443 被 claude-chat 占了。所以方案 A 实际要么：
- 用 HTTP-01 挑战另配（复杂），或
- **直接选方案 B**（自签），这也是我倾向的：省掉域名、备案、443 冲突三个问题。

**建议：先用方案 B 跑通验证，确认收益后再决定要不要折腾域名。**

### 2.3 自签证书（方案 B）

```bash
ssh -i <PEM> ubuntu@43.136.53.247 'sudo openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout /var/lib/derper/certs/43.136.53.247.key \
  -out    /var/lib/derper/certs/43.136.53.247.crt \
  -days 3650 -subj "/CN=43.136.53.247" \
  -addext "subjectAltName=IP:43.136.53.247" && \
  sudo chown -R derper:derper /var/lib/derper/certs'
```

（这台机器上已有同样套路的自签证书 `~/claude-server/server/tls/server.crt`，SAN=IP。
**不要复用它** —— 那份归 claude-chat，混用会让证书轮换互相牵连。）

### 2.4 腾讯云安全组（用户操作）

| 协议 | 端口 | 用途 |
|---|---|---|
| TCP | 8443 | DERP over HTTPS |
| **UDP** | **3478** | **STUN（端点发现，打洞靠它，别漏）** |

### 2.5 Tailscale ACL 加 derpMap（用户贴，或用 API key）

管理台 → Access controls，在顶层 JSON 里加：

```json
{
  "derpMap": {
    "OmitDefaultRegions": false,
    "Regions": {
      "900": {
        "RegionID":   900,
        "RegionCode": "gz",
        "RegionName": "Guangzhou",
        "Nodes": [
          {
            "Name":     "900a",
            "RegionID": 900,
            "HostName": "43.136.53.247",
            "IPv4":     "43.136.53.247",
            "DERPPort": 8443,
            "STUNPort": 3478,
            "InsecureForTests": true
          }
        ]
      }
    }
  }
}
```

**`OmitDefaultRegions: false` 是故意的** —— 保留公共 DERP 作为兜底。
广州这台挂了还能回落到洛杉矶，不会整个 tailnet 断联。
（等它稳定运行一段时间后再考虑是否设 `true` 强制只用自建。）

**自定义 RegionID 必须 ≥ 900**，这是 Tailscale 的约定，别用小号。

方案 A（域名）则把 `HostName` 换成 `derp.team-agent.net` 并删掉 `InsecureForTests`。

---

## 三、验证清单（改完立刻跑，判据是硬的）

```bash
# 1. derper 活着且端口在听
ssh -i <PEM> ubuntu@43.136.53.247 'sudo systemctl is-active derper; sudo ss -tlnp | grep 8443; sudo ss -ulnp | grep 3478'

# 2. Mac 侧：新区域出现在 netcheck，且延迟是域内量级
/Applications/Tailscale.app/Contents/MacOS/Tailscale netcheck > /tmp/nc.txt 2>&1
grep -E 'Nearest DERP|gz:' /tmp/nc.txt
#    期望：出现 `gz: <20ms 量级 (Guangzhou)`，且成为 Nearest DERP

# 3. 与手机的实际路径与延迟
/Applications/Tailscale.app/Contents/MacOS/Tailscale status > /tmp/st.txt 2>&1
grep v2502a /tmp/st.txt
#    期望：`relay "gz"`（或运气好直接 direct）
ping -c 20 -i 0.3 100.69.43.120
#    期望：avg 从 390–960ms 降到几十毫秒
```

### 判据

| 指标 | 现在 | 目标 | 不达标说明 |
|---|---|---|---|
| netcheck 里 `gz` 区域延迟 | 不存在 | **< 30ms** | 大于这个说明广州到深圳的线路也不好，或安全组没开 |
| `tailscale status` 的 relay | `lax` / `sfo` | **`gz`** | 仍是 lax 说明 ACL 没生效或 derper 不可达 |
| 手机 ↔ Mac RTT avg | 390–960ms | **< 100ms** | 达不到就看 derper 日志与两端到广州各自的 RTT |

**任一项不达标就 halt 报告，不要自己调参数往下试** —— 今晚已经因为
「改了→没效果→再改」的循环浪费了三轮，每轮都带着「是不是没生效」的二义性。

---

## 四、今晚踩过的坑（部署时别重蹈）

1. **Shadowrocket 的 TUN 会本地代答 ICMP** —— 经默认路由 ping 任何地址都返回
   0.3–0.9ms、TTL=64。**测真实 RTT 必须 `ping -b en0` 绕开 TUN**，
   或确认 Shadowrocket 已完全退出。今晚测广州得到 0.5ms 就是这个假象。
2. **Shadowrocket 的引擎读的是编译产物不是源配置库** ——
   改了 `Documents/Databases/*.db` 后必须点「编译配置」，
   否则「配置→规则」里看得到、`测试规则` 和引擎却用旧的。
3. **`tailscale` 等命令在当前 Claude Code 环境里输出回终端会报框架自身的错**
   （`claude native binary not installed`），**重定向到文件再读就正常**。
4. **`grep -i tailscale` 一个"偏好设置"plist 会把 authkey 打上屏** —— 已发生，
   已请用户轮换。查任何配置前先想凭据。
5. **`nc -z` 扫端口在 Shadowrocket 开着时全是假的** —— 它的 TUN 对所有 TCP
   先本地应答再决定路由，`62222`、`36000` 这种不存在的端口也报"开着"。
   与坑 1（ICMP 本地代答）同源：**任何经 TUN 的连通性探测都不可信。**
6. **`guangzhou-app-server` skill 里的连接参数有两处与实际不符**（非本项目文件，未改，仅记录）：
   - `Port: 22` → 实际 **52222**
   - Key 源文件 `/mnt/f/code/安卓claude_code/guangzhou.pem`（WSL 路径）→ 本机实际
     `/Users/alauda/Documents/code/安卓claude_code_开源框架/guangzhou.pem`
   今晚为此耗了两轮排查。**建议用户回头订正那份 skill。**

---

## 五、执行顺序（明早）

1. 用户回四个问题（§1）
2. 交叉编译 + scp + systemd（§2.1–2.3）—— 我做
3. 用户开安全组两个端口（§2.4）
4. 用户贴 ACL 或给 API key（§2.5）
5. 跑验证清单（§3）—— 我做，三条判据逐条报
