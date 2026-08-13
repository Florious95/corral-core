# TS 链路性能指导（w-perf-ts · 顾问席交付）

> 日期：2026-08-13 22:54–23:10（所有实测均带时刻，纪律②）。
> 席位：w-perf-ts（一次性顾问席，只出指导不施工，零代码改动）。
> 任务：perf-ts-link-guidance。用户裁定：「在 v6 版本优化性能」「核心是优化 tailscale 的链路」
> 「性能优化要有基线和指标」「你们复现不了也是因为网络好」。
> 方法：只读实测（lsof / netstat / arp / STUN 探针 / zerotier-cli / ping）+ 引用既有审计文档。
> **未触碰生产 daemon、未触碰用户 tmux、未读任何密钥原文。**
> 每个数字都标了作用域与产地（纪律③⑨）；推断与实测分开标注（纪律①）。

---

## §0 一句话结论

**「直连还是 DERP」这个问题的前提今晚被实测推翻：用户到 daemon 的活跃连接走的不是
Tailscale，是 ZeroTier；本次生产 daemon 的 tsnet 是关闭的（LAN-only 降级）；主机上
系统级 Tailscale 已卸载。** 而且如果将来重新启用 tsnet，它会被本机 Clash TUN 劫持默认
路由而半残（实测：通配绑定 UDP 经海外代理出口，稳态 +124ms、尖刺 0.5–1.2s、对称 NAT
行为 → 打洞必败 → 必上 DERP）。ZeroTier 能活是因为它显式绑定物理网卡绕开了 Clash。

优化的第一步不是改任何代码，是**问用户一句话 + 一个 5 分钟的蜂窝网实测**（§4），
确定真实远程链路是 ZT 还是 tsnet、以及远程时是 DIRECT 还是 RELAY。

---

## §1 链路诊断结论（实测，附命令原始输出）

### 1.1 主机侧：本次生产 daemon 是 LAN-only，tsnet 未启用

daemon 进程（`pgrep -fl 'server/agentmirrord'` → pid 70317，二进制 08-13 00:25 编译）
的全部网络 socket（lsof 只读观察，交接文档 §0 批准的核验方式）：

```
$ lsof -nP -p 70317 | grep -E 'TCP|UDP'          # 2026-08-13 22:54
agentmirr 70317 alauda  7u IPv6 ... TCP *:9900 (LISTEN)
agentmirr 70317 alauda  8u IPv6 ... TCP 10.202.81.20:9900->10.202.81.184:59564 (ESTABLISHED)
```

- **零 UDP socket、零控制面/DERP 连接** → tsnet 未启动。代码印证：`tsnetd.New` 在无
  `TS_AUTHKEY` 时降级 LAN-only（`server/internal/tsnetd/tsnetd.go`，ErrTailnetDisabled 契约）。
  本次 daemon 由 `.team/prod-daemon-launch.sh` 拉起，启动环境显然没有 TS_AUTHKEY。
- tsnet 状态目录 `~/Library/Application Support/agentmirror/tsnet/` 最后写入
  **8月9日 23:50**（`tailscaled.state` mtime）→ 服务端 tsnet 上一次真正入网是 08-09。
- 主机上**系统级 Tailscale 已不存在**：`/Applications/Tailscale.app` 不存在（
  `/usr/local/bin/tailscale` 是指向它的坏包装脚本，执行报 No such file），无 tailscaled
  进程，`ifconfig` 无任何 100.x 接口。**所以本文无法给出 `tailscale status/netcheck`
  原始输出——这台机器上当前不存在任何可查询的 Tailscale 节点，这本身就是主机侧判定结论。**

### 1.2 那个活跃连接是谁：用户手机，走 ZeroTier

`10.202.81.20` 是本机 `feth3056` 接口（MTU 2800、feth 对编号差 5000——ZeroTier
MacEthernetTapAgent 的标志性特征，进程实证）：

```
$ pgrep -fl -i zerotier                            # 22:55
531 zerotier-one
43361 /Library/Application Support/ZeroTier/One/MacEthernetTapAgent 3056 06:1c:8c:12:07:d5 2800 5000
$ zerotier-cli info
200 info d128b0dfe4 1.16.2 ONLINE
$ zerotier-cli listnetworks
200 listnetworks 633e31d8a2a4cd06 rabbit 06:1c:8c:12:07:d5 OK PRIVATE feth3056 10.202.81.20/24
$ zerotier-cli peers
<ztaddr>   <ver>  <role> <lat> <link>   <path>
633e31d8a2 1.16.2 LEAF     299 DIRECT   35.206.108.191/23014   ← 自建 ZT 控制器（GCP）
778cde7190 -      PLANET   266 DIRECT   103.195.103.66/9993    ← ZT 根服务器（潜在中继）
a062381409 1.16.0 LEAF     191 DIRECT   192.168.31.57/9994     ← 手机（当前在家，经局域网直连）
cafe04eba9 -      PLANET   260 DIRECT   84.17.53.155/9993
cafe80ed74 -      PLANET   212 DIRECT   185.152.67.145/9993
cafefd6717 -      PLANET   209 DIRECT   79.127.159.187/9993
```

**`10.202.81.184` = ZT 节点 `a062381409` 的身份是数学验证的**，不是猜的：ZeroTier 的
虚拟 MAC 由 nwid+节点号确定性推导（首字节 `(nwid&0xfe)|0x02`，后五字节 = 节点号逐字节
XOR nwid 的第 2–6 字节）。nwid `633e31d8a2a4cd06` + 节点 `a062381409` 推导出
`06:6d:c6:9a:cc:38`，与 ARP 实测逐字节相同：

```
$ arp -an -i feth3056                              # 22:56
? (10.202.81.184) at 6:6d:c6:9a:cc:38 on feth3056
验算：a0^cd=6d  62^a4=c6  38^a2=9a  14^d8=cc  09^31=38   → 五字节全中
```

该节点物理路径当前是 `192.168.31.57`（家中局域网，Android 随机化 MAC）→ **用户此刻在家，
ZT 路径 DIRECT，底下是同一个 WiFi**——与「局域网很流畅」吻合（在家时 ZT 只是包个壳）。

**推论（标注为推断，非实测）**：App 侧 WS 经 tsnet SOCKS 只发生在 tsnet Up 且目标是
tailnet 地址时；`10.202.81.20` 不是 tailnet 地址，daemon 也没有 tailnet 监听 → 这条连接
是 App 经系统网络栈直拨、由手机上的 ZeroTier VPN 承载。**即当前会话里我们自己的 tsnet
代码整条不在数据路径上。**（本工程已有「以为在 TS 上、其实不在」的先例
`fix-upload-transport-tsnet`——这是同一形状的第二例，这次是整个 WS 主通道。）

### 1.3 手机侧 tsnet：无法从主机判定（诚实标注）

角色文件要求两端各判。手机侧 tsnet 是嵌入式用户态节点，其直连/中继状态**主机侧无从
观测**，且 App 无任何状态暴露（`docs/ts-link-baseline.md` ① 已查证 Kotlin 层零暴露）。
当前判定：**手机侧 tsnet 大概率根本未参与数据路径**（见 1.2 推论）；确证需要 §4 的
一步用户配合。**此项按 NOT_MEASURED 处理，不默认通过。**

### 1.4 「如果重新启用 tsnet」：主机侧会被 Clash TUN 半残（实测支撑）

本机默认路由被 Clash Verge（mihomo，进程实证 clash-verge-service）的 TUN 接管：

```
$ netstat -rn -f inet | head                       # 22:57
default            link#24            UCSg     utun4     ← 第一默认路由 = Clash TUN
default            192.168.31.1       UGScIg   en0       ← 物理路由只剩接口作用域
```

tsnet 的 magicsock 是**通配绑定** UDP，出包按默认路由走 → 全部进 Clash。我用同样通配
绑定的 STUN 探针实测了这条路径（即 tsnet 将经历的真实条件）：

```
$ python3 stun_probe.py                            # 22:58，同一 socket 问多个服务器
stun.l.google.com:19302  -> mapped 82.21.81.136:41242  rtt=1453ms
stun.cloudflare.com:3478 -> mapped 82.21.81.136:35899  rtt=1965ms
derp1e/derp13.tailscale.com:3478 -> NO RESPONSE        （主机名或代理丢包，不作结论）
VERDICT: endpoint-DEPENDENT mapping (端口随目的地变化) -> 对称 NAT 行为，打洞基本必败
稳态复测（连续 6 次问 Google STUN）：1217 / 123 / 126 / 127 / 510 / 124 ms
$ curl https://api.ipify.org（默认路由）→ 82.21.81.136   （海外代理出口）
$ curl --interface en0 https://api.ipify.org      → 超时   （家宽直连出境不可达）
```

**结论**：主机侧 UDP 经 Clash 海外出口，稳态 +~124ms、首包/尖刺 0.5–1.5s、映射端口随
目的地变化（对称 NAT 行为）。**tsnet 若启用：打洞几乎必败 → 必上 DERP；而 DERP 的
TCP-over-HTTPS 又要再过一遍 Clash 代理 → 双重穿代理 + TCP 队头阻塞**——这与用户描述的
「数据分批慢慢来、看得见每个中间状态」高度吻合，且解释了为什么同一套代码在别人家里
测不出来（纪律：网络条件与用户一致是复现前提）。
**ZeroTier 不受此害的原因（实测）**：它显式绑定物理网卡，绕开默认路由：

```
$ netstat -an -f inet -p udp | grep 9993
udp4  0  0  192.168.31.116.9993   *.*        ← 绑定 en0 实地址，不走 utun4
```

### 1.5 家里 WiFi 本身的抖动（补充实测，含警告）

```
$ ping -c 5 192.168.31.57   （手机物理地址）   # 22:59
round-trip min/avg/max = 70.9 / 162.0 / 259.0 ms
$ ping -c 5 10.202.81.184   （同一手机的 ZT 覆盖地址）
round-trip min/avg/max = 19.1 / 73.3 / 162.0 ms
```

**警告（不要过度解读）**：测时手机很可能熄屏，Android WiFi 省电模式会把 RTT 抬到
100–300ms；两组数不构成「ZT 比物理还快」的悖论（不同时刻、省电状态漂移）。它说明的
只是一件事：**「用户在家、亮屏使用」时的 RTT 是一个尚未测过的数**，任何把「家=低延迟」
当默认前提的推理都要先补这个数。

### 1.6 §1 判定汇总

| 问题 | 判定 | 依据 |
|---|---|---|
| 主机侧 Tailscale 直连还是 DERP | **不适用：无 Tailscale 节点存在**（daemon LAN-only，系统 TS 已卸载） | 1.1 |
| 用户当前远程链路 | **ZeroTier（rabbit，自建控制器）**，此刻在家 DIRECT-via-LAN | 1.2 |
| 手机侧 tsnet | NOT_MEASURED（大概率不在数据路径上） | 1.3 |
| 用户在外（蜂窝）时 ZT 是 DIRECT 还是 RELAY | **NOT_MEASURED——这是新的「一击定胜负」问题**，ZT 根服务器离本机 209–266ms，一旦 RELAY 与用户主诉完全吻合 | §4 |
| tsnet 若重启用的前景 | 被 Clash TUN 劫持 → 半残（实测支撑的强结论） | 1.4 |
| 「基于 ts」指什么 | **待用户一句话裁定**（已请 leader 转问） | — |

---

## §2 基线与指标定义

既有规范（`docs/performance-baseline-spec.md` 的 P1–P8、`docs/ts-link-baseline.md`、
`docs/roundtrip-audit.md`）仍然有效，本节不重复它们，只做**链路维度的订正与收口判据**。

### 2.1 网络条件轴（订正：原规范只有 0ms / 400ms 两档，缺真实档）

每个指标的一个数值必须挂在下面某一档上，**档位不同的数字不可比**：

| 档 | 定义 | 怎么获得 | 作用 |
|---|---|---|---|
| C0 对照 | 模拟器 + 本机 daemon，0ms | 现有 e2e | 排除渲染/逻辑回归的对照组 |
| C1 实验室慢链 | `e2e/delay_proxy.py` 400ms + ~20KB/s 限流 + RCVBUF 2048 | 现有工具（第四版才有真背压，动它前读文件头） | 可重复的失败态基准，棘轮卡门用 |
| C2 真实·在家 | 用户手机、家中 WiFi、ZT 包壳 | 用户正常使用 + 服务端仪表 | 用户说「流畅」的那个条件的真值 |
| C3 真实·在外 | 用户手机、蜂窝网、远程链路（ZT 或 tsnet，待 §4 判定） | §4 的 5 分钟实测起步 | **用户主诉发生的条件，一切优化的最终判据** |

### 2.2 指标（每条：对应体感 / 探针位置 / 作用域）

**链路本体（新增，本文档的增量）：**

| 指标 | 对应体感 | 探针位置 | 作用域标注 |
|---|---|---|---|
| L1 路径类型（DIRECT/RELAY） | 「在外面就闪」的总开关 | ZT：两端 `zerotier-cli peers` / 手机 App peers 页；tsnet：需先做 §3-D1 状态暴露 | 每端各测，单点时刻值 |
| L2 链路 RTT p50/p95/max | 每个往返要付几次的单价 | 主机 ping 手机覆盖网地址；或 App 侧 WS ping-pong 打点 | 按网络档分开记 |
| L3 单条 WS 消息端到端（daemon 写出→App onMessage） | 「一段字多久蹦出来」 | 需两端打点（时钟不同源，只看差值分布不看绝对值） | per-connection |
| L4 中间状态个数 | 「看得见每个中间状态」= 闪烁本体 | 机器眼时间算子（`test/framework/machine_eye/`）数一次响应内的可见状态数 | 每场景每次采样 |

**应用往返（沿用 roundtrip-audit，不重列）**：P1 冷启动 4 往返、进会话 2 往返+4.7KB、
捏合 10–20 往返、翻页每页 126KB、发消息 2 往返（已理论最优）。

**服务端仪表（已写好未部署，`server/internal/api/sendq_metrics.go`）**：
`conn.*`（单连接）与 `total.*`（进程累计）双前缀，`deltas_dropped` / `snapshots_from_*` /
`queue_peak` / `frames_sent`，连接拆除记 `close_reason`。**引用任何计数前先看前缀定作用域
（纪律⑨的来历就是把 total 读成 conn）。装它需要重启 daemon（断线几秒），等用户点头。**

### 2.3 出处可比性（硬性）

每个数字必须带五元组：**build sha / 设备 / 网络档（C0–C3）/ 前置状态（会话历史规模、
亮屏与否、在家与否）/ 采样时刻+样本量**。缺任一 → 记 `NOT_COMPARABLE`。
今天七轮取证测错对象的教训：**先核「测的是不是用户那个版本 + 工具本身有效 + 网络条件
一致」，再谈数字**（本文 1.5 的 ping 就自带了「熄屏未知」的产地警告作为示范）。

### 2.4 棘轮与判红

- 首版基线**只记录不判好坏**（leader 已裁定：现在定门限就是编数字；推算耗时曾错两个
  数量级，实测 6.6ms vs 推算数百 ms）。
- 有两轮数据后：**改善即收紧**（改进落地并实测后，把该指标基线更新为新值）；
  之后任何一次采样劣化超过「基线 + 2×历史波动幅度」判红。
- **NOT_MEASURED 默认判失败**：没测到 ≠ 很快。每次测量配阳性对照（故意注入 500ms
  必须被测出来），否则该轮全部数据作废。
- C1（delay_proxy）是唯一进 CI/巡检的卡门档；C3 是真值但不可自动化，每次优化落地后
  人工走一轮 §4 清单。

---

## §3 优化方向（按 期望收益 / 风险 / 验证难度 排序）

**排序原则**：先把「链路整体在哪」搞对（十倍级），再省往返（数百 ms 级），再省字节和
感知（次级）。凡未实测的收益一律标【假说】。

### D1【收益最大 · 零代码或极少代码 · 先做】把远程链路钉在「直连」上

- **改什么**：分两支，等 §4 判定后只做命中的那支——
  - **ZT 支**：若蜂窝下 RELAY → 排查两侧打洞条件（家宽 NAT 类型、蜂窝 CGNAT、ZT
    controller 的成员配置）。ZT RELAY 走 209–266ms 外的根服务器，能扳成 DIRECT 就是
    一次「几百 ms → 物理 RTT」的十倍级改善。**依赖对端配合：用户装置上的 ZT 设置 +
    路由器（如开 UPnP/端口映射）；我们出诊断步骤和判据。**
  - **tsnet 支**：若用户确实要用内嵌 tsnet → **先解决 1.4 的 Clash 劫持**（Clash 规则
    给 tailscale 控制面/DERP/WireGuard UDP 直连豁免，或 tsnet 侧绑定 en0 实地址）。
    不解决这个，tsnet 的一切协议层调优都是给错误前提做微调。**依赖对端配合：Clash
    是用户自己的软件，改它的分流规则是用户操作；我们只出规则清单。**
- **为什么体感变好**：用户主诉的一切（分批到、看见中间状态）都随 RTT 线性放大；路径
  类型是 RTT 的最大单因子。
- **怎么证明**：改前改后各跑一轮 §4 清单（路径类型 + RTT p50/p95 + 一次会话的 L4
  中间状态数），同五元组产地。
- **风险**：动的是用户网络环境不是我们代码——错误配置可能断他的既有连接。**只出
  step-by-step 指导让用户自己动手，且每步可回退。**

### D2【已有数字支撑 · 中收益 · 我们自己就能做】delta 背压合并（不丢、合帧）

- **改什么**：`ws_conn.go` sendMirror 的 `sendCh` 满时不再丢 delta，改为并入待发缓冲，
  队列排空后 flush 成一个大 delta 帧（≤1MiB）。设计与语义安全性已在
  `docs/ts-link-baseline.md` 查证（字节序逐字节等价，AnsiParser 顺序状态机）。
- **为什么体感变好**：慢链上 chunk 一个个到、各自触发一次渲染 = 用户看见每个中间状态；
  合并后「本来就在排队的」中间状态在服务端聚成一帧 → **中间状态个数下降而延迟零增加**
  （合并的是已经在等的东西，不引入定时器）。链路无关：ZT/tsnet/LAN 都受益，慢时才生效。
- **怎么证明**：红测=合并前后客户端收到字节流逐字节相同（不依赖网络）；效果=C1 档
  机器眼数 L4 中间状态个数，改前改后对比。
- **风险**：低（空闲路径行为不变=零回归面）；注意与 `deltas_dropped` 仪表语义联动
  （丢弃路径没了，计数器含义要改）。【收益量级未实测=假说；机制与安全性已查证】

### D3【往返审计已定档 · 大收益 · 我们自己就能做】捏合 resize 风暴 10–20 往返 → 1

- raw/041 早有裁定「捏合中只本地预览、松手才发一次 resize」，从未实现
  （fix-pinch-preview-commit）。C1 档实测判据与注意事项见 `docs/roundtrip-audit.md` §三。
- **风险提示**：今天捏合预览/提交那版被用户判「比 v6 更差」回退过——重做前先读
  `docs/reverted-to-v6/` 对应 patch 与失败原因；且捏合注入器在新 AVD 上是坏的
  （工具自证先行）。

### D4【已实测有效但回退过 · 中收益】进会话双快照消除（几何持久化）

- 实测收益已锚定：`conn.snapshots_from_resize` 2→0，省 1 往返 + 1.3KB + 1 次整屏重建。
- **为什么回退了**：引入首帧渲染异常（内容挤在左侧一条），两个假说均被证伪后按事先
  上限整条回退——**根因未明，不是「改法错了」而是「没查清为什么坏」**。
- **重做前置**：先修「名义 vs 实测字格宽」（`docs/nominal-vs-measured-cell-width.md`，
  用户报过 4 次的右列截断同根因）。补丁在 `docs/reverted-to-v6/geometry-persist.patch`。

### D5【中收益 · 我们自己就能做】冷启动 4→2 往返 + 翻页免每页 126KB

- 均已在 `docs/roundtrip-audit.md` 完成代码级查证（一-A 节：零协议改动，subscribe 不依赖
  list；翻页用 `#{history_size}` 替代全量 capture，语义已实测 62==62/1998==1998）。
- 注意：翻页改动落在 scrollback 路径上，D-36 虽被搁置但根因未明——改前领 leader 裁定。

### D6【感知层 · 假说 · 排最后】客户端侧「让中间状态不被看见」

- 与 D2 是同一目标的另一条路：D2 在服务端减少中间状态的**产生**，D6 在客户端减少其
  **可见**（如把 <一帧间隔内连续到达的 delta 合成一次渲染）。
- **风险**：任何客户端渲染缓冲都可能伤打字回显手感（发消息回显是 2 往返理论最优，
  不能再加人为延迟）；v6 时代的「整屏重写抑制」三次证明零收益且一次让 App 全黑。
  **在 D2 效果量化之前不动这条**。【纯假说】

### 传输层存目（低优先级，前提未定不展开）

tsnet gVisor 缓冲/MTU 调优、SOCKS5 逐连接多绕一跳的代价——**只在 §4 判定用户真用
tsnet、且 D1 的 Clash 豁免完成后才有意义**；届时先测后调（ZT 路径 MTU 2800 已够大，
Go 服务端 TCP_NODELAY 默认开启，小帧 Nagle 问题在服务端不存在——此句为代码常识级
推断，若要引用请先以抓包实证）。

---

## §4 第一步做什么（一小时内拿到第一批数）

**一个 5 分钟的用户实测 + 两条主机命令，同时回答三个悬而未决的问题**
（哪条链路 / DIRECT 还是 RELAY / 远程 RTT 是多少）：

1. **用户带手机切到蜂窝网**（关 WiFi），正常打开 App 进一个会话，用 30 秒。
2. **同一时刻主机侧跑**（只读，不碰 daemon）：
   ```bash
   zerotier-cli peers | grep a062381409     # 看 <link> 列：DIRECT 还是 RELAY，<lat> 列 RTT
   lsof -nP -iTCP:9900 -sTCP:ESTABLISHED    # 看客户端连接来自 10.202.81.x 还是别的
   ping -c 20 10.202.81.184                 # 蜂窝下 ZT 覆盖网 RTT 分布
   ```
3. **用户顺手回答一句**：手机上装的是 ZeroTier App 还是用 App 内嵌 Tailscale 配对？
   （手机 ZT App 的 peers 页也能看到 DIRECT/RELAY，截图更好。）

**判读树**：

| 观测 | 结论 | 下一步 |
|---|---|---|
| 连接来自 10.202.81.x 且 peers 显示 RELAY | 主诉坐实：蜂窝下走 ZT 根服务器中继（209–266ms 级） | 全力做 D1-ZT 支 |
| 连接来自 10.202.81.x 且 DIRECT，但 RTT 高/抖 | 链路是直连但质量差 | 记 C3 基线，转 D2（减中间状态） |
| 连接不来自 10.202.81.x | 存在第三条路径，本文判定要修订 | 先弄清那是什么 |
| 用户答「用内嵌 Tailscale」 | tsnet 支激活 | 先做 D1-tsnet 支（Clash 豁免），daemon 带 TS_AUTHKEY 重启（需用户点头，断线几秒） |

产出物：把三条命令的原始输出按 §2.3 五元组记入 `docs/ts-link-baseline.md` 的「待测」
第 1 条下面——这就是 C3 档的第一批基线数。之后（同样需用户点头）装 sendq 仪表到生产，
C2/C3 档的服务端侧数据开始自动积累。

---

## 附：本席测量自证清单（纪律②③⑨）

- 所有命令在 2026-08-13 22:54–23:10 于 daemon 主机执行；结论时效以此为界。
- STUN 探针脚本本身先经 Google/Cloudflare 双服务器交叉验证（都返回同一公网 IP、
  不同端口），工具有效性自证；对 derp*.tailscale.com 无响应**不作结论**（主机名
  未核实 + 代理路径可能丢包，二义不消）。
- ping 数字带「熄屏状态未知」产地警告（1.5）。
- 「手机=a062381409」为 MAC 推导数学验证（五字节全中）；「当前连接经手机系统栈而非
  tsnet SOCKS」为推断（已标注）。
- 未读任何密钥/token/日志原文；未向生产 daemon 发送任何请求。
