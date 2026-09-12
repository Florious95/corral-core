# 工程刷新：候选代码与本地 Agent 交接

## 状态：候选实现，不是性能验收完成

本分支不是只有 CI 工作流了，但**本提交仍不是原任务的完整交付**。
本轮优先实现二级工程刷新与全局重扫描的隔离，并提供测试和继续验证入口。
一级轻量索引、真实 tmux/accepted nodeprobe 的前后基准、Android 验收仍须完成。
不要以本文件、编译成功或 mock 测试代替用户体验验收，不要合并或部署。

- 仓库：`Florious95/corral-serve`
- 分支：`perf/workspace-refresh-20260912`
- 修复前 main 基线：`655bd4441b57563d39457954420ae3557dc28ac4`
- 本提交父提交：`2494c3c07fa267bc9d24e6371a303e17eb4d9842`（仅增加验证工作流）
- 候选身份：在本提交 checkout 执行 `git rev-parse HEAD` 和 `git rev-parse HEAD^{tree}`；每次后续修订重新记录。
- 用户此前提供的生产身份：`1fb9375e55c65c77ae72a1dbe6e5bf539c530b4b`。本轮没有接入生产主机，不宣称核实了现在运行的生产二进制。
- 不修改 corral-core PR98；其 `0a6a4ed818260ffbda965663726e47660ce0e3a8` 的页面重建、订阅所有权和 `sessions:null` 兼容应继续保留。

## 独立核验的源码链路与实现

基线 `handleLevel2Subscribe -> scans.level2 -> pending generation -> Discover(all) -> SampleModel(all) -> projection[cwd]`。
`maintain` 还会把所有当前 L2 订阅加入每次全局扫描；因此即使只想刷新 A，仍存在全局排队、枚举、采样屏障。
这是已核验的源码事实，不是已完成的生产端到端耗时证明。

候选默认生产路径：

```text
认证 / 原有全局 cadence / List
    -> 原全局协调器
    -> tmux Discover
    -> 更新 WorkspaceIndex（此时就释放路由，不等全局状态采样）
    -> 原 SampleModel / catalog / Listing / list_delta

Level2Subscribe(A) / 原有 L2 cadence
    -> 独立 workspaceCoordinator（2 workers，最多 64 pending workspaces）
    -> WorkspaceIndex.Sockets(A)
    -> DiscoverSockets（每次重新枚举目标 sockets，结果按 CWD 筛选）
    -> SampleModel（只传 A，调用它涉及的 sockets）
    -> 临时终端路由目录 / epoch 检查 / Level2Frame 或 ErrorFrame
```

`WorkspaceIndex` 是工程到 socket 的**多对多路由索引**，不缓存会话结果和运行状态。
一个 socket 多工程、一个工程多 socket 都有索引单测。
全局 best-effort 扫描遗漏慢 socket 时，不把路由误删；只有确实不存在的 socket 才删除。
已知 socket 的定向查询不使用全局 stale-socket 跳过结果制造空表；查询失败返回错误，明确删除才返回空表。

每连接只保留当前 epoch；同工程正在执行时，新刷新进入一个合并的 pending 批次，不使用接收请求之前的快照。
无订阅时定向协调器没有周期性扫描。没有新建高频全局轮询，也没有每请求 goroutine。
内部任务预算为 12 秒，包含内部排队、枚举和采样；网络发送、客户端重试和 UI 时间不在这个数值内。
旧全局批次不再向定向 L2 发帧；临时路由目录使新 L2 引用不必等下一次全局目录发布才进入 `catalogEntry`。
全局批次开始时间早于定向结果时，不能撤销其新增引用或删除墓碑。

新增 `workspace: refresh phases` 日志仅记录数值：内部排队、发现、采样、总耗时，以及 `sample_sockets`、`sample_panes`。
这两个对象数是**传入采样的范围**，不是 tmux 内部真正处理的全部 pane 数，更不是完整 App 端到端测量。

## 必须继续完成的工作和边界

1. **一级尚未轻量化。** `List` 仍保持旧完整 Listing 和全局采样路径。不能宣称一级性能已修复。
   需核查 Android、桌面、收藏、重新连接对完整 Listing 的依赖后，选择兼容的摘要/索引方案。
   不能简单把所有 tmux pane 当作 Agent 计数，或把未识别新 Agent 永久过滤掉。
2. **首次冷路由和新 socket 发现仍依赖原全局 Discover。** 已知 socket 上的新增会话会由定向枚举发现；
   新 socket、新工程进入索引的延迟仍需实测，并可能需要继续拆开全局发现与状态采样的周期控制。
   `DiscoverSockets` 不会为了找新任务回退到全机扫描。
3. **共享 socket 仍是采样粒度下限。** accepted Runner 目前只调用 `nodeprobe -S socket`。
   向 `SampleModel` 传入 A 并不代表这个二进制只检查 A 的 pane；同 socket 的大量 B pane 仍可能影响 A。
   不要绕过 accepted binary 校验、编造 `--workspace` 参数，或以改写后的 fake runner 作为真实通过证据。
4. **冷终端订阅边界。** 新引用已进入 scoped catalog，但 `handleSubscribe` 在全局首个 snapshot 尚未建立时仍调用 `ensureInitialScan`。
   本地 Agent 应补查/修正这条已知引用的冷启动等待路径，并测“列表出现 -> 点击可进入”，不能只测列表行。
5. 2 个慢目标同时占满独立 L2 池时，其他目标仍可能排队或有界失败；这不是任意故障负载下的隔离证明。
   全局采样仍消耗资源；总 CPU、RSS、子进程数及 accepted sampler 在并发调用下的正确性必须复核。
6. 新增 API 测试明确使用 scripted discovery/sampling，不是生产依赖验收；实际 WebSocket/Android、收藏交接和重连需另测。

## 本轮实际执行的检查

当前运行环境是独立 Linux x86_64 容器，不是用户主机；Go 为 `go1.23.2`，`tmux` 不在 PATH，
`github.com` 和 `proxy.golang.org` 的 DNS 检查没有解析结果。
仓库要求 Go `1.26.5`。没有降低仓库 go.mod，没有替换 accepted nodeprobe，没有修改生产配置。

已执行：

- 新增 Go 文件及两处生产接线文件经过 `gofmt` 语法解析。
- `bash -n scripts/workspace-refresh/verify-candidate.sh`。
- **5 个 WorkspaceIndex 单元测试通过，包含 `-race`。** 使用本提交的真实索引实现，以及逐字核验的基线 `model.go`；
  模型文件 Git blob 为 `48da8a67c1abf2c4479efe29e4f0923e5530c2df`。
  执行方式是独立目录的显式文件测试，不是整个 discovery/API 包构建：

```bash
GO111MODULE=off GOTOOLCHAIN=local go test -race -count=1 -v \
  model.go workspace_index.go workspace_index_test.go
```

原始输出：`docs/verification/workspace-refresh/index-unit.log`。

**未执行：** 完整生产构建、7 个新增 API 集成/边界测试、全仓回归、真实 tmux、accepted nodeprobe、
前后延迟分布、持续新任务可见延迟、Android 设备或模拟器验收。没有候选 APK，也没有经过验证的服务端构件 SHA256。
这些不能标记 PASS；基线尚未测量，因此也尚未设置或验证性能通过标准。

## 本地接手入口

使用独立 checkout，不能覆盖根工作树的未提交修改。先阅读本文件，然后：

```bash
# 在新的隔离 checkout 中；目标 evidence 路径必须不存在且位于 checkout 外。
# 先自行准备与 go.mod 匹配的真实 Go 工具链。
CORRAL_VERIFICATION_DIR=/absolute/new/evidence-dir \
  bash scripts/workspace-refresh/verify-candidate.sh
```

该脚本只跑指定回归并编译，不启动 agentmirrord，不调用 tmux，不合并或部署。
API 测试使用独立 httptest 监听器。脚本把编译缓存、模块缓存、日志、候选二进制与 SHA256 留在新 evidence 目录，失败也不清理。
执行前仍需确认整个 checkout 的测试初始化没有现场副作用；真实验收必须另建隔离服务和 tmux fixture。

## 独立真实性能与正确性验收矩阵（全部待执行）

先固定基线和同一台隔离测试机器，完成基线后写下通过阈值，再跑候选；不要事后移动标准。

| 场景 | 固定项和变化项 | 必须记录 |
| --- | --- | --- |
| 规模对照 | 固定 A 会话数；增加其他工程的 server、pane；分别测分离 socket 与共享 socket | L1/L2 的 p50/p95/max；请求数；socket 发现、枚举、采样对象数；CPU/RSS/子进程 |
| 持续创建 | 同 socket 新会话、新 socket 上 A 会话、全新工程 | 从创建到 L1/L2 实际可见、从点击到终端可用的延迟；不能只看转圈 |
| 故障隔离 | 无关慢 socket；A 的一个 socket 慢；多个慢目标使池饱和 | A 成功或明确错误的时间；不能用部分扫描当作权威空表 |
| 删除与页面 | 删除最后会话；权威 sessions:null；退出、重进、页面重建 | 空表不复活；没有晚到全局或旧工程帧污染 |
| 订阅与网络 | A/B 切换、收藏接管/归还、断线重连、重复下拉 | 当前订阅唯一；恢复后内容正确；请求不积压；列表和可点击路由一致 |

App 发请求前、服务端排队、发现/枚举/采样、传输/解码/UI 都要分别计时。
已有 `workspace` 日志只覆盖其中一部分，不能补造缺失区间。

## 现场、后续 PR 与构件

本轮所有代码编辑与测试仅在独立容器内，没有连接、清理、重启或部署生产主机。
本地 Agent 同样必须保留原有 socket、任务、服务、日志、缓存、工作树；不读取凭据、不读取真实 pane 正文、不向真实 pane 输入。
保护 `9900`、`emulator-5580`、ADB `5055`。新测试环境、日志和失败现场也保留，不自动清理。
不要以设置 discovery 路径包含默认主机目录的方式“复用”现场；隔离必须显式且 fail closed。

完成剩余实现和独立验收后，在本分支继续提交并创建/更新 PR；PR 正文记录精确 base/head、全部实际执行与未执行项、
性能基线/阈值/结果、构件身份和限制。仅有这个候选提交不能标记 ready-to-merge。

这是服务端修改，**仅安装 APK 无法生效**。后续部署只能在用户另行授权后进行：
从最终候选 SHA 使用原项目构建方式生成匹配平台的 agentmirrord，记录 SHA256、Go 版本和 accepted nodeprobe 身份，
先在隔离端口/目录完成验收，再另行安排生产更新；本轮不得执行这些生产步骤。
