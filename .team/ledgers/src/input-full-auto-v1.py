import os
from ledgerdsl import WT, Check, EnvironmentFidelity, FallbackDef, Handoff, Ledger, Provenance, Resources, ScriptRef, Task, dep, role

基线 = dict(
    environment_fidelity=EnvironmentFidelity(runs_real_cli=True, injects_latency=True, carries_long_history=True),
    provenance=Provenance(identity="git", revision="d65ba733f6aa76b5e624a721b5b394f282040ecf"),
)

roles = {
    "perf_design": role(".team/nodes/input-full-auto/perf-design/", agent="pi-codex-bridge", team="remote-agent-android", provider="codex"),
    "perf_measure": role(".team/nodes/input-full-auto/perf-measure/", agent="input-test-luna", team="remote-agent-android", provider="codex"),
    "perf_verify": role(".team/nodes/input-full-auto/perf-verify/", agent="input-review-luna", team="remote-agent-android", provider="codex"),
    "advisor": role(".team/nodes/input-full-auto/advisor/", agent="input-advisor-luna", team="remote-agent-android", provider="codex"),
}

t_design = Task(
    title="按任务书 `.team/nodes/input-full-auto/perf-design/任务书.md` 冻结可执行性能契约：以用户裁定的稳定 tag+参考 md5 为行为基线，废弃 null JSON 的可执行权威；只落 CONTRACT.md，不能唯一证明安全采样入口就如实 blocked，产物齐后 report_result。",
    owner_role="perf_design",
    seat_wait_seconds=1800,
    resources=Resources(
        worktree_id="wt-input-perf",
        write_paths=[".team/nodes/input-full-auto/perf-design/"],
        read_paths=[".team/nodes/input-full-auto/perf-design/任务书.md", ".team/nodes/feat-remote-scroll-mouse-wheel/perf-attribution-20260823.md", "CLAUDE.md", "tools/perfbase/"],
        **基线,
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/input-full-auto/perf-design/CONTRACT.md"]),
    checks=[Check(id="M.perf.design", script=ScriptRef(path=".team/ledgers/acceptance/input-perf-design.sh"), cwd=WT, budget=120)],
)

t_measure = Task(
    title="按任务书 `.team/nodes/input-full-auto/perf-measure/任务书.md` 执行稳定 tag A 与当前候选 B 的同批 A/B/A/B 新鲜测量；先 envcheck，三夹具×四段各包 n>=10；不得改产品/判据/历史基线，产物齐后 report_result。",
    owner_role="perf_measure",
    seat_wait_seconds=10800,
    resources=Resources(
        worktree_id="wt-input-perf",
        write_paths=[".team/nodes/input-full-auto/perf-measure/"],
        read_paths=[".team/nodes/input-full-auto/perf-measure/任务书.md", ".team/nodes/input-full-auto/perf-design/", "CLAUDE.md", "tools/perfbase/", ".team/perf/"],
        **基线,
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/input-full-auto/perf-measure/perf-ab.json", ".team/nodes/input-full-auto/perf-measure/MEASURE.md"]),
    checks=[Check(id="M.perf.measure", script=ScriptRef(path=".team/ledgers/acceptance/input-perf-measure.sh"), cwd=WT, budget=300)],
)

t_verify = Task(
    title="按任务书 `.team/nodes/input-full-auto/perf-verify/任务书.md` 零上下文独立复算三夹具×四段，并做越过 1.10 的破坏齿；不改产品/测量产物/判据，只有证据完整才 verdict: pass，产物齐后 report_result。",
    owner_role="perf_verify",
    seat_wait_seconds=3600,
    resources=Resources(
        worktree_id="wt-input-perf",
        write_paths=[".team/nodes/input-full-auto/perf-verify/"],
        read_paths=[".team/nodes/input-full-auto/perf-verify/任务书.md", ".team/nodes/input-full-auto/perf-design/", ".team/nodes/input-full-auto/perf-measure/", ".team/ledgers/acceptance/input-perf-measure.sh", "CLAUDE.md"],
        **基线,
    ),
    handoff=Handoff(required_artifacts=[".team/nodes/input-full-auto/perf-verify/VERDICT.md"]),
    checks=[Check(id="M.perf.verify", script=ScriptRef(path=".team/ledgers/acceptance/input-perf-verify.sh"), cwd=WT, budget=300)],
)

ledger = Ledger(
    ledger_id="ledger.input-full-auto.v1",
    roles=roles,
    tasks={"t.perf.design": t_design, "t.perf.measure": t_measure, "t.perf.verify": t_verify},
    dependencies=[dep("t.perf.design", "t.perf.measure"), dep("t.perf.measure", "t.perf.verify")],
    fallback={"F.escalate": FallbackDef(role="advisor", triggers=["blocked_on_unknown", "provider_unavailable", "result_deadline_elapsed", "delivery_uncertain"])},
    repo_root=os.getcwd(),
)

print(ledger.compile(), end="")
