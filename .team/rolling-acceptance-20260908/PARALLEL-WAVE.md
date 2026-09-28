# 五项并行执行冻结令

## 执行中具体接口裁定（2026-09-08）

- 性能窗口：#15 实测 envcheck exit2，用户 qemu 存在与原预检零 qemu 门冲突。原门不改、用户设备不关闭；源码/hosted/功能验收继续并行，性能实测安排到本批功能准备齐后的独占环境窗口。各项保留精确 A/B 产物、可执行采样入口与环境原件，性能标待窗口，不当通过，也不以此停止其它工作。具体独占环境尚未落实，不虚报已具备。
- #16 设备后端：允许仅运行同候选 hosted 预编译 api.test 的 TestPerf16A6FixtureProcess 作为自有设备受控后端。禁止本机 Go 编译/普通测试矩阵不变。须先核原artifact/hash/serve source/tree/test-only hook，换候选必须hosted重建；不能旧fixture混新daemon。单列带hook后端与标准产品的证明边界，期限/隔离/真实loss及清理门不变。
- OAuth workflow 发布：owner完成全部文件与静态检查；非workflow文件可正常提交[skip ci]，leader通过Chrome最后写精确workflow，核最终tree/blob。作者commit、公开交付commit、被测source身份分列；这项权限操作不转移owner端到端责任。

用户最新授权：先五项并行，后组合验收建立滚动基线。本文件覆盖 PROCESS、PERF14-GOAL 及旧角色的串行/只读/逐工序分席限制。

共同参考输入：serve b98504e742ed1e7c7475767c512934b07eac592b；App04bfde8d161c244c9dfced3b5ab90d22d1460d86；APK `/Users/alauda/Downloads/AgentMirror-Three-Surface-NAME-A-04bfde8-signed.apk` SHA256 bd4ababfcec3301c19ced02350ff2193f183311f9ec0bdd455145deab3b3e861。它们是固定比较输入，尚不冒称新流程已验 R0。各席独立核身份/运行正常功能资格，不互相等待。

## 唯一所有权

| Issue | 唯一owner | owning PR / branch | 唯一工作clone |
|---|---|---|---|
| 10 | perf-triage-astra | PR22 / fix/multisocket-discovery | .team/nodes/perf-triage-astra/serve-issue10-e2e |
| 14 | multisocket-luna | PR24 / fix/scrollback-page-metadata | .team/nodes/multisocket-luna/serve-perf14-r1 |
| 15 | real-avd-astra2 | PR26 / fix/catalog-refresh-coordinator | .team/nodes/real-avd-astra2/serve-perf15-e2e |
| 16 | perf16-repair-astra | PR20 / fix/overflow-resync | .team/nodes/perf16-repair-astra/serve-perf16-e2e |
| 17 | closeout-luna | PR25 / fix/resize-readback-reuse | .team/nodes/closeout-luna/serve-perf17-e2e |

## 各项目标与写边界

原始 Issue、来源/hash、验收表正本为 `.team/nodes/perf-triage-astra/tmp/rolling-foundation/` 中 ISSUE-N.md、SOURCE-MANIFEST.json、ISSUE-ACCEPTANCE.md、CAUSE-AND-BASELINE.md。每项完整采用对应 A…末项标准；以下补全未定字段。每项都先定位具体原因，旧报告不替代本轮同候选执行。

### 10

完成10-A/B/C：明确私有当前uid目录中的 default+两个不同名真实 socket，含相同 paneID，逐项按 socket-qualified ref 经 Discover→listing/L2→真实 App 完整对应；死项/越域拒绝负控和查询范围可证。仅 discovery 原枚举/过滤/identity必要路径、直接测试、tools/issue10及一个workflow。沿PR22正常追加，base b985，不夹带PR27外部-S/FD IPC；外部-S旧需求和证据保留，未纳入原始Issue本项且不宣称已取消/完成。真实用户/Team socket不作夹具。无毫秒优化主张，不发明性能阈值；完整集合、隔离、健康及退出是完成门。

### 14

按 PERF14-GOAL.md 完整执行，但它不再是全队串行第一项。本项 R1 字样均改读为“组合待选差量”，不能单项晋升滚动基线。不等其他Issue。

**#14 race 依赖组合验证（2026-09-08，msg_c04c6f0ba6a8）**：run34221641525 已报告实际 cleanup oracle 通过，但候选首根因 ConnMetrics/sendMirror teardown race 失败，后续门及候选二进制未执行。保留此失败，不将 metadata/capture 计数达标升为 root PASS。授权原 owner 在原PR24测试载体中显式建立固定依赖候选 1fa3b651c613f7b973b5187429d8e9e50d9d3d45 + 精确 #14 产品/测试差量的隔离验证树，核 PR20 对该具体race的修复路径及所有patch/blob/tree身份后运行同一完整 -race/-count=1 门。不得关闭race、吞失败或顺改 ws_conn/复制未声明产品；b985仍为原始行为/性能参考，不以1fa3替换，也不把依赖候选叫稳定基线。原PR24继续作为#14唯一交付入口，正文明确依赖PR20固定候选；验证树与公开PR树分别记录，禁止冒称同一身份。组合runtime、App及性能门仍需实际完成；本授权不准许merge、Issue关闭或基线晋升。

### 15

完成15-A…F；限原交付6个产品路径及直接测试、tools/perf15和单一workflow，不扩目录框架。PR26原stack fix/overflow-resync可继续绑定精确1fa3b651c613f7b973b5187429d8e9e50d9d3d45作依赖候选，非已验R0；基线性能/行为仍b985，最终组合重验依赖交互。无需等16才能开发和测试。

沿此前有来源裁定冻结每连接16/global1024等待者、scan30s/admission至实际writer终态35s绝对上限；内部每次请求独立ID，合法重复wire req_id不吞请求；单worker+最多一个pending、双completion顺序/水位/epoch/取消及清理全部实测。1/10/50会话同App A/B/A/B每block10样本，先envcheck，T_full=实际选择到完整当前屏与所需历史应用，T_resume=恢复动作到正确可用屏。新增本轮比较门：p50/p95各B/A≤1.10、失败率不增；缺端点不可判。已知ref受控慢扫描release前须收到完整snapshot/cursor，以因果屏障证明不阻塞，不虚构耗时。

**#15 ReqID 契约纠正（2026-09-08，msg_6948b09ba160）**：已直接核精确 b98504e742ed1e7c7475767c512934b07eac592b 与 1fa3b651c613f7b973b5187429d8e9e50d9d3d45 的 internal/protocol/validate.go：List/Listing 的 ReqID=0 均返回 ErrInvalidField。此前“零req_id兼容成功”解释作废，保持协议及 Validate 不变。真实 writer 重复请求使用合法相同 ReqID=7，要求每个已接纳请求独立等待、逐一获得实际 writer 终态，不得按 wire ID 覆盖/吞掉。零值另验既有协议拒绝；内部零值仅作独立入队/取消资源控制，不送入 writer 充当合法输出。内部成功答复场景也使用合法非零 ID。保留 run34210579778/artifact10050071627 的原始失败；这两项 MarshalFrame 失败按非法测试输入归属，不据此认定并发产品错误，其余失败逐项处理。原容量、绝对期限、取消、原子发布和 App 功能门不变；实际运行仍待后继 hosted 验证。


**#15 性能端点仪表授权（2026-09-08，msg_6dff81a4e4c0）**：允许原 owner 在自有隔离目录为固定 App04bf 构建外置 instrumentation test APK/采样器；只读真实活动 UI 实例的历史应用、当前屏内容/样式/光标及连接恢复状态，记录动作起点至真实应用完成/正确可用屏的单调时钟端点。明确事件来源、ref/恢复代次、期望/实际内容、分页完成条件、观测误差和仪表开销；first_draw、wire收到、截图命令结束均不能替代完成端点。仪表不得注入快照/改变产品状态制造完成；晚到旧代、未应用历史及错误屏必须拒绝。同一仪表/固定APK用于两侧，保留原15-F比较门。允许在原PR26以test-only载体+原单一workflow做hosted构建（不本机Gradle、不新增App产品PR），测试APK独立签名身份核验；产品APK不换。先交具体可执行端点与资源占用时长，不等独占窗口才准备。窗口仍未落实：不启动性能采样，不关闭用户AVD、不改envcheck；实际采样须既有环境闸通过。原功能分项证据可保留，6条同基线scrollback失败、strict-T3不可判及emulator child exit未知不得被取消，整体15-F和Issue验收未完成。

### 16

完成16-A…E；仅原PR20 overflow/resync产品范围、直接测试、tools/perf16与单一workflow。已有1fa3是候选，base b985；不等14/15/17。旧A6限定证据保留，不能替代真实设备。

桥/WS真实loss、快端全量连续、一次明确终结、无旧代flush、真实App无新增输出/点击/resize即恢复完整当前grid/style/cursor须通过。退避门冻结为10轮受控loss/恢复及前后台序列，先从固定App04bf提取已有delay/上限/认证重置常量作oracle，不擅加退避算法；实际无重叠重连，未认证连续失败不得绕过既有delay，队列/任务不得随轮数无界增长。完整画面定义为重订后当前屏；已翻阅历史不得冒用旧代内容，重新加载沿既有入口，跨断线无损保留全部历史不是本轮新增要求。若现有行为导致立即无界循环按本项失败处理。App产品固定04bf，可写外置测试；确证必需App产品差量才一次报告跨仓最小契约，不偷偷新建第二PR。

### 17

完成17-A…E；仅 handleResize实际readback复用、直接测试、tools/perf17和单一workflow。base b985，原PR25正常追加；旧1fa3组合只作依赖兼容证据。单handler query3→2、resize-window1→1；含其余固定命令时总6→5，两口径分列不混首订。actual geometry/错误路径、同连接持续reader+具名Ack的same-size健康、真实reflow/ref/snapshot/cursor、多订阅80x24→108x96→60x40→末退80x24及pipe-before-capture必须实测。第二阶段仅记录各阶段调用/耗时/alloc，不扩大产品重构。性能门为真实删除一次查询且几何/输入不回退，耗时原件如实报告，不声称固定毫秒提升。

## 每个owner的完整目标模式

持续自己完成精确base核定、复现归属、最小修复、远端具名红绿与兼容、同一PR提交、精确产物取回、专用真实App验收、累计正常功能及清理；不以计划/准备/编译/载体为终点，不逐步转席。各席正常功能门包括配对/auth、支持集合workspace/session/ref及名称状态/收藏、终端首屏/增量/输入/resize/前后台、退出。已知其他Issue红分别归档，不夹带修复或称全套绿。

按真实task-id做basegen/架构影响闭包及既有门；工具缺失不可判，不复制引擎。Go/Gradle仅hosted，Go -race -count=1、Gradle --rerun-tasks，具名非零且无skip。owner负责workflow和取件；OAuth缺workflow scope时一次交完整文件/hash/diff给leader必要UI发布，其余不转交。禁Codex Apps/状态轮询/无修改重跑；按入口最大时限+2分钟一次精确定位取件，仍无终态则保留真实有界阻塞。期间推进独立工作，不能无人负责等待事件。

各自专用clone/AVD/adb/socket/端口；先核资源与归属，使用已安装镜像与已验APK。资源不足可暂缓本席设备重阶段并报操作数，不能把它扩成五项开发串行。禁止生产9900、用户5580/5038、真实pane/凭据回显、共写工作树。fixture由完成信号管理，不靠600s自然退出；保留真实退出码，完整清理自有资源。

只提交本项范围、正常追加原PR，不force/merge/关闭Issue。终态一次report_result交精确PR/head/tree/artifact、逐项PASS/FAIL/UNJUDGEABLE与原始trace/log/截图/样本/退出码/hash、完整剩余问题集。每项通过仅准入组合，统一组合累计功能验收后由leader建立滚动基线。模型按已指定frontmatter不改；本目标覆盖旧角色只读、旧目录/分支及作者不运行App的限制。
