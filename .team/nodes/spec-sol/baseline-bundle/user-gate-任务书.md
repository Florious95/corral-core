# t.baseline-bundle.user-gate — 记录用户真机金标准裁定

背景与必读：bundle verify 必须先绿。本格不替用户操作或判断，只把用户对确切 bundle 的裁定结构化。

精确交付：`.team/nodes/baseline-bundle-user/USER-GATE.json` 与 `USER-GATE.md`。JSON 必须绑定 bundle id、APK SHA-256、signer certificate SHA-256、观察时间、`reported_by.kind=user`、网络=`cellular`、relay=`广州中转`、至少一个真实 alt-screen Agent CLI、`session_open=秒开`、`blank_frame=false`、`verdict=pass|fail|unjudgeable`。

硬约束：agent、模拟器或实验室 A/B 不能代替用户；不得要求用户公开 APK/凭据。没有用户原始确认就写 unjudgeable，不能自造 pass。

合法出口：用户绑定确切 bundle 且确认秒开无空白为 exit 0；用户确认倒退为 exit 1；尚未测、身份未绑定或证据缺失为 exit 2。只 `report_result` 一次。

