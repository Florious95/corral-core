# 主机自动发现：PR Portfolio

## 冻结输入与两项 owning PR

- 方案放行：`.team/stable-pr/host-auto-discovery/DESIGN-VERDICT.md`（提交 `6ef9b11480a332ea7c06e2b0c3b50e0dedc1543d`）；协议/行为以 `DESIGN.md`、`GOAL-DRAFT.md` 为准。
- **Server PR**：`corral-serve`，从 `911dd94176a34e11f66b61b15b191216463c48cf` 建 `pr/host-auto-discovery-server`；服务端 host_id、QR、whoami/identify、DNS-SD、TS 失败保 LAN。在任务声明的独立 corral-serve 克隆内直接 push owning 分支，PR base=`pr/server-status-upload-compose`，不运行 mirror 脚本。
- **Client PR**：`corral-core`，从 `eaaa7d47d88e7e2de6c82988fe462e7adf29f86d` 建 `pr/host-auto-discovery-client`；客户端/AAR PeerSnapshot、NSD、绑定模型/UI、异步共享择路、实际徽标、live upload。在任务声明的独立 corral-core 克隆内直接 push owning 分支，PR base=`pr/foreground-resume-refresh`，不运行 mirror 脚本。
- 两个 PR 均为已验收未合并产品线上的 stacked PR，不以 `main` 为目标。已核 PR7/PR74 的 OPEN 状态、head branch 与冻结 SHA 完全相符；开 PR 前只核 base ref 未漂移。两个 owning 分支从 exact SHA 起，不移动/重置历史分支，不改 client 仓旧 `server/` 镜像，不重复过滤远端谱系。实际 commit SHA、PR URL、CI URL 待实施后回填。

## 依赖与并行边界

- 两 PR **可并行实现**，无代码级先后依赖；共同依赖冻结的 HTTP/QR/DNS-SD/PeerLines 文字契约。Server 不等 Client，Client 可用假 handler/AAR seam 开发。
- 第一阶段：各 PR 各自实现、模块测试、提交 exact HEAD、开 PR。第二阶段：各 PR 各自跑在线 CI/Grok/full；AAR 生成只在 Grok。第三阶段：两端 exact revision 配对做 R1 默认 `:9900`、identify→WS、TS/LAN 路由与 R2 key 时序验收。只完成一仓不算交付，不先合服务端。
- `docs/protocol.md` 物理上属于 corral-core，是唯一人读协议文档，由 Client PR 更新；Server PR 只实现/测试契约，不在 corral-serve 新建重复文档。`:core-protocol` 不改业务帧、不纳入回滚单元。

## 真阻塞与验收边界

- **当前无设计阻塞，可派两 PR**。R1 已冻结：无记录/QR/NSD/last-good 时 TS whoami 仅打协议默认 9900；非默认无提示如实不发现，不恢复手填/全端口扫描。R2 已冻结：READY 只写最新待用 key，下一代次才应用；有→无时分类前禁用旧 manager，不能把 stale Up 放行；改 key 期间 TS/LAN 的 stop/close/AuthFrame 增量均为 0。
- 不把未就绪装置误当阻塞：PeerSnapshot/AAR、NSD 组播、tsnet accepted `LocalAddr`、真实发现夹具仍 **UNJUDGEABLE**，由实施/配对验收在隔离环境完成；失败按 DESIGN §11 的静态退化语义，不扩大范围。
- 功能回归必须承接 DESIGN §10：17 项全名、前台五项、性能 `envcheck --gate` + 同批 A/B/A/B（三夹具四段、`B/A≤1.10`）、签名/覆盖安装。未跑、0 tests、缓存绿、身份不匹配均不得宣称通过。
- 任何安全/协议/资源阻塞只由对应 owning PR 提交一条结构化 blocker（事实、坐标、已完成证据、为何不能继续、所需裁定、最小恢复）；不另拆 TS/LAN/UI PR，不建补偿 PR。

## 交付与不可逆动作

- 每个 PR 一事一闭环：commit → push/open PR → exact revision CI → 独立验收证据。两 PR 配对 acceptance 后才可讨论组合；merge、release、替换/重启生产 binary、生产状态仍需单独授权。所有编译/大测试走 Grok Bot/CI；本机不编译、不起设备、不碰生产/用户 tailnet/真实凭据。
