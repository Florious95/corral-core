# 主机自动发现方案：实现与验收独立审查

## 结论

- 输入身份 **PASS**：`shasum -a 256 -c .team/stable-pr/host-auto-discovery/REVIEW-INPUT.sha256` 六项均 `OK`（需求 101、GOAL、DESIGN、两份本席证据及设计席证据）。未读任何 Grok 审查结果。
- 方案总体 **FAIL（当前不可直接派开发）**；实现/真机验收中的若干事实仍 **UNJUDGEABLE**。以下阻断项收敛前，不应把“已选定设计”当作可执行任务书。未编译、未跑设备、未碰生产/tailnet/凭据。
- 代码基线：`corral-core@eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`、`corral-serve@911dd94176a34e11f66b61b15b191216463c48cf`。

## 阻断清单

### B1 — 仅输入 TS key 的绑定路径未闭合

- **位置/反例**：`DESIGN.md:42-47,59-67,184-193`。用户只有 TS key、无 QR、无 `host_id`、无主机 token 时，`PeerLines` 只能给一批 tailnet peer；`identify` 的 HMAC 又要求客户端已有主机 token（`:43-44`），且 `PeerStatus` 无 AgentMirror 服务证明。流程无法“发现→展示目标主机→输入主机 token→绑定”；`:67` 直接假定已有合法 `host_id+token` 落盘。
- **最小修正**：补一条明确的无 QR 状态机：定义如何从非秘密 peer/服务描述确定目标（唯一目标或主机选择，不是路径选择），何时展示主机、何时收主机 token、如何在 token 到手后重证；或明确该入口的必要前置。必须覆盖多 peer，不能把任意在线 peer 当目标。
- **验证建议**：新安装仅填 TS key，分别只有一个、多个 peer（含非 AgentMirror peer）；确认能到主机+token 绑定，且在 identity 证明前没有主机 token 请求/WS `AuthFrame`。此项不补，不能派实现。

### B2 — 候选上限与冷启数据模型会静默漏目标

- **位置/反例**：`DESIGN.md:34,101-117,145-147,184-193`。`PeerLines` ≤32、TS/LAN 各 ≤8 没有超限排序/报错/分页规则：目标为第 33 个 peer 或第 9 台服务端时可被静默丢掉。QR 有 `host_id/port/ts_node_id/name`，但 `PairingConfig` 只列 `hostId/token/tsAuthKey/legacyBootstrapUrl/last_*`；`port`、`ts_node_id` 未持久化，`name` 也未持久化。若新 QR 的 `url` 可空，进程在首次 READY 前退出后，冷启没有端口来构造候选；没有 `ts_node_id` 也无法兑现“精确过滤”。
- **最小修正**：明确 `name` 是纯临时展示；至少持久化 URL-less 冷启所需 `port`，并决定 `ts_node_id` 是持久化还是每次完整重发现。先按已知 host/TS ID 过滤再限额；未知目标或超限必须显式“本轮不可判/继续来源”，不得静默截断。每路 8 的上限只能是过滤后的目标候选上限，不能是全局盲截。
- **验证建议**：构造目标在第 33 行、第 9 个候选；构造 host_id+port、无 URL 的 QR，首次 READY 前杀进程再冷启；逐字段验证解析、持久化、候选生成与 token 安全。

### B3 — `resolve(): String?` 与异步 WS/8 秒预算不相容

- **位置/反例**：`DESIGN.md:59-61,197-199,222-233`；core `ConnectionManager.kt:667-674,763-839`（eaaa）固定一次 `transportFactory.create(config.url)`，掉线后对同 URL 退避；core `Connection.kt:132-146` 在异步 `onOpen` 才发送 `AuthFrame`。`resolve()` 返回单字符串，表达不了候选队列、identify 完成后的 WS READY、取消和“某候选 WS 未 READY 后继续下一候选”。
- **具体轨迹**：8 个 TS 候选按 2 秒 identify 即 16 秒，已超过“总 8 秒”；若并行，设计没有并发/取消/优先级契约。首个 identify 成功但 WS 拨号/认证失败时，现有 manager 只进入 `RECONNECTING` 重拨同 URL，不会自动进入下一 TS 或 LAN；`resolve()=null` 的 manager 状态/退避也未定义。即使 resolver 墙钟 8 秒，现 OkHttp 连接超时仍是 10 秒级，直接威胁秒开。
- **最小修正**：把接口改成有序候选/异步 coordinator：要么 resolver 统筹 identify+WS 到 READY，要么 `ConnectionManager` 持有候选状态，在 READY 前的拨号失败推进下一项、超时有总预算；明确并发上限、每候选/全局 deadline、取消、LAN 何时启动。READY 后仍只走下一次重连重新解析，不能抢占。
- **验证建议**：8 TS+LAN、每个候选在 identify/WS 各位置失败；记录墙钟、取消和 AuthFrame；验证 TS 全失败后 LAN 不被 8×2 秒饿死，WS 未 READY 自动推进，READY 后不重证/不换路。

### B4 — last-good、TS key 变化与上传 live base 没有闭环

- **位置/反例**：`DESIGN.md:145-147,152-160,211,225-233`；core `PersistentConnection.kt:103-106`（eaaa）只初始化一次 `ServiceWire.uploadBaseUrl`；`SessionRoute.kt:141-196` 将其传入 `SessionViewModel`；`SessionViewModel.kt:80-90,477-509` 的 `baseUrl` 是构造期快照并在上传时读取该快照。路由 LAN→TS 重连后，即使“每次上传读 live”实现只读 `ServiceWire.uploadBaseUrl`，若没有 READY 原子回写，仍会打旧 host；现有 `recordConnectionPath` 只记路径，不更新 upload base。
- **最小修正**：定义单一 READY 结果（字面 endpoint+实际 path）回调，在 UI/上传可见前原子更新 `ServiceWire` 的 live upload base 与 `last_ts_url/last_lan_url`；现有 Session VM 上传必须改为 provider/live 读取而非构造快照。明确定义 `setConfig` 身份比较：host/token 变更重建，last-good 回写不重建；TS key 变更是否只后台重启并等下次重连，不能靠“不闪断”一句带过。
- **验证建议**：同一个已存在 Session VM 在 LAN READY 后断线转 TS，再上传，核 HTTP host 与 WS host 一致；反向转路、冷启、重复扫码同 host+不同 TS key、不同 host/token，分别断言是否 release、last-good 落盘及旧版 URL 回读。

### B5 — 发现失败回退偷换了“自主发现”语义

- **位置/反例**：`DESIGN.md:170-175,191-193,207-210,308-309`。NSD 失败仅回退 QR/last-good；PeerLines 失败仅回退 QR/`lastTsUrl`。在“只填 TS key、无 QR、无 last-good”的合法路径，这两条都没有候选；在“LAN 实际可用但 NSD 失败、无静态提示”的路径也会错误落空。静态提示可以是安全候选（仍需 identify），但不是另一种主动发现。
- **最小修正**：明确回退是 degraded/不可宣称完整发现，不得把它记为多途径 PASS；对已知 host 可用静态提示+重证，对无提示的 TS-key-only 入口必须仍有目标发现来源或明确终止为无候选。将该边界写进实现状态和验收，而非默认“退化即满足”。
- **验证建议**：禁用 NSD、禁用 PeerLines，各测有/无 QR、last-good、LAN/TS 实际可用四组；确认没有静默喷 token，也没有把“静态地址重证”说成自主发现通过。

### B6 — 旧兼容安全例外的触发条件及范围不够可判

- **位置/反例**：`DESIGN.md:86-95,207-213`；`GOAL-DRAFT.md:11,26,38,57`。新 App+旧 daemon 的“identify 404/无端点后，仅扫码主 URL 直接 WS auth”可成为合理的用户当面信任例外，但“无端点”是否包含 timeout、DNS/TLS/5xx 未定义；若误把网络失败归入该类，就会把 token 发给 stale/伪地址。另有旧 App+新 QR 必然按 `url+candidates` 向候选喷 token（`:207`），与 GOAL 的无验证地址安全表述冲突，必须明确这是旧客户端遗留例外，不能宣称全客户端安全不变量成立。
- **最小修正**：把来源写入状态（仅 `scanned_primary` 可进入遗留直 auth；`discovered/last-good/candidates` 永不直 auth），把允许的 HTTP 状态/响应精确定义为 404，明确 timeout、DNS、TLS、5xx 均不触发；单独记录旧 App 候选喷 token 的残余风险与适用范围。不得用“用户扫过”扩大到候选。
- **验证建议**：对主 URL注入 404、timeout、DNS/TLS、5xx、错误 body；对 candidates/last-good/NSD/PeerLines 同样注入，断言只有精确扫码主 URL可 Auth。旧 App+新 QR 仅作为显式兼容例外，不给新安全结论。

### B7 — 原 17 项、前台恢复与性能装置尚非可执行验收范围

- **位置/反例**：`GOAL-DRAFT.md:12,40,51,54,58`；`DESIGN.md:281-302` 只写“原 17 项/性能/覆盖安装”宽泛回归，没有逐项测试、owner、基线产物、真实发现夹具、环境门或 A/B 证据绑定。当前不能从这些文字判定新路由不回退秒开无空白，也不能判定前台恢复是否保留。
- **最小修正**：冻结一份逐项回归矩阵（17 项+前台恢复→测试/装置/证据路径），把真实发现的 TS/LAN 四格、失败回退、性能 CONTRACT 与 `envcheck --gate`、APK signer/覆盖安装列为明确输入；不在本审查写最终机械脚本。
- **验证建议**：矩阵收齐后由 Grok/真实设备按各自范围验证；任一证据缺失保持 UNJUDGEABLE，不以单测或“TS Up”替代真机。

### B8 — 流程文字会阻断已授权的 owning 分支 PR

- **位置/反例**：`GOAL-DRAFT.md:27,61-64` 写“不得合并、推送或发布”且未按阶段区分；本轮流程已授权 owning 分支推送/开 PR，但没有 merge/生产授权。`DESIGN.md:254-273` 同时写“无合并授权”又写 PR A“建议先合或与 B 同审”。“先合”会被执行者理解为要求 merge，直接撞未授权 merge。
- **最小修正**：GOAL 改成阶段边界：冻结前不改产品；冻结后允许 owning 分支 push/open PR；merge、release、生产仍需单独授权。DESIGN §9 将“先合”改为“先完成 A 的契约评审/CI，再与 B 成对验收”，不暗示合并；保留 A+B 同契约回滚单元。
- **验证建议**：方案作者/leader复核最终文字与动作门：允许分支/PR，禁止未经授权 merge/release/生产；检查只上 A、只上 B 的兼容范围与成对验收证据。

## 非阻断项与可在实现期验证的 UNJUDGEABLE

- **N1 PeerLines runtime**（`DESIGN.md:184-193,308`）：静态 API 已有 `Status.Peer`，反例是 Android AAR 内 `Status` 返回错误/空表；最小修正是维持本轮“无 TS 候选、不得扫 100.64/10”的 fail-closed 回退；验证仅用隔离测试节点核 `StableID/Online/IPv4`，不能把有 peer 写成 AgentMirror 服务发现通过。
- **N2 NSD/组播 runtime**（`DESIGN.md:170-175,309`）：API 可用但设备可能不回 `_agentmirror._tcp` 或 MulticastLock 失败；最小修正是停止发现、使用已知静态提示并仍 identify，不能把 mDNS 当 TS 发现；验证 API 26+ 真机多 Wi-Fi 设备的发现窗口、撤销和权限。
- **N3 HMAC bound 运行语义**（`DESIGN.md:42-44,82,308-309`）：userspace listener `LocalAddr` 可能不是客户端拨的 100.x；最小修正只能按两种结果冻结“实际服务端地址集合”绑定规则，不能删除 bound；验证同一目标通过 TS/LAN 观察 `LocalAddr`、MAC 校验和中继拒绝。
- **N4 AAR/交叉协议装置**（`DESIGN.md:184-193,264-271`）：`PeerLines` Java 形状、16KB 对齐、Go/Android HMAC 向量、异步候选状态机、route badge/live upload 仍未运行；最小修正是把每项挂到 owning PR 的测试/证据，不把设计文字当 PASS；实现期由 Grok/真机分别验证。
- **N5 展示字段**（`DESIGN.md:110-113`）：`name=os.Hostname()`/`PeerStatus.HostName` 不唯一，反例是同名 peer；最小修正是强制 name 仅展示、不参与匹配；验证同名 peer 不串主机身份。此项不阻断路由契约。
- **密码学边界**：本审查没有实现 HMAC，故不对算法强度/时序作 PASS；只要求实现期向量、常数时间比较、来源隔离测试，直到证据齐全保持 UNJUDGEABLE。

## 派发裁定

在 B1-B8 的最小修正落纸并由 leader 冻结前，**不建议派 owning PR**。其中 B1/B2/B3/B4/B5/B6 是功能与安全路径的实质缺口；B7 是验收不可执行；B8 是流程动作冲突。补齐后仍需把上述 UNJUDGEABLE 作为实现期有界实验，不得把它们提前写成 PASS。
