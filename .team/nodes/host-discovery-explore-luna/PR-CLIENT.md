# PR Task: host-auto-discovery-client

- `id`: `host-auto-discovery-client`
- `base_sha`: `corral-core@eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`（PR74；不要以本地主仓 dirty `main` 或移动后的 PR74 分支作基座）
- `base_branch`: `pr/foreground-resume-refresh`（已核 PR74 OPEN，HEAD 与冻结 SHA 完全相同；不是 main）
- `worktree`: `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/host-auto-discovery-client/repo`（独立克隆 corral-core，不复用 dirty main）
- `goal`: 客户端从“固定 URL + 候选 URL”改为主机记录与自动发现：未绑定可发现并选择主机后再收主机 token，已绑定在共享连接入口异步地以 TS 优先择路，身份验证前绝不把 token 发给发现地址。

## Write scope（corral-core 仓库根相对路径）

允许写：
- `app/app/src/main/java/dev/agentmirror/app/pairing/PairingModels.kt`、`QrPayloadParser.kt`、`PairingConfigStore.kt`、`PairingRoute.kt`、`PairingScreen.kt`、`PairingViewModel.kt`、`PersistentConnection.kt`；同目录新增 `HostRouter`/`HostDialCoordinator`/identify 客户端/主机记录实现及其测试；
- `app/app/src/main/java/dev/agentmirror/app/tsnet/TsnetBackend.kt`、`GomobileTsnetBackend.kt`、`TsnetDial.kt`、`TsnetWire.kt`、`TsnetManager.kt`；
- `app/core-conn/src/main/java/dev/agentmirror/app/conn/ConnectionManager.kt`，及其连接管理测试；`Connection.kt` 不改业务 auth 帧，除非为保持现有调用契约确有最小接线改动；
- `app/app/src/main/java/dev/agentmirror/app/service/ServiceWire.kt`、`OkHttpWebSocketTransport.kt`；`session/SessionRoute.kt`、`SessionViewModel.kt`；`workspace/WorkspaceScreen.kt`；
- `app/app/src/main/AndroidManifest.xml`（仅 NSD/组播发现所需权限）；
- `tools/tsnetbind/tsnetbind.go`、`tsnetbind_test.go`、`README.md`，必要的 `build.sh` 绑定接线；Grok 生成的 `app/app/libs/tsnetbind.aar` 与 `tsnetbind-sources.jar`；
- `docs/protocol.md`（corral-core 中的唯一协议人读文档，补 QR/`whoami`/`identify` 契约）；
- 受影响的既有测试文件与新增测试文件，仅限 `app/app/src/test/**`、`app/app/src/androidTest/**`、`app/core-conn/src/test/**`、`tools/tsnetbind/**`。

禁止写：服务端 `server/**`（尤其禁止顺带改 core 仓旧 server 镜像）、`app/core-protocol/**` 业务帧、Web、PR72/73/74 历史、生产状态与凭据。`app/app/build.gradle.kts` 无需为平台 `NsdManager` 添加 Maven 依赖；若实际接线必须改，须只保留该依赖/打包的最小变更并在 PR 说明。

## Initial read scope

- `.team/stable-pr/host-auto-discovery/{DESIGN-VERDICT.md,DESIGN.md,GOAL-DRAFT.md}`；
- `requirement-base/entries/101-主机自动发现与TS优先路由.md`、`docs/protocol.md`；
- `app/app/src/main/java/dev/agentmirror/app/pairing/{PairingModels,QrPayloadParser,PairingConfigStore,PairingRoute,PairingScreen,PairingViewModel,PersistentConnection}.kt`（实际文件名按仓库为 `QrPayloadParser.kt`）；
- `app/app/src/main/java/dev/agentmirror/app/{tsnet,service,session,workspace}/` 中上述限定文件；`app/core-conn/src/main/java/dev/agentmirror/app/conn/{ConnectionManager,Connection}.kt`；
- `tools/tsnetbind/{tsnetbind.go,tsnetbind_test.go,build.sh}` 与当前本地 AAR 生成面；
- 既有测试：`QrPayloadParserTest`、`SharedPreferencesPairingConfigStoreTest`、`PairingViewModelTest`、`PairingScreenClockPumpTest`、`PairingTsnetWindowProbeTest`、`ConnManagerTest`、`ForegroundResumeTest`、`OkHttpWebSocketTransportTest`、`ServiceWireStaleConfigTest`、`SessionUploadBaseWiringTest`、`TsnetDialTest`、`TsnetWireTest`；
- `.team/nodes/host-discovery-explore-luna/{EXPLORATION.md,CAPABILITY-EVIDENCE.md,CLOSURE-v3.md}`（能力导航；架构工具缺失保持 unknown）。

## Non-goals

- 不改 `core-protocol`/Go 业务帧，不重做 `Connection.onOpen → AuthFrame`；不把 identify 当登录。
- 不让用户填写/查看/选择 URL、IP、端口、候选地址或 TS/LAN 路径；不恢复候选下拉、TS 失败或“两路不可用”提示。
- 不将 TS auth key 当主机 token；不向未经 identify 的地址发送主机 token；不把 TS Up、端口开、peer 自报 host_id/hostname 当目标主机可达或身份证明。
- 不扫描 `100.64/10` 或全端口，不把 NSD 当 TS 发现，不扩 PKI，不改变旧客户端行为。
- 不修改服务端 `server/**`、生产 daemon、旧验收结论；不在本机编译 AAR/产品，不操作生产或用户 tailnet。

## Expected behavior

1. **主机绑定与发现**：无扫码可选填 TS key；先在 LAN/TS 列举公开候选主机，再让用户选择主机（不是路径）并输入主机 token；whoami/发现前零主机 token、零 `AuthFrame`。同一 host_id 多地址合并；name 仅展示。TS-only 无主机记录/QR/NSD/last-good 时仅向每个 peer 的字面 IPv4:产品默认 `9900` 请求 whoami；可信记录优先级为主机记录 → QR/主 URL → NSD → 9900，非默认无提示范围明确不发现。
2. **Peer/AAR**：扩展 `tools/tsnetbind` 导出受控扁平 `PeerSnapshot(knownId,cursor) -> lines,nextCursor`；Go 侧读取完整 `Status.Peer`，已知 `ts_node_id` 先全表匹配，未知按 StableID 旋转窗口/跨代 cursor 续扫。`Online` 只排序，hostname/name 不匹配身份；无 PeerLines 时 fail-closed，不扫 100.64/10。Grok 重建 AAR 并验证 16KB 对齐，不能把当前仅 Proxy API 的 AAR 当已具备 peer API。
3. **LAN NSD**：使用平台 `NsdManager` 的 `_agentmirror._tcp` discover/resolve，TXT 仅 host_id；只在发现窗口持有所需 MulticastLock，manifest 权限最小化；NSD 不穿 TS。候选端口来自 resolve，仍须 identify。
4. **证明后拨号**：候选仅使用原始字面 IPv4:port；identify HTTP 成功、`bound` 与 dest 一致且 MAC 通过后，才允许同一字面地址 `transport.create` 与现有 WS `AuthFrame`。OkHttp 本路径禁重定向/DNS；identify 失败不 create、不发 token；旧 legacy 直 auth 仅 §3.5 的精确 404+来源/地址/未升级身份白名单。
5. **共享异步择路**：`ConnectionManager.attemptConnect` 是唯一 create 方，启动一轮 generation；HostRouter/Coordinator 不自己建 WS。取消、过期回调、候选失败推进、每地址一次 create、identify 并发 ≤4、WS 并发=1、identify 2s、WS 3s、TS ≤4s/LAN 槽、总 8s 均落实；pump 不同步跑发现/Status/IO，删除 `whenSettled` 等待门闩。READY 期间不因另一条路径/TS Up 重拨；掉线下一代重新发现且 TS 优先。
6. **R2 key 边界**：同 host/token 仅变 TS key 时，READY 期间只持久化最新待用值，不调用会 stop 旧节点的 `ensureStarted`，不 stop/close/新 auth、不拆当前 TS/LAN socket；下一连接代次才应用最后一把。若有→无，下一代次在候选分类前停用旧 TS manager 或等价 fail-closed，不能让 stale Up 放行；无→有与连续修改只应用最后值。
7. **记录、上传与 UI**：持久化 host_id/token/port/ts_node_id/name/加密 TS key/legacy URL/last-good/scan hints，`clear()` 全清；READY 原子写当前 endpoint、实际 `ConnectionPath`、live `ServiceWire.uploadBaseUrl`、last-good 和降级 URL 后再发布 READY。Session VM 每次上传读取 live base；徽标只显示 READY 的实际 TS/局域网；已绑定非 READY 且非 auth 拒绝时工作区空白无文案。
8. **兼容**：v1 QR/旧 prefs 迁移保留合法主 URL；新客户端不对旧 candidates 直 auth；Keystore 失败整份失效；旧 daemon 仅精确扫码主 URL 404 可走 legacy。服务端 PR7 仍可由旧 App 使用 url。

## Failure shape / reproduction

- TS Up 失败：静默、LAN 仍并行；两路不可用：工作区空白且无弹窗/错误条/重连文案。NSD/PeerLines 失败：静态提示可重证但不得宣称自主发现 PASS。
- 伪 peer/DNS-SD/自报 host_id/端口开：不发 token、不 create、不产生 AuthFrame；whoami 的公开 port 不能改原始 IP，仍需 identify。
- HMAC/nonce/host_id/bound/重定向/DNS/旧 404 谓词不满足：候选丢弃并推进或退避；auth_ack 拒绝进入 STOPPED，不换候选喷 token。
- 过期 generation、取消、重复 start/网络/前台回调：无旧回调 READY、无双 create、无重连风暴；发现窗口/缓存/线程/子进程资源有界。
- READY TS/LAN 改 key：`stop/close/AuthFrame` 增量为 0；自然断线后只用最新 key，空 key 不得沿用旧 TS Up。

## Dependencies on other PR tasks

- **代码依赖：无硬依赖，可并行**。服务端 PR 提供匹配的 `/pair/whoami`、`/pair/identify`、QR/NSD/TS 启动契约；客户端可先用冻结契约与假 handler/AAR seam 实现。
- **验收依赖**：两 PR 必须以匹配协议版本、各自 exact HEAD 做成对 CI/隔离发现验收；只完成一仓不宣称功能交付，不能先合服务端。

## Architecture closure artifact

- `.team/stable-pr/host-auto-discovery/{DESIGN-VERDICT.md,DESIGN.md,GOAL-DRAFT.md}`；`.team/nodes/host-discovery-explore-luna/{EXPLORATION.md,CAPABILITY-EVIDENCE.md,CLOSURE-v3.md}`。
- `/Users/alauda/team-agent-scratch/wiki-tooling` 已确认缺失；不得复制生成器、恢复工具或宣称架构闭包 PASS。影响闭包锚点为 pairing → core-conn/ServiceWire → session upload/workspace，tsnetbind/AAR 与 NSD 为新增验证边界。

## Module tests（实现期）

- **新增用例**：`HostDiscoveryFlowTest`（TS key-only 多 peer，9900 whoami，选择主机后收 token）；`HostIdentifyClientTest`（无 token、字面地址、MAC/重定向/DNS/伪端点）；`TsnetPeerSnapshotTest`（>256/目标序末/known ID 全表/cursor）；`HostDialCoordinatorTest`（generation/cancel/单 create/失败推进/8s+LAN 槽）；`TsnetKeyRotationTest`（TS READY、LAN READY、有→无、无→有、连续改 key）；`SessionUploadLiveBaseTest`；NSD 权限/窗口和 AAR/16KB seam。新测试必须实际执行并记录数量、失败名、耗时，禁止 0 tests、缓存绿或重跑刷绿。
- **既有范围**：扩展并保持 Initial read scope 中列出的 pairing、conn、service、session、tsnet 测试；`app/core-conn` 的现有重连/前台测试与 app 现有 UI/工作区行为不得回退。

## GitHub CI

- 远端仓库：`Florious95/corral-core`，PR base branch=`pr/foreground-resume-refresh`，基点必须仍为冻结的 `eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`；新 owning branch 从该 exact SHA 起。CI 必须绑定 client exact HEAD，并覆盖 core-conn、pairing、tsnet/AAR 接线、旧 v1 兼容。
- CI 结果须含 revision、job URL、实际测试数/失败名/耗时；不采信 `(cached)`、`UP-TO-DATE` 或文档自报。

## Grok/full tests

- Android/Gradle 编译、AAR `gomobile bind`、16KB ELF 对齐检查与受影响模块大测试只在 Grok Bot/CI；本机不编译、不生成/替换 AAR、不起设备。
- Grok/full 需覆盖 client 单测、AAR PeerLines 生成 Java API、TS/LAN route seam、R1/R2 contract tests、旧 QR/prefs/上传/前台回归；收据记录 exact SHA、环境、执行数量/失败名/时长/产物 digest。

## Subscription real-machine tests

- 仅在实施/配对验收阶段用隔离测试节点与批准的测试凭据：TS-only 默认 9900、LAN NSD、两路四格、TS 黑洞不阻断 LAN、实际徽标/静默空工作区、断线再择路、TS/LAN READY 改 key 与下一代应用。
- 真实设备须按 `DESIGN.md:327-396` 的功能矩阵复核：原 17 个 androidTest（完整名字以 `DESIGN.md:356-372` 为准）、前台五项（`:374-386`）、覆盖安装/signer（`:392-396`）；性能先过 `sh tools/perfbase/envcheck.sh --gate`，再同批 A/B/A/B（三夹具四段、`B/A≤1.10`，A 绑定 baseline release 与参考 APK）。本任务未运行这些装置，不得标 PASS；禁止生产/用户 tailnet 扫描/真实生产凭据。

## Evidence required

- client owning commit SHA、实际 GitHub PR URL、exact HEAD 与冻结 base SHA ancestry；PR body 写明只改 corral-core、未改旧 server 镜像、无 merge/release/生产动作。
- 模块/CI/Grok 收据：exact revision、环境、实际执行测试数/失败名/耗时、AAR/16KB 产物身份；不得含 token/key/生产日志。
- R1/R2 证据：默认 `peerIPv4:9900` 真 whoami 请求、非默认无提示边界、token 发送时序；TS/LAN READY 改 key 的 stop/close/AuthFrame 计数及下一代 route；空 key 禁用 stale manager。
- 配对证据：伪候选零 AuthFrame、identify MAC/重定向/DNS/legacy 404、旧 QR/prefs/Keystore 迁移、live upload host 与 READY WS host 一致。
- 回归证据：17 项全名、前台五项、性能 envcheck/A-B、signer/覆盖安装，均绑定实际 exact revision；任何缺证据保持 UNJUDGEABLE。

## Branch/PR naming

- 从 exact SHA 新建 owning branch：`pr/host-auto-discovery-client`。
- 在声明 worktree 独立克隆 `https://github.com/Florious95/corral-core.git`，从 exact SHA 新建分支；这里已经是目标远端谱系，禁止再运行/裁剪 mirror 脚本或过滤历史。仅在该克隆执行 `git push -u origin HEAD` 与 `gh pr create --repo Florious95/corral-core --base pr/foreground-resume-refresh --head pr/host-auto-discovery-client`（已存在则更新同一 PR）。开 PR 前确认 base ref 仍为冻结 SHA，若漂移则报告，不能默换基座。不 push main、不改历史分支；实际 SHA/PR URL 回填证据。
- 允许 owning branch commit/push/open PR；**不允许** merge、release、替换/重启生产 binary。若旧 PR 分支移动，仍从 `eaaa7d47…` 新建，不移动/重置冻结基座。

- `Merge boundary`: open PR only; merge after independent paired acceptance and explicit authorization。
- `Blocker contract`: 只能提交一条结构化 blocker（阻塞事实+源码/测试坐标+已完成证据+为何无法安全继续+所需裁定+最小恢复动作）；不得发送进度或完成闲聊。
