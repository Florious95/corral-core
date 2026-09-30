# 主机自动发现 v3：R1/R2 定点闭合复审

## 判定

- 输入身份 **PASS**：`shasum -a 256 -c .team/stable-pr/host-auto-discovery/REVIEW-INPUT-v3.sha256` 六项均 `OK`；其中 Grok `REVIEW-v2.md` 仅校验 hash，未读取。
- 方案可派实现判定：**PASS（设计层）**。装置尚未执行，真实行为与设备证据保持 **UNJUDGEABLE**；不以未运行否决。
- 冻结基线：`corral-core@eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`、`corral-serve@911dd94176a34e11f66b61b15b191216463c48cf`。

## R1：TS-only 首次 whoami 端口 — PASS（设计闭合）

- `REVIEW-v3.diff` 对 `DESIGN.md:34-40` 增加了确定的来源优先级：主机记录 → QR/主 URL → NSD → **产品默认 9900**；不是从 peer 地址猜端口。serve7 的真实默认也有源码依据：`corral-serve@911dd941:internal/config/config.go:167,190` 的 `-listen`/环境回退为 `0.0.0.0:9900`。
- `DESIGN.md:39,65-66,293` 同时收窄了边界：whoami 响应的 `port` 只能附着本次原始字面 IPv4，不能改地址；后续仍须 identify。非默认监听且没有 QR/NSD/记录时允许找不到，明确不手填、不扫全端口、不扫 `100.64/10`。因此没有把不可信端口元数据变成发 token 依据，也未恢复用户路径选择。
- 与 GOAL `GOAL-DRAFT.md:6,29-31,55-58` 一致：TS key-only 的默认端口路径已进入可验收范围；非默认无提示属于明确范围边界，不是未决猜值。

## R2：同 host/token 仅换 TS key — PASS（延迟时机闭合）

- `REVIEW-v3.diff` / `DESIGN.md:164-168,279-289,301-305` 明确：READY 期间只持久化最新待用 key，后写覆盖先写；不调用会 stop 旧 manager 的 `ensureStarted`，不后台重启、不拆当前 TS/LAN socket；当前连接自然结束后下一连接代次才应用最后一把 key。现有 `TsnetWire.kt:84-115` 的 `m?.stop()` 因此不构成反例：v3 已禁止在 READY 改 key路径调用它。
- 身份/token 变化仍可显式 `releaseManager`；与同身份 key 变化分开。过期代次不得写回旧 key或抢占新 READY，语义没有倒置。
- 新增的 TS READY/LAN READY 场景（`DESIGN.md:338-340`）准确要求改 key期间 `stop/close/AuthFrame` 增量为 0，下一次自然断线才应用最新值；这些是**未运行的必要实验**，不是设计失败。

## 一个必须随实现落实的边界（不重开延迟裁定）

- key 从“有”改为“无”时，下一代次的“按有/无 key 择路”必须在候选分类前禁用或停止旧 TS manager；否则旧 manager 可能仍报 `Up`，造成无 key 仍走旧 TS 的状态假象。该动作应发生在下一代次、不得发生在 READY 改 key时；v3 文字已给出“按有/无 key”方向，但实现需把空值分支写实。若实现明确该分支，则 R2 无设计阻断。

## 剩余 UNJUDGEABLE / 必要实验

1. R1 隔离新装：仅 TS key、无 QR/记录/last-good，混入默认 9900 AgentMirror 与非服务 peer；确认实际 GET `peerIPv4:9900/pair/whoami`、选主机后才收 token、证明前零 `AuthFrame`。另测非默认端口无提示时如实不发现，NSD/QR/记录端口仍可发现。
2. R2 两条时序：TS READY 与 LAN READY 各测 key 改变、快速连续改 key（只留最后值）、有→无及无→有；记录 stop/close/AuthFrame、当前 socket/徽标，断线后验证新代次应用最后值及 TS 优先/无 key 不走旧 TS。
3. 仍按 `DESIGN.md:398-406` 保留 PeerSnapshot/AAR、NSD/组播锁、tsnet `LocalAddr` 三项隔离实验；按 `:327-396` 保留功能、17 项、前台、性能 envcheck/A-B、签名覆盖的未运行状态。不得把静态提示、TS Up 或本文完成当作自主发现/性能/真机 PASS。

未改产品、未编译、未操作设备/生产/tailnet/凭据，未提交或推送。
