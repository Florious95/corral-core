# successor7 apparatus test 任务书

只写 `.team/nodes/baseline-bundle-successor7-test/RED.md` 与 repo-local假夹具，不碰真实 adb/qemu/emulator。必须真实执行 `baseline-bundle-successor7-emulator-regression.sh`、`baseline-bundle-successor7-bypass-regression.sh`，并记录：strict gate是首动作；fresh task AVD；PID/serial/install；success/timeout cleanup；ambient存活；dirty preflight=2且零launch；证据伪造1/缺失2；old missing bypass rc2、新 permanent gate0、伪造1。另固定四组审查回归：非空非1 fixture selector=1；production 任意 test env=1且零launch；explicit test mode 绿；evidence/derived mode=0600且 chmod0644 后 validator=1。

TERM-ignoring envcheck/runner 必须在测试给定 deadline+grace 上界内返回2；runner齿在 readiness/PID+serial绑定后才装坏，证明 forced cleanup 只命中 runner/owned qemu，三者 cleanup 后 ambient 仍活。记录 elapsed 两边操作数；不得把旁路的 empty raw/root mismatch 当作该齿命中。

必须含固定 token：`SUCCESSOR7_EMULATOR_EVIDENCE`、`SUCCESSOR7_IMPL_BYPASS`、`SUCCESSOR7_CONTINUITY`、`preflight_first=true`、`invalid_mode_exit=1`、`production_test_override_exit=1`、`production_empty_test_override_exit=1`、`unknown_test_override_exit=1`、`evidence_mode_0600=true`、`derived_mode_0600=true`、`evidence_0644_exit=1`、`bounded_term_exit=2`、`forced_runner_exit=2`、`forced_runner_cleanup=true`、`foreign_qemu_touched=false`、`forged_evidence_exit=1`、`missing_evidence_exit=2`、`permanent_forged=1`、`permanent_missing=2`、`3528c2ad5`、`wt-maple-core`、`-count=1`、`--rerun-tasks`、`--no-build-cache`。

最终判者在副本选齿：删 strict preflight、把 production evidence改fixture、把 cleanup true伪造、让 permanent fixture缺失/改摘要或把 retained WT换名，均须1/2且绿控仍0。测试每次直接执行，Go若新增必须 `-count=1`，Gradle若新增必须 `--rerun-tasks --no-build-cache`。只 report_result一次。
