# successor11 user-gate timeout 后只读连续性核验

## 核验范围

原 case：`ledger_baseline-bundle_successor11_v1__t.baseline-bundle.user-gate__r6`。
本次仅检查账本要求、用户门产物落盘状态及可见的 durable result 证据；未 collect、未重派、未改 ledger，
也未读取 APK 字节、凭据或生产明文日志。

## 三种状态分离

| 项目 | 结论 | 只读依据 |
|---|---|---|
| result 已落 | 未证实 | 本地仓内没有该 case 的可核验 `report_result` durable receipt；worker idle 或前台 driver timeout 不能代替 result 证据。 |
| 产物已落 | 否 | `.team/nodes/baseline-bundle-user/` 仅有 `.gitkeep`；`USER-GATE.json` 与 `USER-GATE.md` 均不存在，也未发现其 Git 历史。 |
| 真实用户裁定 | 未取得 | 账本 user task 仍为 `planned`；既无 `reported_by.kind=user` 的 USER-GATE，也无可核验的用户原始确认。 |

`.team/ledgers/baseline-bundle-successor11-v1.json` revision 6 的 user task 要求：
`.team/nodes/baseline-bundle-user/USER-GATE.json`、`USER-GATE.md`，并通过
`baseline-bundle-successor7-user-gate.sh`；该门把 `reported_by.kind=user`、cellular、广州中转、
真实 alt-screen Agent CLI、`session_open=秒开`、`blank_frame=false` 和确切 bundle 绑定作为必要事实。
因此 agent 自报、模拟器/实验室结果、driver 存活或 timeout 后的 transport receipt 均不能判用户验收成功。

## 结论与不重复派单的最小继续方案

当前状态是 `unjudgeable / awaiting-human`，不是 pass，也不是产品失败。保留原 case 和现场，
不 collect、不重派、不启动账本；只等待同一 successor11 verified bundle 的用户原始裁定。

用户需要在自己的真机上：

1. 使用与 successor11 fresh verify/APPARATUS 绑定的确切 bundle，不公开 APK 或凭据。
2. 在蜂窝网络、广州中转路径下，通过至少一个真实 alt-screen Agent CLI 打开目标会话。
3. 观察并明确确认“秒开、没有空白”，或明确报告倒退；同时让接管席记录 bundle 身份、观察时间、
   `reported_by.kind=user`、`session_open` 与 `blank_frame`，不以 agent 转述替代原始确认。
4. 仅在用户确认后，由原 user-gate case 一次性落盘 `USER-GATE.json`/`USER-GATE.md` 并调用一次
   `report_result`；随后由 leader 按既有 successor11 user-gate acceptance 做 command-consume，
   不新建 case、不重派已完成格。

在上述事实落盘前，不建议 command-consume successor；现阶段没有合法成功证据可消费。

verdict: unjudgeable
