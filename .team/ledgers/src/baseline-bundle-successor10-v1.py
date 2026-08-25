from ledgerdsl import CommandSpec, WT, Check, EnvironmentFidelity, FallbackDef, Handoff, Ledger, Provenance, Resources, ScriptRef, Task, dep, role

provenance_revision = "ad7468f747d421305279632f0db9cbc227b08cd4"
core_wt = "wt-maple-core"
test_wt = "wt-s7-cedar"
probe_wt = "wt-s7-orbit"

common_read = [
    ".team/nodes/spec-sol/baseline-bundle-successor10-final/任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor10/任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor10/BOOTSTRAP-RESULT.md",
    ".team/nodes/spec-sol/baseline-bundle-successor10-bootstrap-review/VERDICT.md",
    ".team/nodes/spec-sol/baseline-bundle-successor10-bootstrap-review/tests.log",
    ".team/nodes/baseline-bundle-successor10-wt-preflight/VERDICT.md",
    ".team/nodes/baseline-bundle-successor10-wt-preflight/COMMAND.md",
    ".team/nodes/baseline-bundle-successor9-apparatus-diagnosis/VERDICT.md",
    ".team/ledgers/baseline-bundle-successor9-v1.json",
    ".team/nodes/_driver/baseline-bundle-successor9-v1.out",
    ".team/ledgers/acceptance/baseline-bundle-successor10-avd.py",
    ".team/ledgers/acceptance/baseline-bundle-successor10-avd-regression.sh",
    ".team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh",
    ".team/nodes/spec-sol/baseline-bundle-successor9-final/任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor9/任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor9/BOOTSTRAP-RESULT.md",
    ".team/nodes/spec-sol/baseline-bundle-successor9-bootstrap-review/VERDICT.md",
    ".team/nodes/spec-sol/baseline-bundle-successor9-bootstrap-review/tests.log",
    ".team/nodes/baseline-bundle-successor9-wt-preflight/VERDICT.md",
    ".team/nodes/baseline-bundle-successor9-wt-preflight/COMMAND.md",
    ".team/nodes/baseline-bundle-successor8-apparatus-diagnosis/VERDICT.md",
    ".team/nodes/baseline-bundle-successor8-apparatus-diagnosis/INSTALLED-IMAGES.md",
    ".team/ledgers/baseline-bundle-successor8-v1.json",
    ".team/nodes/_driver/baseline-bundle-successor8-v1.out",
    ".team/ledgers/acceptance/baseline-bundle-successor9-owned-emulator.sh",
    ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-selector.sh",
    ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-selector.py",
    ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-regression.sh",
    ".team/nodes/spec-sol/baseline-bundle-successor8/任务书.md",
    ".team/nodes/baseline-bundle-successor7-frontier-recovery/VERDICT.md",
    ".team/nodes/_driver/baseline-bundle-successor7-v1.out",
    ".team/nodes/baseline-bundle-successor7-command-pair/VERDICT.md",
    ".team/nodes/baseline-bundle-successor7-final-review/VERDICT.md",
    ".team/nodes/baseline-bundle-successor7-final-review/tests.log",
    ".team/ledgers/baseline-bundle-successor7-v1.json",
    ".team/nodes/spec-sol/baseline-bundle-successor7/final-任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor7/任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor7/BOOTSTRAP-RESULT.md",
    ".team/nodes/baseline-bundle-successor6-verify-diagnosis/VERDICT.md",
    ".team/ledgers/baseline-bundle-successor6-v1.json",
    ".team/nodes/spec-sol/baseline-bundle-successor6/final-任务书.md",
    ".team/nodes/spec-sol/baseline-bundle-successor6/任务书.md",
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
    title="审计消费 successor9 r4 continuity succeeded：在原 wt-maple-core 精确重跑现有 successor7 continuity required；绑定efed31310/918b4c06f/9ea73dff8/ad7468f74、successor9 r4 ledger+driver 与 command-pair=7485102b26ed34eb828e94900902147d5e00e995，不 collect/重派/起设备。",
    owner_role="continuity",
    seat_wait_seconds=300,
    parallel="successor10-consume-wave",
    resources=resources(
        core_wt,
        [],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor7/final-continuity-任务书.md",
            ".team/nodes/baseline-bundle-successor7-frontier-recovery/VERDICT.md",
            ".team/nodes/_driver/baseline-bundle-successor7-v1.out",
            ".team/ledgers/baseline-bundle-successor7-v1.json",
            ".team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh",
            ".team/ledgers/baseline-bundle-successor6-v1.json",
            ".team/nodes/baseline-bundle-impl/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
        ],
    ),
    executor="command",
    command=CommandSpec(
        argv=["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh"],
        cwd="${worktree}",
        expected_exit_code=0,
        unjudgeable_exit_codes=[2],
        time_budget_seconds=300,
        artifacts=[],
    ),
)

apparatus_test_consume = Task(
    title="审计消费 successor9 r4 apparatus-test succeeded：在原 wt-s7-cedar 精确重跑 successor7-test required，只读消费 SHA=04cdbd661548a4b3261c88d491cf80c48f98dcbe3c080e710fb7d12bbe6c105a 的 RED.md；绑定efed31310 successor9 r4 ledger+driver 与 command-pair=7485102b26ed34eb828e94900902147d5e00e995，不发 agent。",
    owner_role="test",
    seat_wait_seconds=3600,
    parallel="successor10-consume-wave",
    resources=resources(
        test_wt,
        [],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor7/test-任务书.md",
            ".team/ledgers/baseline-bundle-successor7-v1.json",
            ".team/ledgers/acceptance/baseline-bundle-successor7-structure.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor7-test.sh",
            ".team/nodes/baseline-bundle-successor7-frontier-recovery/VERDICT.md",
            ".team/nodes/_driver/baseline-bundle-successor7-v1.out",
        ],
    ),
    executor="command",
    command=CommandSpec(
        argv=["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor7-test.sh"],
        cwd="${worktree}",
        expected_exit_code=0,
        unjudgeable_exit_codes=[2],
        time_budget_seconds=600,
        artifacts=[".team/nodes/baseline-bundle-successor7-test/RED.md"],
    ),
)

apparatus_probe_consume = Task(
    title="审计消费 successor9 r4 apparatus-probe succeeded：在原 wt-s7-orbit 精确重跑 successor7-probe required，只读消费 SHA=88868a1a1979d3f1504e5efd6876dc5ca8ed5cc6b45a2eb6f6dd23b8e5176cf7 的 PROBE.md；绑定efed31310 successor9 r4 ledger+driver 与 command-pair=7485102b26ed34eb828e94900902147d5e00e995，不发 agent。",
    owner_role="probe",
    seat_wait_seconds=3600,
    parallel="successor10-consume-wave",
    resources=resources(
        probe_wt,
        [],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor7/probe-任务书.md",
            ".team/ledgers/baseline-bundle-successor7-v1.json",
            ".team/ledgers/acceptance/baseline-bundle-successor7-structure.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor7-probe.sh",
            ".team/nodes/baseline-bundle-successor7-frontier-recovery/VERDICT.md",
            ".team/nodes/_driver/baseline-bundle-successor7-v1.out",
        ],
    ),
    executor="command",
    command=CommandSpec(
        argv=["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor7-probe.sh"],
        cwd="${worktree}",
        expected_exit_code=0,
        unjudgeable_exit_codes=[2],
        time_budget_seconds=600,
        artifacts=[".team/nodes/baseline-bundle-successor7-probe/PROBE.md"],
    ),
)

apparatus = Task(
    title="精确 successor10 apparatus command：cwd=retained wt-maple-core，argv=/bin/sh .team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh；三 consume 成功后才运行。先 strict envcheck，再唯一 SDK selector，再 fail-closed 无输入 AVD create，最后进入 successor7 ownership 后链；产出0600 AVD-CREATE.json+APPARATUS.json，完整保留有界 PID+serial ownership/只清 owned qemu/恢复闸。",
    owner_role="apparatus",
    seat_wait_seconds=7200,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-apparatus/", ".team/nodes/baseline-bundle-sdk-teeth/", "app/local.properties"],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor7/apparatus-任务书.md",
            ".team/nodes/baseline-bundle-successor7-test/",
            ".team/nodes/baseline-bundle-successor7-probe/",
            ".team/ledgers/acceptance/baseline-bundle-successor7-owned-emulator.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor7-apparatus.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor7-apparatus.py",
            ".team/ledgers/acceptance/baseline-bundle-successor7-impl-bypass.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh",
            ".team/ledgers/acceptance/fixtures/baseline-bundle-successor7/",
            ".team/nodes/baseline-bundle-impl/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            "tools/perfbase/",
            "app/",
        ],
    ),
    executor="command",
    command=CommandSpec(
        argv=["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh"],
        cwd="${worktree}",
        expected_exit_code=0,
        unjudgeable_exit_codes=[2],
        time_budget_seconds=7200,
        artifacts=[
            ".team/nodes/baseline-bundle-apparatus/AVD-CREATE.json",
            ".team/nodes/baseline-bundle-apparatus/APPARATUS.json",
        ],
    ),
    handoff=Handoff(required_artifacts=[
        ".team/nodes/baseline-bundle-apparatus/AVD-CREATE.json",
        ".team/nodes/baseline-bundle-apparatus/APPARATUS.json",
    ]),
    checks=[
        check("M.baseline-bundle.successor10-avd", ".team/ledgers/acceptance/baseline-bundle-successor10-avd-regression.sh", 300),
        check("M.baseline-bundle.successor9-sdk-selector", ".team/ledgers/acceptance/baseline-bundle-successor9-sdk-regression.sh", 300),
        check("M.baseline-bundle.successor7-apparatus", ".team/ledgers/acceptance/baseline-bundle-successor7-apparatus.sh", 600),
        check("M.baseline-bundle.successor7-fixture", ".team/ledgers/acceptance/baseline-bundle-successor7-impl-bypass.sh", 300),
        check("M.baseline-bundle.successor7-continuity", ".team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh", 300),
    ],
    required=[
        "M.baseline-bundle.successor10-avd",
        "M.baseline-bundle.successor9-sdk-selector",
        "M.baseline-bundle.successor7-apparatus",
        "M.baseline-bundle.successor7-fixture",
        "M.baseline-bundle.successor7-continuity",
    ],
)

verify = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor7/verify-任务书.md 在 retained wt-maple-core fresh 独立重做 verify，不重启 emulator；必须绑定同批 APPARATUS bytes/bundle/cleanup 与 permanent fixture，只 report_result 一次。",
    owner_role="verify",
    seat_wait_seconds=7200,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-verify/", ".team/nodes/spec-sol/baseline-bundle/tmp/", "app/local.properties"],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor7/verify-任务书.md",
            ".team/nodes/baseline-bundle-apparatus/",
            ".team/nodes/baseline-bundle-impl/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/ledgers/acceptance/baseline-bundle-successor7-verify.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor7-apparatus.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor7-impl-bypass.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor6-verify.sh",
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
    checks=[check("M.baseline-bundle.successor7-verify", ".team/ledgers/acceptance/baseline-bundle-successor7-verify.sh", 3600)],
)

user_gate = Task(
    title="按 .team/nodes/spec-sol/baseline-bundle-successor7/final-user-gate-任务书.md 只结构化用户对同一 verified bundle 的真机蜂窝+广州中转‘秒开、没有空白’裁定；无用户原始确认必须不可判。",
    owner_role="user",
    seat_wait_seconds=10800,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-user/"],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor7/final-user-gate-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/user-gate-任务书.md",
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
    title="按 .team/nodes/spec-sol/baseline-bundle-successor7/final-migrate-任务书.md，仅在 fresh verify+用户 gate 全绿后以精确 PID/账本状态前置停旧 perf-regress，只用 ledgerdsl plan/apply paused，不清历史。",
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
            ".team/nodes/spec-sol/baseline-bundle/migrate-任务书.md",
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
            ".team/nodes/spec-sol/baseline-bundle/measure-任务书.md",
            ".team/nodes/baseline-bundle-impl/",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-user/",
            ".team/nodes/baseline-bundle-migrate/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/ledgers/acceptance/baseline-bundle-successor7-measure.sh",
            ".team/ledgers/acceptance/baseline-bundle-successor7-impl-bypass.sh",
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
    title="按 .team/nodes/spec-sol/baseline-bundle-successor7/final-final-任务书.md 独立终审 continuity、apparatus、fresh verify、真机 gate、迁移、fresh 1.10 与 permanent fixture；破坏齿还原后才可 pass。",
    owner_role="final",
    seat_wait_seconds=3600,
    resources=resources(
        core_wt,
        [".team/nodes/baseline-bundle-final/", ".team/nodes/spec-sol/baseline-bundle/tmp/", "app/local.properties"],
        [
            ".team/nodes/spec-sol/baseline-bundle-successor7/final-final-任务书.md",
            ".team/nodes/spec-sol/baseline-bundle/final-任务书.md",
            ".team/nodes/baseline-bundle-apparatus/",
            ".team/nodes/baseline-bundle-impl/",
            ".team/nodes/baseline-bundle-verify/",
            ".team/nodes/baseline-bundle-user/",
            ".team/nodes/baseline-bundle-migrate/",
            ".team/nodes/baseline-bundle-measure/",
            ".team/private/baseline-vault/",
            ".team/private/baseline-backup/",
            ".team/ledgers/acceptance/baseline-bundle-successor7-final.sh",
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
        check("M.baseline-bundle.successor7-final", ".team/ledgers/acceptance/baseline-bundle-successor7-final.sh", 3600),
        check("M.baseline-bundle.successor7-real-chain", ".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh", 300),
    ],
)

ledger = Ledger(
    ledger_id="ledger.baseline-bundle.successor10.v1",
    roles=roles,
    tasks={
        "t.baseline-bundle.continuity-consume": continuity_consume,
        "t.baseline-bundle.apparatus-test-consume": apparatus_test_consume,
        "t.baseline-bundle.apparatus-probe-consume": apparatus_probe_consume,
        "t.baseline-bundle.apparatus": apparatus,
        "t.baseline-bundle.verify": verify,
        "t.baseline-bundle.user-gate": user_gate,
        "t.baseline-bundle.migrate": migrate,
        "t.baseline-bundle.measure": measure,
        "t.baseline-bundle.final": final,
    },
    dependencies=[
        dep("t.baseline-bundle.continuity-consume", "t.baseline-bundle.apparatus"),
        dep("t.baseline-bundle.apparatus-test-consume", "t.baseline-bundle.apparatus"),
        dep("t.baseline-bundle.apparatus-probe-consume", "t.baseline-bundle.apparatus"),
        dep("t.baseline-bundle.apparatus", "t.baseline-bundle.verify"),
        dep("t.baseline-bundle.verify", "t.baseline-bundle.user-gate"),
        dep("t.baseline-bundle.user-gate", "t.baseline-bundle.migrate"),
        dep("t.baseline-bundle.migrate", "t.baseline-bundle.measure"),
        dep("t.baseline-bundle.measure", "t.baseline-bundle.final"),
    ],
    parallelism=[{"group": "successor10-consume-wave", "max_concurrency": 3, "failure_policy": "halt"}],
    fallback={
        "F.escalate": FallbackDef(
            role="advisor",
            triggers=["blocked_on_unknown", "provider_unavailable", "result_deadline_elapsed", "delivery_uncertain"],
        ),
    },
    repo_root="/Volumes/nvme/Projects/远程Agent安卓",
)
print(ledger.compile(), end="")
