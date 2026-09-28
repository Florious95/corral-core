# Issue10 外部 -S 自动发现

采用 `.team/nodes/perf16-repair-astra/EXTERNAL-SOCKET-PLAN.md` 的唯一最小方案：保留标准根枚举，补当前 uid 的真实 tmux 原生 AF_UNIX 监听 socket，再走原 scanServer→安全N→listing/L2→App。禁止扫描磁盘或周期调用 lsof/ps，禁止通过 argv 猜 socket。

原固定基线不变，本 owning PR 堆叠 PR23：base b85ec329153d274d22928c56a2f022a7a09ef2c9，PR base fix/tmux-metadata-delimiter；新分支 fix/external-tmux-socket-discovery。作者 perf16-repair-astra（Codex/gpt-6-astra/medium）用本席全新 serve-external clone；旧 N7/PR23 和诊断证据冻结，不共写 multisocket-luna 的树。

## 冻结边界

- 当前产品平台 Darwin arm64/cgo，用 SDK 结构和小型 libproc 绑定；同 uid、真实 tmux 可执行身份、稳定 PID/start-time、AF_UNIX/STREAM/listener、本地绑定绝对路径验证。禁止手写 ABI 偏移和读取 argv/env/正文。
- 实际 socket uid/type、raw/解析后隔离判断、标准根优先ref拼写、dev/inode去重及替换重试保留；不修改 PR22 ta 与 PR23 字段修复。
- 显式 DiscoverWithDirs/Options/E2E scope（包括空集合）调用宿主原生来源次数必须为零。原生运行测试 seam 必须在访问 FD 之前限制为自有 PID，不得全机读完再过滤。
- 每实例最多一个在途原生枚举，无新常驻ticker/守护进程。暂定预算1s、PID最多16384、单tmux FD4096、全轮FD65536、socket1024；满缓冲和超限明确标为不完整，禁止取前N伪装完整。数字是内部资源保护、待hosted核验，不是性能承诺。
- 支持的 Darwin 后端 permission/capacity/deadline 等真实失败保持旧好快照并记具体阶段、操作数和错误；不能发布不完整空列表。
- **兼容裁定覆盖原建议：非Darwin或无cgo构建保持原标准根发现可用，清楚记录外部发现能力 unavailable/standard-roots-only；不得因新后端不支持而让旧平台全部会话消失，也不得声称那些平台已完整支持外部-S。** 不扩展本格去实现其他平台native后端。
- stale元数据只作必要容量/过期清理，不缓存Model或新增注册表。App/协议/nodeprobe/bridge/性能四项代码不改。手机枚举故障UI未新增，诊断可见不等于手机已有完整性提示。

产品限定 discovery/scan.go 与小型 external_sockets 平台文件、api/discoverer.go 必要实例接线；options.go 仅在确需自有PID测试接缝时增加内部测试字段，不授予广泛API重构。

## 实施和验收

直接最小实施，保留同因果旧base可编译具名缺外部路径红；新增helper未定义不能算产品红。计划内八组确定性门、原PR23兼容，hosted Darwin实际libproc系统门必须RUN/无SKIP；Go -race -count=1，本机禁止编译。用相同scope自有socket证明原生来源自动发现，不得用标准根override代替自动发现。

一次性交付精确patch/tree+单一hosted compile/红/绿/兼容/候选Darwin二进制接口，产品验过后同owning单项提交。不增加第二执行方案、反复量具阶梯或未请求平台。外部2+标准6真实Pi refs、重复paneID/共享与分离CWD、pipe+Unicode、shell/隔离反控、退出/重建及真实App逐行截图由后续独立验收执行；作者不能自判最终通过。

可复用本席授权短根 `.team/s/exts-0908/` 仅自有socket/owner，其他文件本席tmp；预建/长度/显式-S/创建后socket_path自证。禁止用户9900/AVD/真实pane正文输入。正常一次report_result；具体接口缺口才消息，不查CI或轮询，不把base对象缺失/框架问题当停工理由。
