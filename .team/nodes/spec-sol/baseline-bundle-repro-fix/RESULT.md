# baseline-bundle repro 返修结果

## 已关闭

- 真实旧链 probe 保持裸 exit 1，并新增固定 schema `REAL_CHAIN_PROBE_JSON`；
- 新 translator 只有在两次真实 rc=1、shape/provenance 与 REPRO.json 全吻合时才输出 acceptance 0；probe rc=2/缺事实传递 2，伪造/矛盾输出 1；
- repro 任务书逐字段声明 `agentmirror.baseline-bundle.repro.v1`，机器权威为 REPRO.json，REPRO.md 只做人读；
- 回归齿 fresh 结果：语义正确但无旧字面 token=0、伪造 rc=1、缺 provenance=2、伪造 shape=1；
- 候选编译、jsonschema、preflight、dry-run、四脚本 `sh -n`、ShellCheck、真实 probe expected-red 与 fresh translator 均符合预期。

## 未关闭的唯一阻塞

`ledgerdsl 0.1.1 plan` 无法读取 live 中由同版 DSL 合法生成的 `tasks.*.parallel` 字段，因而不能诚实生成 revision 1→2 注入计划。未绕过字段所有权守卫，未 apply、未清 failed attempt、未重派；细节见 `plan-report.md` 和 `plan.log`。

verdict: blocked
