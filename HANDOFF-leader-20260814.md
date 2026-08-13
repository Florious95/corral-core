# HANDOFF · leader · 2026-08-14

> 写给**刚接手、没看过过程**的人。代号首次出现即解释，路径 / sha / pid / 端口写全。
> 前三份交接：`HANDOFF-leader-20260812.md`、`HANDOFF-leader-20260812-night.md`、
> `HANDOFF-leader-20260813.md`。**四份都别删，但这份优先级最高**——
> 20260813 那份里「等用户答三件事」的状态已全部作废，见 §2。

---

## §0 compact 后先做什么

### 一句话现状

**网络链路这条线昨夜收工了，成果是十倍**：自建广州 DERP 上线，
手机↔Mac 平均 RTT 从 1221ms 降到 123ms。
**接下来是三个纯 App 缺陷**，跟网络无关。其中一个还等用户一句确认。

### 开口第一句（对用户说）

> 「广州 DERP 还活着（`systemctl is-active` = active、开机自启已设），
> 你昨晚测到的 ~50ms 稳住了吗？
> 今天按计划做三个 App 缺陷，顺序是：① 捏合后右列跑屏幕外（根因最硬）
> → ② 重进 CLI 输入框跑中间（有三次失败前科，走回炉）
> → ③ 上滑投送到远端。
> **③ 还等你一句确认**：你说的是「上滑手势要送到远端终端、等价于在那个界面滚鼠标滚轮」，
> 对吗？确认了我就派单。」

### 必读清单（按优先级，全绝对路径）

1. **本文**
2. `/Volumes/nvme/Projects/远程Agent安卓/CLAUDE.md` —— 工程铁律（派单五步、眼见为实、回炉流程、凭据红线）
3. `/Volumes/nvme/Projects/远程Agent安卓/docs/derp-guangzhou-deploy.md` —— **广州 DERP 部署件 + 四个测量陷阱**（§四那节比部署步骤更值钱）
4. `/Volumes/nvme/Projects/远程Agent安卓/docs/nominal-vs-measured-cell-width.md` —— 缺陷①（右列截断）的根因
5. `/Volumes/nvme/Projects/远程Agent安卓/docs/d38-three-attempts-postmortem.md` —— 缺陷②（输入框跑中间）三版失败复盘
6. `/Volumes/nvme/Projects/远程Agent安卓/docs/cellular-ts-optimization.md` —— 链路实测与代码侧排序（**§3 有一节「⚠️ 订正」，以订正为准**）
7. `/Volumes/nvme/Projects/远程Agent安卓/docs/c1-brief.md` —— C1 三席共同简报（含今日全部纪律）
8. `/Volumes/nvme/Projects/远程Agent安卓/HANDOFF-leader-20260813.md` —— 昨日交接（**其「等用户答三件事」已作废**）

### 恢复动作

```bash
cd /Volumes/nvme/Projects/远程Agent安卓

# 1. leader 绑定（pane 变了就要重认领，否则 add-agent/send 会被 owner gate 拒）
.team/ta claim-leader --confirm

# 2. 生产 daemon —— 2026-08-14 01:45 核实仍在跑，pid 70317，监听 *:9900
#    停了才需要拉：
bash .team/prod-daemon-launch.sh -host 192.168.31.116
#    核：lsof -nP -iTCP:9900 -sTCP:LISTEN

# 3. 广州 DERP —— 01:45 核实 active + enabled，不需要动
ssh -p 52222 -i /tmp/guangzhou.pem ubuntu@43.136.53.247 'sudo systemctl is-active derper'
#    密钥：/Users/alauda/Documents/code/安卓claude_code_开源框架/guangzhou.pem → cp 到 /tmp 并 chmod 600
#    ⚠️ 端口是 52222 不是 22（guangzhou-app-server skill 里写的 22 是错的）

# 4. 席位（5 个历史席 DEAD，3 个 C1 席在线但任务已结）
.team/ta status
```

---

## §1 身份与不变量

### 角色边界

- **leader 只编排不亲做**。本轮有一次越界：直接改了用户 Shadowrocket 的 SQLite 配置库
  （已备份、已验证、方向后被证明无关）。**下次这类"改用户自己软件的配置"应先做隔离实验再动手。**
- 跨 team 转交用全名：框架问题直投
  `/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader`

### 客观核对，不凭自报（本轮实证）

- **测试席的红测抓到了开发席自报「全绿」的实现里一个真 bug** ——
  ref 切换时整条流的字节被丢、内容还串到另一条流。**自报全绿 ≠ 真绿。**
- **顾问席 w-perf-ts 的 §1 整节作废**：它把 22:54 那半小时的 ZeroTier 快照
  写成了常态结论，又用 STUN 探针代替真实流量下结论。
- **leader 自己也错过两次**：① 说「Shadowrocket 那条线整个是错的」是过度更正
  （它确实在路径里，只是不是打不通的原因）；② 把 utun4 认成 Clash，
  实为 Shadowrocket（`scutil --nc list` 才是权威）。

### 环境已知坑（**这四条会让人反复误判，务必先读**）

1. **Shadowrocket 的 TUN 本地代答 ICMP** —— 经默认路由 ping 任何地址都返回
   0.3–0.9ms、TTL=64。**测真实 RTT 必须 `ping -b en0`**，或确认 Shadowrocket 已退出。
2. **同源问题：`nc -z` 扫端口全报"开着"** —— `62222`、`36000` 这种不存在的端口也"开着"。
   **任何经 TUN 的连通性探测都不可信。**
3. **Shadowrocket 引擎读编译产物不读源配置库** —— 改 `Documents/Databases/*.db` 后
   必须在 App 里点「编译配置」，否则「配置→规则」看得见、引擎却用旧的。
4. **Claude Code 命令行环境不稳**：某些命令输出直接回终端会报框架自身的错
   （`claude native binary not installed`），**重定向到文件再用 Read 工具读就正常**。
   本轮 `tailscale` / `scutil` / `.team/ta send` / `grep` 都中过。

### 工具的已知缺陷

- **`e2e/delay_proxy.py` 迭代了四版**：前三版只加转发延迟**不产生背压**
  （daemon 写入瞬间完成进代理的 OS 接收缓冲）。终版靠 ~20KB/s 限流 +
  `SO_RCVBUF` 缩到 2048 才造出真背压。**动它前先读文件头。**
- **捏合注入器在新 AVD 上完全不生效**（工具坏的，不是被测对象好）。缺陷①②要真机验。
- **macOS loopback 的 TCP 自动调窗让「对端慢读」造不出背压** ——
  对端完全不读，writer 照样能推 4MB+。**任何靠慢读的黑盒背压测试都是无效的**（C1 探针实证）。

---

## §2 排期与封存令

### 用户裁定（原文，仍生效）

> 「整体需全量回退到 v6 版本……**现在修改策略，在 v6 版本优化性能，无论是服务端还是 app**」
> 「**性能优化要有基线和指标**」「**核心是优化 tailscale 的链路**」
> 「我在本地局域网很流畅……基于 ts 就有（闪烁）。**你们复现不了也是因为网络好**」
> 「只修缺陷零新功能」（更早的裁定，仍生效）

### 已闭环

- **链路优化**：广州 DERP 上线，1221ms → 123ms（§3）。**这条线收工。**
- **C1（delta 背压合并）**：关卡 1 判定「队列不会满」，**不上线**，留档（§4.1）。
- **仓库卫生 + 账目审计**：跟踪文件 72832 → 2433，跟踪体积 3.39 GB → 114.6 MB；
  看门狗在途数 7 → 0（01:45 核实仍为 0）。

### 当前排期（用户 2026-08-14 亲口给的三条）

**这三条是纯 App 缺陷，跟网络无关。** 顺序按「把握度」排，不是按用户说的顺序：

| 序 | 缺陷 | 根因状态 | 为什么排这个位置 |
|---|---|---|---|
| ① | 捏合后右列文字跑到屏幕外 | **已闭合** | 用户报过 4 次；两个席位独立撞上同一根因；补丁已存 |
| ② | 重进 CLI 时输入框跑到屏幕中间 | **已闭合** | 根因清楚但**三版实现全退**，必须走回炉流程 |
| ③ | 上滑没投送到远端 | **定义刚变，需先设计** | 需要一条全栈都不存在的能力，且**等用户确认** |

**约束：同一 Gradle 模块同一时刻只放一席施工。三条都在 `app/app`，必须串行。**

---

## §3 P0 / 插队项：链路优化（已完成，但它压掉了原排期）

### 现象与根因（全部实测，非推断）

用户主诉「慢链上看得见每个中间状态」的闪烁。**根因是网络，不是渲染。**

**打不通直连的真原因**（今晚逐条排除）：

| 方案 | 结论 | 实测依据 |
|---|---|---|
| 打洞直连 | **结构上不可能** | `MappingVariesByDestIP: true`（对称 NAT）。用户公寓**网线直入、没有光猫可改桥接**，小米路由 WAN 口是 `10.0.0.122` 私网 —— NAT 在楼里/运营商那层 |
| 路由器端口映射 / UPnP | 不够 | UPnP 已开且 `PortMapping: UPnP, NAT-PMP, PCP` 三种全可用，但只能在小米那层开洞 |
| IPv6 | 拿不到 | `IPv6: no, but OS has support`；路由器「未检测到IPv6信息」，楼里不下发 |
| 关掉 Shadowrocket | **无关** | 完全退出后仍 `MappingVariesByDestIP: true`、仍中继 |

### 止血 + 根治

1. **装官方 Tailscale**（原先跑在 Shadowrocket 内置里）→ 打洞成功过一次，1221ms → 147ms，但守不住。
2. **自建广州 DERP** → 稳定拿到十倍。

```
                        min      avg      max     丢包
起点（洛杉矶公共中继）  394.3   1221.0   2298.4    0%
现在（广州自建中继）     32.6    122.7    344.1    0%
                        ──────────────────────────
                         12×      10×      6.7×
```

**核实状态（2026-08-14 01:45 亲测）**：
`systemctl is-active derper` = **active**；`is-enabled` = **enabled**；
`tailscale status` → `relay "gz"`；`netcheck` → `Nearest DERP: Guangzhou 50.2ms`（lax 184.4ms）。
原始快照：`e2e/artifacts/cellular-ts-baseline/snapshot-04-gz-derp.txt`

### 广州 DERP 的运维参数（后继要能接管）

| 项 | 值 |
|---|---|
| 机器 | 腾讯云广州 `43.136.53.247`，2 核 / 1963MB RAM / **无 swap** / 磁盘 7GB 余 |
| SSH | **端口 52222**（不是 22），user `ubuntu`，密钥 `/Users/alauda/Documents/code/安卓claude_code_开源框架/guangzhou.pem` |
| 服务 | `derper.service`，`systemctl status/restart derper`，日志 `journalctl -u derper` |
| 端口 | **TCP 8444**（8443 被那台机器的 nginx 占）+ **UDP 3478**（STUN） |
| 证书 | 自签，`/var/lib/derper/certs/43.136.53.247.{crt,key}`，10 年 |
| ACL 绑定 | `CertName: sha256-raw:92f3b9d993e883143985c101e900f7ef3e0b63fe69128cdaa70ce6ab3522f850` |
| ACL 内容 | `docs/derp-guangzhou-acl.json`（RegionID 900，`OmitDefaultRegions: false` 保留公共 DERP 兜底） |

**derper 的三个非显然 flag**（缺一个就起不来）：
`-c /var/lib/derper/derper.key`（新版必填，存节点私钥）、
`-http-port -1`（否则抢 :80，被 nginx 占着会反复重启）、
`-certmode manual -certdir ...`。

**同机红线**：那台机器 `:443` 跑着 `claude-chat.service`（另一个项目的服务），
**不要碰**，它的 PORT=443 是硬红线（中国出境对非标 TLS 端口劫持已实证）。

### P0 对原排期的扰动（显式提醒）

**链路这条线占了整晚，三个 App 缺陷一行代码都没动。** §2 里那三条全部未开工。
另外 **C1（delta 背压合并）本来是「代码侧第一优先」，被链路修复推翻了** ——
往返单价从 1.5–1.8s 降到 ~0.15s 后，C1 和 C2 的收益都缩水一个数量级。

---

## §4 在途未收尾任务

### 4.1 C1（perf-delta-backpressure-merge）—— 已判定不上线，**待 leader 收口归档**

**这是唯一有未提交代码的任务，必须先处理，否则后继会以为工作区脏了。**

- **判定**：`w-c1-probe` 关卡 1 结论 **「sendCh(cap 256) 不会满」** →
  合并永不触发 → **C1 不该上线**。
  数字：LLM 流式 400–500 B/s 时 `queue_peak=1`、84 帧零缓冲；
  即使对端不读 + 2KB 窗口 + 2000 行突发，`queue_peak` 也只到 **2**。
- **三席状态（01:45 核实）**：`w-c1-probe` 空闲、`w-c1-dev` 空闲、`w-c1-test` 工作中
  （它在写最后的 evidence）。**全部已 halt 并交付，没有卡死，不要去干预。**
- **证据**：`.team/evidence/perf-delta-backpressure-merge-test.json` = `halted`；
  `perf-delta-backpressure-merge.json` 已落盘（**status 字段待补，probe 写的格式与其它不同**）。

**未提交的 16 项工作区改动，逐条怎么处置**：

| 文件 | 性质 | 建议 |
|---|---|---|
| `server/internal/api/ws_conn.go` | **C1 实现**（多 ref 缓冲 + pendingWake） | **不提交**，留档 |
| `server/internal/api/sendq_metrics.go` / `_test.go` | C1 计数器（DeltasBuffered 新增、DeltasDropped 保留） | 同上 |
| `server/internal/api/ws_conn_merge_test.go` | C1 单测（dev 写） | 同上 |
| `server/internal/api/delta_merge_scenario_test.go` | **红测**（test 席写，抓到了真 bug） | **值得单独提交留档** |
| `test/cases/delta_merge_bytes.test.js` | e2e 字节等价闸 | 同上 |
| `e2e/harness/c1_sendq_probe_test.go` | 关卡 1 探针 | **值得提交**，它是「队列不会满」的证据 |
| `docs/c1-delta-backpressure-merge-impl.md` | dev 实现留档 | 提交 |
| `docs/c1-probe-production-steps.md` | 生产取数步骤 | 提交 |
| `.team/evidence/perf-delta-backpressure-merge*.json` | 证据 | 提交 |
| `docs/cellular-ts-optimization.md` / `docs/ts-link-perf-guidance.md` / `.team/evidence/perf-cellular-ts.json` | 顾问席自己回来订正的（C1 前提被推翻） | 提交 |
| `docs/wiki/t3-report.md` | archwiki 生成物 | 跟着走 |
| `e2e/artifacts/c1-TestC1QueueFillsWhenWriteBlocks.daemon.log` | 探针跑出的隔离 daemon 日志 | 提交或删，无所谓 |

**还有一件 dev 请示未答**：它在改 `ws_conn.go` 时，`archwiki --strict-t3` 暴露了一处
**存量** `@inv` 契约标签缺失（`restoreOnce`，非它引入），它补上了。
**这是独立于 C1 的有效修复，即使 C1 不上线也该留** —— 但要**单独一个提交**（纪律⑦）。
**已核**：`cd server && go build ./...` 通过，未提交代码不污染构建。

**C1 留下的三条认知资产（比代码值钱）**：
1. `pendingWake` 时序陷阱：合并缓冲若只靠 writeLoop 每轮触发 flush，
   sendCh 排空后 writer 阻塞在 `<-sendCh`，**缓冲里最后一段永远发不出去**。
   解法是让「入缓冲这个动作本身」当唤醒源（零延迟，非定时器）。
2. ref 隔离 bug：单缓冲在 ref 切换时 seal 失败会丢掉第二条流，**内容还会串**。
3. macOS loopback 自动调窗 → 纯黑盒造不出背压（见 §1）。

### 4.2 缺陷① 捏合后右列文字跑到屏幕外 —— **未开工，把握最大，建议先做**

- **根因已闭合**：`presenter.cellWidth` 恒为**名义值 10**（只有捏合会改它，
  `measureCells()` **从不回写**），而绘制按**实测 cellW ≈ 11px** 步进 ——
  上报给服务端的 cols 按名义值算、绘制按实测值走，**两套栅格永不收敛**。
- **证据强度高**：用户报过 4 次；今日由 `w-dev-cols` 与 `study-web-terminal-model`
  两个席位从两个方向**独立**撞上同一结论。
- **文档**：`docs/nominal-vs-measured-cell-width.md`
- **已回退的补丁**：`docs/reverted-to-v6/horizontal-grid-convergence.patch`（62630 字节）。
  **不建议直接 apply** —— 那版是在别的上下文里写的，先读再决定。
- **证据文件**：`.team/evidence/fix-cols-grid-convergence.json`（status = `reverted_no_deliverable`）
- **验收红线**：捏合注入器在新 AVD 上是坏的 → **必须真机实测**，
  且必须「改前复现 + 改后看到修复 + 不倒退」三件齐（眼见为实铁律）。

### 4.3 缺陷② 重进 CLI 时输入框跑到屏幕中间 —— **未开工，必须走回炉流程**

- **根因已闭合**：回前台时 IME 仍在屏上，`onRealViewportChanged` 重算并上报，
  把**被挤压的几何**当成了永久基线；而 `onViewportSizeChanged`（IME 收起）
  按 `fix-ime-no-resize` 不再上报 → 挤压值成为永久基线。
- **实测数据**：`bottomMarginPx=106`（健康值 6），5 秒后仍稳定；
  用户真机 1123px ≈ 56 行，而 140（视口行）− 84（已绘行）= 56，**数值精确吻合**。
- **三版失败复盘**：`docs/d38-three-attempts-postmortem.md`
  （v1 两值取自不同时刻；v2 `imeBottom` 恒为 0 因为 Compose 的 `imePadding()`
  作用在兄弟节点上、终端 Box 是被布局挤小的；v3 改用 Compose 事件源 → 引入黑屏闪）
- **流程红线**：**三次失败前科 → 必须走 CLAUDE.md 的「回炉」流程**
  （回退 → 审查席从回退的 diff 反推根因产出**根因探针** → 回退后跑探针验证诊断
  → 三席并行 → 修完再跑探针）。
- **补丁**：`docs/reverted-to-v6/d38-viewport-restore.patch`（65467 字节）

### 4.4 缺陷③ 上滑要投送到远端当滚轮 —— **等用户一句确认，未开工**

- **用户 2026-08-14 的新定义（原话）**：
  > 「我在屏幕里面向上滑的时候，我向上滑的这个行为**没有投放到这个界面**。
  > 也就是说我向上滑，要**类似于我在这个界面鼠标滚轮也向上滑**，它才能配合看到上面的内容。」
  > 「之前所有的**假的复现、假的修改正确**，全部都是它本身从上往下加载了大量的 CLI 的内容，
  > 因此我才能上上滑。」
- **这个定义推翻了前四轮的全部工作** —— 我们一直在修「App 本地缓冲怎么滚」和
  「服务端 scrollback 分页怎么对齐」，而用户要的是**把手势送到远端终端**。
- **现状已核（grep 实证）**：
  ```
  App 往服务端发过滚轮/鼠标事件？   零处，从来没有
  服务端 input 路径支持什么？        只有 send-keys（按键/文本）
  bridge 有没有 copy-mode/滚动？     没有
  ```
  **整条链路上不存在「把滚动送到远端」这个能力。**
- **卡在哪**：leader 已问用户确认这个理解，**用户尚未回答**。
- **范围提醒**：严格说这不是「修缺陷」而是**补一条从来没有的能力**
  （手势 → 滚轮事件 → 协议 → 服务端 → tmux，跨四层），
  与「只修缺陷零新功能」的封存令有张力。**leader 的判断是该做**
  （终端里滚轮看历史本就是终端的基本行为），但**要用户点头**。
- **相关证据**：`.team/evidence/fix-scrollback-history-d36.json`（status = `refuted_by_user`）；
  注意 `fix-scrollback-d36.json` 是 `pass_scoped_server_side_only`（**只覆盖服务端坐标平移那一段，不关闭 D-36**）。

### 4.5 服务端未部署改动（低优先，但别忘了）

`server/internal/api/sendq_metrics.go` 等仪表 + `agentstate` 锚点修复
（`fix-agentstate-anchor-region`，status = `pass_unit_tests_pending_deploy`）**至今未部署**。
生产 daemon 二进制编译于 **08-13 00:25**，不含这批改动。
部署需重启 daemon（断线几秒），**要用户点头**。

---

## §5 运维与外部

### 进程与端口（2026-08-14 01:45 核实）

| 对象 | 状态 | 核实方式 |
|---|---|---|
| 生产 daemon | **pid 70317**，`-host 192.168.31.116`，监听 `*:9900` | `pgrep -fl 'server/agentmirrord'` + `lsof -nP -iTCP:9900` |
| 广州 derper | **active + enabled** | `ssh -p 52222 ... systemctl is-active/is-enabled derper` |
| Mac 的 tailnet IP | **`100.75.207.88`**（旧节点 `100.103.128.78` 已 offline，建议管理台删掉） | `tailscale status` |
| 手机 tailnet IP | `100.69.43.120`（节点名 `v2502a`） | 同上 |

### 资源约束

- 广州机器：1963MB RAM，**无 swap**，磁盘 82% 已用（7GB 余）。
  **不要在上面装 Go 编译 derper** —— 用 Mac 交叉编译后 scp（本轮就是这么做的）。
- 本地仓库：跟踪 2433 文件 / 114.6 MB；`.git` 仍 1.1 GB
  （**历史里的 3.1GB 副本没清，决定见下**）。

### 已记录的决定：不重写 git 历史

量过代价：`.team/evidence/` + `docs/` + `taskbook.yaml` 里有 **54 个真实 commit sha 引用**，
重写会全部失效。`.team/evidence/` 是任务状态的唯一权威，
**它的 54 处引用比 800MB 值钱**，何况本机无远端，1.1GB 今天不值钱。
**开源发布时再做**，届时用 `git-filter-repo` 的 `commit-map` 机械替换（当前未安装）。

### 外部通告

无。跨 team 通道见 §1。

---

## §6 安全约束（原文保留，不可弱化）

- **密钥只存在于 `.team/current/profiles/*.env`，任何席位禁止读其原文。**
- **`.team/current/profiles/tailnet-test.env` 全员禁读（含 leader）。** 里面是用户 tailnet 的
  auth key，只能通过 `TS_AUTHKEY` 环境变量注入测试节点，任何形式的 cat/grep/plist/Read 都禁止。
  取值只用 `set -a; . <file>; set +a` 注入子进程，不打印、不落日志、不入截图。
- **查任何配置前先想凭据**：`grep -i tailscale` 一个"偏好设置"文件就把 authkey 打上了屏
  （2026-08-13 实发，**已请用户轮换，但用户裁定保留该 key 供测试节点用**）。
  同类禁令：无过滤 `ps aux`（暴露席位 API key）、
  `tail .team/logs/agentmirrord-prod.log`（daemon 明文打配对 token）。
  **Shadowrocket 的偏好 plist 与 `tailscale_keys.bin` 列入禁读。**
- 配对 token 与 TS authkey 同级——不落日志、不上屏明文、不入截图，QR 是唯一合法出口；
  TS authkey 传入只经 `TS_AUTHKEY` 环境变量。
- 不许手改 App 的 SharedPreferences 来绕过配对流程。
- **绝不触碰生产 daemon 与用户真实 tmux，只读也不行**；起隔离 daemon 必须用
  `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS` 收窄扫描。
- **席位禁 git push。**
- GPL 隔离：Termux 系 GPLv3 不可用；herdr-remote AGPL、mosh GPLv3 只借鉴算法不复制代码。
- 测试净化前缀 `env -u TEAM_AGENT_*`。
- 全局 CLAUDE.md：**禁止写 memory；禁止用 AskUserQuestion 工具问用户。**

### 本轮新增的一条运维禁令

**广州机器 `43.136.53.247` 的 `:443` 跑着另一个项目的 `claude-chat.service`，不要碰。**
它的 `PORT=443` 是硬红线（中国出境对非标 TLS 端口劫持已 tcpdump 实证）。
我们的 derper 用 8444，两者互不干扰。

---

## §7 给后继的一句话

**今天最容易犯的错，是拿昨天的结论开工。**

昨夜的链路修复把往返单价降了十倍，**这让 C1、C2 的收益全部缩水一个数量级**，
`docs/cellular-ts-optimization.md` 的原始排序已被作者自己订正过（§3 有「⚠️ 订正」节）。
同理，缺陷③ 的定义在 2026-08-14 被用户彻底改写，**前四轮的工作全是修错方向**。

**开工前先确认三件事**：这条结论是什么时候得出的、当时的链路条件是什么、用户的定义变了没有。
