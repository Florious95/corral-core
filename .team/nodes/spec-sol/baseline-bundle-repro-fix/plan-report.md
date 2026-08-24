# baseline-bundle repro 注入计划报告

## 目标与现盘

- live：`.team/ledgers/baseline-bundle-v1.json`，revision=1；
- 目标：由修订后的 DSL 创作面生成下一 revision=2，只使 `t.baseline-bundle.repro` 采用新 REPRO.json 契约并换新 case；
- 必须保留：当前 `failed_retryable` 与 attempt `att-t.baseline-bundle.repro-seq1-t1787590762873`，本轮不 apply、不清 attempt、不重派。

## fresh plan 结论

`ledgerdsl 0.1.1` 的 `plan(source, live_path)` 在产生注入计划前拒绝拆分 live：

```text
现盘账本任务 t.baseline-bundle.impl 含映射层不认识的字段 ['parallel']
```

同一版 DSL 的 `Task.parallel` 已把该字段合法编译到 live/candidate；候选又通过 jsonschema、`ledger-run --preflight` 与 `--dry-run`。但 `applyops.field_ownership()` 的创作面探针未覆盖这个可选字段，所以 plan 把自身生成的合法字段误判成未知字段。见 `plan.log` 与 `schema.log`。

不能通过从 live 副本删除 `parallel`、monkey-patch `field_ownership()` 或手工拼 Plan 来冒充真实 plan；这些做法都会绕过字段所有权守卫，且无法证明 revision 1→2 的安全投影。因此没有执行 apply。

## 不变性证明

`plan.log` 记录：

- live SHA-256 前后相同：`89ba716e85f151b06f05bf61a5631eacbcad910c0f1190a8f264a9b69a6b5723`；
- live repro 仍为 `failed_retryable`，attempt 数仍为 1，原 attempt_id 未变；
- baseline-bundle live 无 lease，前后均 absent；
- `apply_executed: false`。

## 解阻前置

需要 ledgerdsl 发布/提供能够把 `Task.parallel` 识别为创作面字段的 plan 实现；随后对同一 live revision=1 重新运行 plan，只有 plan 明确列出 repro 语义失效、旧 attempt 归档侧车、目标 revision=2，才允许 leader 审核后另行 apply。

verdict: blocked
