from ledgerdsl import WT, Check, EnvironmentFidelity, FallbackDef, Handoff, Ledger, Provenance, Resources, ScriptRef, Task, dep, role

provenance_revision = "548572dfd7d8ee2e3f602a274268e8bd881ef8b2"
core_wt = "wt-maple-core"
test_wt = "wt-indigo-tests"
probe_wt = "wt-falcon-review"

common_read = [
    ".team/nodes/spec-sol/baseline-bundle-successor6/任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor6/final-任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor6/BOOTSTRAP-RESULT.md",
    ".team/nodes/baseline-bundle-successor5-impl-diagnosis/VERDICT.md",
    ".team/ledgers/baseline-bundle-successor5-v1.json",
    ".team/nodes/spec-sol/baseline-bundle-successor4/任务书.md",
    ".team/nodes/baseline-bundle-successor3-run1-diagnosis/VERDICT.md",
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
    ".team/ledgers/baseline-bundle-successor3-v1.json",
    ".team/ledgers/baseline-bundle-successor4-v1.json",
    ".team/nodes/baseline-bundle-repro-diagnosis/VERDICT.md",
    ".team/nodes/baseline-bundle-repro-fix-review/VERDICT.md",
    ".team/nodes/baseline-bundle-successor-review/VERDICT.md",
    ".team/nodes/baseline-bundle-successor-run1-diagnosis/VERDICT.md",
    ".team/nodes/baseline-bundle-successor2-review/VERDICT.md",
    ".team/nodes/baseline-bundle-impl-run1-diagnosis/VERDICT.md",
    ".team/artifacts/ledger-p0-ledgerdsl-plan-rejects-parallel-20260824.md",
    ".team/artifacts/ledger-p0-drive-resume-redispatch-before-consume-20260824.md",
]


def resources(worktree_id, write_paths, read_paths):
    return Resources(
        worktree_id=worktree_id,
        write_paths=write_paths,
        read_paths=common_read + read_paths,
        environment_fidelity=EnvironmentFidelity(runs_real_cli=True),
        provenance=Provenance(identity="git", revision=provenance_revision),
    )


def check(check_id, path, budget):
    return Check(
        id=check_id,
        script=ScriptRef(path=path, expect=0, unjudgeable=[2]),
        cwd=WT,
        budget=budget,
    )


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
    title="successor6 首格：按原 repro 任务书在 fresh core WT 重跑真实 legacy probe 两次并交固定 schema REPRO.json+REPRO.md；旧六本 attempts、successor5 真实 A2 构建与三门成功、impl 旧固定点红只作 provenance，不得重放冒充新证据。translator 仅完整 expected red 转0，probe 2传2，非预期形1。不得修改旧账，产物齐后只 report_result。",
    owner_role="repro",
    seat_wait_seconds=3600,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-repro/"],
        [
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
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-repro/REPRO.json",
        ".team/nodes/baseline-bundle-repro/REPRO.md",
    ]),
    checks=[
        check("M.baseline-bundle.repro", ".team/ledgers/acceptance/baseline-bundle-repro.sh", 600),
        check("M.baseline-bundle.repro-regression", ".team/ledgers/acceptance/baseline-bundle-repro-regression.sh", 300),
    ],
)

test = Task(
    title="按 successor6 test 任务书在全新隔离 WT 独立交 canonical/fixture/SDK fallback 与 required-list legacy-negative 红测；任何 Gradle 前先用有效环境，否则从主仓白名单安全派生最小0600且未跟踪的 app/local.properties，失败2。只写 RED.md，产物齐后只 report_result。",
    owner_role="test",
    seat_wait_seconds=3600,
    parallel="baseline-bundle-wave",
    resources=resources(
        test_wt,
        [".team/nodes/baseline-bundle-test/", ".team/nodes/baseline-bundle-sdk-teeth/", "app/local.properties"],
        [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/test-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor3/test-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor6/test-任务书.md",
            ".team/nodes/baseline-bundle-repro/",
            ".team/nodes/input-full-auto/perf-design/CONTRACT.md",
            ".team/ledgers/baseline-bundle-successor6-v1.json",
            ".team/ledgers/acceptance/baseline-bundle-successor6-test.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-structure.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.py",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk-regression.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-test.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-projection-regression.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-projection.py",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor6/",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            "app/",
            "tools/perfbase/",
        ],
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-test/RED.md"]),
    checks=[
        check("M.baseline-bundle.successor6-test", ".team/ledgers/acceptance/baseline-bundle-successor6-test.sh", 300),
    ],
)

probe = Task(
    title="按 successor6 probe 任务书在全新隔离 WT 独立重算 canonical/fixed-fixture/SDK fallback 与 required-list legacy-negative 操作数；probe 门不要求 source_tree_sha256。任何 Gradle 前先用有效环境，否则从主仓白名单安全派生最小0600且未跟踪的 app/local.properties，失败2。只写 PROBE.md，产物齐后只 report_result。",
    owner_role="probe",
    seat_wait_seconds=3600,
    parallel="baseline-bundle-wave",
    resources=resources(
        probe_wt,
        [".team/nodes/baseline-bundle-probe/", ".team/nodes/baseline-bundle-sdk-teeth/", "app/local.properties"],
        [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/probe-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor3/probe-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor6/probe-任务书.md",
            ".team/nodes/baseline-bundle-repro/",
            ".team/nodes/input-full-auto/perf-design/CONTRACT.md",
            ".team/ledgers/baseline-bundle-successor6-v1.json",
            ".team/ledgers/acceptance/baseline-bundle-successor6-probe.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-structure.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.py",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk-regression.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-probe.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-projection-regression.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-projection.py",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor6/",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            ".team/nodes/pb-core/tmp/apksigner-verify.txt",
            "app/",
            "tools/perfbase/",
        ],
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-probe/PROBE.md"]),
    checks=[
        check("M.baseline-bundle.successor6-probe", ".team/ledgers/acceptance/baseline-bundle-successor6-probe.sh", 300),
    ],
)

impl = Task(
    title="按 successor6 impl 任务书在 fresh core WT 修 canonicalization、真实 bundle/retrieve/archive；任何 Gradle 前先用有效 SDK 环境，否则从主仓根白名单安全派生最小0600且未跟踪的 app/local.properties，失败2。required 只挂 successor6 impl 与已 bootstrap controlled bypass，禁止 legacy impl-bypass。产物齐后只 report_result。",
    owner_role="impl",
    seat_wait_seconds=10800,
    parallel="baseline-bundle-wave",
    resources=resources(
        core_wt,
        [
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
            ".team/nodes/baseline-bundle-sdk-teeth/",
            ".team/nodes/spec-sol/baseline-bundle/tmp/",
            "app/local.properties",
        ],
        [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/impl-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor3/impl-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor6/impl-任务书.md",
            ".team/nodes/baseline-bundle-repro/",
            ".team/nodes/baseline-bundle-test/",
            ".team/nodes/baseline-bundle-probe/",
            ".team/nodes/input-full-auto/perf-design/CONTRACT.md",
            ".team/ledgers/baseline-bundle-successor6-v1.json",
            ".team/ledgers/acceptance/perf-regress.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-impl.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-structure.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-deep.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-projection-regression.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-projection.py",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor6/",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.py",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk-regression.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-canonical.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-real-fixture.py",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            ".team/nodes/spec-sol/perf-regress/任务书.md",
            ".team/nodes/pb-core/tmp/apksigner-verify.txt",
            "CLAUDE.md",
            "app/",
            "tools/perfbase/",
        ],
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-impl/ROUTE.md",
        ".team/nodes/baseline-bundle-impl/IMPL.md",
        ".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json",
        ".team/nodes/baseline-bundle-impl/INSTALL.md",
        ".team/nodes/baseline-bundle-impl/RETRIEVE.md",
    ]),
    checks=[
        check("M.baseline-bundle.successor6-impl", ".team/ledgers/acceptance/baseline-bundle-successor6-impl.sh", 3600),
        check("M.baseline-bundle.successor6-bypass", ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh", 300),
    ],
)

verify = Task(
    title="按原 verify 与 successor6 总任务书独立核 canonical bundle、固定 fixture、双归档取回、安装与破坏齿；任何 Gradle 前安全生成非版本化 app/local.properties，SDK 缺失2。旧 result/自报不算，产物齐后只 report_result。",
    owner_role="verify",
    seat_wait_seconds=7200,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-verify/", ".team/nodes/spec-sol/baseline-bundle/tmp/", "app/local.properties"],
        [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/verify-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle-successor6/impl-任务书.md",
            ".team/nodes/baseline-bundle-repro/",
            ".team/nodes/baseline-bundle-test/",
            ".team/nodes/baseline-bundle-probe/",
            ".team/nodes/baseline-bundle-impl/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/ledgers/acceptance/baseline-bundle-successor6-verify.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-impl.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.py",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-real-fixture.py",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            "tools/perfbase/",
            "app/",
        ],
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-verify/VERDICT.md",
        ".team/nodes/baseline-bundle-verify/RETRIEVE.md",
        ".team/nodes/baseline-bundle-verify/INSTALL.md",
        ".team/nodes/baseline-bundle-verify/MUTATION.md",
        ".team/nodes/baseline-bundle-verify/VERIFY.json",
    ]),
    checks=[
        check("M.baseline-bundle.successor6-verify", ".team/ledgers/acceptance/baseline-bundle-successor6-verify.sh", 3600),
        check("M.baseline-bundle.successor6-verify-bypass", ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh", 300),
    ],
)

user_gate = Task(
    title="按原 user-gate 任务书仅结构化用户对确切 successor6 bundle 的蜂窝+广州中转真机‘秒开、没有空白’裁定；agent/模拟器/旧口述不得代判，产物齐后只 report_result。",
    owner_role="user",
    seat_wait_seconds=10800,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-user/"],
        [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/user-gate-任务书.md",
            ".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json",
            ".team/nodes/baseline-bundle-verify/",
            ".team/ledgers/acceptance/baseline-bundle-user-gate.sh",
        ],
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-user/USER-GATE.json",
        ".team/nodes/baseline-bundle-user/USER-GATE.md",
    ]),
    checks=[check("M.baseline-bundle.user", ".team/ledgers/acceptance/baseline-bundle-user-gate.sh", 120)],
)

migrate = Task(
    title="按原 migrate 任务书仅在 successor6 verify+用户 gate 全绿后，以精确 PID/账本状态前置 TERM 旧 perf-regress，并用 ledgerdsl plan/apply paused；漂移不发信号，不清历史。产物齐后只 report_result。",
    owner_role="migrate",
    seat_wait_seconds=3600,
    resources=resources(
        core_wt,
        [
            ".team/nodes/baseline-bundle-migrate/",
            ".team/ledgers/src/perf-regress-v1.py",
            ".team/ledgers/perf-regress-v1.json",
            ".team/ledgers/perf-regress-v1.json.lease",
            ".team/nodes/_driver/perf-regress-v1.pid",
        ],
        [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/migrate-任务书.md",
            ".team/nodes/baseline-bundle-impl/",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-user/",
            ".team/nodes/perf-regress/FIXED-MEASURE.md",
            ".team/ledgers/acceptance/baseline-bundle-migrate.sh",
            "tools/perfbase/migrate-perf-regress.sh",
        ],
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-migrate/MIGRATION.md"]),
    checks=[check("M.baseline-bundle.migrate", ".team/ledgers/acceptance/baseline-bundle-migrate.sh", 600)],
)

measure = Task(
    title="按原 measure 与 successor6 总任务书先安全生成非版本化 app/local.properties、过 SDK/资产取回/摘要/安装/envcheck，再做唯一 fresh 三夹具四段 A/B/A/B；每段n>=10、同批A2/B、nearest-rank p50/p95、全格B/A<=1.10。产物齐后只 report_result。",
    owner_role="measure",
    seat_wait_seconds=10800,
    resources=resources(
        core_wt,
        [
            ".team/nodes/baseline-bundle-measure/",
            ".team/private/baseline-candidates/",
            ".team/nodes/spec-sol/baseline-bundle/tmp/",
            "app/local.properties",
        ],
        [
            ".team/nodes/spec-sol/baseline-bundle/任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/measure-任务书.md",
            ".team/nodes/baseline-bundle-impl/",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-user/",
            ".team/nodes/baseline-bundle-migrate/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/nodes/input-full-auto/perf-design/CONTRACT.md",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.py",
            ".team/ledgers/acceptance/baseline-bundle-successor3-measure.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            "tools/perfbase/",
            "app/",
        ],
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-measure/MEASURE.md",
        ".team/nodes/baseline-bundle-measure/perf-ab-bundle.json",
        ".team/nodes/baseline-bundle-measure/PRE-MEASURE.json",
    ]),
    checks=[
        check("M.baseline-bundle.successor6-sdk-measure", ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh", 300),
        check("M.baseline-bundle.successor3-measure", ".team/ledgers/acceptance/baseline-bundle-successor3-measure.sh", 1800),
        check("M.baseline-bundle.measure-bypass", ".team/ledgers/acceptance/baseline-bundle-bypass-probes.sh", 300),
        check("M.baseline-bundle.successor6-measure-bypass", ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh", 300),
    ],
)

final = Task(
    title="按原 final 与 successor6 总任务书独立终审 exact/A2、canonical bundle/双归档、SDK/fixture四态、迁移、fresh B/A<=1.10 与真机‘秒开无空白’；任何 Gradle 前安全生成非版本化 local.properties。产物齐后只 report_result。",
    owner_role="final",
    seat_wait_seconds=3600,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-final/", ".team/nodes/spec-sol/baseline-bundle/tmp/", "app/local.properties"],
        [
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
            ".team/ledgers/acceptance/baseline-bundle-successor6-final.sh",
            ".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.py",
            ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor3/",
            "tools/perfbase/",
            "app/",
        ],
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-final/VERDICT.md",
        ".team/nodes/baseline-bundle-final/EVIDENCE-MATRIX.md",
        ".team/nodes/baseline-bundle-final/MUTATION.md",
    ]),
    checks=[
        check("M.baseline-bundle.successor6-final", ".team/ledgers/acceptance/baseline-bundle-successor6-final.sh", 3600),
        check("M.baseline-bundle.final-real-chain", ".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh", 300),
        check("M.baseline-bundle.successor6-final-bypass", ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh", 300),
    ],
)

ledger = Ledger(
    ledger_id="ledger.baseline-bundle.successor6.v1",
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
