# t.baseline-bundle.verify — 独立验证 bundle、恢复与可安装性

背景与必读：只读共享任务书、repro/test/probe、impl diff 与 manifest；不采信 IMPL 自报。不得读取私钥或凭据。

精确交付：`.team/nodes/baseline-bundle-verify/{VERDICT.md,RETRIEVE.md,INSTALL.md,MUTATION.md,VERIFY.json}`。独立重跑 fixture 门；从 backup 恢复到新路径，核 manifest/APK/签名/package/归一化摘要；先过 envcheck，再在本次唯一自建 emulator 上实际 `adb install` 并在 trap 中只清理本次 PID。记录 build/runtime/artifact/archive 的两边操作数。

破坏齿：先重跑永久 `baseline-bundle-bypass-probes.sh`，确认两枚冻结旧门绕过在新门分别命中固定 provenance 与空 raw 拒绝；再独立选择共享任务书规定性质中的一齿，在副本/假夹具上验证改坏必红、还原必绿。不得破坏真实 primary/backup。VERDICT.md 末行 `verdict: pass|fail|unjudgeable`。

合法出口：全部独立证据成立为 exit 0；有效量具下任一不符为 exit 1；SDK/adb/emulator/envcheck/私有 payload 不可用为 exit 2。只 `report_result` 一次。
