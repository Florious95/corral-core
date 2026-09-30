# 主机自动发现与 TS 优先路由：技术摸底

> 只读探索；没有修改产品、配置、生产或提交。源码依据均来自 Git 对象，不以 dirty `main` 作为产品基线。

## 1. 冻结身份与边界

- 客户端验收身份：`corral-core@eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`（PR74 exact head，parent PR72 `a8901ea6cb53d002879a84760345787d3bcd201f`）。用户给定最终 APK SHA-256 `72e373cfbf2c67d1c53e8cf49b361b4fba3597aec0f429c4a8cd8c61e1f9e2b4`、signer `ea427eb4e14f95654a66802b6558fbbf6f93f1ca69d8117795fb7cef376cb13b`。
- 服务端验收身份：`corral-serve@911dd94176a34e11f66b61b15b191216463c48cf`（PR #7，仍 OPEN；见 `.team/stable-pr/server-status-upload-compose/FINAL-VERDICT.md` 与 `LIVE-DEPLOY-VERDICT.md`）。当前 binary SHA-256 `fc57ef21da03e5afe486878d60351dec0624d98ecea92f2f583e88d57c77c3aa`，darwin/arm64，VCS modified=false。没有把生产状态当作本轮验证，也未操作生产。
- 需求来源：`requirement-base/entries/101-主机自动发现与TS优先路由.md`、`requirement-wiki/wiki/concepts/host-auto-discovery-routing.md`、`.team/stable-pr/host-auto-discovery/DECISIONS.md`；来源提交 `67d472b83f7a6715c464fd204adad8afa58bb640`。
- 必须保留的裁定：用户绑定主机+主机 token，不接触 URL；发现 TS/LAN；首次及重连 TS 优先；当前可用链路不抢占；下次重连重新择路；右上角仅显示实际 TS/LAN；两者不可用时工作区为空且不提示。TS auth key 与主机 token 分离。具体发现协议、QR 字段、身份实现仍是 unknown。
- `/Users/alauda/team-agent-scratch/wiki-tooling` 不存在；按要求尝试的 `build_wiki.py`/`code_abstract_tree.py` 帮助输出记录在本目录 `tmp/`，无法固定工具 SHA 或声称全量架构闭包通过。

## 2. 现有真实路径与事实

### 配对、身份和持久化

- `corral-core@eaaa...:app/app/src/main/java/dev/agentmirror/app/pairing/PairingModels.kt:29-65`：`QrPayload` 只有 `version,url,token,tsAuthKey,candidates`；`PairingConfig` 只有 `url,token,tsAuthKey`，没有 `host_id` 或已发现地址集合。
- `.../pairing/QrPayloadParser.kt:30-91`：只校验 v1、合法 ws URL、非空 token；未知字段忽略，候选只做格式过滤。`PairingViewModel.kt:172-209,360-467` 仍是 QR/manual→URL 队列→逐候选拨号；`selectCandidateUrl`/`retryCandidate` 是现有用户选址路径，需移除而非沿用。
- `.../pairing/PairingConfigStore.kt:46-155`：URL/token 持久化；TS key 以 Keystore AES-GCM 保存并有旧明文迁移。迁移/新增字段必须保持旧绑定可判断、失败不降级猜测。
- `.../pairing/PairingScreen.kt:90-102,427-562`：现有手填卡要求 ws 地址+token，并展示候选下拉；TS 卡展示 auth key 和 `Error` 文案。该 UI 与新裁定冲突；不能恢复“选择路径”或 TS 失败提示。

### TS、地址和连接管理

- `corral-core@eaaa...:app/app/src/main/java/dev/agentmirror/app/tsnet/TsnetBackend.kt:49-65`、`GomobileTsnetBackend.kt:22-55`：Android 后端只提供 `start(...): TsnetProxy`/`close()`，没有 peer 列表、目标主机发现或可达性 API；新增能力是否需要扩展 AAR/Go binding = **unknown**。
- `.../tsnet/TsnetWire.kt:77-170`：TS 只有 Idle/Starting/Up/Error；按 auth key 指纹分状态目录，`whenSettled` 只报告本机节点终态。`sanitizeHostname`（`:172-183`）是客户端设备型号，不是目标主机身份。TS Up 只能证明本机节点就绪，不能证明目标主机可达。
- `.../tsnet/TsnetDial.kt:30-88`：`socketFactoryFor` 仅在目标字面 IPv4 100.64/10 且本机 TS Up 时选 SOCKS；hostname/LAN/未 Up 直拨，且不解析发现地址。
- `.../conn/OkHttpWebSocketTransport.kt:144-188`：每次 `create`（含重连）重新按地址+TS 状态选路，并经 `ServiceWire.recordConnectionPath` 记录实际路径；这是可复用的拨号接缝，但当前输入仍必须是 URL。
- `.../conn/ConnectionManager.kt:191-241,260-317,667-839`：`start`、`pump`、网络恢复、前台恢复最终都走 `attemptConnect`；该函数固定以 `config.url` 创建连接（`:667-674`），掉线只对同一 URL 指数重试（`:763-839`）。最小改点应在此共享入口上方增加一次“重新解析/择路”，不能分别在 TS/LAN/UI 各补一份。
- `.../pairing/PersistentConnection.kt:67-112`、`ServiceWire.kt:151-181,195-230,265-344`：冷启动和配对都注入单一 `ConnectionConfig(url,token)`；Session/前台服务均复用 `ServiceWire.manager`（`SessionRoute.kt:141-196`、`MirrorForegroundService.kt:141-170`）。配置模型变化会影响 WebSocket、上传及服务生命周期，不能只改配对页。
- `.../MainActivity.kt:83-107,133-154` 是网络 watcher、存储加载、冷启动连接和前后台恢复锚点；`WorkspaceScreen.kt:116-136,262-270` 只在 READY 显示 `ConnectionPath.label`，已是“实际路径徽标”而不是选择器。现有 label 为 `LAN`/`tailnet`，需求名义映射 TS/LAN需在方案中统一。

### 服务端地址和协议

- `corral-serve@911...:internal/pairing/probe.go:53-149,180-260`：`DetectAddresses` 只枚举本机接口，LAN 排序后 tailnet、loopback；排除 link-local/IPv6/RFC2544。`WithTailnet`（`:275-312`）仅注入 userspace TS 自身 IP。`buildCandidates`/`onboardingPayload`（`:231-273`）仍生成 URL candidates，未绑定主机身份。
- `...:internal/pairing/qr.go:17-59`：QR v1 字段仍为 `v,url,token,ts_authkey,candidates`；没有 `host_id`、签名或发现证明。`internal/pairing/token.go:13-37` 的静态 token validator 只验证 token，token 本身是当前唯一绑定凭据。
- `...:cmd/agentmirrord/main.go:149-212,290-327`：有 TS 时 `group.Up` 失败即启动失败（与“TS 失败不提示、不阻止 LAN”冲突）；随后把地址 candidates/token 打印到 QR/guide。`internal/tsnetd/tsnetd.go:97-177,208-262` 总是开 LAN，有 key 才构造 TS，`Up` 只返回本机 tailnet IP。
- `...:internal/api/ws_handler.go:53-70` 与 `internal/protocol/frames.go:37-57`：客户端首个认证帧只有 token，服务端直接 `handleAuth` 验证后回 auth_ack；没有“先验目标主机身份”的握手。客户端 `core-protocol/.../Frame.kt:22-63`、`FrameCodec.kt:25-117` 同样只有 v1 固定 type，新增帧需要两端同改；服务端分派在 `internal/protocol/json.go:74-143`。

## 3. 最小可实施路径（建议，不是已存在能力）

1. **一个主机记录**：将持久模型从固定 URL 改成 `host_id + host token + 加密 TS auth key + 内部 endpoint/验证状态`；发现候选只做内部数据，不暴露输入框/下拉。`host_id` 的生成、持久化、轮换规则当前 **unknown**，必须先定契约。
2. **身份先于 token**：任何新发现地址不得直接发送主机 token。建议发现响应/预握手先证明“该 endpoint 属于 QR 指定 host_id”（签名主机密钥、证书指纹或等价认证均只是候选设计）；证明失败丢弃地址，绝不进入 `AuthFrame`。当前协议没有该能力，具体 challenge/attestation、是否需要新 WS/HTTP endpoint = **unknown**。网络连通、TS Up、端口打开都不能代替身份验证。
3. **复用而非新增扫描框架**：服务端可复用 `DetectAddresses`/`WithTailnet` 作为自报地址来源，客户端可复用 `TsnetWire`/`TsnetDial`/OkHttp SOCKS。是否需要 Android NSD/mDNS、服务端广播或 Tailscale peer API尚未有代码证据，暂不引入；“发现传输”= **unknown**。
4. **集中择路**：在 `ConnectionManager.attemptConnect` 之前（或 `ServiceWire` 之上的单一 coordinator）维护已验证 endpoint，首连/每次重连按 TS→LAN 选择；当前 socket READY 不重建、不主动从 LAN 切 TS。掉线进入下一次拨号前重新刷新发现/验证结果。保留现有每次 transport create 记录实际路径，右上角只消费 READY 后的事实。
5. **静默语义**：TS 入网失败仅内部状态；不阻止 LAN 尝试。两路都不可用时不要将 pairing/connection 错误转成额外提示，workspace 以空状态渲染。需重新定义当前 `PairingStatus.Failed`、`WorkspaceScreen` 的断连 banner 行为，不能复用现有“失败可见”文案。
6. **旧绑定/扫码兼容**：建议 v1 QR `url+token` 仍可解析为 legacy endpoint 记录，已存旧 `PairingConfig` 可迁移；但旧 candidates 曾会携同 token 自动逐地址发送（`PairingViewModel.kt:360-467`），与新安全边界冲突。兼容模式只能在明确的服务端身份验证成立后自动使用，或保留受限单一旧端点；具体迁移与安全取舍 = **unknown**，需裁定后再写实现。

## 4. 影响闭包与 PR 边界

- **确定受影响**：`corral-core` 的配对模型/存储/UI、连接择路与 route 状态、`:core-protocol`（若采用预握手/身份帧）；`corral-serve` 的 QR/配对身份、地址自报、API/协议认证及 TS 失败不阻塞 LAN 的启动编排。
- **不应先假定受影响**：`tsnetbind` AAR/原生 tsnet、Tailscale peer 查询、Android NSD/mDNS；当前接口没有这些能力，是否扩展取决于“发现协议/身份验证”裁定。Web 端没有本需求的证据闭包。
- **最小回滚单元建议**：按同一 host-record + discovery/identity + route coordinator 端到端契约组织一个跨仓变更（客户端 corral-core PR 与服务端 corral-serve PR 成对、同一协议版本/兼容策略），而非按 TS/LAN/UI 机械拆三份。若最终身份仅在 QR/HTTP 预握手完成，则可不改业务 WS frame；否则 `core-protocol` 与 Go `internal/protocol` 必须同一回滚单元。旧 v1 迁移测试与新路径测试应随 owning PR 收口。

## 5. 建议验证面（仅场景，不是最终机械判据）

- 新扫码只显示主机/token：TS 与 LAN 均被发现；TS Up 但目标不可达时不得误判可用或发 token。
- 未验证/伪造发现地址、host_id 不匹配、身份证明过期/篡改：token 不得出现在首个未验证请求；TS auth key 与主机 token 互换必须失败。
- TS 可用/LAN 可用四格状态：首连和断线重连 TS 优先；TS 失败静默转 LAN；两者皆失时工作区为空无弹窗/错误条；当前 LAN 在 TS 恢复时不抢占。
- 已连链路掉线后才重新择路；下一次重连可从 LAN 变 TS，或 TS 变 LAN；实际 route badge 只显示成功连接的 TS/LAN。
- 旧 v1 QR、旧持久配置、重复扫码、进程重启/TS key 更换的迁移与单实例语义；上传基址必须跟随当前有效 endpoint。
- 资源与回归：发现无固定高频扫描/子进程，候选/缓存有界；现有连接秒开/无空白与性能基线不回退；覆盖安装、APK signer/升级与服务端二进制身份保持可追溯。

## 6. 未决项（不得猜）

发现传输（TS peer API、mDNS/NSD、广播或服务端自报）、host_id 来源/生命周期、身份预握手载体与密钥信任根、LAN 地址验证方式、QR schema/version 及 v1 安全兼容、TS 入网失败时服务端 listener 生命周期、AAR 是否要扩展、上传在路由切换后的 endpoint 语义，均无现有代码契约；应先形成协议/安全裁定，再派开发与判据。
