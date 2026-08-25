# successor7 fresh verify 任务书

只在 apparatus command required=0 后，于同一 retained `wt-maple-core` fresh 派 `sampler-review-luna2`。旧 unjudgeable VERIFY文件只作输入案卷；必须重新核 manifest/双归档/恢复/安装/变异并重写 `.team/nodes/baseline-bundle-verify/{VERDICT.md,RETRIEVE.md,INSTALL.md,MUTATION.md,VERIFY.json}`，不得只改末行。

VERIFY.json 保留 successor6 固定布尔，并新增：

- `apparatus_evidence_sha256` 精确等于 fresh APPARATUS.json bytes SHA-256；
- `apparatus_bundle_id` 精确等于 APPARATUS.json/manifest bundle_id；
- `permanent_bypass_probe_exit=0`；
- `verdict_basis=apparatus_complete`；
- `install_exit=0`、`runner_pid_cleanup=true`、`serial_cleanup=true`、`owned_qemu_cleanup=true`、`forced_kill=false`；APPARATUS.json 必须 regular/non-symlink mode0600。

required `baseline-bundle-successor7-verify.sh` 先跑 successor7 apparatus/fixture/continuity，再跑 successor6 real impl+verify深门，最后独立重算上述交叉引用。APPARATUS过期、bundle/摘要不同、旧 VERIFY未重做或 permanent fixture不可用均2；有效伪造/矛盾1；全绿0。不得重新启动 emulator，fresh verify消费 apparatus command的同批机器证据。

正常收工只 report_result一次，不清 successor6 attempts、不提交/分发 private APK、不改App/server。
