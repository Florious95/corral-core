# t.baseline-bundle.final — 独立终审整条根治链

背景与必读：只读共享任务书及所有上游产物、判据、实际 diff；产出方自证不算数。

精确交付：`.team/nodes/baseline-bundle-final/{VERDICT.md,EVIDENCE-MATRIX.md,MUTATION.md}`。独立重跑 bundle、user gate、migration、measurement 四个机械门；核 exact/A2 路线诚实、bundle 可从 backup 再恢复、旧 perf ledger paused 且无旧 driver/lease、fresh 数据独立重算全格<=1.10、用户 gate 绑定同一 bundle。

永久根治齿：终审必须重跑 `.team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh`，证明同一探针已由旧链 exit 1 变为 paused+bundle 绑定 exit 0；并重跑 `baseline-bundle-bypass-probes.sh`，证明短 digest/stub manifest 与空 raw/伪造 JSON 两枚冻结夹具仍是 legacy=0、hardened=1|2。缺任一对照不是通过。

破坏齿：在副本上选择一个能跨链否决的齿（优先 runtime 摘要、backup 恢复、迁移 pid 或 1.10 比例），改坏必红、还原必绿；记录 diff、rc 和还原后 porcelain。

合法出口：严格合取全部成立为 exit 0；有效量具反证为 exit 1；任一环境/证据/用户 gate 不足为 exit 2。VERDICT.md 末行 `verdict: pass|fail|unjudgeable`。不 merge、push、打 tag，只 `report_result` 一次。
