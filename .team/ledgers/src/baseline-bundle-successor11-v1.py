from ledgerdsl import CommandSpec, WT, Check, EnvironmentFidelity, FallbackDef, Handoff, Ledger, Provenance, Resources, ScriptRef, Task, dep, role

provenance_revision = "3597b823204c7d25d5a77367bf2022347532e5d3"
core_wt = "wt-maple-core"
test_wt = "wt-s7-cedar"
probe_wt = "wt-s7-orbit"
archive_consume_wt = "wt-archive-probe"
consume_script = "/Volumes/nvme/Projects/远程Agent安卓/.team/ledgers/acceptance/baseline-bundle-successor11-consume.sh"

common_read = [
    ".team/nodes/spec-sol/baseline-bundle-successor11-final/任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor11/任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor11/BOOTSTRAP-RESULT.md",
    ".team/nodes/spec-sol/baseline-bundle-successor11-bootstrap-review/VERDICT.md",
    ".team/nodes/spec-sol/baseline-bundle-successor11-bootstrap-review/tests.log",
    ".team/nodes/baseline-bundle-successor11-wt-preflight/VERDICT.md",
    ".team/nodes/baseline-bundle-successor11-wt-preflight/COMMAND.md",
    ".team/ledgers/baseline-bundle-successor10-v1.json",
    ".team/nodes/_driver/baseline-bundle-successor10-v1.out",
    ".team/nodes/baseline-bundle-successor10-verify-diagnosis/VERDICT.md",
    ".team/ledgers/acceptance/baseline-bundle-successor11-consume.py",
    ".team/ledgers/acceptance/baseline-bundle-successor11-consume.sh",
    ".team/ledgers/acceptance/baseline-bundle-successor11-verify.py",
    ".team/ledgers/acceptance/baseline-bundle-successor11-verify.sh",
    ".team/ledgers/acceptance/baseline-bundle-successor11-verify-regression.sh",
    ".team/ledgers/acceptance/baseline-bundle-successor11-structure.sh",
    ".team/ledgers/acceptance/baseline-bundle-successor11-final.sh",
    ".team/ledgers/acceptance/fixtures/baseline-bundle-successor11/verify-contract.json",
    ".team/nodes/spec-sol/baseline-bundle-successor10-final/任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor7/final-任务书.md",
    ".team/nodes/input-full-auto/perf-design/CONTRACT.md",
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
    "continuity": role(".team/nodes/baseline-bundle-continuity/", agent="sampler-review-luna2", team="remote-agent-android", provider="codex"),
    "test": role(".team/nodes/baseline-bundle-successor7-test/", agent="sampler-test-luna2", team="remote-agent-android", provider="codex"),
    "probe": role(".team/nodes/baseline-bundle-successor7-probe/", agent="sampler-review-luna2", team="remote-agent-android", provider="codex"),
    "apparatus": role(".team/nodes/baseline-bundle-apparatus/", agent="sampler-dev-luna2", team="remote-agent-android", provider="codex"),
    "verify": role(".team/nodes/baseline-bundle-verify/", agent="sampler-review-luna2", team="remote-agent-android", provider="codex"),
    "user": role(".team/nodes/baseline-bundle-user/", agent="takeover-codex-luna", team="remote-agent-android", provider="codex"),
    "migrate": role(".team/nodes/baseline-bundle-migrate/", agent="sampler-dev-luna2", team="remote-agent-android", provider="codex"),
    "measure": role(".team/nodes/baseline-bundle-measure/", agent="sampler-dev-luna2", team="remote-agent-android", provider="codex"),
    "final": role(".team/nodes/baseline-bundle-final/", agent="sampler-review-luna2", team="remote-agent-android", provider="codex"),
    "advisor": role(".team/nodes/input-full-auto/advisor/", agent="input-advisor-luna", team="remote-agent-android", provider="codex"),
}

continuity_consume = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor11-final/continuity-consume-任务书.md 审计消费 successor10 r5 continuity succeeded。冻结 ebd0dc5c285ee65244824b99db6667a1bc569c83 / 3597b823204c7d25d5a77367bf2022347532e5d3 / 13c301fd086092b02e1cb8535d1eff38ffcf0173 / 7c1a856ba0043c87b1aeb9ed8ffac0fefe9ebfce / pair 7485102b26ed34eb828e94900902147d5e00e995；不 collect/重派/起设备。",
    owner_role="continuity",
    seat_wait_seconds=300,
    parallel="successor11-consume-wave",
    resources=resources(
        core_wt,
        [],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor11-final/continuity-consume-任务书.md",
            ".team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh",
            ".team/nodes/baseline-bundle-impl/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
        ],
    ),
    executor="command",
    command=CommandSpec(
        argv=["/bin/sh", consume_script, "continuity"],
        cwd="${worktree}",
        expected_exit_code=0,
        unjudgeable_exit_codes=[2],
        time_budget_seconds=600,
        artifacts=[],
    ),
)

apparatus_test_consume = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor11-final/apparatus-test-consume-任务书.md 审计消费 successor10 r5 apparatus-test succeeded 与原 RED 摘要，不派 agent。",
    owner_role="test",
    seat_wait_seconds=300,
    parallel="successor11-consume-wave",
    resources=resources(
        test_wt,
        [],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor11-final/apparatus-test-consume-任务书.md",
            ".team/ledgers/acceptance/baseline-bundle-successor7-test.sh",
            ".team/nodes/baseline-bundle-successor7-test/RED.md",
        ],
    ),
    executor="command",
    command=CommandSpec(
        argv=["/bin/sh", consume_script, "apparatus-test"],
        cwd="${worktree}",
        expected_exit_code=0,
        unjudgeable_exit_codes=[2],
        time_budget_seconds=600,
        artifacts=[".team/nodes/baseline-bundle-successor7-test/RED.md"],
    ),
)

apparatus_probe_consume = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor11-final/apparatus-probe-consume-任务书.md 审计消费 successor10 r5 apparatus-probe succeeded 与原 PROBE 摘要，不派 agent。",
    owner_role="probe",
    seat_wait_seconds=300,
    parallel="successor11-consume-wave",
    resources=resources(
        probe_wt,
        [],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor11-final/apparatus-probe-consume-任务书.md",
            ".team/ledgers/acceptance/baseline-bundle-successor7-probe.sh",
            ".team/nodes/baseline-bundle-successor7-probe/PROBE.md",
        ],
    ),
    executor="command",
    command=CommandSpec(
        argv=["/bin/sh", consume_script, "apparatus-probe"],
        cwd="${worktree}",
        expected_exit_code=0,
        unjudgeable_exit_codes=[2],
        time_budget_seconds=600,
        artifacts=[".team/nodes/baseline-bundle-successor7-probe/PROBE.md"],
    ),
)

apparatus_consume = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor11-final/apparatus-consume-任务书.md 审计消费 successor10 r5 apparatus succeeded 与原 wt-maple-core APPARATUS/AVD/permanent fixture；只读归档，禁止重跑 owned-emulator 或任何 live device 动作。",
    owner_role="apparatus",
    seat_wait_seconds=300,
    parallel="successor11-consume-wave",
    resources=resources(
        archive_consume_wt,
        [],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor11-final/apparatus-consume-任务书.md",
            ".team/ledgers/acceptance/baseline-bundle-successor7-impl-bypass.sh",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor7/",
        ],
    ),
    executor="command",
    command=CommandSpec(
        argv=["/bin/sh", consume_script, "apparatus"],
        cwd="${worktree}",
        expected_exit_code=0,
        unjudgeable_exit_codes=[2],
        time_budget_seconds=600,
        artifacts=[],
    ),
)

verify = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor11-final/verify-任务书.md fresh 重写五件 0600 regular non-symlink 证据；只绑 same-batch archived APPARATUS/producer/manifest + successor7 permanent fixture，current/live adb 不作门且不得操作。",
    owner_role="verify",
    seat_wait_seconds=7200,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-verify/"],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor11-final/verify-任务书.md",
            ".team/nodes/baseline-bundle-apparatus/",
            ".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor7/impl-bypass/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
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
        check("M.baseline-bundle.successor11-verify", ".team/ledgers/acceptance/baseline-bundle-successor11-verify.sh", 1800),
        check("M.baseline-bundle.successor11-regression", ".team/ledgers/acceptance/baseline-bundle-successor11-verify-regression.sh", 300),
        check("M.baseline-bundle.successor11-structure", ".team/ledgers/acceptance/baseline-bundle-successor11-structure.sh", 300),
    ],
    required=[
        "M.baseline-bundle.successor11-verify",
        "M.baseline-bundle.successor11-regression",
        "M.baseline-bundle.successor11-structure",
    ],
)

user_gate = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor7/final-user-gate-任务书.md 只结构化用户对同一 successor11 verified bundle 的真机蜂窝+广州中转‘秒开、没有空白’裁定；无用户原始确认必须不可判。",
    owner_role="user",
    seat_wait_seconds=10800,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-user/"],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor7/final-user-gate-任务书.md",
            ".team/nodes/baseline-bundle-apparatus/",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json",
            ".team/ledgers/acceptance/baseline-bundle-successor7-user-gate.sh",
        ],
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-user/USER-GATE.json",
        ".team/nodes/baseline-bundle-user/USER-GATE.md",
    ]),
    checks=[check("M.baseline-bundle.successor7-user", ".team/ledgers/acceptance/baseline-bundle-successor7-user-gate.sh", 300)],
)

migrate = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor7/final-migrate-任务书.md，仅在 fresh successor11 verify+用户 gate 全绿后以精确 PID/账本状态前置停旧 perf-regress，只用 ledgerdsl plan/apply paused，不清历史。",
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
            ".team/nodes/spec-sol/baseline-bundle-successor7/final-migrate-任务书.md",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-user/",
            ".team/ledgers/acceptance/baseline-bundle-successor7-migrate.sh",
            "tools/perfbase/migrate-perf-regress.sh",
        ],
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/baseline-bundle-migrate/MIGRATION.md"]),
    checks=[check("M.baseline-bundle.successor7-migrate", ".team/ledgers/acceptance/baseline-bundle-successor7-migrate.sh", 600)],
)

measure = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor7/final-measure-任务书.md 先 envcheck/SDK/资产取回门，再做唯一 fresh 三夹具 A/B/A/B；每段 n>=10，nearest-rank p50/p95，同批 A2/B，全格 B/A<=1.10。",
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
            ".team/nodes/spec-sol/baseline-bundle-successor7/final-measure-任务书.md",
            ".team/nodes/baseline-bundle-impl/",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-user/",
            ".team/nodes/baseline-bundle-migrate/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/ledgers/acceptance/baseline-bundle-successor7-measure.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor3-measure.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor5-sdk.sh",
            "tools/perfbase/",
            "app/",
        ],
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-measure/MEASURE.md",
        ".team/nodes/baseline-bundle-measure/perf-ab-bundle.json",
        ".team/nodes/baseline-bundle-measure/PRE-MEASURE.json",
    ]),
    checks=[check("M.baseline-bundle.successor7-measure", ".team/ledgers/acceptance/baseline-bundle-successor7-measure.sh", 3600)],
)

final = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor11-final/final-任务书.md 独立终审四 consume、fresh successor11 verify、真机 gate、迁移、fresh 1.10、permanent fixture 与 real-chain；无 legacy verify，破坏齿还原后才可 pass。",
    owner_role="final",
    seat_wait_seconds=3600,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-final/", ".team/nodes/spec-sol/baseline-bundle/tmp/", "app/local.properties"],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor11-final/final-任务书.md",
            ".team/nodes/baseline-bundle-apparatus/",
            ".team/nodes/baseline-bundle-impl/",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-user/",
            ".team/nodes/baseline-bundle-migrate/",
            ".team/nodes/baseline-bundle-measure/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/ledgers/acceptance/baseline-bundle-successor11-final.sh",
            ".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh",
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
        check("M.baseline-bundle.successor11-final", ".team/ledgers/acceptance/baseline-bundle-successor11-final.sh", 3600),
        check("M.baseline-bundle.successor7-real-chain", ".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh", 300),
    ],
)

ledger = Ledger(
    ledger_id="ledger.baseline-bundle.successor11.v1",
    roles=roles,
    tasks={
        "t.baseline-bundle.continuity-consume": continuity_consume,
        "t.baseline-bundle.apparatus-test-consume": apparatus_test_consume,
        "t.baseline-bundle.apparatus-probe-consume": apparatus_probe_consume,
        "t.baseline-bundle.apparatus-consume": apparatus_consume,
        "t.baseline-bundle.verify": verify,
        "t.baseline-bundle.user-gate": user_gate,
        "t.baseline-bundle.migrate": migrate,
        "t.baseline-bundle.measure": measure,
        "t.baseline-bundle.final": final,
    },
    dependencies=[
        dep("t.baseline-bundle.continuity-consume", "t.baseline-bundle.verify"),
        dep("t.baseline-bundle.apparatus-test-consume", "t.baseline-bundle.verify"),
        dep("t.baseline-bundle.apparatus-probe-consume", "t.baseline-bundle.verify"),
        dep("t.baseline-bundle.apparatus-consume", "t.baseline-bundle.verify"),
        dep("t.baseline-bundle.verify", "t.baseline-bundle.user-gate"),
        dep("t.baseline-bundle.user-gate", "t.baseline-bundle.migrate"),
        dep("t.baseline-bundle.migrate", "t.baseline-bundle.measure"),
        dep("t.baseline-bundle.measure", "t.baseline-bundle.final"),
    ],
    parallelism=[{"group": "successor11-consume-wave", "max_concurrency": 4, "failure_policy": "halt"}],
    fallback={
        "F.escalate": FallbackDef(
            role="advisor",
            triggers=["blocked_on_unknown", "provider_unavailable", "result_deadline_elapsed", "delivery_uncertain"],
        ),
    },
    repo_root="/Volumes/nvme/Projects/远程Agent安卓",
)
print(ledger.compile(), end="")
