# baseline-bundle successor3 bootstrap result

## 结论

启动审查的四项 refutes 已在 bootstrap 阶段逐条关闭；本轮没有生成或启动 final successor3 ledger。leader 可先独立语义审查并提交本包，随后 sol 才能以实际 bootstrap commit SHA 生成 final DSL/compiled ledger。

## Refutes 对照

1. **真实 canonical 红绿齿**：`baseline-bundle-successor3-real-fixture.py` 复制但不改写真实 `tools/perfbase/baseline_bundle.py` 字节，通过其真实 `retrieve` 入口验证 final projection 取回 rc0、stale bundle id 对 final path projection 精确 `manifest bundle_id mismatch` rc2。实现入口 SHA-256 为 `6c15312b82aa78a1a31dcb8315cf2cc6b492999f071e631cfc8697898811938d`；判据中没有重写 synthetic hash 恒等式。
2. **Controlled bypass**：canonical 绿色控制 rc0，仅改 `implementation.bundle_py.sha256` 后真实入口精确报 mismatch；底层不可信 manifest rc2 由 hardened 齿归类为1。measure 绿色控制 rc0，仅改声明 runner SHA 后 hardened rc1 且精确原因 `runner provenance mismatch`。两组均保持合法 git root、非空 raw 和其它身份/样本不变，明确拒绝 `repository root mismatch`/`empty raw log` 旁路红。
3. **稳定 fixture**：固定路径 `.team/ledgers/acceptance/fixtures/baseline-bundle-successor3/control-contract.json`，SHA-256 `ffcea3d0d3282618ad91f9db44c7a99616868b6610c88516e022385e59bd3fd9`。缺失和摘要漂移均 fresh exit2；路径未被 Git ignore，待 leader 审查后与 acceptance/任务书一并提交，不引用 untracked tmp 作为 future ledger 输入。
4. **SDK/IMPL 四态**：`implementation: unjudgeable` fresh exit1；`implementation: pass` 且 `app/local.properties` 缺失 fresh exit2。判据只做存在性/可读性/可执行性前置，不输出 local.properties、SDK、工具或凭据值。
5. **两阶段断环**：bootstrap 任务书冻结旧账本/attempt/commits 和完整九格图，明确阶段二必须 `git cat-file` 验证 bootstrap commit 中每个固定路径后才能编译 final。当前 final DSL、JSON、lease、PID、三个预留 WT 及其 metadata 全部不存在。

test/probe 的便宜 grep gate仍只核 RED.md/PROBE.md 交付形状，不冒充事实覆盖；future verify/final 必须 fresh 执行 canonical/bypass 真实入口门。B/A<=1.10、真机“秒开无空白”、迁移前置、envcheck、测试禁缓存、旧历史只读等终局均未弱化。

## Bootstrap 文件

- 总任务书：`.team/nodes/spec-sol/baseline-bundle-successor3/任务书.md`
- 路径级任务书：`impl-任务书.md`、`test-任务书.md`、`probe-任务书.md`
- 真实齿：`.team/ledgers/acceptance/baseline-bundle-successor3-real-fixture.py`
- 判据：`.team/ledgers/acceptance/baseline-bundle-successor3-{canonical,impl,test,probe,measure,bypass}.sh`
- 固定契约：`.team/ledgers/acceptance/fixtures/baseline-bundle-successor3/control-contract.json`

## Fresh 证据

- `bootstrap-real-teeth.log`：真实 retrieve 红绿、canonical provenance 与 measure runner 单变量控制，两个 meta wrapper 均 exit0。
- `bootstrap-fixture-four-state.log`：fixture 缺失2、摘要漂移2、精确 fixture 控制绿。
- `bootstrap-sdk-impl-four-state.log`：IMPL unjudgeable=1、local.properties 缺失=2，未输出值。
- `bootstrap-syntax.log`：六个脚本 `sh -n`/`shellcheck -s sh` 与 helper `ast.parse` 全部 exit0。
- `bootstrap-continuity.log`：旧三本 SHA 不变、历史 commit 可达、固定路径可提交、产品范围未改。
- `bootstrap-no-final.log`：final DSL/JSON/runtime/WT 均不存在，未启动。

阶段二禁止复用本轮临时隔离目录；它只能读取 leader 提交后的稳定 Git 路径和实际 commit SHA 重新生成 final successor3。

verdict: pass
