# 账本「perfbase-v1」：用户 2026-08-22 三连令 —— 仪表 → 基线 → corral-core 重构。
# 链形状：三条链串死（基线未测得 ⛔ 不许动重构；仪表未过评审 ⛔ 不许测基线）。
#   t.red ──▶ t.app ─┐
#                    ├─▶ t.rv.instr ──pass──▶ t.base ──▶ t.rv.base ──pass──▶ t.core
#            t.srv ──┘        └─rework─▶ t.app（上限3，穷尽升报 t.exh1）
#   t.core ──▶ t.cperf ──▶ t.repo ──▶ t.rv.final ──pass──▶ t.close
# 判据全部外置到 tools/perfbase/*.sh（编译期做存在性 + sh -n + shellcheck）。
# 底座 commit 2d4e1d66f（产品树 = 4120c0884 归档回退态）；leader 已实测双端单测退出码 0 ⇒ 存量红为零。
import sys

sys.path.insert(0, "/Users/alauda/.claude/skills/ledger-orchestration/reference/ledgerdsl-0.1.0")

from ledgerdsl import (  # noqa: E402
    WT, Check, EnvironmentFidelity, FallbackDef, Ledger, Provenance, Resources,
    Assemble, ScriptRef, StatusDef, Task, Transition, dep, role, 合并, 返修回环,
)

REPO_ROOT = "/Volumes/nvme/Projects/远程Agent安卓"
BASE = dict(
    environment_fidelity=EnvironmentFidelity(runs_real_cli=True),
    provenance=Provenance(identity="git", revision="2d4e1d66f098e5bc36c23c75bc9fbd4f19513c99"),
)

书01 = ".team/tasks/perfbase/任务书-01-仪表.md"
书02 = ".team/tasks/perfbase/任务书-02-基线.md"
书03 = ".team/tasks/perfbase/任务书-03-重构.md"
书04 = ".team/tasks/perfbase/任务书-04-评审.md"
交接 = "docs/交接任务书-性能基线与仓库重构.md"
判据dir = "tools/perfbase"

roles = {
    # 实现线：grok（与评审线异源）
    "r.test": role(".team/nodes/pb-red/", agent="pb-test", team="grok-l2", policy="fresh"),
    "r.impl": role(".team/nodes/pb-green/", agent="pb-impl", team="grok-l2", policy="resident_reuse"),
    "r.srv": role(".team/nodes/pb-srv/", agent="pb-srv", team="grok-l2", policy="fresh"),
    # 量测线与评审线：Claude 订阅 Opus 5（⛔ 禁 Deepseek、⛔ 禁 Fable 5 评审）
    "r.emu": role(".team/nodes/pb-emu/", agent="pb-emu", team="remote-agent-android", policy="resident_reuse"),
    "r.rv1": role(".team/nodes/pb-rv-instr/", agent="pb-rv1", team="remote-agent-android", policy="resident_reuse"),
    "r.rv2": role(".team/nodes/pb-rv-base/", agent="pb-rv2", team="remote-agent-android", policy="resident_reuse"),
    # 升报席位：⛔ 不拥有任何任务（门④硬要求）
    "r.advisor": role(".team/nodes/advisor/", policy="resident_reuse"),
}

DONE = [StatusDef(name="done", description="本格交付物全落盘，交下游独立判",
                  artifact={"report": ".team/nodes/<本格>/说明.md"})]
裁定 = [
    StatusDef(name="pass", description="核过且成立，放行下游", artifact={"report": "裁定.md"}),
    StatusDef(name="rework", description="不成立，带逐条理由与代码原文证据打回", artifact={"report": "裁定.md"}),
    StatusDef(name="inconclusive", description="判不出（合法终态），写清判不出什么", artifact={"report": "裁定.md"}),
]

# ── 任务一：仪表 ────────────────────────────────────────────────────────────
t_red = Task(
    title=(
        "任务一·先红格：写 PerfTrace 的 API 骨架（能编译、方法体不发日志）+ 三条红测。"
        "红测文件 app/app/src/test/kotlin/dev/agentmirror/app/perf/PerfTraceChainTest.kt，"
        "方法名逐字：perfTrace_关闭时零行 / perfTrace_一次打开产出八事件且open_id一致且时间单调 / "
        "perfTrace_两次并发打开open_id不串。判据要求「编译得过 且 测试 FAILED」——"
        "⛔ 编译不过=不可判(2)，不算先红；⛔ 不许写实现。细则读 " + 书01
    ),
    owner_role="r.test", seat_wait_seconds=1800,
    resources=Resources(
        worktree_id="wt-pb-red",
        write_paths=["app/app/src/test/kotlin/dev/agentmirror/app/perf/",
                     "app/app/src/main/java/dev/agentmirror/app/perf/",
                     ".team/nodes/pb-red/"],
        read_paths=[书01, 交接, "CLAUDE.md", 判据dir + "/judge-red.sh",
                    "app/app/src/main/java/dev/agentmirror/app/diag/DiagLog.kt"],
        **BASE),
    checks=[Check(id="c.red", script=ScriptRef(path=判据dir + "/judge-red.sh"), cwd=WT, budget=1800)],
)

t_app = Task(
    title=(
        "任务一·转绿格：实现 PerfTrace 本体并把八个打点接进产品链路（tap/route_enter/subscribe_sent/"
        "geom_seed/first_frame_recv/snapshot_applied/first_draw/layout_settled），红测转绿、全量单测绿。"
        "关时必须在调用点最外层短路（关=零分配零拼串）；每个事件带操作数不只带判决；"
        "收藏页进入/断线重连后进入/旋转后重进这些路径也要覆盖。"
        "⛔ 不许改红测方法名/断言/加 @Ignore。细则读 " + 书01
    ),
    owner_role="r.impl", seat_wait_seconds=3600,
    resources=Resources(
        worktree_id="wt-pb-app",
        write_paths=["app/", ".team/nodes/pb-green/"],
        read_paths=[书01, 交接, "CLAUDE.md", 判据dir + "/judge-app-green.sh"],
        **BASE),
    checks=[Check(id="c.app.green", script=ScriptRef(path=判据dir + "/judge-app-green.sh"),
                  cwd=WT, budget=2400)],
    statuses=DONE,
)

t_srv = Task(
    title=(
        "任务一·服务端格：给 subscribe 帧打三时间戳，键名逐字 perf_subscribe/recv_ms/start_ms/"
        "done_ms/queue_ms（queue_ms=start-recv）。用 server 现有 logger，⛔ 不引新依赖，"
        "⛔ 热路径不许无条件格式化字符串。go build ./... && go test ./... 必须绿"
        "（基线退出码为 0，增量任何红即红）。细则读 " + 书01
    ),
    owner_role="r.srv", seat_wait_seconds=2400,
    resources=Resources(
        worktree_id="wt-pb-srv",
        write_paths=["server/", ".team/nodes/pb-srv/"],
        read_paths=[书01, 交接, "CLAUDE.md", 判据dir + "/judge-srv-green.sh"],
        **BASE),
    checks=[Check(id="c.srv.green", script=ScriptRef(path=判据dir + "/judge-srv-green.sh"),
                  cwd=WT, budget=1800)],
)

t_rv_instr = Task(
    title=(
        "任务一·异源评审：判仪表三条主线——①关时真零成本（去证明它有成本）②链路完整性与可算性"
        "（光看日志能否算出每段耗时、能否区分「该做而没做」与「做了但做错了」）③单测是不是形式绿"
        "（自己重跑 gradle 与 go test，退出码写进证据）。"
        "裁定书落 .team/nodes/pb-rv-instr/裁定.md，必须有恰好一行 status=pass|rework|inconclusive。"
        "⛔ 产出方自证不算数。细则读 " + 书04
    ),
    owner_role="r.rv1", seat_wait_seconds=2400,
    resources=Resources(
        worktree_id="wt-pb-rv1",
        write_paths=[".team/nodes/pb-rv-instr/"],
        read_paths=[书04, 书01, 交接, "app/", "server/", ".team/nodes/pb-green/", ".team/nodes/pb-srv/"],
        **BASE),
    checks=[Check(id="c.rv.instr",
                  script=ScriptRef(path=判据dir + "/judge-verdict.sh",
                                   args=[".team/nodes/pb-rv-instr/裁定.md"]),
                  cwd=WT, budget=300)],
    statuses=裁定,
)

# ── 任务二：基线 ────────────────────────────────────────────────────────────
t_base = Task(
    title=(
        "任务二·测基线：模拟器 agentmirror_test_b 上，三夹具（real_claude_idle / redraw_tui / "
        "big_scrollback）各冷点开 ≥10 次，**只从 adb logcat -s PerfTrace 取数**"
        "（用户明令 ⛔ 不许识图、不许取帧/取间隔）。极端值 ⛔ 不许剔除，列进 outliers 并附原始日志段。"
        "落 .team/perf/baseline-<日期>.json + .team/perf/raw/。"
        "起隔离 tmux 必须自检在自己 socket 上（建 socket 失败会静默回退到用户真实 tmux）；"
        "⛔ 不碰 9900、⛔ 不点真实舰队会话；资源不够如实报不可判。细则读 " + 书02
    ),
    owner_role="r.emu", seat_wait_seconds=5400,
    resources=Resources(
        worktree_id="wt-pb-base",
        write_paths=[".team/perf/", ".team/nodes/pb-emu/"],
        read_paths=[书02, 交接, "CLAUDE.md", 判据dir + "/judge-baseline.sh", "e2e/"],
        **BASE),
    checks=[Check(id="c.base", script=ScriptRef(path=判据dir + "/judge-baseline.sh"),
                  cwd=WT, budget=600)],
)

t_rv_base = Task(
    title=(
        "任务二·异源评审：判基线可不可信当地板——三夹具是否各 ≥10 次真冷点开、数是否真来自 "
        "PerfTrace 原始日志（去 .team/perf/raw/ 核统计值对得上）、有没有剔除极端值、p95 算法、"
        "环境自证是否齐。发现动了用户真实 tmux 或点了真实舰队会话 ⇒ 直接 rework 并头行标红。"
        "裁定书落 .team/nodes/pb-rv-base/裁定.md。细则读 " + 书04
    ),
    owner_role="r.rv2", seat_wait_seconds=2400,
    resources=Resources(
        worktree_id="wt-pb-rv2",
        write_paths=[".team/nodes/pb-rv-base/"],
        read_paths=[书04, 书02, ".team/perf/", ".team/nodes/pb-emu/"],
        **BASE),
    checks=[Check(id="c.rv.base",
                  script=ScriptRef(path=判据dir + "/judge-verdict.sh",
                                   args=[".team/nodes/pb-rv-base/裁定.md"]),
                  cwd=WT, budget=300)],
    statuses=裁定,
)

# ── 任务三：重构（基线过评审才动） ──────────────────────────────────────────
t_core = Task(
    title=(
        "任务三·仓内切分：拆出纯 JVM 的 :core-protocol / :core-terminal / :core-conn，app 只剩壳。"
        "现有 :terminal 已是纯 Kotlin/JVM，优先并入或改名复用，⛔ 不许另抄一份。"
        "核模块 ⛔ 不许上 Android 插件、⛔ 源码不许 import android./androidx.；"
        "核里的打点用接口回调由 app 壳注入，八个事件名与格式不许变。"
        "一次只挪一个模块，每步跑全量单测，红了立刻回退该步。细则读 " + 书03
    ),
    owner_role="r.impl", seat_wait_seconds=5400,
    resources=Resources(
        worktree_id="wt-pb-core",
        write_paths=["app/", ".team/nodes/pb-core/"],
        read_paths=[书03, 交接, "CLAUDE.md", 判据dir + "/judge-core-split.sh"],
        **BASE),
    checks=[Check(id="c.core.split", script=ScriptRef(path=判据dir + "/judge-core-split.sh"),
                  cwd=WT, budget=2400)],
)

t_cperf = Task(
    title=(
        "任务三·切分后复测：用任务书 02 完全相同的流程与环境复测，落 "
        ".team/perf/recheck-<日期>-split.json（同形状）。判据逐夹具逐段比 p50/p95，超基线 +10% 即红。"
        "⛔ 不许换夹具、换次数、换取数方式来让它变绿。细则读 " + 书02 + " 末节"
    ),
    owner_role="r.emu", seat_wait_seconds=5400,
    resources=Resources(
        worktree_id="wt-pb-cperf",
        write_paths=[".team/perf/", ".team/nodes/pb-cperf/"],
        read_paths=[书02, 判据dir + "/judge-perf-nonregress.sh", ".team/nodes/pb-core/"],
        **BASE),
    checks=[Check(id="c.perf.nonregress",
                  script=ScriptRef(path=判据dir + "/judge-perf-nonregress.sh"), cwd=WT, budget=600)],
)

t_repo = Task(
    title=(
        "任务三·引用式构建暂存件：在 .team/staging/corral-app/ 造独立工程，app 壳整体搬过去，"
        "用 Gradle composite build（includeBuild）引三个核模块，写 迁移清单.md 逐条列搬了什么、"
        "还留在 core 里的 Android 残留（有就明写，⛔ 不许谎称已全部剥离）。"
        "判据要求暂存工程 ./gradlew :app:assembleDebug 绿并产出 APK。"
        "⛔ 席位不许 git push / 不许动 mirror-pr 脚本 / 不许碰远端仓库——推送是 leader 的动作。"
        "图标资产遵守 093 §2 只用 Provider 原生厂家官方图标。细则读 " + 书03
    ),
    owner_role="r.impl", seat_wait_seconds=5400,
    resources=Resources(
        worktree_id="wt-pb-repo",
        write_paths=[".team/staging/", ".team/nodes/pb-repo/"],
        read_paths=[书03, 交接, "CLAUDE.md", 判据dir + "/judge-repo.sh", "app/"],
        **BASE),
    checks=[Check(id="c.repo", script=ScriptRef(path=判据dir + "/judge-repo.sh"), cwd=WT, budget=2400)],
    statuses=DONE,
)

t_rv_final = Task(
    title=(
        "任务三·终审：自己重跑 judge-core-split / judge-perf-nonregress / judge-repo 三条判据"
        "（不是看别人的自报），并 git diff 核 tools/perfbase/ 有没有被动过——动了直接 rework。"
        "核迁移清单里「已剥离」逐条成立、核模块零 android import。"
        "最后写「留给用户的缺口」一节，至少含①用户真机装包实测『秒进秒排好』金标准门"
        "②远端 PR 推送，各写清下一步怎么做。裁定书落 .team/nodes/pb-rv-final/裁定.md。细则读 " + 书04
    ),
    owner_role="r.rv1", seat_wait_seconds=3600,
    resources=Resources(
        worktree_id="wt-pb-rvf",
        write_paths=[".team/nodes/pb-rv-final/"],
        read_paths=[书04, 书03, "app/", ".team/staging/", ".team/perf/", 判据dir + "/"],
        **BASE),
    checks=[Check(id="c.rv.final",
                  script=ScriptRef(path=判据dir + "/judge-verdict.sh",
                                   args=[".team/nodes/pb-rv-final/裁定.md"]),
                  cwd=WT, budget=300)],
    statuses=裁定,
)

# ── 收口与三个穷尽升报格（⛔ 不在各自回环上） ───────────────────────────────
t_close = Task(
    title=(
        "收账：把三件事的现状汇成一页给用户早上看——每件事「已验证完成 / 自报未核 / 未做」三分，"
        "各附证据路径；把 t.rv.final 的「留给用户的缺口」原样带上。⛔ 不许把自报当已完成。"
        "落 .team/artifacts/perfbase-收账-<日期>.md"
    ),
    owner_role="r.rv2", seat_wait_seconds=1800,
    resources=Resources(worktree_id="wt-pb-close", write_paths=[".team/artifacts/"], **BASE),
    checks=[Check(id="c.close", script=ScriptRef(path=判据dir + "/judge-doc.sh",
                                                 args=[".team/artifacts/perfbase-收账-*.md"]),
                  cwd=WT, budget=120)],
)
t_exh1 = Task(
    title="仪表返修 3 轮穷尽：整理三轮 rework 理由与代码现状，升报 leader 裁定，⛔ 不许静默收工。",
    owner_role="r.rv2", seat_wait_seconds=1800,
    resources=Resources(worktree_id="wt-pb-exh", write_paths=[".team/escalations/"], **BASE),
    checks=[Check(id="c.exh1", script=ScriptRef(path=判据dir + "/judge-doc.sh",
                                              args=[".team/escalations/perfbase-exh1-*.md"]),
                  cwd=WT, budget=120)],
)
t_exh2 = Task(
    title="基线返修 2 轮穷尽：整理两轮 rework 理由与量测现状，升报 leader 裁定，⛔ 不许静默收工。",
    owner_role="r.rv1", seat_wait_seconds=1800,
    resources=Resources(worktree_id="wt-pb-exh2", write_paths=[".team/escalations/"], **BASE),
    checks=[Check(id="c.exh2", script=ScriptRef(path=判据dir + "/judge-doc.sh",
                                              args=[".team/escalations/perfbase-exh2-*.md"]),
                  cwd=WT, budget=120)],
)
t_exh3 = Task(
    title="重构返修 2 轮穷尽：整理两轮 rework 理由与重构现状，升报 leader 裁定，⛔ 不许静默收工。",
    owner_role="r.rv2", seat_wait_seconds=1800,
    resources=Resources(worktree_id="wt-pb-exh3", write_paths=[".team/escalations/"], **BASE),
    checks=[Check(id="c.exh3", script=ScriptRef(path=判据dir + "/judge-doc.sh",
                                              args=[".team/escalations/perfbase-exh3-*.md"]),
                  cwd=WT, budget=120)],
)

t_base2 = Task(
    title=(
        "任务二·按评审意见重测基线：读 .team/nodes/pb-rv-base/裁定.md 的 rework 逐条，"
        "用任务书 02 完全相同的流程与环境重测，覆盖 .team/perf/baseline-<日期>.json。"
        "⛔ 不许为了让判据变绿而换夹具、减次数、剔极端值。细则读 " + 书02
    ),
    owner_role="r.emu", seat_wait_seconds=5400,
    resources=Resources(
        worktree_id="wt-pb-base2",
        write_paths=[".team/perf/", ".team/nodes/pb-emu2/"],
        read_paths=[书02, 判据dir + "/judge-baseline.sh", ".team/nodes/pb-rv-base/"],
        **BASE),
    checks=[Check(id="c.base2", script=ScriptRef(path=判据dir + "/judge-baseline.sh"),
                  cwd=WT, budget=600)],
    statuses=DONE,
)

def 升报格(role_id, wt, tag, 说明):
    return Task(
        title=("判不出升报：" + 说明 + " 把「判不出什么、缺哪份材料、下一步谁来补」写清，"
               "升报 leader 裁定。⛔ 不许静默收工，⛔ 不许把判不出改写成通过。"
               "落 .team/escalations/perfbase-" + tag + "-<日期>.md"),
        owner_role=role_id, seat_wait_seconds=1800,
        resources=Resources(worktree_id=wt, write_paths=[".team/escalations/"], **BASE),
        checks=[Check(id="c." + tag, script=ScriptRef(path=判据dir + "/judge-doc.sh",
                                                      args=[".team/escalations/perfbase-" + tag + "-*.md"]),
                      cwd=WT, budget=120)],
    )

t_esc1 = 升报格("r.rv2", "wt-pb-esc1", "esc1", "仪表面判不出。")
t_esc2 = 升报格("r.rv1", "wt-pb-esc2", "esc2", "基线面判不出。")
t_esc3 = 升报格("r.rv2", "wt-pb-esc3", "esc3", "重构终审判不出。")

# inconclusive（判不出，合法终态）也必须有出边，否则预检拒：各自指向对应升报格
额外边 = [
    Transition(frm="t.rv.instr", to="t.esc1", on_status=["inconclusive"],
               assemble=Assemble(include_upstream_case=True)),
    Transition(frm="t.rv.base", to="t.esc2", on_status=["inconclusive"],
               assemble=Assemble(include_upstream_case=True)),
    Transition(frm="t.rv.final", to="t.esc3", on_status=["inconclusive"],
               assemble=Assemble(include_upstream_case=True)),
]

回环1 = 返修回环(review=t_rv_instr, fix=t_app, accept=t_base, exhausted=t_exh1, max_rounds=3,
              ids=("t.rv.instr", "t.app", "t.base", "t.exh1"))
回环2 = 返修回环(review=t_rv_base, fix=t_base2, accept=t_core, exhausted=t_exh2, max_rounds=2,
              ids=("t.rv.base", "t.base2", "t.core", "t.exh2"))
回环3 = 返修回环(review=t_rv_final, fix=t_repo, accept=t_close, exhausted=t_exh3, max_rounds=2,
              ids=("t.rv.final", "t.repo", "t.close", "t.exh3"))
图 = 合并(回环1, 回环2, 回环3)

tasks = dict(图.tasks)
tasks.update({"t.red": t_red, "t.srv": t_srv, "t.cperf": t_cperf,
              "t.esc1": t_esc1, "t.esc2": t_esc2, "t.esc3": t_esc3})

dependencies = list(图.dependencies) + [
    dep("t.red", "t.app"),        # 先红才许转绿
    dep("t.app", "t.rv.instr"),
    dep("t.srv", "t.rv.instr"),   # app 与 server 并行，在评审格汇合
    dep("t.base", "t.rv.base"),
    dep("t.core", "t.cperf"),     # 切分完先复测基线
    dep("t.cperf", "t.repo"),     # 切分不掉性能才许迁仓
    dep("t.repo", "t.rv.final"),
]

ledger = Ledger(
    ledger_id="ledger.perfbase.v1",
    roles=roles,
    tasks=tasks,
    dependencies=dependencies,
    transitions=list(图.transitions) + 额外边,
    parallelism=图.parallelism,
    fallback={"f.escalate": FallbackDef(
        role="r.advisor",
        triggers=["blocked_on_unknown", "result_deadline_elapsed", "delivery_uncertain"])},
    repo_root=REPO_ROOT,
)

if __name__ == "__main__":
    sys.stdout.write(ledger.compile())
