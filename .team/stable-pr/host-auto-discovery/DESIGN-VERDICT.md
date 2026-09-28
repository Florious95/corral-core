# 主机自动发现：设计放行

**PASS — 可编写并冻结 owning PR 任务；不等于产品、装置或性能验收通过。**

- 选定方案：[DESIGN.md](DESIGN.md)；目标：[GOAL-DRAFT.md](GOAL-DRAFT.md)。文件名保留，内容按 [v3 输入身份](REVIEW-INPUT-v3.sha256) 冻结。
- 独立闭合依据：[Grok](../../nodes/host-discovery-review-grok/CLOSURE-v3.md)、[Luna](../../nodes/host-discovery-explore-luna/CLOSURE-v3.md)。两路均 PASS，原阻断及 R1/R2 已闭合。
- 产品基座不变：core `eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`（PR74）；serve `911dd94176a34e11f66b61b15b191216463c48cf`（PR7）。文档 main 不是产品基座，不修改或合并历史 PR。
- 随实现落实的 R2 边界：TS key 从有变无，READY 仍保持当前连接；下一代次在候选分类前停用旧 TS manager，不得把 stale Up 当作现行 TS 可用。测试同时覆盖有→无、无→有及连续修改只应用最后值。
- 首次无提示 TS 发现使用协议默认 9900；非默认且无 QR/NSD/记录的覆盖边界按方案如实保留，不恢复地址/端口输入或端口扫描。
- PeerSnapshot/AAR、NSD/组播、tsnet LocalAddr，以及功能/17项/前台/性能/签名升级均尚未运行，仍须真实证据。静态提示不能替代自主发现通过。
- 架构工具目录缺失仍为已知不可判，不宣称架构工具闭合通过，不启动工具恢复或治理旁支。
- 下一阶段：最小客户端/服务端配对 PR，冻结完整任务集再并行实施；允许 owning 分支与 PR，尚无 merge/release/生产授权。
