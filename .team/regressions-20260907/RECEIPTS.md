# 首轮审计接收与 leader 客观核对

## contract-astra（2026-09-07）

- dispatch `msg_a4635c35d8b0`，result `res_edf2692748d8`。
- 阶段结论：只读契约审查完成；不是五项修复完成，也不是运行/功能验收 PASS。
- 已读 `.team/nodes/status-contract-astra/CONTRACT-REVIEW.md`。
- leader 校验 `tmp/source-index.json`：13/13 快照 SHA256 与索引一致；其中9份另与主仓 Git 对象内容核对一致，4份 serve 快照的源对象不在主仓，未跨仓重核，不冒充已核源。
- leader 核 Git：`b3fa69835f1d80822f328e6549114c60c8fba72f` parent=`3f481f6c7cb74acc8b2e6a29c68b998994de9404`；composition `a5d520ed1845c527624ddaa900514cfbc7b9b8b0` parent=`889404b0a8eedacc7aa40e693bcdd38c9ce4cfdd`。
- leader 一次 `gh pr view 73 --repo Florious95/corral-core --json number,state,headRefOid,baseRefName,url` 成功：OPEN，HEAD `1642902e7b9fe002f72a0a16a6963be2ef865141`，base=`pr/status-core-nodeprobe-863c`，URL https://github.com/Florious95/corral-core/pull/73 。Astra 先前的 TLS timeout 是当时查询边界，不是 PR 不存在；当前只核远端身份，尚未核精确 PR diff 的引入映射。
- 代码边界：历史 App 候选 `health!=normal` 抑制合法 working；serve 历史候选33b4c481保留四轴，不能用 main 旧Go源码代表运行服务。是否命中今天现场仍等实际身份/真实输入。
- Astra 在报告内提供官方 session 元数据 `model=gpt-6-astra`、`model_provider=openai_http`、CLI0.153.4；leader 未另读 session 文件，记为有元数据坐标的席位证据。
- advisory `result_success_without_executed_tests` 对只读审查不构成失败；没有测试绿可宣称。

## chain-audit-luna（2026-09-07）

- dispatch `msg_a46d8a8348b8`，result `res_ee5509f24f30`。已读 AUDIT.md、five-issues.json、identity.json。
- leader 当前核对：nodeprobe 安装 SHA=d60cacc86…、600112 bytes；server/agentmirrord 与 hand-stack 路径 SHA=5dabb170…、30986306 bytes；全局 extension SHA=51ffcad3…、3572 bytes，均与审计一致。
- leader `lsof` 核听仅 PID63994；该 PID 的 txt 路径为 `.team/nodes/server-enum-hand/bin/agentmirrord-33b4c48-darwin-arm64`。不是以磁盘文件替代在跑进程身份。
- 五项 ID 完整；生产 authenticated listing/WS 与当前 APK 身份仍 unknown，不能以源码或未认证 HTTP 路由推断实帧。
- **不采纳 N1 的已归因表述**：Codex 的 session_name 缺失不是已证根因，现行 skill 对非Pi本就通常 null；报告 N1 最小复现用了 Pi 名称，会测错对象。Codex 必须独立受控复现并核权威名源。
- **不采纳“PR86 明确非根因”的整体免责**：已验收的前台身份修复不重做，但错误基座/产物构建/manifest 重钉链仍需核映射；不能仅从 PR 描述排除交付回归责任。
- identity.json 只有 model=gpt-5.6-luna，缺官方元数据来源坐标，记为席位提交的模型证据；启动时 nodeprobe 已确认该席 provider=pi，尚不冒充独立核过会话模型来源。

## 安全插队与已做止血（2026-09-07）

- 来源：Astra 编排调整消息 `msg_8fdac0c21248`，指出 f629 `src/lib.rs:226–260` 每pane capture-pane -S -30；本轮不再把已安装probe当安全结构探针，不读/复述此前可能采到的正文。
- leader 已运行 heartbeat manage remove，核 label `com.team-agent.heartbeat.834d7731010c` 为 not_loaded、plist 不存在（status 退出1是未安装预期，不是删除失败）。
- workspace heartbeat wrapper 添加无条件运行拒绝闸（--print-config仍可用），`sh -n`通过；普通调用和 --trial 均在probe前返回2并仅输出固定禁用提示。不是产品测试PASS。
- Luna安全约束派单 `msg_d1e970f50ee8` 已由结果 `res_efaad1b46ae8` 确认在受控复现中遵守（只自有合成物理socket，不追溯声称首轮默认socket采样安全）；Astra安全验收门派单 `msg_58db70c4b710` 已在消息 `msg_8fa09e82be28` 明确确认，并纳入综合任务书。daemon 内部旧采集仍未止血。
- 只停本队心跳/采样安排，未改/停线上daemon。生产内部旧probe调用风险未消除，后续候选必须核实际无capture-pane/无argv，再恢复heartbeat。
- 当前GOAL、WORKFLOW和handoff入口已增加安全覆盖，旧产物测试仅物理独立的自有合成socket。

## 当前阶段屏障

两席首轮只读审计均已返回，leader 已核关键产物和运行路径。阶段一完成的是版本/接线事实归类，非功能验收；N1、真实循环、当前 APK/WS 保持未知，进入下一阶段受控复现。

阶段二已返回 `res_efaad1b46ae8`（受控复现）与 `res_efac02b5f5e9`（综合/任务书）。leader 已读两份全文，核Astra proof-index 的30份文件size+sha均一致；核Codex cases的10帧×3产物=30个预期exit1/unknown红，2控制×3产物=6个exit0。仅核证据一致性，未重跑，也不是产品绿。

Luna Pi生命周期是合成Node事件和challenge的channel层执行；缺channel映射到unknown/unknown是契约预期，未由probe实际消费，不采纳为该条绿。S1真实App与真Codex命名仍未执行；已写入 DECISION.md 的对应施工前置，未把五项全链标PASS。Luna identity.json补充PI_MODEL/PI_PROVIDER/PI_SESSION_ID官方环境坐标，报告model=gpt-5.6-luna/provider=openai-codex；leader未读取该worker进程环境原文。

leader 按 DECISION.md 接受上游路线和PR归责：C70完整源+C86已验身份差量重组写回PR86，之后serve#6钉N；App#73完整撤health veto；名称按真实Codex官方完整title和Pi独立session_name保真，真命名红先于写入。

已新建基本Pi/Luna两席：probe-fix-luna（T-N实施，msg_dbe1a34aa1f0）、ui-repro-luna（App/真正Codex名字改前实测，msg_dbe0af4dcc20）。队列受理不等于报告完成。T-A写手等App红，T-S等N，T-NAME等真Codex红及模块锁释放。全局probe/extension、生产、merge均未动。

## Grok Bot 空间阻塞与继续路径

- probe-fix-luna 报默认preflight远端可用9,007,512KiB（约8.6GiB），低于20GiB；候选Cargo构建/测试不能启动。该环境阻塞不是测试FAIL，更不是PASS。
- a1=`gb-e50a6e93bfb691977b4313fa9d2d465402a89d45-t-n-red-a1` sync超时/status=missing，未执行；精确stage `/workspace/grok-bot-offload/jobs/gb-e50a6e93bfb691977b4313fa9d2d465402a89d45-t-n-red-a1/.sync-stage-36487-19600` 298M。无verified receipt，保留不手删；也不能用这298M冒充约11.4GiB缺口已解。
- 已新建基本Pi/Luna资源席bot-space-luna，派单msg_ab4e0ba86c58；只回收本项目可证归属、terminal、有verified fetch receipt且无有效保留消费者的exact unit，走fetch→verify→wipe。active/missing/unknown/归属不明/其他团队/共享缓存均不动，不改基础设施或min-gib。
- T-N资源调整msg_abd61710b618：继续源码/收据准备，空间恢复后默认preflight重新过门、新unit执行，不重用a1。
- UI资源调整msg_a7cd20e7a1d8已由msg_f60fd3e2ddd0确认：不新增远端构建或同步，继续已有APK、自有AVD与隔离Codex命名红证据，不碰清理席路径。任务并未全停。
- 资源产物待 `.team/nodes/status-bot-space/SPACE-RECEIPT.md`，仅磁盘恢复不代表产品验收通过。

## ui-repro-luna 改前证据接收与纠偏

- result `res_f28a5bc7fd30`（关联资源调整msg_a7cd20e7a1d8）；leader读RED-REPORT、app-projection-evidence与codex-name-red、原始codex-structural、cleanup-receipt。
- leader核正式596 APK SHA=`0c9aa3285689d9f4004fb2570f3e5af65bbab6e67437343a78496b15c6d2cf30`；角色SHA=`c30c133a8261b8ccb75c65a0acc849ff3ce71985924a41f1aa54d539a766d85b`。核52 XML：normal12/12工作且6个glyph；unknown6/6有行无动画；abnormal6/6同样；idle6/6静态；offline6/6无行；恢复8/8工作；重订阅8/8工作。另亲看normal/unknown两PNG，绿点有/无与XML一致。只核证据与实际截图，未重跑AVD。
- **接受正式596 APK真实投影红，不接受当前C73二进制已实测**：本次没有运行1642902包；其abnormal veto是已核源码，T-A仍需exact C73 targeted红。报告中的“C73 veto红”误标已退回修订。
- **不接受N1消费红已执行**：Codex0.153.4官方/rename与OSC完整title的双会话观察有证据，但old_consumer_projection是手写synthetic，没有旧serve converter/WS/UI执行。原JSON将“编码甲 | 远程Agent安卓”“审查乙 | 远程Agent安卓”裁成纯名，与raw不符；current=bash而provider=codex的活身份/采样时点尚未知，不能用残留title补证。
- 角色未声明effort，报告effort=xhigh不是已核。清理收据报告仅自有AVD/socket/fixture已退，未独立再核进程；不读CODEX_HOME auth链接内容。
- 一次纠正派单msg_e92f58d54b38给ui-repro-luna：改版本标注、完整title、消费NOT_RUN、活身份与effort边界，不重跑52帧；空间门未开，不新增远端构建。
- 已新建app-fix-luna基本Pi/Luna，角色model=openai-codex/gpt-5.6-luna；public add-agent成功，dispatch msg_64e349cbc900受理。运行实际模型尚未独立自证。只准T-A精确C73冻结/测试准备，空间放行后先红再改，不动T-NAME/收藏/生产。无产品提交、PR更新、merge或部署可在此宣称。

## 修订接收与测试准备（2026-09-07）

- ui-repro-luna修订result res_f368be95e0b0已阅；leader核app/codex/cleanup三份JSON可解析与PID收据哈希，完整title与两份结构记录一致，active_identity=unknown、old_consumer_projection=NOT_RUN。原52帧没有重跑，接受的仍仅正式596投影红。
- codex-name-red.json实际SHA256=`23f2d7745a1c2654fefaa7ee32aba9d5cea17424980f4fdf6e07542ae15357e9`；报告漏了末位9，首次严格hash核对因此失败，未放过；其余三份完整hash与报告一致。已交同席订正，不把文档hash误写说成产品失败或测试绿。新PID收据来自codex-pid-a3/b3，是新自有夹具，不是首次codex-a3/b3的历史同一进程证明。
- ui-repro-luna后续准备派单msg_852e4009fc80：精确S-RUN上的真实旧converter具名测试，仅装备/patch与CONSUMER-RED-PLAN.md；不复制产品函数、不新跑CLI/AVD，不新增远端sync/build。执行保持NOT_RUN，资源恢复后才跑。
- app-fix-luna冻结报告msg_082d2066f430：已读FREEZE.md，leader另git核HEAD=`1642902e7b9fe002f72a0a16a6963be2ef865141`、tree=`650f7ddeca62d2a826dfa5f993691f35a9fc1f35`、parent=`51ea20b6a04629e5eb25bfad11c3f0e1696e002e`，两目标产品文件diff空。basegen/项目archwiki exit0与安装Rust archwiki failed/input为席位报告，尚未独立核输出，不冒充功能测试。精确C73红未执行。
- todo114单列远端空间阻塞，bot-space-luna既有派单继续；不重新派单/轮询/降低20GiB门，T-N/T-A与命名装备准备继续。
