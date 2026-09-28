---
name: ui-repro-luna
role: 服务端命名投影修复（复用已完成红测席）
provider: pi
model: openai-codex/gpt-5.6-luna
auth_mode: subscription
dangerously_skip_permissions: true
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
---

用户已要求按精简流程继续实现。本席原App/Codex红测任务已完成，现在复用此席实施T-NAME的服务端部分，不新增调查席，不重跑同类改前调查。按/goal语义持续到实际测试+PR交付或具体阻塞；不是“准备完成”即结束。基本模型Pi/Luna，当前用户令覆盖历史Grok强制令。

入口均在 `/Volumes/nvme/Projects/远程Agent安卓`：`.team/TASKS.md`（最新调度优先）、`.team/regressions-20260907/GOAL.md`、`.team/nodes/status-contract-astra/PR-TASKS.md`的T-NAME服务端写界与兼容要求。用户已批准去掉服务端命名等待T-N/T-S的人工前置；本席只写serve命名/API协议及相应测试，App仍由app-fix-luna单写手后续实施。T-S接线后最终组合只取本席命名差量，绝不能重新带入旧manifest。

已成立红：GitHub run34089160064/job101638970010；test commit b0a87836e333af990194198dc786f51cf04b5c80；精确S-RUN 33b4c481e7545e1194935fd9ea373ed035463bea的产品源未改变，既有listing与真实Level2共4断言got node/want官方完整标题。证据 `.team/nodes/status-ui-repro/tmp/codex-consumer-red-receipt.json`、run log及`consumer-red/internal/api/codex_name_consumer_red_test.go`（sha256 575a8a385bd5e446482e1d7192c57ba654a766b845c856cbcea0c1ba6dc59037）。直接复用该测试和已经实际成功执行的GitHub hosted入口，不重复复现、不用手写输出作证。活provider身份在原CLI结构观察中仍unknown；本红是受控provider输入的真实转换红，不冒充全链。

在自有 `.team/nodes/status-ui-repro/serve-name/` 建独立worktree/clone，从精确S-RUN开始，仅导入已有test+最小CI。不要更改原consumer-red或其已冻结test分支。因当前无远端分支head恰为S-RUN，明确允许创建只指向精确33b4的冻结base分支 `base/status-name-33b4c481`（若已存在不同值停下，不覆盖），产品分支 `pr/codex-pi-display-name`；新PR明确base为该冻结分支。这仅隔离本次diff，不是合并/部署手验堆栈或晋升产品基线。先按已复现事实绑定现有对应Issue；确无合适Issue则建一个再开修复PR，不再立调查任务。不要伪称Codex命名是PR86近期引入的回退。

唯一修复：确认provider为codex时，server name保留官方完整PaneTitle，仅显示投影，不裁分隔段、不解析纯thread名、不从spinner判活；空标题明确未知，不退window=node/cwd/编号。Pi显示权威session_name，缺失/冲突保留未知，不能伪造Pi名字弥补probe。Claude/Grok已验命名规则不变。结构ref/socket/pane/session/window_name/window_index与显示name分离；按现有App字符串契约显式输出window_name/window_index，不能把官方title回填成结构名。复用同一既有转换路径供listing/Level2，不各写一份，不新建classifier。

精确写界：serve `internal/api/{listing,level2}.go`及必要catalog共享转换、`internal/protocol/frames.go`、对应JSON/转换/WS测试与必要协议说明，以及一份最小GitHub CI入口。不写internal/nodeprobe manifest/runner、discovery枚举/过滤、App、图标、收藏规则；不动serve#6/#7/#11/#12 heads。不改ref算法或收藏key。开工按现有工具算受影响闭包，缺工具/partial如实列未过，不复制工具实现、不扩大架构重构。

验证：原4断言同条件变绿；加Codex空title未知、Pi合法/缺失/冲突投影、Claude/Grok保持原规则、window结构字段/JSON类型、仅name变化推送且ref不变；既有API/协议相关测试与全socket/shell过滤兼容。Go必须-count=1，真实执行数/失败集合/CI run head绑定，不跳过旧失败来造绿。现有测试入口若原push分支过滤不匹配，只做必要更改，不新造编排系统。目标源码/产物经Git；本机禁止Go/Gradle/Rust编译。Grok Bot空间门未解且清理已停止，不再访问/扫描/清理远端磁盘，也不反复preflight；直接用已验证的GitHub hosted执行路径。无production token，无pull_request_target，默认只读workflow权限。

安全：不读pane正文、argv、凭据/profile/env原文、全机thread库、生产日志；不运行旧probe/daemon/heartbeat于真实或共享socket。所有API测试用自有合成sampler/WS，不调用真实nodeprobe。临时文件仅本席tmp。不碰9900、全局binary/extension/config/skill、用户Pi、其他团队或主仓脏改动。

交付一份 `.team/nodes/status-ui-repro/NAME-S-DELIVERY.md`：精确source/tree、PR URL/base/head、红→绿实际执行身份/失败集、接口字段约定、未执行全链项和整合时仅应用S-RUN→NAME-S差量。只提交声明写界，推corral-serve实际Git远端并创建该独立命名PR；CI输入提交明确未验证，最终验过才称交付。不要循环提交收据文件改变source head，不另写准备/交接。作者不做最终独立验收、不merge、不关Issue、不部署。正常中文report_result一次；仅具体权限/编译/契约阻塞可一封说明，不发进度，不自建子团队。
