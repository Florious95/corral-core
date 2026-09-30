# R1 / Issue 14 冻结执行目标

裁定时间：2026-09-08。用户授权原始 Issue 标准驱动、单角色端到端独立 PR、滚动功能基线。本文件取代旧 PERF14 各阶段派单及旧角色的 discovery-only/作者不得运行 App 限制。

## 原始需求和裁定

原始 Issue：https://github.com/Florious95/corral-serve/issues/14。
原文正本：`.team/nodes/perf-triage-astra/tmp/rolling-foundation/ISSUE-14.md`，SHA256 `645e65b2b80583dba3dc31f8a7ea7a3cf50618aa67d97c6927b73ce7ab011352`。
采用同目录 `ISSUE-ACCEPTANCE.md` 的 14-A 至 14-F；本文件消除其中待冻结字段。其他四项标准暂作为后续轮次的需求映射，未定字段必须在该轮执行前冻结。

目标：固定页面大小的历史加载不再搬运全历史，实际区间、正文与空白语义正确；真实 App 首批历史和既有分页入口显示正确，资源有界且性能不回退。

## R0 输入与资格门

- serve 源码固定 `b98504e742ed1e7c7475767c512934b07eac592b`，由 owner 在本地对象核 tree；基线二进制须从该源码构建并记录 hash，不用其他 commit 的二进制冒充。
- App 固定 `04bfde8d161c244c9dfced3b5ab90d22d1460d86`；full tree `6121f88087b80ba1014d843fcba13a3528598002`；app subtree `ba02ebedbfb9b208ddbba9a165283bf48f218cbd`。
- 现成 APK `/Users/alauda/Downloads/AgentMirror-Three-Surface-NAME-A-04bfde8-signed.apk`，SHA256 `bd4ababfcec3301c19ced02350ff2193f183311f9ec0bdd455145deab3b3e861`。
- 若使用 N，固定安全 binary SHA256 `e5667b9ebe931de7805a87538e03b9a6ee1fb9cb9250c25282f8b054268226b5`，不运行旧 f629。
- 这些是待资格核验的固定输入，不是宣称完整接受的 R0。历史来源引用 `CAUSE-AND-BASELINE.md`，由 owner 先用下列功能门建立新流程 R0 证据；不得把 1fa3 或旧 #14 组合混入。
- R0 功能门：专用已知静态集合上的配对/auth（合法可用、非法明确拒绝）、workspace/session 列表及 socket-qualified ref 一一对应、名称/状态/收藏针对既有支持集合正确；正常终端首屏完整、连续增量、具名输入回显、实际 resize/reflow、后台回前台恢复、退出清理。动态历史 7/35 数量不是阈值。具体自动测试名与设备动作由 owner 在执行前映射并固定，不能执行后删失败项。
- #14 全历史捕获缺陷及已知其他原始 Issue 缺陷单列为 R0 已知问题，不要求基线先修好本轮目标。若上述正常功能门本身失败，保留事实、判资格不可成立，不伪造既有接受；定位明确后一次报真正超范围问题。

## 唯一 owner / PR / 写范围

- 唯一端到端 owner：multisocket-luna。沿原 PR24 `fix/scrollback-page-metadata` 正常追加，不建补偿 PR、不 force、不修改历史；PR base 为包含 b985 的 main。本轮精确测试 base 始终为 b985，不随 main 漂移。
- 专用 clone：`.team/nodes/multisocket-luna/serve-perf14-r1`；仅在确认不存在后创建，已存在则核身份及干净状态，不覆盖。旧目录与其他席位产物只读。
- 产品写范围：`internal/bridge/bridge.go` 的历史元数据/捕获相关部分，`internal/api/ws_handler.go` 的分页区间、计行与请求处理。相关 Go 测试、`tools/perf14/` 验收工具与单一 `.github/workflows/perf14-rolling-acceptance.yml` 可改。
- 旧 metadata/计行差量可复用，必须机械核对，不夹带 #15/#16/#17/#10 产品。既有 PR24 载体/旧 workflow 保留，不把载体 tree 与被测 tree 混同。
- App 04bf 本轮不改产品和 APK；允许同一 PR 的 tools/perf14 保存外置测试/采集工具。先找到真实既有分页入口，使用 UI 操作触发；不得注入假的请求来代替 App。若完整 wire/消费证据证明实现本 Issue 必須改 App 产品，交精确阻塞及最小跨仓差量建议，不擅自新建第二 PR 或砍掉验收。
- basegen 使用真实任务 ID `perf14-scrollback-page-metadata`，在 own tree 核影响闭包及既有架构门；工具缺陷不伪 PASS，不复制工具引擎或泛化量具。

## 冻结功能与性能门

14-A/B/C/D 按原表独立数值 oracle 执行。空历史纯历史请求固定 FromLine=0、LineCount=1、Data=LF，不 capture 屏幕；无 LF 非空一行计 1；仅去 capture 的最后一个分隔 LF。当前 geometry 必须实际读回；受控变化最多一次重查，错误不能伪装为空页。

14-E 必须设备实测：H1200/P400、逐页到顶、空历史/单行/真实尾空行、历史屏幕跨界、actual/catalog 高度不同、alt 正确显示。完整 trace 关联 UI → App 分页入口 → WS req_id/ref/from/count → raw 响应 → App 应用与截图；同预取周期不叠发、无漏行/非预期重复/残留。READY scroll-wheel 不是分页证明。具体下一请求由已应用真实 anchor 推导，不能只断言旧写死值而不核实际历史。

14-F 本轮服务端 A/B 判据（leader 本轮制定，不冒称原 Issue 或输入透传合同原数值）：

- A=b985 精确服务端，B=本轮候选；App/APK完全相同。允许相同 APK，因为本轮自变量是服务端；禁止把此结果称为旧输入透传 APK A/B 合同通过。
- 先过 `sh tools/perfbase/envcheck.sh --gate`；环境无效即不可判。固定相同设备、network path、geometry、列宽/ANSI、页面 P400，各 H400/10000/100000，顺序 A/B/A/B，每 block 10 个冷点开样本，即每 H 每侧 20 个；不删极值。
- T_full：设备单调时钟的真实会话选择动作开始，到当前屏与首批历史均已由生产 App 应用且内容核对正确。采集手段不能确定这两个端点时记不可判，不用主机 WS 收包时刻或 screenshot 采样代替精确应用时刻。owner 可复用现有生产日志及外部采集，不为了打点改 APK。
- 每 H 的 T_full p50/p95：B/A 均 ≤1.10；失败率不高于 A，任何候选内容错误仍直接功能失败，不能用平均值抵消。H100000 另要求候选 p50 < 基线 p50，以实际完整加载样本证明本轮高历史场景改善；捕获字节与 alloc 单列原始统计，不能冒充耗时。
- 正常场景每样本最多 30s；届时仍不完整计失败/超时并保留，不删除样本；超时是测量上限，不是用户体验目标。报告所有样本与计数，基线异常不得无限测到绿。
- R1 功能基线只有 14-A…E 与全部累计功能门通过才可供 leader 晋升。14-F 未过则性能优化交付仍未完成；记录功能/性能分别状态，不把功能 R1 宣称为完整 Issue 交付。用户真机“秒开无空白”最终接受独立保留。

## 执行责任与证据

目标模式：你自己持续完成 R0资格 → 原因/复现 → 最小修复 → 远端红绿与兼容 → PR交付 → 精确产物取回 → 专用 App 实测与累计门 → 性能 → 清理。普通量具修正由你处理，不每个环节交还 leader，不以准备完成结束。

旧失败直接引用 `PERF14-APP-FIRST-FAILURE-REVIEW.md`。静态源采用 owner 完成信号控制寿命，不靠 600s 自然退出；每场景前后核服务/fixture 身份及存活；先失联则停止依赖动作并保留退出码。禁止猜旧行来源。

远端唯一新入口 `.github/workflows/perf14-rolling-acceptance.yml`，正常 clone 精确 base/candidate，产物记录实际 source/tree/patch、Go build info、binary hash/bytes。Go `-race -count=1`；如必要的外置 App 测试使用 hosted Gradle `--rerun-tasks`，本机禁 Go/Gradle 编译测试。基线已知 race 分项留名和原件，不混入本轮行为红或抹成全套绿。

owner 自己完成 commit/push/更新同一 PR、workflow准备与取件。若 OAuth 缺 workflow scope，先一次交完整可审文件/hash/diff给 leader 做必要 UI 发布；这是权限操作，不把诊断/构建/验收再拆给别的角色。禁止 Codex Apps、改身份或全局代理；既有 gh 子进程直连可用。

禁止 CI/队友/文件状态轮询。每个不可即时完成的远端运行，先记录具体提交和入口，owner在该入口声明的最长运行时间加2分钟后作一次精确定位/取件（不能把终态通知当作已接通）；期间推进不依赖结果的工作。仍未终态则记录实际状态并报告有界阻塞，不循环查询。失败保留原件，只有有因果支撑的新修改才运行后继，不无修改重跑。

专用 AVD/adb/socket/loopback 端口允许创建，先过资源与归属检查；使用已安装镜像、已验 APK，数据留自有目录。禁止生产9900、用户5580/5038和真实 pane；凭据仅内存、不回显。结束核自有所有进程/端口/目录清理，保留原始证据。

终态交：准确 PR/commit、R0与候选身份、每个冻结门实际结果、原始日志/trace/截图/样本/退出码/hash索引、已知限制、R1候选清单。正常出口 report_result 一次；只有具体契约或外部能力阻塞发一次所需决定。leader核原件才升级基线；不 merge、不关闭 Issue。
