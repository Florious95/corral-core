# successor7 user-gate 任务书

目标：将用户真机金标准绑定 fresh verify 与 APPARATUS 指向的同一 bundle。

输入：`APPARATUS.json`、fresh `VERIFY.json`、manifest 与用户对确切 APK 的原始裁定；不得以 agent、模拟器或实验室结论代判。

交付：`.team/nodes/baseline-bundle-user/{USER-GATE.json,USER-GATE.md}`，必须含 bundle/APK/signer 身份、时间、`reported_by.kind=user`、cellular、广州中转、真实 alt-screen Agent CLI、`秒开`、`blank_frame=false`。

验收：`baseline-bundle-successor7-user-gate.sh` 独立重算 APPARATUS↔VERIFY↔USER bundle 身份。用户同 bundle 确认“秒开、没有空白”为0；确认倒退/身份矛盾为1；未测或证据不足为2。只 `report_result` 一次。
