# Goal: 主机自动发现与 TS 优先路由

- Baseline SHA: `corral-core@eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`（PR #74，OPEN，未合并）+ `corral-serve@911dd94176a34e11f66b61b15b191216463c48cf`（PR #7，OPEN，未合并）；来源文档提交 `67d472b83f7a6715c464fd204adad8afa58bb640`
- User outcome: 用户只绑定目标主机及其主机 token；未绑定也可先入 TS 再发现候选主机并选择主机（不是路径）后输入主机 token 完成绑定。客户端自行发现并验证该主机的 TS 与局域网可达地址，用户不再填写、查看或选择 URL、IP、端口或网络路径。首次连接和每次重连优先 TS；当前连接仍可用时保持，不因另一条路径恢复而抢占。
- Observable success:
  - 绑定/扫码流程面向“主机 + 主机 token”，不把链接或候选路径暴露为用户选择项。无扫码时：发现候选主机→展示/选择主机→输入主机 token→验证绑定。无主机记录且无已知端口的 TS 首次 whoami 使用产品默认端口 9900（与 daemon 默认监听一致）；非默认端口只经 NSD/QR/已有记录，不手填端口、不扫全端口。TS token 仍只用于 TS 入网，不能替代主机 token，不能免鉴权绑定。
  - 在 TS、局域网或两者均可用的场景，客户端能从自动发现结果建立目标主机连接；首次连接与掉线后的下一次重连按 TS 优先，TS 不可用而局域网可用时自动使用局域网。PeerLines/NSD 未验证或失败时，QR/last-good 静态提示可保留连接，但不得将自主发现标为通过。
  - 已建立的 TS 或局域网连接仍可用时不主动重建；例如当前为局域网时 TS 恢复，不立即切换，下一次重连才按 TS 优先重新选择。
  - TS 入网失败不弹出提示，不阻止局域网尝试；两条路径均不可用时工作区为空，不出现额外弹窗、错误条或提示文案。
  - 右上角只显示成功连接实际使用的 `TS` 或`局域网`网络类型；该显示不是路径选择器，也不以 TS Up 或自报地址代替目标主机连接成功。
  - 自动发现的地址在身份验证前不得获得或接收主机 token；未知端点不能仅凭自报主机身份拿到绑定 token。发现与鉴权是两个不同阶段。
  - 既有会话性能体验（打开会话秒开、无空白）、原有 17 项 UI/前台恢复验收面、覆盖安装与签名升级兼容性不退回；每项都须以对应基线证据核对，未知证据不得宣称通过。
- Scope:
  - 客户端：从固定 URL/候选 URL 绑定模型转为主机记录与内部发现结果；绑定/扫码界面不再要求或提供 URL、IP、端口、候选路径输入或选择，但未绑定可展示主机列表供选择；连接管理用异步代次状态机在首次连接、断线重连、网络/前后台恢复等共享入口执行 TS 优先且不抢占的择路，禁止同步堵 pump，禁止 `whenSettled` 门闩。
  - 服务端：提供无 token 的主机列举（`GET /pair/whoami`）与 HMAC 身份证明（`POST /pair/identify`），使 TS 与局域网均能作为候选；TS 不可用不阻断局域网服务路径。选定契约见 `DESIGN.md`。
  - 安全边界：区分发现（含无 token 的 `whoami`）、identify HMAC 与主机 token 鉴权。`whoami` 可伪造，不能绑定。HMAC 只覆盖该次 identify HTTP，不是后续 WS 同所有者证明；未完成 identify 的端点不得进入携带主机 token 的 `auth`。TS auth key 与主机 token 不互换、不合并、不写入任务文档。禁止 HTTP/WS 重定向与 DNS 二次解析改变已验证目标。
  - 路由状态覆盖以下完整行为空间（“可用”只表示对应路径当下可用，不规定探测算法或阈值）：

    | TS 可用 | 局域网可用 | 当前为 TS | 当前为局域网 | 首次连接／重连 |
    |---|---|---|---|---|
    | 是 | 是 | 保持 TS | 保持局域网 | TS |
    | 是 | 否 | 保持 TS | 当前连接失效后重连 TS | TS |
    | 否 | 是 | 当前连接失效后重连局域网 | 保持局域网 | 局域网 |
    | 否 | 否 | 工作区为空，不额外提示 | 工作区为空，不额外提示 | 工作区为空，不额外提示 |

  - 兼容边界：旧 v1 QR 与已有持久绑定的迁移、旧候选地址的安全处理、TS key 加密存储/已有明文迁移、上传基址随当前有效 endpoint 的语义必须保持可追溯。选定规则见 `DESIGN.md` §3.5：新客户端不对旧 `candidates` 喷 token；遗留直 auth 仅当 `host_id==null`、来源为扫码主 URL 或已存主 URL、字面地址精确相等、且对该地址的原始 identify POST 确切 HTTP 404。last-good、candidates、发现地址永不走该例外。Keystore 失败仍整份失效。不能因“兼容”绕过新身份安全边界。
  - 跨仓边界：客户端与服务端共享发现/身份契约时成对改动、成对验收；不按 TS、局域网、UI 机械拆 PR。不写“服务端 PR 先合”。方案阶段不改产品；leader 冻结后 owning 分支允许提交、push、开 PR；merge 与生产仍未授权。
- Non-goals:
  - 不让用户继续管理 URL、IP、端口、候选列表或 TS/LAN 路径选择器；不新增“TS 失败”或“两路不可用”的提示。
  - 不把 TS token 当作主机 token，不把主机 token 直接发送给未验证端点；不以 TS 节点 Up、监听端口、地址可达或端点自报 host_id 单独证明目标主机身份。
  - 不在本目标页展开实现细节。发现传输、身份证明与兼容规则的选定值在 `DESIGN.md`。不上 PKI；HMAC 地址证明不是后续 WS 同所有者证明。不把“禁止改业务帧”写成用户硬要求。
  - 不改写 PR #72/#73/#74、PR #7 或其历史验收结论；不把 dirty `main` 当成产品基线，不操作生产，不在本目标阶段编译、运行设备或生产。
  - 不借本需求扩展成全仓架构治理；架构工具目录缺失时不假报架构 PASS。
- Compatibility/public-contract surface:
  - 客户端配对模型、QR 解析与持久化：当前 `QrPayload`/`PairingConfig` 仍含 `url, token, tsAuthKey, candidates`，没有 `host_id` 或已验证地址集合；涉及 `pairing` 模型、存储、扫码/绑定 UI 及旧 TS key 迁移。依据：`host-discovery-explore-luna/EXPLORATION.md` §2“配对、身份和持久化”。
  - 客户端 TS 与连接链路：现有 `TsnetWire` 的 Up 只表示本机 TS 状态，`TsnetDial`/WebSocket transport 仍从 URL 选择代理，`ConnectionManager.attemptConnect` 以单一 `config.url` 重试；涉及 TS 地址发现能力、共享连接择路、Service/Session/前台恢复和实际路径徽标。依据：同文档 §2“TS、地址和连接管理”。
  - 服务端配对/地址/认证：现有 `DetectAddresses`/`WithTailnet` 生成地址候选，QR v1 没有 `host_id`、签名或发现证明；首个认证帧只有 token，服务端当前直接验证 token；TS group Up 失败目前可能阻止启动。涉及服务端配对载荷、发现/身份预验证、认证边界及 TS 失败不阻断 LAN。依据：同文档 §2“服务端地址和协议”。
  - 协议兼容：选定为 `GET /pair/whoami`（无 token，仅列举）+ `POST /pair/identify`（请求不含 token，禁重定向）+ 现有 WS `auth`。`:core-protocol` 本版不进回滚单元。QR 保持 `v=1` 加可选字段。跨仓契约变更须成对验证，不得只合一仓当交付。
  - 用户已给定的冻结身份：服务 binary SHA-256 `fc57ef21da03e5afe486878d60351dec0624d98ecea92f2f583e88d57c77c3aa`，`vcs.revision=911dd941`，`modified=false`；APK SHA-256 `72e373cfbf2c67d1c53e8cf49b361b4fba3597aec0f429c4a8cd8c61e1f9e2b4`，signer `ea427eb4e14f95654a66802b6558fbbf6f93f1ca69d8117795fb7cef376cb13b`。这些是来源/验收身份，不是本目标的实现授权。
  - 既有 UI/前台恢复、性能与覆盖升级是回归面，不因新绑定/路由 UI 改动而缩减。逐项映射见 `DESIGN.md` §10：原 17 项完整 androidTest 名字来自 `.team/stable-pr/external-session-status-ui/FINAL-COMPOSE-VERDICT.md`；前台恢复来自 `.team/stable-pr/foreground-reconnect/FINAL-VERDICT.md`；签名/覆盖来自同目录 `SIGNING-RECEIPT.md` 与 `UPDATE-INSTALL-RECEIPT.md`；性能正本 `.team/nodes/input-full-auto/perf-design/CONTRACT.md` 与 `tools/perfbase/envcheck.sh --gate`。未跑不得 PASS。
- Known facts and source coordinates:
  - **用户裁定（冻结行为）**：需求正本 `requirement-base/entries/101-主机自动发现与TS优先路由.md`；维基概念页 `requirement-wiki/wiki/concepts/host-auto-discovery-routing.md`；执行裁定 `.team/stable-pr/host-auto-discovery/DECISIONS.md`。三者共同确认“主机 + token、TS/LAN 自动发现、首次/重连 TS 优先、当前可用不抢占、右上角实际类型、失败静默、两路不可用工作区为空”。
  - **来源身份**：`.team/nodes/host-discovery-explore-luna/EXPLORATION.md` §1 给出 core PR74、serve PR7、来源提交及用户给定 APK/服务 binary 身份；PR 均 OPEN 未合并。
  - **当前实现事实**：同一探索文档 §2 记录固定 URL 绑定模型、现有 QR v1、TS 本机状态与单 URL 重连路径、服务端地址候选和 token-only 首帧认证；这些是需要适配的现状，不是新需求。
  - **选定方案**：`.team/stable-pr/host-auto-discovery/DESIGN.md` 为 FAIL 审查后的统一返修（S1–S6）。失败审查归档提交 `6528ecaf9a27867f44971404e5b42100a3a5bfc2` 是失败证据不是通过。席位证据 `.team/nodes/host-discovery-design-grok/EVIDENCE.md`。能力补证 `.team/nodes/host-discovery-explore-luna/CAPABILITY-EVIDENCE.md`。
  - **架构工具事实**：探索文档 §1 记录独立工具目录 `/Users/alauda/team-agent-scratch/wiki-tooling` 不存在，无法据此声称 `build_wiki.py`、`code_abstract_tree.py` 或全量架构闭包 PASS；此缺口保留为 unknown，不扩成全仓治理。
- Unknowns requiring bounded experiments:
  - 方案已选定、不再当空洞：无扫码主机发现入口、无记录时 TS whoami 引导 9900、公平续扫而非永久 32/8 盲截、异步代次状态机、identify 字面地址/禁重定向/LocalAddr 与 dest_ip MAC、遗留仅确切 404、冷启 port/ts_node_id、READY 原子发布与 live 上传、同身份仅改 TS key 时 READY 只持久化待用 key、下一代次才 `ensureStarted`。
  - 默认 9900 引导不覆盖「非默认监听且无 QR/NSD/主机记录」的 TS-only 主机：范围已写明，不是实现期再猜端口。
  - 仍需隔离实验、失败不得冒充自主发现通过（`DESIGN.md` §11）：Android `PeerSnapshot`/`Status`；`NsdManager`+组播锁；tsnet 已接受连接 LocalAddr 是否为拨号 100.x（两种 MAC 路径已冻结）。
  - 预存在、本功能不修：tsnet 换 key 同名冲突（`sanitizeHostname` / 按 key 分 state dir）。
  - `DESIGN.md` §10 所列 17 项、前台恢复、CONTRACT/envcheck、签名覆盖：未跑保持未验证，不得 PASS。
- Required GitHub CI:
  - 客户端与服务端各自 PR 的 exact head 必须通过对应仓库 CI；若发现/身份契约跨仓变化，CI 必须在匹配的客户端/服务端协议版本上验证兼容，而非只验证单仓编译。
  - CI 结果须绑定实际提交身份并覆盖受影响模块与旧绑定/协议兼容行为；具体 job、命令和测试矩阵留到后续 PR 任务，不在本目标臆定。
- Required module/Grok/real-machine tests:
  - 行为与安全：见 `DESIGN.md` §10.1（无扫码发现入口、TS-only 对新装必须 `GET <peerIPv4>:9900/pair/whoami`、四格路由、LAN 槽、单 create 推进、公平续扫、冷启字段、重定向/404 谓词、live 上传、TS READY 与 LAN READY 改 key 时 stop/close/AuthFrame 增量为 0）。
  - 回归映射：`DESIGN.md` §10.2–10.5，证据入口为已有 FINAL-COMPOSE-VERDICT / foreground FINAL-VERDICT / SIGNING-RECEIPT / UPDATE-INSTALL-RECEIPT / perf CONTRACT / envcheck --gate。不编造阈值。
  - 真实验收必须在用户认可的真实网络路径与真实设备上确认：首次连接、断线重连、TS/LAN 切换条件下的实际路径徽标与工作区行为；不能以单测、模拟器或“TS Up”代替最终行为证据。静态提示成功不得替代自主发现 PASS。
- Irreversible actions requiring explicit authorization:
  - **方案阶段**不得改产品。
  - **leader 冻结后**：owning 分支允许提交、push、开远端 PR（本轮流程已授权）。
  - **仍需单独授权**：merge、release、替换生产 binary、重启生产、修改生产状态、删除/迁移用户绑定数据。
  - 不得把安全未知项用“先发 token 再判断”临时放行。不得把本稿或 DESIGN 作者自报当作复审 PASS。
