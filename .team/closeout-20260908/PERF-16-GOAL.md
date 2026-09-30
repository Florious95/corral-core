# Issue 16：队列溢出后的完整画面恢复

用户已授权另案检查并尝试修复性能 Issue；本项与已通过手机工作态验收无关。源码分类正本 `.team/nodes/perf-triage-astra/RESULT.md`，App 依赖审查 `.team/nodes/perf-triage-astra/APP-RECOVERY.md`（待交）。

## 冻结范围

- owning Issue：Florious95/corral-serve #16；一个因果修复一个新 PR。
- 开发 base 已冻结：公开 serve main `b98504e742ed1e7c7475767c512934b07eac592b`（#19 已合）。leader核相对已验 S 完整差异仅 LICENSE。
- 目标：bridge 或 WS 队列任一丢失原始增量时，不让连接继续假装画面完整；受影响连接有界失效并由现有 App 恢复完整快照，或明确显示失败。健康订阅不丢字节、不受牵连。
- 不改 #14/#15/#17，不增大队列掩盖丢失，不修改四轴状态/名称、协议几何、生产配置，不部署，不操作用户已配对 AVD。
- 开发席先核 App 恢复审查；若现有 App 无法满足恢复闭环，冻结最小跨仓依赖后再施工，不能先强断开再声称已恢复。

## 可核验判据

1. 决定性改前红：真实生产 fanout/sendMirror 路径经受控屏障产生溢出，证明确有丢失且无现行主动恢复。不能用固定 write 次数冒充 read chunk 次数。
2. 每订阅者一次 loss；每连接一次 abort；loss 后不继续主动排空旧队列。正常认证拒绝等关闭路径仍保留应有发送语义。
3. 健康订阅全量连续、共享 pipe generation 不变；detach 幂等，无死锁/负 refs/几何恢复泄漏，既有 displaced-pipe 错误保持可观测。
4. 输出停止后，真实客户端重连/重订/clear/replay 得到完整静态栅格及 cursor，不依赖 resize 或后续输出补救；重连退避有界，无旧帧串入新连接。
5. 精确 PR revision 的 GitHub hosted Go `-count=1`，并发路径 `-race`；相关 App 测试按实际改动选择，Gradle `--rerun-tasks`。本机不编译。测试数、skip、失败名、checkout SHA 原样保留。
6. 若开展性能测量，先实际运行既有环境闸；不达标保留不可判，不停止用户 AVD 凑通过。源码减少丢失不等于耗时变快，不能未经采样报提速百分比。

## 交付

最小修复、决定性红绿、兼容验证、明确 App 恢复证据与未知边界、既有架构闭包及必要门、一个 PR。作者交付后独立验收，leader核证；不重跑已结束的工作态验收，也不以那次验收代替本项验证。
