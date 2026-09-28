# PR Task: host-auto-discovery-server

- `id`: `host-auto-discovery-server`
- `base_sha`: `corral-serve@911dd94176a34e11f66b61b15b191216463c48cf`（PR7；不要以本地主仓 dirty `main` 或移动后的 PR7 分支作基座）
- `base_branch`: `pr/server-status-upload-compose`（已核 PR7 OPEN，HEAD 与冻结 SHA 完全相同；不是 main）
- `worktree`: `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/host-auto-discovery-server/repo`（独立克隆 corral-serve，不复用主仓 server/）
- `goal`: 服务端在 LAN/TS 监听上提供不含 token 的主机列举与 HMAC 身份证明，保留 v1 QR/旧 WS 兼容，并在 TS Up 失败时继续提供 LAN 服务。

## Write scope（corral-serve 仓库根相对路径）

允许写：
- `internal/pairing/qr.go`、`internal/pairing/probe.go`、`internal/pairing/token.go`，以及同目录新增 host-id/DNS-SD 实现与对应 `*_test.go`；
- `internal/api/server.go`，以及同目录新增 `whoami`/`identify` handler 与对应 `*_test.go`；`internal/api/ws_handler.go` 仅在接线确有必要时改动，现有 `handleAuth` 帧语义不得改；
- `internal/tsnetd/tsnetd.go` 及对应测试；`cmd/agentmirrord/main.go` 及对应启动测试；
- `go.mod`/`go.sum` 仅在选定的 DNS-SD 实现确需声明依赖时改动，禁止无关升级。协议人读文档 `docs/protocol.md` 位于 corral-core，由 client PR 更新；本仓不新建重复文档。

禁止写：客户端 `app/**`、客户端仓库的旧 server 镜像、Web、PR7/PR74 历史、生产状态与任何凭据。`internal/config/config.go` 的既有默认监听 `0.0.0.0:9900`（`911dd941:internal/config/config.go:167,190`）是协议依据；除非为测试/文档保持该契约，不顺手重构配置。

## Initial read scope

- `.team/stable-pr/host-auto-discovery/{DESIGN-VERDICT.md,DESIGN.md,GOAL-DRAFT.md}`；
- `requirement-base/entries/101-主机自动发现与TS优先路由.md`；
- `internal/pairing/{qr.go,token.go,probe.go}`、`internal/api/{server.go,ws_handler.go,upload.go}`、`internal/tsnetd/tsnetd.go`、`cmd/agentmirrord/main.go`、`internal/config/config.go`；
- `internal/pairing/*_test.go`、`internal/api/*_test.go`、`internal/tsnetd/*_test.go`、`cmd/agentmirrord/*_test.go`；
- `.team/nodes/host-discovery-explore-luna/{EXPLORATION.md,CAPABILITY-EVIDENCE.md}`（能力事实导航；架构工具目录缺失保持 unknown）。

## Non-goals

- 不修改业务 WS 帧、`handleAuth` 的 token 校验、`/upload` Bearer 语义或 `core-protocol`；不把 identify 当登录。
- 不把 TS `Up`、peer `StableID`/hostname、开放端口或 `whoami` 自报当作目标主机证明；不把 Tailscale `ListenService` 当本产品发现服务；不扫 tailnet/端口。
- 不将主机 token 放入 whoami/identify 请求、响应、日志或 QR 以外的新增输出；不读取/输出真实 token、TS key 或生产日志。
- 不修既有 tsnet 同名冲突；不扩展 PKI、业务帧或 Web 客户端。
- 不承担客户端 UI、AAR、Android NSD、异步 ConnectionManager；这些归 client PR，跨仓只做配对验收。

## Expected behavior

1. **主机身份与 QR**：为每台 daemon 生成独立 16-byte CSPRNG `host_id`，base32 无填充、与 token 同目录但独立 0600 原子落盘；重启/ token 轮换保持 host_id。v1 QR 继续含合法主 `url`/`token`，新增 `host_id`、监听 `port`、可选 `ts_node_id=Status.Self.ID`、仅展示的 `name=os.Hostname()`；TS 未 Up 时省略 node id，不重打 QR。
2. **`GET /pair/whoami`**：公开列举，不读 token、不置 `authed`、不唤醒 listing；响应仅 `v/host_id/name/port`，body ≤1 KiB、每 IP ≤5/s，错误/非 JSON 不成为主机。服务端不重定向；客户端后续仍必须 identify。默认协议引导端口是 9900；非默认端口不由服务端伪造推导，依赖 QR/NSD/已有记录。
3. **`POST /pair/identify`**：请求不含 token；校验 v/nonce/dest IPv4/host_id，使用配对 token 对 `agentmirror-identify-v1` + host_id + nonce + `bound_ip` + `bound_port` 按冻结字节向量生成 HMAC-SHA256 响应，响应不含 token；请求不带客户端 MAC，不新增比较或 nonce 缓存协议。`bound` 唯一取 accepted connection 的 `http.LocalAddrContextKey`；有效非 loopback IPv4 必须等于 dest，userspace/无效 LocalAddr 才按 `DetectAddresses()` 与当前 tsnet IPv4 集合包含 dest 的替代规则；不得退化为只比端口。未知 host、坏 nonce/dest、bound 不符、格式错误均稳定 4xx + code、无 MAC。
4. **LAN 发现**：LAN listener 就绪前或同时注册 `_agentmirror._tcp`，实例名为 host_id、TXT 仅 `id=<host_id>`、端口为真实监听端口，不含 token/TS key；退出撤销/释放资源。DNS-SD 失败不能关闭 LAN listener，失败要有不含凭据的内部可诊断结果。
5. **TS 启动**：先 Listen/Serve LAN；有 TS key 时后台 `Up`/`ListenTailnet` 同 handler。Up 失败不退出、不阻断 LAN、不向 App 发送失败提示；成功后提供 self Stable ID/100.x 提示。`TS Up` 仅本机状态，不直接证明目标 daemon。
6. **旧兼容**：旧 v1 QR 的 `url` 不得删除；旧 Handler 对 identify 的确切 404 由客户端受限处理。服务端不得因新端点改坏现有 `/ws`、`/upload`、token 常数时间校验。

## Failure shape / reproduction

- 假候选/伪 host_id/无 token：whoami 可返回公开且可伪造字段，但不返回任何秘密；identify 失败时无 MAC/无 token。
- HMAC 向量错误、host_id 不匹配、bad nonce、bad dest、LocalAddr 不符或无法满足 TS 集合规则：4xx 稳定 code；不泄漏 token 是否正确。
- body 超限/速率超限/非 JSON 请求：拒绝且不得产生登录状态；日志只记安全的操作数与错误类型。旧 nonce 响应的重放拒绝由 client PR 验证，不据此新增服务端 nonce 去重存储。
- 配置 TS auth key 时 `Up` 超时/失败：服务仍监听 LAN、DNS-SD 路径不因 TS 失败而消失；不输出 key 和 token。
- 每个失败世界都须保留操作数/来源/结论的可诊断证据，但不得记录凭据、业务内容或生产明文日志。

## Dependencies on other PR tasks

- **代码依赖：无硬依赖**。本 PR 可按冻结 DESIGN 的 HTTP/QR/DNS-SD 契约独立实现；client PR 并行实现。
- **验收依赖**：与 `host-auto-discovery-client` 使用匹配协议版本做成对验收；只合/只验一仓不能宣称自动发现交付，也不先合服务端。

## Architecture closure artifact

- 复用 `.team/nodes/host-discovery-explore-luna/EXPLORATION.md` §2/§4、`CAPABILITY-EVIDENCE.md` §1–§5、`.team/stable-pr/host-auto-discovery/{DESIGN-VERDICT.md,DESIGN.md}` §8/§12。
- `/Users/alauda/team-agent-scratch/wiki-tooling` 已知不存在；不得复制/恢复生成器或声称架构工具 PASS。服务端影响闭包为 `internal/pairing` → `internal/api` → `cmd/agentmirrord`/`internal/tsnetd`，旧 `/ws`/`upload` 回归面随 PR 保留。

## Module tests（实现期）

- **新增用例**：host_id 持久化/权限/重启与 token 轮换；v1 QR 保留 url 并新增字段；whoami 无 token、body/rate limit、字段约束；HMAC 向量、host_id/nonce/dest/bound 两种 LocalAddr 路径、常数时间失败形状；identify 响应无 token；DNS-SD TXT/端口/撤销；TS Up 失败仍 LAN 监听；旧 `/ws`/`/upload` 回归。
- **既有范围**：`internal/pairing/{qr_test.go,probe_test.go,token_test.go}`、`internal/api/*_test.go`、`internal/tsnetd/tsnetd_test.go`、`cmd/agentmirrord/*_test.go`。新增测试必须实际执行且记录数量/失败名/耗时，不接受 0 tests 或缓存绿。

## GitHub CI

- 远端仓库：`Florious95/corral-serve`，PR base branch=`pr/server-status-upload-compose`，基点必须仍为冻结的 `911dd94176a34e11f66b61b15b191216463c48cf`。CI 结果必须绑定本 PR exact HEAD、匹配协议版本及旧 v1/旧 daemon 兼容矩阵。
- 不能用 PR7 的旧分支作为新工作分支；固定 base SHA 隔离新交付。CI 证据须含 revision、job URL、实际执行测试数/失败名/时长。

## Grok/full tests

- 所有 Go 编译、server 全量测试、协议夹具与 DNS-SD/TS 隔离测试只在 Grok Bot/CI 执行，本机不编译、不启动服务、不扫用户 tailnet。
- Grok 收口至少覆盖上述新增服务端用例、server 全量回归、HMAC 交叉向量和 TS Up 失败保 LAN；环境、命令、exact revision、执行数量、日志路径写入收据。

## Subscription real-machine tests

- 本 PR 不操作生产；由配对验收在隔离测试 daemon/网络完成：默认 9900 的 TS-only `whoami`、非默认监听无 QR/NSD/记录的明确不发现边界、LAN DNS-SD 端口、TS Up 失败仍 LAN、两类 LocalAddr MAC 夹具。
- 用户 tailnet/真实凭据不作为装置；静态 QR/last-good 重证不能代替自主发现证据。

## Evidence required

- 本 PR 每个关键点的 commit SHA（未验证不封版）、GitHub PR URL、PR exact HEAD 与声明 base branch/冻结 SHA 的 ancestry 说明。
- 服务端测试收据：执行环境、exact SHA、实际数量/失败名/耗时、HMAC 向量结果、host_id/QR/旧协议矩阵、DNS-SD/TS 失败保 LAN 结果；不得含 token/key/log 原文。
- 配对验收收据：client/server exact revisions、请求不带 token 的网络断言、identify→WS auth 顺序、只合一仓的降级行为；不把服务端文档 PASS 当实证。

## Branch/PR naming

- 从 exact SHA 新建 owning branch：`pr/host-auto-discovery-server`。
- 在声明 worktree 独立克隆 `https://github.com/Florious95/corral-serve.git`，从 exact SHA 新建分支；这里已经是过滤后的服务端仓库，禁止再运行/裁剪 mirror 脚本或执行 subdirectory-filter。仅在该克隆执行 `git push -u origin HEAD` 与 `gh pr create --repo Florious95/corral-serve --base pr/server-status-upload-compose --head pr/host-auto-discovery-server`（已存在则更新同一 PR）。开 PR 前确认 base ref 仍为冻结 SHA，若漂移则报告，不能默换基座。不 push main、不改历史分支；实际 SHA/PR URL 回填证据。
- 允许 owning branch commit/push/open PR；**不允许** merge、release、替换/重启生产 binary。若旧 PR 分支移动，仍从上述固定 SHA 起分支，不移动/重置基座。

- `Merge boundary`: open PR only; merge after independent paired acceptance and explicit authorization。
- `Blocker contract`: 只能提交一条结构化 blocker（阻塞事实+源码/测试坐标+已完成证据+为何无法安全继续+所需裁定+最小恢复动作）；不得发送进度或完成闲聊。
