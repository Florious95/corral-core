# 需求维基索引

> 只增不改；被推翻的结论在 REVISIONS.md 登记，条目本体不回改。

| 编号 | 条目 | 状态 | 一句话 |
|---|---|---|---|
| 001 | [产品命题：tmux 镜像范式](entries/001-产品命题-tmux镜像范式.md) | 已裁定 | 主机唯一运行时，手机是显示器+键盘；sidecar 非侵入；私有 socket 必须枚举 |
| 002 | [两级分组模型](entries/002-两级分组模型.md) | 已裁定 | 一级=cwd 聚合，二级=session；session 名不参与分组 |
| 003 | [对话体验四标准](entries/003-对话体验四标准.md) | 已裁定 | 输入零延迟/发送必达/状态零丢失/需要时被唤醒；图片=落盘注入路径 |
| 004 | [后台策略：无状态免疫](entries/004-后台策略-无状态免疫.md) | 已裁定 | 不保活；客户端无状态，被杀即无所谓；推送双轨待定 |
| 005 | [自适应：让 CLI 自己重画](entries/005-自适应-让CLI自己重画.md) | 已裁定 | resize→SIGWINCH→CLI 重排；window-size latest；双端异尺寸不支持 |
| 006 | [秒开与本地滚动](entries/006-秒开与本地滚动.md) | 已裁定 | 首帧快照+增量流；scrollback 本地化；终端内核选型未定 |
| 007 | [联网模型：tsnet 与扫码](entries/007-联网模型-tsnet与扫码.md) | 部分裁定 | App/服务端内嵌 tsnet 已定；扫码路线 (a)/(b) 待定夺 |
| 008 | [生产级定位与开源许可](entries/008-生产级定位与开源许可.md) | 已裁定 | 非 MVP；状态解析必做；Apache 2.0 全开源；镜像层/状态层严格隔离 |
| 009 | [团队模型编排策略](entries/009-团队模型编排策略.md) | 已裁定 | teammate 用第三方 API；难点模块开 Fable 5 短命席位 |
| 010 | [最终验收与运行方式](entries/010-最终验收与运行方式.md) | 已裁定 | 验收=生产级安卓APP+主机后台进程；无人值守；代码必须注释；leader 只编排 |
| 011 | [技术路线裁定](entries/011-技术路线裁定.md) | 已裁定 | Go 服务端；Kotlin+Compose；WS 协议；QR 路线(a)；前台服务；monorepo |
| 012 | [工作区聚合状态规则](entries/012-工作区聚合状态规则.md) | 已裁定 | 聚合状态服务端权威算；blocked>done>working>idle；unknown 不计入、全 unknown 才聚合；固化 protocol.md |
| 013 | [测试体系与回归门禁](entries/013-测试体系与回归门禁.md) | 已裁定 | 五层测试体系；tools/gate/run.sh 全量门；0新回归标尺；测试数棘轮；失败四归因 |
| 014 | [产品定名](entries/014-产品定名.md) | 已裁定 | 定名 agentmirror；module/applicationId/二进制落地；被否候选留档 |
| 015 | [验收达成](entries/015-验收达成.md) | 已达成 | e2e exit 0；首帧 p90 50.6ms；老化 40/40；全量门 600+ 绿；任务书 24 项 pass；交付 agentmirror |

## 未决议题（契约级，施工前须定夺）

暂无挡道议题。已结清项：
- **终端内核来源**：w-term-core 核验 ConnectBot 系实为 JTA 血统 GPLv2/LGPL-2.1，改造路线出局；裁定=自研最小 VT 引擎（纯 Kotlin/JVM），见 docs/decisions/term-core.md 与 R-002。
- App 技术栈 / 服务端语言 / 扫码路线 / 推送形态：由 011 结清。
- iOS 推送中转：随 010（验收仅安卓）移出当期。
