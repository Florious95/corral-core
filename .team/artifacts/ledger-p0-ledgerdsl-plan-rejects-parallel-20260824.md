# P0：ledgerdsl plan 拒绝同版 DSL 生成的 `Task.parallel`

## 1. 现象

用户视角：baseline-bundle 首格形式红已完成可执行返修，但无法用规定的 `ledgerdsl plan` 生成从 live revision 1 到下一 revision 2 的安全注入计划，因此不能进入审核/apply，也不能换新 case 继续全自动链。

机器视角：候选账本通过 DSL 构造、同代 jsonschema、`ledger-run --preflight` 与 `--dry-run`；随后 `plan(source, live_path)` 在拆分 live 的第一个带 `parallel` 任务时抛 `LedgerCompileError`，尚未产生 Plan。live SHA-256 前后不变，旧 `failed_retryable` attempt 未清。

## 2. 量具与版本身份

复现工作目录：`/Volumes/nvme/Projects/远程Agent安卓`。

- ledgerdsl：tag `v0.1.1` 的字节副本，路径 `/Users/alauda/.agents/skills/ledger-orchestration/reference/ledgerdsl-0.1.1/`；`MANIFEST.md5` md5=`577d1cbed6572f4dc07242c7338947c5`，mtime=`2026-08-22T04:21:57.906807+08:00`。
- `ledgerdsl/applyops.py`：md5=`1bc370c892d053d7fbf35c00efe50bff`，sha256=`60e6a68a57468af48293ca1d125c09ca15a42213b3de88130fe4708e940f4aa9`，mtime=`2026-08-22T04:21:57.722817+08:00`。
- `ledgerdsl/models.py`：md5=`a96ba2f51b40e890a2b72694bb2cad9f`，sha256=`37e60bd0e7c6be2c75acb5306e74f35febd129f9d1d7c14621f3fc2b76bc8ea2`，mtime=`2026-08-22T04:21:57.721632+08:00`。
- 解释器与依赖：`/usr/bin/python3` 3.9.6，pydantic 2.13.4，jsonschema 4.25.1。
- preflight 量具：`/Users/alauda/.cargo/bin/ledger-run`，md5=`8c1c850bec4c86d230480b99fd6cd671`，sha256=`1cf44a9d40d2dbf025fd9c0bd65ab6ae345e40d7ef103916474d6160c3414175`，mtime=`2026-08-20T15:06:13.757937+08:00`。

原始读数：

- `.team/nodes/spec-sol/baseline-bundle-repro-fix/plan.log`，md5=`08dfe99141f44a9f4c84dcad45c22e33`；
- `.team/nodes/spec-sol/baseline-bundle-repro-fix/schema.log`，md5=`5b64ce19c37cde6761e3c543a20965ca`；
- `.team/nodes/spec-sol/baseline-bundle-repro-fix/preflight.log`，md5=`dc722444f964a690e273b8780e126bd6`。

## 3. Candidate 与 live 坐标

- DSL 源：`.team/ledgers/src/baseline-bundle-v1.py`，sha256=`b3924949c14a733dc92a63b3023595c9d2bfa5a62dfa217efaae3dd0fdff1a59`。
- 编译 candidate：`.team/nodes/spec-sol/baseline-bundle-repro-fix/baseline-bundle-v1.candidate.json`，seed revision=1，sha256=`514051acf21fad8b9cbaabf735e799da683483fff221bb3d1bb59005d2f492be`。
- live：`.team/ledgers/baseline-bundle-v1.json`，runtime revision=1，sha256=`89ba716e85f151b06f05bf61a5631eacbcad910c0f1190a8f264a9b69a6b5723`。
- live repro：state=`failed_retryable`；唯一 attempt=`att-t.baseline-bundle.repro-seq1-t1787590762873`。
- 预期 plan：描述 live revision 1 到将来 apply 后 revision 2 的创作面差异与运行事实投影；本次不 apply。

candidate 与 live 的 `t.baseline-bundle.impl`、`t.baseline-bundle.test`、`t.baseline-bundle.probe` 都含：

```json
"parallel": {"group": "baseline-bundle-wave"}
```

## 4. 最小安全复现

只读复现，不创建副本、不改 live：

```sh
cd /Volumes/nvme/Projects/远程Agent安卓
PYTHONPATH=/Users/alauda/.agents/skills/ledger-orchestration/reference/ledgerdsl-0.1.1 \
  /usr/bin/python3 - <<'PY'
import contextlib
import io
import runpy
from ledgerdsl import plan

with contextlib.redirect_stdout(io.StringIO()):
    source = runpy.run_path('.team/ledgers/src/baseline-bundle-v1.py')['ledger']
print(plan(source, '.team/ledgers/baseline-bundle-v1.json'))
PY
```

期望：输出一个 Plan，至少能说明 repro 的新 task contract 对现有 failed attempt 的失效/归档投影，并把后续 revision 标定为 2。

实际：exit 2；精确拒绝形状为：

```text
ledgerdsl.errors.LedgerCompileError: 现盘账本任务 t.baseline-bundle.impl 含映射层不认识的字段 ['parallel']
规则: 字段所有权表（field_ownership()）之外的键无法拆面，静默丢弃=丢数据；运行面词表: ['attempts', 'blocking_reasons', 'completion', 'replan', 'replay', 'rounds', 'state', 'status_record', 'succession_gap', 'workspace_repair_required']
建议: 该字段若是新 schema 能力，先给 DSL 模型补字段再 apply
```

## 5. 同版 DSL 与合法性对照

这不是调用方手写的未知键：

1. v0.1.1 `models.py:496-508` 明确声明 `Task.parallel: Optional[str]`；`models.py:577-578` 在非空时稳定 emit `{"group": ...}`。
2. 本账本 DSL 源在 `test`、`probe`、`impl` 三格声明 `parallel="baseline-bundle-wave"`，同版编译器把它写入 candidate；live 也由同一创作面产生同形字段。
3. 同代 `ledger.v2.schema.json` 声明 task `parallel`，candidate 的 fresh jsonschema 日志为 `PASS`。
4. `ledger-run --preflight --json <candidate>` fresh 返回 `{"ok":true,"preflight_rejected":false,"issues":[]}`；`--dry-run` 也 exit 0，并得出 repro 为唯一 frontier。

因此对照成立：同一字段由 v0.1.1 模型接受并 emit、由同代 schema 接受、由 preflight 接受，只有 v0.1.1 的 plan 拆面拒绝。

## 6. 原因分析与边界

已直接确认的原因链：

- `applyops.py:44-56` 声称 `_probe_task()` “全可选字段拉满”，但构造 Task 时没有给可选 `parallel` 赋值；
- `applyops.py:59-68` 用 `_probe_task().emit()` 的键集生成创作面 `field_ownership`；于是 `parallel` 不在 creative keys；
- `applyops.py:108-127` 的 `_split_task` 将 live 中既非 creative keys、又非 runtime keys 的 `parallel` 判为未知字段并拒绝。

判断边界：本报告只定位到 `field_ownership` 的 Task 创作面探针漏覆盖 `parallel`，没有调查其它可选 Task 字段是否也存在同族缺口，没有替框架决定代码组织，也没有修改或运行框架测试套件。异常在 plan 拆面阶段已充分解释当前停机；更深层设计取舍属于框架队。

## 7. 为什么不能 normalization / monkey-patch

- 在传给 plan 前手工删除 live 的 `parallel`，会让量具看到的不是待 apply 的真实账本；plan 无法证明并行归属被保留，也可能把相关任务误判为语义变化。
- monkey-patch `_probe_task`/`field_ownership` 或手工拼 `Plan`，会绕过框架用于防止静默丢字段的所有权守卫；结果不是发布量具的读数，不能授权真实 apply。
- 直接覆盖 live candidate 更危险：会丢失 revision 1 的 runtime state/attempt，违反 plan/apply 保留运行面的契约。

所以我方把该状态保留为 blocked，而不是制造一份看似可 apply 的计划。

## 8. 所需最小修复与回归标准

最小框架行为修复：让 `field_ownership()` 的 Task 创作面键集覆盖 v0.1.1 `Task.emit()` 能产生的全部合法创作字段，至少包括 `parallel`；不得放宽未知字段拒绝，也不得把 `parallel` 错归为运行面。具体采用完整探针、模型字段枚举或其它实现方式由框架队决定。

必须新增的回归：

1. 构造含 `Task.parallel` 与对应顶层 `parallelism` 的源，compile 后作为 live；对源仅修改另一格的判据/handoff，再 `plan(source2, live)`，必须成功且不把合法 `parallel` 报未知。
2. Plan 必须只列真实创作差异；未变并行格不得被伪标语义失效。
3. 带 runtime `failed_retryable` + attempt 的目标格发生真实语义变化时，Plan 必须列出将失效/归档的运行事实；在临时账本上 apply 后 revision 只加 1、侧车保留旧 attempt、其它格运行事实不丢。
4. 负对照继续成立：live 注入拼写错误 `paralell` 时仍必须由字段所有权守卫大声拒绝，不能以“接受 parallel”为由吞掉未知字段。
5. 同一夹具必须同时通过同代 compile、jsonschema、preflight、plan；避免再次出现前门接受、手术门拒绝的版本内矛盾。

## 9. 我方处置

我方没有 apply，没有清理 live 的 failed attempt，没有重派 repro，没有停止/改写 driver，也没有修改 framework。`plan.log` 记录 live SHA-256 前后相同、attempt 数仍为 1、attempt_id 未变、`apply_executed: false`。

verdict: pass
