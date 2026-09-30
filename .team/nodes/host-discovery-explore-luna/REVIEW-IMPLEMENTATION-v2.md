# 主机自动发现方案 v2：实现与验收独立复审

## 结论

- 输入身份 **PASS**：`shasum -a 256 -c .team/stable-pr/host-auto-discovery/REVIEW-INPUT-v2.sha256` 八项均 `OK`。第 8 项 Grok 结果仅校验 hash，未读取。
- 基线仍为 `corral-core@eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`（PR74）与 `corral-serve@911dd94176a34e11f66b61b15b191216463c48cf`（PR7），不以 dirty `main` 为产品基座。
- 总判定 **FAIL（尚不可直接派实现）**：原 B2–B8 的设计缺口已收敛，运行证据仍为 **UNJUDGEABLE**；但修订设计新留下 **R1：无扫码、仅 TS key 的初始 TS 服务端口没有来源**，使目标主流程仍可能零候选。

## B1–B8 逐项闭合

|项|判定|复核事实|
|---|---|---|
|B1 无扫码绑定|**FAIL（被 R1 阻断）**|`DESIGN.md:30-40` 已明确“发现主机→选主机→收 token→identify”，且 `whoami` 可伪造、选主机不等于选路径；但 TS 步骤要求 `peer IPv4:port`，新安装无 QR/last-good 时没有 port（R1）。流程文字闭合，TS-only 可执行性未闭合。|
|B2 全表/公平/冷启|**设计 PASS；运行 UNJUDGEABLE**|`DESIGN.md:217-228,260-277`：已知 `ts_node_id` 先全表匹配；未知 peer 按 StableID 旋转窗口，cursor 跨代次续扫；目标工作集 ≤8 是并发上限；NSD 匹配目标不因 8 条丢弃；`port/ts_node_id/name` 与 clear、URL-less 冷启均列入 prefs。需实现期证据验证。|
|B3 异步择路|**设计 PASS；运行 UNJUDGEABLE**|`DESIGN.md:158-193`：单一 `ConnectionManager` create 责任、generation/cancel/过期回调、identify 失败推进、auth 拒绝 STOPPED、WS 单并发；8s 总预算、TS ≤4s、identify 2s、WS 3s、LAN 槽及 cursor 续扫均明确。需验证现有 transport 的默认 10s 是否被本路径覆盖。|
|B4 READY/上传/key|**设计 PASS；运行 UNJUDGEABLE**|`DESIGN.md:195-207,279-287`：READY 前原子发布 endpoint/path/live upload base/last-good/url；上传禁构造期快照；同主机回写不 release，TS key 变化不拆 READY。现有 `TsnetWire.ensureStarted` 在 `eaaa7d47:app/app/src/main/java/dev/agentmirror/app/tsnet/TsnetWire.kt:84-115` 换 key 会 `m?.stop()`，见 R2。|
|B5 静态退化语义|**设计 PASS；运行 UNJUDGEABLE**|`DESIGN.md:230-236,398-404` 明确 PeerLines/NSD 失败只能用 QR/last-good 提示并重证；静态重证不得宣称自主发现 PASS；无扫码且无提示时列表可空。需跑特性开关/隔离夹具。|
|B6 旧兼容安全例外|**设计 PASS；运行 UNJUDGEABLE**|`DESIGN.md:136-149,203-216`：仅 `host_id==null`、`scanned_primary/persisted_legacy`、字面地址精确相同、原始 identify 确切 404 才 legacy auth；发现/last-good/candidates/NSD/PeerLines/whoami 永不例外；写入 host_id 后关闭。需覆盖超时、DNS、3xx、5xx、坏 MAC。|
|B7 回归/装置映射|**范围 PASS；执行 UNJUDGEABLE**|`DESIGN.md:327-348` 本功能矩阵；`:350-372` 原 17 个测试全名；`:374-386` 前台恢复；`:388-390` CONTRACT + envcheck + A/B；`:392-396` signer/覆盖安装。设计明确“未跑=未验证”，不得据文档宣称绿。|
|B8 PR 边界|**PASS**|`GOAL-DRAFT.md:60-62` 已分阶段：冻结后 owning branch 可 commit/push/open PR，merge/release/生产仍单独授权；`DESIGN.md:313-323` 删除“PR A先合”，改为契约依赖与成对验收。|

## 阻断与残余反例

### R1（阻断）：TS-only 新绑定缺少服务端监听端口

- **证据**：需求正本明确用户不填写端口且端口约定未指定（`requirement-base/entries/101-主机自动发现与TS优先路由.md:29-31,54`）。修订设计的 TS 无扫码步骤却要求对 peer 的 `IPv4:port` 请求 `whoami`（`DESIGN.md:30-40`）；Peer API 传输的选定格式仅为 `stableID|online|ip,ip`（`DESIGN.md:289-295`），而 `port` 只从 QR/LAN 或已有记录得到（`:244-254,260-275,293`）。
- **反例轨迹**：新安装 → 只输入 TS auth key → `Status.Peer` 列出目标 IPv4 → 无 QR、无 last-good、prefs 无 port → 无法构造 `/pair/whoami` 请求 → 无法展示目标主机 → 不能进入“输入主机 token”步骤。Tailscale `PeerStatus` 元数据也不能推出 AgentMirror 服务端口（`CAPABILITY-EVIDENCE.md:17-24,53-55`）；不能假定 `9900` 或把 underlay endpoint 端口当服务端口。
- **最小修正（需方案裁定）**：在不让用户输入端口的前提下冻结一个确定来源：例如明确产品固定监听端口并由客户端使用，或让服务端/受控发现 API 明确携带服务端口；不能仅依赖当前 `PeerLines` 的 IP。随后补 TS-only 隔离夹具：目标与非 AgentMirror peer 混合，确认列出主机、选主机后才收 token，证明前零 `AuthFrame`。

### R2（非定论但必须在实现前锁语义）：TS key 变更与当前 TS 连接

- **具体反例**：`DESIGN.md:279-287` 要求同主机 TS key 变更“不拆 READY、后台重启”。冻结基线的 `TsnetWire.ensureStarted` 换 key 在 `eaaa7d47:.../TsnetWire.kt:84-115` 先停止旧 manager；若当前 WS 正借旧 TS SOCKS，旧节点停止是否关闭该 socket、何时发布状态，设计没有定义。不能同时把“当前连接保持可用”与“换 key 立即 stop”当成已证事实。
- **最小修正/验证**：明确延迟到当前 TS socket 结束后重启，或明确 key 变更是允许断开的一类配置事件；不得靠“不拆 READY”字样代替。实现期验证 TS READY→换 key 时无意外 close/第二次 AuthFrame/徽标抖动，下一次断线才用新 key；LAN READY→换 key 也需确认不抢占。

## 可派实现前仍需保留的 UNJUDGEABLE

1. `DESIGN.md:398-404` 的 PeerSnapshot/Android AAR、NSD+MulticastLock、tsnet accepted `LocalAddr` 三项只能在隔离装置验证；现有 `eaaa7d47:tools/tsnetbind/tsnetbind.go:29-114` 尚未暴露 peer API，不能把设计 API 当现成能力。
2. `DESIGN.md:327-396` 的功能矩阵、原 17 项、前台恢复、性能 `envcheck`/A-B、签名覆盖均未运行；缺证据不得 PASS。
3. HMAC 只按设计边界审查：identify 成功不等于后续 WS 同所有者；实现仍须验证 nonce、`bound==dest`、无重定向/DNS、先验后写与 legacy 404 谓词，不能凭算法名称下密码学结论。

**复审裁定**：除 R1（以及需锁定语义的 R2）外，v2 已对原 B1–B8 给出可实现方向；R1 收敛前不应直接派 owning PR。R1/R2 补齐后，以上未运行项目仍只作为实现期验证，不得把完成设计文档当已实现。
