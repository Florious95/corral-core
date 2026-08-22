# 账本「coreapp-v1」：core 变成被引用的发布产物，app 壳搬进 corral-app。
#
# 链形状（全串行，⛔ 无并行施工格）：
#   t.path → t.pub → t.capp → t.perf → t.rv --pass--> t.close
#                                        └--rework--> t.capp（上限 2，穷尽升报 t.esc）
#                                        └--inconclusive--> t.esc
#
# 🔴 上一条链（perfbase-v1）的三条形状教训，本账本逐条改进：
#   ① 两棵 worktree 改同一批文件 ⇒ land 时 add/add 冲突（PR#6/#10 park）
#      ⇒ 本链**所有施工格共用 wt-ca 一棵树**，靠 worktree_id 互斥天然串行。
#   ② 下游分支基于上游分支而非等它 land ⇒ content 冲突
#      ⇒ 共用一棵树后不存在这个问题。
#   ③ 某格的树建在上游 land 之前 ⇒ 基点错、包里没仪表、取数 0 行
#      ⇒ 共用一棵树后，后一格直接看到前一格的成果，不与 land 竞速。
import sys

sys.path.insert(0, "/Users/alauda/.claude/skills/ledger-orchestration/reference/ledgerdsl-0.1.1")

from ledgerdsl import (  # noqa: E402
    WT, Assemble, Check, EnvironmentFidelity, FallbackDef, Ledger, Provenance,
    Resources, ScriptRef, StatusDef, Task, Transition, dep, role,
)

REPO_ROOT = "/Volumes/nvme/Projects/远程Agent安卓"
BASE = dict(
    environment_fidelity=EnvironmentFidelity(runs_real_cli=True),
    provenance=Provenance(identity="git", revision="baseline-20260822-release"),
)
书 = ".team/tasks/coreapp/任务书.md"
判据 = "tools/perfbase"
WTID = "wt-ca"  # 🔴 所有施工格共用这一棵树

roles = {
    "r.impl": role(".team/nodes/ca-impl/", agent="pb-impl", team="grok-l2", policy="resident_reuse"),
    "r.emu": role(".team/nodes/ca-emu/", agent="pb-emu", team="remote-agent-android", policy="resident_reuse"),
    "r.rv": role(".team/nodes/ca-rv/", agent="pb-rv1", team="remote-agent-android", policy="resident_reuse"),
    "r.rv2": role(".team/nodes/ca-close/", agent="pb-rv2", team="remote-agent-android", policy="resident_reuse"),
    # 升报席位：⛔ 不拥有任何任务（门④硬要求）
    "r.advisor": role(".team/nodes/advisor/", policy="resident_reuse"),
}

DONE = [StatusDef(name="done", description="本格交付物全落盘，交下游独立判",
                  artifact={"report": ".team/nodes/<本格>/说明.md"})]
裁定 = [
    StatusDef(name="pass", description="四条判据自己重跑均过且核过未被改弱", artifact={"report": "裁定.md"}),
    StatusDef(name="rework", description="不成立，带逐条理由与代码原文证据打回", artifact={"report": "裁定.md"}),
    StatusDef(name="inconclusive", description="判不出（合法终态），写清判不出什么", artifact={"report": "裁定.md"}),
]


def 施工(tid_title, wpaths, check_id, script, budget=2400, statuses=None, seat="r.impl", extra_read=()):
    return Task(
        title=tid_title,
        owner_role=seat, seat_wait_seconds=5400,
        resources=Resources(
            worktree_id=WTID,
            write_paths=list(wpaths),
            read_paths=[书, "docs/基线-20260822-release.md", "CLAUDE.md",
                        判据 + "/" + script] + list(extra_read),
            **BASE),
        checks=[Check(id=check_id, script=ScriptRef(path=判据 + "/" + script),
                      cwd=WT, budget=budget)],
        statuses=statuses,
    )


t_path = 施工(
    "格1·钉住性能关键路径：产出 docs/性能关键路径.md，把八个事件逐条列出（事件｜在核还是在壳｜"
    "真实存在的文件路径｜慢了会表现成什么）+ 一节「改动纪律」。判据会逐个 test -e 你写的路径，"
    "写错即红；必须标明「核」或「壳」。⛔ 不许只写模块名糊弄、⛔ 不许把不在链路上的文件塞进来充数"
    "（那会让守门判据天天误报）。细则读 " + 书,
    ["docs/", ".team/nodes/ca-impl/"], "c.path", "judge-perfpath.sh", budget=600)

t_pub = 施工(
    "格2·三核发布成 maven 产物：给 core-protocol/core-terminal/core-conn 加 maven-publish，"
    "坐标 group=dev.agentmirror.core、artifact=模块名、version=20260822.0（对应基线 tag），"
    "产物发到本地目录 .team/staging/maven-repo/（标准 maven 布局）。"
    "三核必须仍是纯 JVM：⛔ 不许上 Android 插件、⛔ 源码不许 import android./androidx.；三核单测必须绿。"
    "⛔ 你不许 push——推 maven 分支是 leader 的动作。细则读 " + 书,
    ["app/", ".team/staging/", ".team/nodes/ca-pub/"], "c.pub", "judge-pub.sh")

t_capp = 施工(
    "格3·corral-app 只引用产物：把 .team/staging/corral-app 从「源码 composite」改造成「只引用发布产物」。"
    "⛔ settings.gradle.kts 里不许再有 includeBuild；⛔ 工程内不许有核源码目录。"
    "仓库声明两个且顺序固定：① raw.githubusercontent.com/Florious95/corral-core/maven/ "
    "② 本仓 .team/staging/maven-repo（本地兜底，maven 分支推上去之前也能构建）。"
    "依赖钉死 dev.agentmirror.core:<模块>:20260822.0。assembleRelease 必须绿并产出 APK，"
    "且 dex 里 PerfTrace/addBinaryListener/debug.agentmirror.perftrace 都还在"
    "（量具没了就没法自证不回退）。细则读 " + 书,
    [".team/staging/", ".team/nodes/ca-app/"], "c.capp", "judge-capp.sh",
    statuses=DONE, extra_read=[".team/nodes/ca-pub/"])

t_perf = 施工(
    "格4·用引用式构建的 APK 复测性能门：被测物 = 格3 装出的 release APK（记 md5 与来源）。"
    "流程与 .team/tasks/perfbase/任务书-02-基线.md **完全相同**：模拟器 agentmirror_test_b、"
    "三夹具各 ≥10 次冷点开、**只从 adb logcat -s PerfTrace 取数**（⛔ 不识图/不取帧/不取帧间隔）、"
    "**极端值不剔除**（另列 outliers）、**每批记 load 读数**。"
    "落 .team/perf/recheck-<日期>-capp.json（形状同基线文件）。逐夹具逐段比 p50/p95，超基线 +10% 即红。"
    "⛔ 不许换夹具/换次数/换取数方式来让它变绿；环境不具备或数据不齐 ⇒ 如实报不可判（合法终态）。"
    "你的前任四轮说明与现成脚本在 .team/nodes/pb-emu/，⛔ 不要重新摸索。细则读 " + 书,
    [".team/perf/", ".team/nodes/ca-emu/"], "c.perf", "judge-perf-nonregress.sh",
    budget=1200, seat="r.emu", statuses=DONE,
    extra_read=[".team/tasks/perfbase/任务书-02-基线.md", ".team/nodes/pb-emu/", ".team/perf/"])

t_rv = Task(
    title=("格5·异源终审：你与施工席不同席位、零上下文，⛔ 不采信自报。**自己重跑**四条判据"
           "（judge-perfpath / judge-pub / judge-capp / judge-perf-nonregress）并贴退出码原文。"
           "另外必须核：① git diff 看 tools/perfbase/ 有没有被动过——动了直接 rework；"
           "② corral-app 里确实没有核源码（自己 find，别信文档）；"
           "③ 复测的数真从 .team/perf/raw*/ 的 PerfTrace 原文来（抽查 ≥3 份对上统计值）、"
           "有没有剔极端值、load 读数在不在；④ 性能关键路径清单里的文件逐个存在且没塞无关文件。"
           "裁定书落 .team/nodes/ca-rv/裁定.md，恰好一行 status=pass|rework|inconclusive。"
           "判不出写 inconclusive，那是合法终态，⛔ 不许把没核到的写成 pass。细则读 " + 书),
    owner_role="r.rv", seat_wait_seconds=3600,
    resources=Resources(
        worktree_id="wt-ca-rv",
        write_paths=[".team/nodes/ca-rv/"],
        read_paths=[书, "app/", ".team/staging/", ".team/perf/", 判据 + "/", "docs/"],
        **BASE),
    checks=[Check(id="c.rv", script=ScriptRef(path=判据 + "/judge-verdict.sh",
                                              args=[".team/nodes/ca-rv/裁定.md"]),
                  cwd=WT, budget=300)],
    statuses=裁定,
)

t_close = Task(
    title=("收账：把本轮现状汇成一页给用户——三分口径「已验证完成 / 自报未核 / 未做」，各附证据路径。"
           "必须单列一节「留给 leader 与用户的缺口」，至少含：① 推 maven 分支与推 corral-app 远端仓"
           "（席位禁 push，是 leader 的动作）；② **用户真机复验**引用式构建的包「秒开无空白」"
           "——模拟器绿不能替代金标准；③ 本仓 app/app 壳还没删（删壳是真机复验通过之后的下一步，"
           "不在本链范围）。⛔ 不许把自报当已完成。落 .team/artifacts/coreapp-收账-<日期>.md"),
    owner_role="r.rv2", seat_wait_seconds=1800,
    resources=Resources(worktree_id="wt-ca-close", write_paths=[".team/artifacts/"], **BASE),
    checks=[Check(id="c.close", script=ScriptRef(path=判据 + "/judge-doc.sh",
                                                 args=[".team/artifacts/coreapp-收账-*.md"]),
                  cwd=WT, budget=120)],
)

t_esc = Task(
    title=("升报：返修穷尽或判者判不出时，把「卡在哪、试了什么、缺什么、需要 leader 决定什么」"
           "写清升报，⛔ 不许静默收工、⛔ 不许把判不出改写成通过。"
           "落 .team/escalations/coreapp-esc-<日期>.md"),
    owner_role="r.rv2", seat_wait_seconds=1800,
    resources=Resources(worktree_id="wt-ca-esc", write_paths=[".team/escalations/"], **BASE),
    checks=[Check(id="c.esc", script=ScriptRef(path=判据 + "/judge-doc.sh",
                                               args=[".team/escalations/coreapp-esc-*.md"]),
                  cwd=WT, budget=120)],
)

tasks = {
    "t.path": t_path, "t.pub": t_pub, "t.capp": t_capp, "t.perf": t_perf,
    "t.rv": t_rv, "t.close": t_close, "t.esc": t_esc,
}

# 全串行依赖；t.close 与 t.esc 只经转移边可达
dependencies = [
    dep("t.path", "t.pub"),
    dep("t.pub", "t.capp"),
    dep("t.capp", "t.perf"),
    dep("t.perf", "t.rv"),
]

transitions = [
    Transition(frm="t.capp", to="t.perf", on_status=["done"],
               assemble=Assemble(include_upstream_case=False)),
    Transition(frm="t.perf", to="t.rv", on_status=["done"],
               assemble=Assemble(include_upstream_case=False)),
    Transition(frm="t.rv", to="t.close", on_status=["pass"],
               assemble=Assemble(include_upstream_case=False)),
    # 🔴 形状教训（2026-08-22 实撞）：返修边原本指向 t.capp，指错了格。
    # 判者第一轮判 rework，理由全在**测量**（复测覆盖基线 raw、跨包比历史地板），
    # 而 corral-app 改造本身判者逐条核过是干净的。返修必须落在**出问题的那格**——
    # 链尾判者打回时，默认收件人应是它上一格（t.perf），不是链首。
    Transition(frm="t.rv", to="t.perf", on_status=["rework"],
               assemble=Assemble(include_upstream_case=True),
               max_rounds=2, on_exhausted="t.esc"),
    Transition(frm="t.rv", to="t.esc", on_status=["inconclusive"],
               assemble=Assemble(include_upstream_case=True)),
]

ledger = Ledger(
    ledger_id="ledger.coreapp.v1",
    roles=roles,
    tasks=tasks,
    dependencies=dependencies,
    transitions=transitions,
    parallelism=[],
    fallback={"f.escalate": FallbackDef(
        role="r.advisor",
        triggers=["blocked_on_unknown", "result_deadline_elapsed", "delivery_uncertain"])},
    repo_root=REPO_ROOT,
)

if __name__ == "__main__":
    sys.stdout.write(ledger.compile())
