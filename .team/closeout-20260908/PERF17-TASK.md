# PERF17：复用 Resize 的实际读回

只修 serve Issue17，独立于历史分页/目录协调。固定 base b98504e742ed1e7c7475767c512934b07eac592b，新独立 owning 分支 fix/resize-readback-reuse，PR base main。背景为 `.team/nodes/perf-triage-astra/RESULT.md` 的 #17；先核实际 base 相关差量。

作者 closeout-luna：Pi / openai-codex/gpt-5.6-luna / xhigh；全新本席 serve-perf17 clone，旧 closeout 树保持。产品只改 ws_handler.go 的 handleResize：接住 br.Resize 返回的实际几何，删除紧随其后的重复 br.Size。保留 resize 前 Size、错误映射、实际值比较和 snapshot/cursor；不得用请求尺寸冒充实际尺寸，不缓存 windowID、不改首次 Subscribe、geometry 所有权或其他性能项。

验收：生产 handler 的命令观测证明 same-size/reflow 各少一次 Size 查询；固定未修 base 同 oracle 具名红。请求尺寸与实际 readback 不同、before 查询失败、Resize失败、same-size零新snapshot且连接仍健康、真实reflow完整Ref/snapshot/cursor以及多端最后离开恢复原几何均保留。真实断连不得被“没读到快照”当作 no-op 通过；完成屏障与健康请求回执须独立证明。

Go测试/编译仅hosted，-race -count=1。本机只静态检查，先准备最小源码候选和同oracle base红/candidate绿单一workflow接口；产品验过才正常单项commit和推同PR。未验patch可作明确标注的测试载体。其他已知全套race按独立证据归属，不改进本项/删除测试/去race/重跑碰绿。独立审查与真实组合随后执行，作者不自验收。

只声称少一次查询的结构变化，不承诺毫秒收益或首次subscribe加速；性能仍先envcheck再既有同批A/B，用户秒开无空白金标准保留。临时文件仅本席目录，不本机开生产服务/用户AVD，不读真实pane正文输入，不查CI或轮询席位。正常一次report_result，仅具体交付接口缺口才消息。

## 独立审查与验证依赖裁定

PERF17-REVIEW.md 的 R1–R3 一次修正：same-size观察使用连续reader，不用关闭原WS的Read deadline；核心命令观测仅单handler阶段，size query旧3→新2且resize-window均1，不以全流程首订/恢复混合计数为oracle；所有回归根必须实际RUN/唯一PASS/无SKIP。

保留b985同命令oracle红，候选兼容验证在全新隔离serve1fa3b651+本项最小patch进行，记录精确来源及tree。原因是旧ws_conn竞态已由PR20修复且在#14两轮兼容实证命中；不复制该修复进#17、不改owning基底或全局基线、不partial merge。
