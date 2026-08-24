from ledgerdsl import WT, Check, EnvironmentFidelity, FallbackDef, Handoff, Ledger, Provenance, Resources, ScriptRef, Task, dep, role

base = dict(environment_fidelity=EnvironmentFidelity(runs_real_cli=True), provenance=Provenance(identity="git", revision="a538117cc2e9832c88754ccfa9d6f9becb6a91b0"))
roles = {
    "repro": role(".team/nodes/baseline-bundle-repro/", agent="sampler-test-luna2", team="remote-agent-android", provider="codex"),
    "test": role(".team/nodes/baseline-bundle-test/", agent="sampler-test-luna2", team="remote-agent-android", provider="codex"),
    "impl": role(".team/nodes/baseline-bundle-impl/", agent="sampler-dev-luna2", team="remote-agent-android", provider="codex"),
    "probe": role(".team/nodes/baseline-bundle-probe/", agent="sampler-review-luna2", team="remote-agent-android", provider="codex"),
    "verify": role(".team/nodes/baseline-bundle-verify/", agent="sampler-review-luna2", team="remote-agent-android", provider="codex"),
    "user": role(".team/nodes/baseline-bundle-user/", agent="takeover-codex-luna", team="remote-agent-android", provider="codex"),
    "migrate": role(".team/nodes/baseline-bundle-migrate/", agent="sampler-dev-luna2", team="remote-agent-android", provider="codex"),
    "measure": role(".team/nodes/baseline-bundle-measure/", agent="sampler-dev-luna2", team="remote-agent-android", provider="codex"),
    "final": role(".team/nodes/baseline-bundle-final/", agent="sampler-review-luna2", team="remote-agent-android", provider="codex"),
    "advisor": role(".team/nodes/input-full-auto/advisor/", agent="input-advisor-luna", team="remote-agent-android", provider="codex"),
}

repro = Task(
    title="返修新 case：按 `.team/nodes/spec-sol/baseline-bundle/repro-任务书.md` 沿用上轮语义分析但必须重新连续运行两次真实 probe，交 REPRO.json 固定 schema+REPRO.md；由 translator 把完整的 expected legacy red 转为 acceptance 0，probe 2 仍传 2，伪造/非预期为 1。不得改变真实 ledger/emulator/daemon，产物齐后 report_result。",
    owner_role="repro", seat_wait_seconds=3600,
    resources=Resources(worktree_id="wt-bundle-core", write_paths=[".team/nodes/baseline-bundle-repro/"], read_paths=[".team/nodes/spec-sol/baseline-bundle/任务书.md", ".team/nodes/spec-sol/baseline-bundle/repro-任务书.md", ".team/nodes/baseline-bundle-repro-diagnosis/VERDICT.md", ".team/ledgers/perf-regress-v1.json", ".team/ledgers/perf-regress-v1.json.lease", ".team/nodes/_driver/perf-regress-v1.pid", ".team/ledgers/acceptance/perf-regress.sh", ".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh", ".team/ledgers/acceptance/baseline-bundle-repro-translate.sh", ".team/ledgers/acceptance/baseline-bundle-repro-regression.sh"], **base),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-repro/REPRO.json", ".team/nodes/baseline-bundle-repro/REPRO.md"]),
    checks=[
        Check(id="M.baseline-bundle.repro", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-repro.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=600),
        Check(id="M.baseline-bundle.repro-regression", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-repro-regression.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
    ],
)

test = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/test-任务书.md` 并行产出缺资产、exact、A2、归档恢复、迁移与性能门红测设计；只写 RED.md，产物齐后 report_result。",
    owner_role="test", seat_wait_seconds=3600, parallel="baseline-bundle-wave",
    resources=Resources(worktree_id="wt-bb-test", write_paths=[".team/nodes/baseline-bundle-test/"], read_paths=[".team/nodes/spec-sol/baseline-bundle/任务书.md", ".team/nodes/spec-sol/baseline-bundle/test-任务书.md", ".team/nodes/baseline-bundle-repro/", ".team/nodes/baseline-bundle-prelaunch-review/PRELAUNCH-VERDICT.md", ".team/nodes/baseline-bundle-prelaunch-review/tmp/", ".team/nodes/input-full-auto/perf-design/CONTRACT.md", ".team/ledgers/acceptance/baseline-bundle-test.sh", "tools/perfbase/"], **base),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-test/RED.md"]),
    checks=[Check(id="M.baseline-bundle.test", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-test.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=120)],
)

probe = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/probe-任务书.md` 并行产出运行内容等价、双份归档、旧 park 迁移操作数与破坏齿；只写 PROBE.md，产物齐后 report_result。",
    owner_role="probe", seat_wait_seconds=3600, parallel="baseline-bundle-wave",
    resources=Resources(worktree_id="wt-archive-probe", write_paths=[".team/nodes/baseline-bundle-probe/"], read_paths=[".team/nodes/spec-sol/baseline-bundle/任务书.md", ".team/nodes/spec-sol/baseline-bundle/probe-任务书.md", ".team/nodes/baseline-bundle-repro/", ".team/nodes/baseline-bundle-prelaunch-review/PRELAUNCH-VERDICT.md", ".team/nodes/baseline-bundle-prelaunch-review/tmp/", ".team/nodes/input-full-auto/perf-design/CONTRACT.md", ".team/ledgers/acceptance/perf-regress.sh", ".team/ledgers/acceptance/baseline-bundle-probe.sh", ".team/nodes/pb-core/tmp/apksigner-verify.txt", "tools/perfbase/"], **base),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-probe/PROBE.md"]),
    checks=[Check(id="M.baseline-bundle.probe", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-probe.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=120)],
)

impl = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/impl-任务书.md` 并行实现诚实 exact/A2 路线、Baseline Bundle v1、私有双份归档/恢复、runner 消费与安全迁移工具；不改 App/server，产物齐后 report_result。",
    owner_role="impl", seat_wait_seconds=10800, parallel="baseline-bundle-wave",
    resources=Resources(worktree_id="wt-bundle-core", write_paths=["tools/perfbase/baseline-bundle.sh", "tools/perfbase/baseline_bundle.py", "tools/perfbase/test-baseline-bundle.sh", "tools/perfbase/migrate-perf-regress.sh", "tools/perfbase/run-input-ab.sh", "tools/perfbase/parse-input-ab.py", ".gitignore", ".team/baseline-bundles/", ".team/private/baseline-vault/", ".team/private/baseline-backup/", ".team/nodes/baseline-bundle-impl/", ".team/nodes/spec-sol/baseline-bundle/tmp/"], read_paths=[".team/nodes/spec-sol/baseline-bundle/任务书.md", ".team/nodes/spec-sol/baseline-bundle/impl-任务书.md", ".team/nodes/baseline-bundle-repro/", ".team/nodes/baseline-bundle-test/", ".team/nodes/baseline-bundle-probe/", ".team/nodes/baseline-bundle-prelaunch-review/tmp/", ".team/nodes/input-full-auto/perf-design/CONTRACT.md", ".team/ledgers/src/perf-regress-v1.py", ".team/ledgers/perf-regress-v1.json", ".team/ledgers/acceptance/perf-regress.sh", ".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", ".team/nodes/spec-sol/perf-regress/任务书.md", ".team/nodes/pb-core/tmp/apksigner-verify.txt", "CLAUDE.md", "app/", "tools/perfbase/"], **base),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-impl/ROUTE.md", ".team/nodes/baseline-bundle-impl/IMPL.md", ".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json", ".team/nodes/baseline-bundle-impl/INSTALL.md", ".team/nodes/baseline-bundle-impl/RETRIEVE.md"]),
    checks=[
        Check(id="M.baseline-bundle.impl", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-impl.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=3600),
        Check(id="M.baseline-bundle.impl-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
    ],
)

verify = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/verify-任务书.md` 独立核 bundle 身份、backup 取回、实际隔离安装和破坏齿；不采信实现自报，产物齐后 report_result。",
    owner_role="verify", seat_wait_seconds=7200,
    resources=Resources(worktree_id="wt-bundle-core", write_paths=[".team/nodes/baseline-bundle-verify/", ".team/nodes/spec-sol/baseline-bundle/tmp/"], read_paths=[".team/nodes/spec-sol/baseline-bundle/任务书.md", ".team/nodes/spec-sol/baseline-bundle/verify-任务书.md", ".team/nodes/baseline-bundle-repro/", ".team/nodes/baseline-bundle-test/", ".team/nodes/baseline-bundle-probe/", ".team/nodes/baseline-bundle-impl/", ".team/nodes/baseline-bundle-prelaunch-review/tmp/", ".team/private/baseline-vault/", ".team/private/baseline-backup/", ".team/ledgers/acceptance/baseline-bundle-verify.sh", ".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", "tools/perfbase/", "app/"], **base),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-verify/VERDICT.md"]),
    checks=[
        Check(id="M.baseline-bundle.verify", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-verify.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=3600),
        Check(id="M.baseline-bundle.verify-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
    ],
)

user_gate = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/user-gate-任务书.md` 仅把用户对确切 bundle 的蜂窝+广州中转真机秒开无空白裁定结构化；不得由 agent 代判，产物齐后 report_result。",
    owner_role="user", seat_wait_seconds=10800,
    resources=Resources(worktree_id="wt-bundle-core", write_paths=[".team/nodes/baseline-bundle-user/"], read_paths=[".team/nodes/spec-sol/baseline-bundle/任务书.md", ".team/nodes/spec-sol/baseline-bundle/user-gate-任务书.md", ".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json", ".team/nodes/baseline-bundle-verify/", ".team/ledgers/acceptance/baseline-bundle-user-gate.sh"], **base),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-user/USER-GATE.json", ".team/nodes/baseline-bundle-user/USER-GATE.md"]),
    checks=[Check(id="M.baseline-bundle.user", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-user-gate.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=120)],
)

migrate = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/migrate-任务书.md` 在机械前置全绿后仅 TERM 旧 perf-regress 精确 PID，并用 ledgerdsl plan/apply 将旧链置 paused、保留历史；现场漂移不得发信号，产物齐后 report_result。",
    owner_role="migrate", seat_wait_seconds=3600,
    resources=Resources(worktree_id="wt-bundle-core", write_paths=[".team/nodes/baseline-bundle-migrate/", ".team/ledgers/src/perf-regress-v1.py", ".team/ledgers/perf-regress-v1.json", ".team/ledgers/perf-regress-v1.json.lease", ".team/nodes/_driver/perf-regress-v1.pid"], read_paths=[".team/nodes/spec-sol/baseline-bundle/任务书.md", ".team/nodes/spec-sol/baseline-bundle/migrate-任务书.md", ".team/nodes/baseline-bundle-impl/", ".team/nodes/baseline-bundle-verify/", ".team/nodes/baseline-bundle-user/", ".team/nodes/perf-regress/FIXED-MEASURE.md", ".team/ledgers/acceptance/baseline-bundle-migrate.sh", "tools/perfbase/migrate-perf-regress.sh"], **base),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-migrate/MIGRATION.md"]),
    checks=[Check(id="M.baseline-bundle.migrate", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-migrate.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=600)],
)

measure = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/measure-任务书.md` 重新核 bundle 取回/摘要/安装与 envcheck 后，用 bundle A 对新 B 做唯一 fresh 三夹具四段 A/B/A/B；不改 1.10，产物齐后 report_result。",
    owner_role="measure", seat_wait_seconds=10800,
    resources=Resources(worktree_id="wt-bundle-core", write_paths=[".team/nodes/baseline-bundle-measure/", ".team/private/baseline-candidates/", ".team/nodes/spec-sol/baseline-bundle/tmp/"], read_paths=[".team/nodes/spec-sol/baseline-bundle/任务书.md", ".team/nodes/spec-sol/baseline-bundle/measure-任务书.md", ".team/nodes/baseline-bundle-impl/", ".team/nodes/baseline-bundle-verify/", ".team/nodes/baseline-bundle-user/", ".team/nodes/baseline-bundle-migrate/", ".team/nodes/baseline-bundle-prelaunch-review/tmp/", ".team/private/baseline-vault/", ".team/private/baseline-backup/", ".team/nodes/input-full-auto/perf-design/CONTRACT.md", ".team/ledgers/acceptance/baseline-bundle-measure.sh", ".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", "tools/perfbase/", "app/"], **base),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-measure/MEASURE.md", ".team/nodes/baseline-bundle-measure/perf-ab-bundle.json", ".team/nodes/baseline-bundle-measure/PRE-MEASURE.json"]),
    checks=[
        Check(id="M.baseline-bundle.measure", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-measure.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=1800),
        Check(id="M.baseline-bundle.measure-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
    ],
)

final = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/final-任务书.md` 独立终审诚实路线、不可变归档取回、用户 gate、旧链迁移与 fresh 1.10 门，并选择跨链破坏齿；产物齐后 report_result。",
    owner_role="final", seat_wait_seconds=3600,
    resources=Resources(worktree_id="wt-bundle-core", write_paths=[".team/nodes/baseline-bundle-final/", ".team/nodes/spec-sol/baseline-bundle/tmp/"], read_paths=[".team/nodes/spec-sol/baseline-bundle/任务书.md", ".team/nodes/spec-sol/baseline-bundle/final-任务书.md", ".team/nodes/baseline-bundle-impl/", ".team/nodes/baseline-bundle-verify/", ".team/nodes/baseline-bundle-user/", ".team/nodes/baseline-bundle-migrate/", ".team/nodes/baseline-bundle-measure/", ".team/nodes/baseline-bundle-prelaunch-review/tmp/", ".team/private/baseline-vault/", ".team/private/baseline-backup/", ".team/ledgers/perf-regress-v1.json", ".team/ledgers/acceptance/baseline-bundle-final.sh", ".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh", ".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", "tools/perfbase/"], **base),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-final/VERDICT.md"]),
    checks=[
        Check(id="M.baseline-bundle.final", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-final.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=3600),
        Check(id="M.baseline-bundle.final-real-chain", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
        Check(id="M.baseline-bundle.final-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
    ],
)

ledger = Ledger(
    ledger_id="ledger.baseline-bundle.v1",
    roles=roles,
    tasks={
        "t.baseline-bundle.repro": repro,
        "t.baseline-bundle.test": test,
        "t.baseline-bundle.probe": probe,
        "t.baseline-bundle.impl": impl,
        "t.baseline-bundle.verify": verify,
        "t.baseline-bundle.user-gate": user_gate,
        "t.baseline-bundle.migrate": migrate,
        "t.baseline-bundle.measure": measure,
        "t.baseline-bundle.final": final,
    },
    dependencies=[
        dep("t.baseline-bundle.repro", "t.baseline-bundle.test"),
        dep("t.baseline-bundle.repro", "t.baseline-bundle.probe"),
        dep("t.baseline-bundle.repro", "t.baseline-bundle.impl"),
        dep("t.baseline-bundle.test", "t.baseline-bundle.verify"),
        dep("t.baseline-bundle.probe", "t.baseline-bundle.verify"),
        dep("t.baseline-bundle.impl", "t.baseline-bundle.verify"),
        dep("t.baseline-bundle.verify", "t.baseline-bundle.user-gate"),
        dep("t.baseline-bundle.user-gate", "t.baseline-bundle.migrate"),
        dep("t.baseline-bundle.migrate", "t.baseline-bundle.measure"),
        dep("t.baseline-bundle.measure", "t.baseline-bundle.final"),
    ],
    parallelism=[{"group": "baseline-bundle-wave", "max_concurrency": 3, "failure_policy": "halt"}],
    fallback={"F.escalate": FallbackDef(role="advisor", triggers=["blocked_on_unknown", "provider_unavailable", "result_deadline_elapsed", "delivery_uncertain"])},
    repo_root="/Volumes/nvme/Projects/远程Agent安卓",
)
print(ledger.compile(), end="")
