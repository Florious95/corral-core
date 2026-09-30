# 主机自动发现：v3 定点闭合（B11 / R2）

席位：`host-discovery-review-grok`。日期：2026-09-05。  
只确认本席 v2 的 B11 与 Luna R2 对应的 TS key 延后应用。未读另一席 v3 结果。未改输入/产品。未编译、未跑设备、未碰生产/凭据/真实 tailnet。

## 裁决

**pass**（设计阶段可派实现）

这次 diff 把 B11 写成可执行的默认端口规则，并把 R2 从「不拆 READY」收成「READY 只写待用 key、下一代次才 `ensureStarted`」，没有改坏已闭合的 identify/代次/不抢占语义。  
未跑 PeerSnapshot/NSD/真机/性能不否决本设计放行。设计放行 ≠ 功能、设备或性能验收通过。

## 输入

`shasum -a 256 -c .team/stable-pr/host-auto-discovery/REVIEW-INPUT-v3.sha256`：六项 OK。主证据 `REVIEW-v3.diff`；对照 `DESIGN.md` §2.2/§3.2/§4.3/§6.3/§7/§8/§10.1 与 `GOAL-DRAFT.md` 对应句。基线 `TsnetWire.ensureStarted`（`eaaa7d47` `:84-115`）换 key 会 `stop()` 旧节点，与 R2 禁止在 READY 调用它一致。

## B11（v2 唯一阻断）

**闭合。**

| 问项 | 结论 | 依据 |
|---|---|---|
| 无提示 TS 首次 whoami | 只打协议默认 **9900**，不是缺 port 丢弃、也不是现场猜 | `DESIGN.md` §2.2、§7；GOAL 可观察成功 |
| 显式端口仍可用 | 记录 `port` → QR `port`/主 url port → NSD `resolveService` 端口 → 否则 9900 | §2.2 优先级 |
| whoami 不改原始 IP、不跟重定向 | 只字面 IPv4；`followRedirects=false`；3xx 不是主机；禁止 `Location` 当候选；响应 `port` 只附着**本次 GET 的原始字面 IPv4**；不得写 prefs `token`/`host_id` | §2.2、§3.2 |
| 不恢复手填端口 | 不手填端口/链接，不扫全端口，不扫 `100.64/10` | §2.2；GOAL |
| 非默认且无提示 | **如实范围**：改了 `-listen`/`AGENTMIRROR_LISTEN` 且无 QR/NSD/主机记录时，TS-only 列表可以找不到该主机。不宣称能推导任意服务端口 | §2.2；GOAL Unknowns |

后续 identify 仍走 §3（HMAC、原始字面、禁重定向）。whoami 的 `port` 仍是不可信元数据，不能当新主机地址。这没有重新打开 B1/B5/B10。

owning PR 必测（§10.1 已列，未跑）：无 QR/无 last-good/无记录、仅 TS key，必须发出 `GET http://<peerIPv4>:9900/pair/whoami`；证明前零 `AuthFrame`。

## R2（延后应用 TS key）

**未改坏已闭合安全/代次语义。**

diff 把「同主机、token 不变、只改 TS key」从「后台立即 `ensureStarted`」改成：

- READY：**只**把最新待用 key 加密写盘（后写覆盖先前待用值）；
- **不**在 READY 调 `TsnetWire.ensureStarted`（基线换 key 会停旧节点，会拆当前 SOCKS/socket）；
- 不拆现有 TS 或 LAN socket，不因 TS Up 开新代次；
- **下一次连接代次 `begin`** 才读磁盘最新待用 key，非空才 `ensureStarted`；
- 过期代次不得把旧 key 写回磁盘、不得抢占新 READY；
- 身份（`host_id`/主机 token）变化仍可 `releaseManager`，与 key-only 分开；
- `whenSettled` 仍删除；TS Starting 仍不得挡 LAN。

这与 101「当前能用就保持、下次重连再择路」一致，也与 v2 已闭合的「CM 唯一 `create`、identify 后才 auth、READY 不抢占」一致。TS key 仍只入网，不替代主机 token。

owning PR 必测（§10.1 已列，未跑）：TS READY 改 key 与 LAN READY 改 key，期间 `stop`/`close`/新 `AuthFrame` 增量 = 0；自然断线进入新代次后才 `ensureStarted(最后一把 key)`。

## 这次 diff 的遗留

无新阻断。非默认端口无提示找不到主机是写明的覆盖边界，不是漏洞。B1–B10 不重复展开。§11 实验与 §10 回归未跑，保持未验证，不作为本闭合的否决。
