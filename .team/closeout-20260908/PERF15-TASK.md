# PERF15：目录扫描不得挡住已有会话首帧

唯一目标：已有可用目录时，同连接 List/L2 的 Discover 或 Sample 被阻塞，已知 ref 的 Subscribe 仍能在扫描释放前收到真实完整 SNAPSHOT。保持目录新鲜性、四轴/名称投影与请求可见结果，不用永久缓存换速度。

## Leader 冻结裁定

采用 `.team/nodes/perf-triage-astra/PERF15-CONTRACT.md` 的最小协调方案，并以下列裁定覆盖其建议：

1. 只异步登记 List/L2 意图；auth/input/resize/unsubscribe 保留 reader 顺序。无已发布目录的冷启动仍有界等待初始化；已有目录中的未知 ref 不新增自动刷新。
2. 每 server 最多一个扫描 worker、一个合并的 pending 代；Discover 与 Sample 使用同代输入。保留原 cadence，无额外固定频率扫描、每请求 goroutine 或新配置系统。请求在 scan 开始截止点之后到达归下一代。
3. catalog/snapshot/listingSeq/L2 projection 一致发布，发送在锁外。选定“先发送本代待答 Listing(S)，再跳过该连接本代 Delta”的连接水位规则。L2 用内部 epoch 过滤过期排队输出；不承诺撤回已开始的网络 write。
4. 内部限额采用每连接 16、全 server 1024 个待答 List；L2 每连接一个当前 epoch 意图。扫描总 deadline 30s，List admission 到终态 35s，冷启动等待最多30s。限额/时限是资源保护，不是性能承诺。用一次性 deadline timer，无每请求 ticker。
5. **不采纳“重复 req_id 即断连”**。保持现有可重复/零值 req_id 兼容；每次被接收的请求按独立内部入队序号记账并各答一次，wire req_id 原样保留。不得以 Map[req_id] 覆盖请求。超限/超时或目录输出排队失败时真实终结连接，明确 catalog_backpressure/timeout 原因，不能冒充 mirror_loss。
6. 自然扫描失败保留原 last-good/无缓存空 Listing 兼容与显式日志，不把失败空回复标记为初始化成功。保留 nodeprobe 已有部分观察/unknown 的含义；只把原接口实际返回的失败视为失败，不新造“某 socket unknown 导致全目录失败”。不扩 ErrorFrame 协议。
7. 取消只移除该连接等待者，不取消他连仍需要的扫描；worker 真退出前不开第二个。Close 后零等待者/worker/继续发布。使用真实可取消生产依赖，不声称能强停任意忽略 context 的 Go 函数。

## 实施身份和边界

- 原始性能固定基线仍为 b98504e742ed1e7c7475767c512934b07eac592b。
- 为避免重写已经验证的传输终结机制，**本 owning PR 明确堆叠于 PR20**：开发 base 为 serve 1fa3b651c613f7b973b5187429d8e9e50d9d3d45，PR base 分支 fix/overflow-resync；新分支 fix/catalog-refresh-coordinator。这是依赖关系，不是宣布 PR20 已最终验收或滚动提升全局基线。
- 作者 real-avd-astra2：Codex / gpt-6-astra / medium。A6 core R4保持冻结，在本席新 serve-perf15 clone 实施；不共写其他席位或修改原 PR20。发现 A6 返工时先在本任务安全边界交接，不并发写同树。
- 只改 server.go、ws_handler.go 的 List/初始化入口、level2.go、ws_conn.go 必须的目录入队/退出/读取接口及一个 scan_coordinator.go；保留 PR20/N7 原机制和其27/2/8及N7证据。不改 #14 的分页路径、不改 discovery/nodeprobe/protocol/App。
- 具体声明位置扩边已裁定：允许 `internal/api/ws.go` 的 `wsMsg` 增加内部 `level2Epoch uint64` 字段，以承载冻结的 writer 过期过滤；仅此声明点，wire 不变，不用队外映射替代，也不授权其他 ws.go 重构。
- 基于已完成报告执行已有 basegen；不能只用文档代替当前base影响闭包。若真实最小实现必须越界，只报告具体调用点，不先重构。

## 可执行验证与交付

先将同连接 Discover/Sample 屏障下的 Subscribe SNAPSHOT 场景写成可在未修复1fa3执行的具名红测。与最小实现同一轮收敛：单扫描/单pending、同代发布、每请求回复、水位连续、L2 epoch、超载/取消/退出及原refresh-on-open兼容。确定性屏障代替性能时间猜测；真实传输终结不接受客户端Read deadline冒充。

限本次既定八组因果验证，不另起通用调度框架。hosted Go -race -count=1，本机禁止编译；静态编译面先自核，一次交精确 source/patch/tested-tree + 单一 workflow接口，编译、旧版具名红、候选绿与相关兼容门分别归档。未验补丁可以作为测试载体，产品验过才正常单项 commit/同 owning PR 推送。最终与 #14/#16/多socket 组合仍需同候选验收和性能环境闸，不能只凭 Git 无冲突放行。

不碰生产9900/用户AVD/真实pane正文与输入，不轮询CI或席位；正常一次 report_result，只有具体接口缺口才发消息。编排材料不算实现完成。
