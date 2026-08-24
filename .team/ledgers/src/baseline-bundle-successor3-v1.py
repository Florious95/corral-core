from ledgerdsl import WT, Check, EnvironmentFidelity, FallbackDef, Handoff, Ledger, Provenance, Resources, ScriptRef, Task, dep, role

bootstrap_revision = "f0fce0a44182558c23e4bba66d6b0b6ea91bdf1d"
base = dict(
    environment_fidelity=EnvironmentFidelity(runs_real_cli=True),
    provenance=Provenance(identity="git", revision=bootstrap_revision),
)
continuity_read_paths = [
    ".team/nodes/spec-sol/baseline-bundle-successor3/任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor3/BOOTSTRAP-RESULT.md",
    ".team/nodes/spec-sol/baseline-bundle-successor3/bootstrap-real-teeth.log",
    ".team/nodes/spec-sol/baseline-bundle-successor3/bootstrap-fixture-four-state.log",
    ".team/nodes/spec-sol/baseline-bundle-successor3/bootstrap-sdk-impl-four-state.log",
    ".team/nodes/baseline-bundle-successor3-review/VERDICT.md",
    ".team/nodes/baseline-bundle-successor3-review/tests.log",
    ".team/ledgers/baseline-bundle-v1.json",
    ".team/ledgers/baseline-bundle-successor-v1.json",
    ".team/ledgers/baseline-bundle-successor2-v1.json",
    ".team/nodes/baseline-bundle-repro-diagnosis/VERDICT.md",
    ".team/nodes/baseline-bundle-repro-fix-review/VERDICT.md",
    ".team/nodes/baseline-bundle-successor-review/VERDICT.md",
    ".team/nodes/baseline-bundle-successor-run1-diagnosis/VERDICT.md",
    ".team/nodes/baseline-bundle-successor2-review/VERDICT.md",
    ".team/nodes/baseline-bundle-impl-run1-diagnosis/VERDICT.md",
    ".team/artifacts/ledger-p0-ledgerdsl-plan-rejects-parallel-20260824.md",
    ".team/artifacts/ledger-p0-drive-resume-redispatch-before-consume-20260824.md",
]
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
    title="审计连续性 successor3 首格：本账本仅 supersede 旧三本 future execution，不修改/清洗旧 attempts；在从包含 bootstrap commit f0fce0a44 的当前 main 新建且从未存在的 core WT 中 fresh 重跑真实 legacy probe 两次，交 REPRO.json+REPRO.md。旧 repro/两轮不可判/重复 dispatch 只作 provenance，translator 仅把完整 expected legacy red 转0，probe 2 传2，非预期形1。产物齐后只 report_result。",
    owner_role="repro", seat_wait_seconds=3600,
    resources=Resources(
        worktree_id="wt-bundle3-core-f0",
        write_paths=[".team/nodes/baseline-bundle-repro/"],
        read_paths=continuity_read_paths + [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/repro-任务书.md",
            ".team/ledgers/perf-regress-v1.json",
            ".team/ledgers/perf-regress-v1.json.lease",
            ".team/nodes/_driver/perf-regress-v1.pid",
            ".team/ledgers/acceptance/perf-regress.sh",
            ".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh",
            ".team/ledgers/acceptance/baseline-bundle-repro-translate.sh",
            ".team/ledgers/acceptance/baseline-bundle-repro-regression.sh",
        ],
        **base,
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-repro/REPRO.json",
        ".team/nodes/baseline-bundle-repro/REPRO.md",
    ]),
    checks=[
        Check(id="M.baseline-bundle.repro", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-repro.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=600),
        Check(id="M.baseline-bundle.repro-regression", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-repro-regression.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
    ],
)

test = Task(
    title="按原 test 与 `.team/nodes/spec-sol/baseline-bundle-successor3/test-任务书.md` 在全新隔离 WT 独立产出三项返修红测：canonical final path projection 红绿、SDK/local.properties 缺失2且不泄露值、固定 fixture 缺失2/伪造1、IMPL unjudgeable=1；同时保留原缺资产/exact/A2/归档/迁移/性能门设计。不改实现，产物齐后只 report_result。",
    owner_role="test", seat_wait_seconds=3600, parallel="baseline-bundle-wave",
    resources=Resources(
        worktree_id="wt-canon-red-lane",
        write_paths=[".team/nodes/baseline-bundle-test/"],
        read_paths=continuity_read_paths + [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/test-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor3/test-任务书.md",
            ".team/nodes/baseline-bundle-repro/",
            ".team/nodes/baseline-bundle-prelaunch-review/PRELAUNCH-VERDICT.md",
            ".team/nodes/input-full-auto/perf-design/CONTRACT.md",
            ".team/ledgers/acceptance/baseline-bundle-test.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-test.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-impl.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            "tools/perfbase/",
        ],
        **base,
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-test/RED.md"]),
    checks=[
        Check(id="M.baseline-bundle.test", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-test.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=120),
        Check(id="M.baseline-bundle.successor3-test", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-successor3-test.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=120),
    ],
)

probe = Task(
    title="按原 probe 与 `.team/nodes/spec-sol/baseline-bundle-successor3/probe-任务书.md` 在全新隔离 WT 独立重算 canonical、SDK、固定 provenance 三项返修操作数与破坏齿，并保留运行内容等价、双归档、迁移和1.10探针；旧 probe/replay 只作 provenance。产物齐后只 report_result。",
    owner_role="probe", seat_wait_seconds=3600, parallel="baseline-bundle-wave",
    resources=Resources(
        worktree_id="wt-provenance-oracle",
        write_paths=[".team/nodes/baseline-bundle-probe/"],
        read_paths=continuity_read_paths + [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/probe-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor3/probe-任务书.md",
            ".team/nodes/baseline-bundle-repro/",
            ".team/nodes/baseline-bundle-prelaunch-review/PRELAUNCH-VERDICT.md",
            ".team/nodes/input-full-auto/perf-design/CONTRACT.md",
            ".team/ledgers/acceptance/perf-regress.sh",
            ".team/ledgers/acceptance/baseline-bundle-probe.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-probe.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-impl.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            ".team/nodes/pb-core/tmp/apksigner-verify.txt",
            "tools/perfbase/",
        ],
        **base,
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-probe/PROBE.md"]),
    checks=[
        Check(id="M.baseline-bundle.probe", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-probe.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=120),
        Check(id="M.baseline-bundle.successor3-probe", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-successor3-probe.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=120),
    ],
)

impl = Task(
    title="按原 impl 与 `.team/nodes/spec-sol/baseline-bundle-successor3/impl-任务书.md` 在 fresh core WT 修 canonicalization 并用真实 final apk_relpath 红绿齿锁 bundle_id；SDK/local.properties 只作不泄露值的存在/可执行前置，缺失2；固定 provenance fixture 在新 WT 可寻址，缺失2、伪造1；IMPL unjudgeable/旧 replay 不通过。不改 App/server，产物齐后只 report_result。",
    owner_role="impl", seat_wait_seconds=10800, parallel="baseline-bundle-wave",
    resources=Resources(
        worktree_id="wt-bundle3-core-f0",
        write_paths=[
            "tools/perfbase/baseline-bundle.sh",
            "tools/perfbase/baseline_bundle.py",
            "tools/perfbase/test-baseline-bundle.sh",
            "tools/perfbase/migrate-perf-regress.sh",
            "tools/perfbase/run-input-ab.sh",
            "tools/perfbase/parse-input-ab.py",
            ".gitignore",
            ".team/baseline-bundles/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/nodes/baseline-bundle-impl/",
            ".team/nodes/spec-sol/baseline-bundle/tmp/",
            ".team/nodes/spec-sol/baseline-bundle-successor3/tmp/",
        ],
        read_paths=continuity_read_paths + [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/impl-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor3/impl-任务书.md",
            ".team/nodes/baseline-bundle-repro/",
            ".team/nodes/baseline-bundle-test/",
            ".team/nodes/baseline-bundle-probe/",
            ".team/nodes/input-full-auto/perf-design/CONTRACT.md",
            ".team/ledgers/src/perf-regress-v1.py",
            ".team/ledgers/perf-regress-v1.json",
            ".team/ledgers/acceptance/perf-regress.sh",
            ".team/ledgers/acceptance/baseline-bundle-impl.sh",
            ".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-impl.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-real-fixture.py",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            ".team/nodes/spec-sol/perf-regress/任务书.md",
            ".team/nodes/pb-core/tmp/apksigner-verify.txt",
            "CLAUDE.md",
            "app/",
            "tools/perfbase/",
        ],
        **base,
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-impl/ROUTE.md",
        ".team/nodes/baseline-bundle-impl/IMPL.md",
        ".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json",
        ".team/nodes/baseline-bundle-impl/INSTALL.md",
        ".team/nodes/baseline-bundle-impl/RETRIEVE.md",
    ]),
    checks=[
        Check(id="M.baseline-bundle.successor3-impl", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-successor3-impl.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=3600),
        Check(id="M.baseline-bundle.impl-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
        Check(id="M.baseline-bundle.successor3-impl-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
    ],
)

verify = Task(
    title="按原 verify 任务书并结合 successor3 总任务书，独立核 canonical bundle identity、SDK 前置不泄露、固定 fixture 四态、backup 取回、实际隔离安装与破坏齿；旧 result/实现自报不算，产物齐后只 report_result。",
    owner_role="verify", seat_wait_seconds=7200,
    resources=Resources(
        worktree_id="wt-bundle3-core-f0",
        write_paths=[
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/spec-sol/baseline-bundle/tmp/",
            ".team/nodes/spec-sol/baseline-bundle-successor3/tmp/",
        ],
        read_paths=continuity_read_paths + [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/verify-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor3/impl-任务书.md",
            ".team/nodes/baseline-bundle-repro/",
            ".team/nodes/baseline-bundle-test/",
            ".team/nodes/baseline-bundle-probe/",
            ".team/nodes/baseline-bundle-impl/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/ledgers/acceptance/baseline-bundle-verify.sh",
            ".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-real-fixture.py",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            "tools/perfbase/",
            "app/",
        ],
        **base,
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-verify/VERDICT.md"]),
    checks=[
        Check(id="M.baseline-bundle.verify", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-verify.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=3600),
        Check(id="M.baseline-bundle.verify-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
        Check(id="M.baseline-bundle.successor3-verify-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
    ],
)

user_gate = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/user-gate-任务书.md` 仅把用户对确切 successor3 bundle 的蜂窝+广州中转真机“秒开、没有空白”裁定结构化；不得由 agent/旧口述代判，产物齐后只 report_result。",
    owner_role="user", seat_wait_seconds=10800,
    resources=Resources(
        worktree_id="wt-bundle3-core-f0",
        write_paths=[".team/nodes/baseline-bundle-user/"],
        read_paths=continuity_read_paths + [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/user-gate-任务书.md",
            ".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json",
            ".team/nodes/baseline-bundle-verify/",
            ".team/ledgers/acceptance/baseline-bundle-user-gate.sh",
        ],
        **base,
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-user/USER-GATE.json",
        ".team/nodes/baseline-bundle-user/USER-GATE.md",
    ]),
    checks=[
        Check(id="M.baseline-bundle.user", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-user-gate.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=120),
    ],
)

migrate = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/migrate-任务书.md` 在 successor3 verify+用户 gate 机械前置全绿后，仅 TERM 旧 perf-regress 精确 PID，并用 ledgerdsl plan/apply 将旧链 paused、保留全部历史；现场漂移不得发信号，产物齐后只 report_result。",
    owner_role="migrate", seat_wait_seconds=3600,
    resources=Resources(
        worktree_id="wt-bundle3-core-f0",
        write_paths=[
            ".team/nodes/baseline-bundle-migrate/",
            ".team/ledgers/src/perf-regress-v1.py",
            ".team/ledgers/perf-regress-v1.json",
            ".team/ledgers/perf-regress-v1.json.lease",
            ".team/nodes/_driver/perf-regress-v1.pid",
        ],
        read_paths=continuity_read_paths + [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/migrate-任务书.md",
            ".team/nodes/baseline-bundle-impl/",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-user/",
            ".team/nodes/perf-regress/FIXED-MEASURE.md",
            ".team/ledgers/acceptance/baseline-bundle-migrate.sh",
            "tools/perfbase/migrate-perf-regress.sh",
        ],
        **base,
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-migrate/MIGRATION.md"]),
    checks=[
        Check(id="M.baseline-bundle.migrate", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-migrate.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=600),
    ],
)

measure = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/measure-任务书.md` 重新核 successor3 bundle 取回/摘要/安装与 envcheck 后，用 bundle A2 对新 B 做唯一 fresh 三夹具四段 A/B/A/B；每夹具每段 n>=10、nearest-rank p50/p95、同批身份且全格 B/A<=1.10，不改阈值，产物齐后只 report_result。",
    owner_role="measure", seat_wait_seconds=10800,
    resources=Resources(
        worktree_id="wt-bundle3-core-f0",
        write_paths=[
            ".team/nodes/baseline-bundle-measure/",
            ".team/private/baseline-candidates/",
            ".team/nodes/spec-sol/baseline-bundle/tmp/",
            ".team/nodes/spec-sol/baseline-bundle-successor3/tmp/",
        ],
        read_paths=continuity_read_paths + [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/measure-任务书.md",
            ".team/nodes/baseline-bundle-impl/",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-user/",
            ".team/nodes/baseline-bundle-migrate/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/nodes/input-full-auto/perf-design/CONTRACT.md",
            ".team/ledgers/acceptance/baseline-bundle-measure.sh",
            ".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-measure.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            "tools/perfbase/",
            "app/",
        ],
        **base,
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-measure/MEASURE.md",
        ".team/nodes/baseline-bundle-measure/perf-ab-bundle.json",
        ".team/nodes/baseline-bundle-measure/PRE-MEASURE.json",
    ]),
    checks=[
        Check(id="M.baseline-bundle.successor3-measure", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-successor3-measure.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=1800),
        Check(id="M.baseline-bundle.measure-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
        Check(id="M.baseline-bundle.successor3-measure-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
    ],
)

final = Task(
    title="按 `.team/nodes/spec-sol/baseline-bundle/final-任务书.md` 独立终审诚实 exact/A2 路线、canonical 不可变 bundle 与双归档取回、SDK/fixture 四态、用户真机“秒开无空白”、旧链迁移和 fresh 三夹具四段 B/A<=1.10，并选择跨链破坏齿；产物齐后只 report_result。",
    owner_role="final", seat_wait_seconds=3600,
    resources=Resources(
        worktree_id="wt-bundle3-core-f0",
        write_paths=[
            ".team/nodes/baseline-bundle-final/",
            ".team/nodes/spec-sol/baseline-bundle/tmp/",
            ".team/nodes/spec-sol/baseline-bundle-successor3/tmp/",
        ],
        read_paths=continuity_read_paths + [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/final-任务书.md",
            ".team/nodes/baseline-bundle-impl/",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-user/",
            ".team/nodes/baseline-bundle-migrate/",
            ".team/nodes/baseline-bundle-measure/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/ledgers/perf-regress-v1.json",
            ".team/ledgers/acceptance/baseline-bundle-final.sh",
            ".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh",
            ".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            "tools/perfbase/",
        ],
        **base,
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-final/VERDICT.md"]),
    checks=[
        Check(id="M.baseline-bundle.final", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-final.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=3600),
        Check(id="M.baseline-bundle.final-real-chain", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
        Check(id="M.baseline-bundle.final-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
        Check(id="M.baseline-bundle.successor3-final-bypass", script=ScriptRef(path=".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh", expect=0, unjudgeable=[2]), cwd=WT, budget=300),
    ],
)

ledger = Ledger(
    ledger_id="ledger.baseline-bundle.successor3.v1",
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
    fallback={
        "F.escalate": FallbackDef(
            role="advisor",
            triggers=["blocked_on_unknown", "provider_unavailable", "result_deadline_elapsed", "delivery_uncertain"],
        ),
    },
    repo_root="/Volumes/nvme/Projects/远程Agent安卓",
)
print(ledger.compile(), end="")
