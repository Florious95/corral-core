#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tools/gate/gate.py —— 全量回归门核心逻辑（run.sh 的薄壳后端）。

对应需求 013「回归门禁」与知识基底 test-gate 的四个目标：
  1) run <suite>       —— 单面套件运行器（server / app / archwiki），各自输出原始结果 JSON。
  2) finalize <rundir> —— 汇总三面结果，做「用例数棘轮」校验，写 gate-report.json 与
                          baseline.json；exit 0=过 / 1=红。
  3) selftest          —— 用手工 fixture 验证门本身：三条红测 + 绿路 + 棘轮上行 + 显式下行。

设计约束（知识基底 §1）：
  - 零第三方依赖，纯标准库。
  - Go 用例数来自 `go test -json` 流式解析（仅 Action=pass/fail 的 Test 事件）。
  - Gradle 用例数来自 build/test-results/**/TEST-*.xml 的 tests 属性求和。
  - 失败四归因字段默认 unclassified；门不自动猜归因，只做上一轮人工/上游注解的 carry-over
    （模板位真实可用，而不是每次运行被抹掉）。

判红点统一加 SUITE=<name> 注释，selftest 场景即判例（机器可校验标注）。
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree as ET

# 净化前缀：测试运行时剔除 team-agent 注入的环境变量（知识基底 §2 照旧）。
_SANITIZE = (
    "env -u TEAM_AGENT_WORKSPACE -u TEAM_AGENT_ID -u TEAM_AGENT_OWNER_TEAM_ID "
    "-u TEAM_AGENT_AGENT_ID"
)

# 仓库根：gate.py 位于 <root>/tools/gate/gate.py，向上三级即根。脚本自身路径解析，
# 与调用者 cwd 无关，保证 run.sh 可从任意目录执行。
REPO = Path(__file__).resolve().parent.parent.parent


def _ms(start: float) -> int:
    """耗时（毫秒）换算，四舍五入取整。"""
    return int(round((time.monotonic() - start) * 1000))


def _bash(cmd: str, cwd: Path) -> subprocess.CompletedProcess:
    """统一经 bash -lc 执行（JAVA_HOME 在 profile，知识基底 §2），并净化测试环境。"""
    return subprocess.run(
        ["bash", "-lc", f"cd {shlex_quote(str(cwd))} && {cmd}"],
        capture_output=True,
        text=True,
        errors="replace",
    )


def shlex_quote(s: str) -> str:
    """shell 引号包裹，防路径含空格破坏命令行。"""
    return "'" + s.replace("'", "'\\''") + "'"


# ---------------------------------------------------------------------------
# 套件运行器
# ---------------------------------------------------------------------------

def run_server_suite() -> dict:
    """server 面：go test -json 流式计用例 + go vet + gofmt -l 空输出（需求 013 目标 1）。"""
    start = time.monotonic()
    sdir = REPO / "server"
    failures: list[dict] = []
    cases = 0

    # 1) go test -json：只计 Action∈{pass,fail} 的 Test 事件（知识基底 §1）。
    #    包级编译失败（无 Test 事件）单独记一条失败，不计入用例数。
    proc = subprocess.Popen(
        ["bash", "-lc", f"cd {shlex_quote(str(sdir))} && {_SANITIZE} go test ./... -count=1 -json 2>&1"],
        stdout=subprocess.PIPE,
        text=True,
        errors="replace",
    )
    events: list[dict] = []
    for line in proc.stdout:
        line = line.strip()
        if not line:
            continue
        try:
            events.append(json.loads(line))
        except Exception:
            continue  # 非 JSON 行（例如 vendor 里的杂音）跳过，不计、不判。
    rc = proc.wait()
    for ev in events:
        act = ev.get("Action")
        test = ev.get("Test")
        pkg = ev.get("Package")
        if test and act in ("pass", "fail"):
            cases += 1
            if act == "fail":
                failures.append({"test": test, "category": "unclassified"})
        elif test is None and act == "fail" and pkg:
            # SUITE=server：仅当该包没有任何 test 级 fail 事件时才视为编译/打包失败，
            # 否则与既有用例失败重复，静默去重。
            has_test_fail = any(
                e.get("Package") == pkg and e.get("Test") and e.get("Action") == "fail"
                for e in events
            )
            if not has_test_fail:
                label = f"{pkg} (package failure)"
                if not any(f["test"] == label for f in failures):
                    failures.append({"test": label, "category": "unclassified"})
    if rc != 0 and not failures:
        # 进程失败但事件流里没有可归因的失败：环境/工具链问题，如实记一条。
        failures.append({"test": "go test (exit non-zero, no attributable event)", "category": "unclassified"})

    # 2) go vet ./...：退出码非 0 即记失败。
    vet = _bash(f"{_SANITIZE} go vet ./...", sdir)
    if vet.returncode != 0:
        failures.append({"test": "go vet", "category": "unclassified"})

    # 3) gofmt -l .：空输出为过；非空列出未格式化文件，逐文件记失败（需求：空输出）。
    gf = _bash(f"{_SANITIZE} gofmt -l .", sdir)
    if gf.returncode != 0:
        failures.append({"test": "gofmt (invocation error)", "category": "unclassified"})
    else:
        for fname in gf.stdout.splitlines():
            fname = fname.strip()
            if fname:
                failures.append({"test": f"gofmt:{fname}", "category": "unclassified"})

    # 4) staticcheck ./...（gate-static-analysis 接入）：BSD-3，008 全开源兼容；默认规则集
    #    不裁剪（红线：不许为了让门禁变绿而降规则）。二进制不在登录 PATH（GOPATH/bin 未导出），
    #    经 go env GOPATH 解析绝对路径；找不到即显式红（环境缺失不得静默当 pass）。
    #    每条 finding 记一条失败（test 键 stable，供四归因 carry-over），文件:行 归因到包。
    for line in _staticcheck_findings(sdir):
        failures.append({"test": f"staticcheck:{line}", "category": "unclassified"})

    return {
        "name": "server",
        "ok": not failures,
        "skipped": False,
        "cases": cases,
        "failures": failures,
        "duration_ms": _ms(start),
    }


def _staticcheck_findings(sdir: Path) -> list[str]:
    """跑 staticcheck ./...，返回 (file:line) 形式的 finding 列表；工具缺失/崩溃返回哨兵条目。

    哨兵也走失败通道：空清单不算健康（知识基底§2 T3-2/terminal 双盲区教训），
    工具没跑成必须可见红，而不是被当成「干净」。
    """
    gopath = subprocess.run(
        ["bash", "-lc", f"cd {shlex_quote(str(sdir))} && {_SANITIZE} go env GOPATH"],
        capture_output=True, text=True,
    ).stdout.strip()
    scbin = Path(gopath or "go") / "bin" / "staticcheck"
    if not scbin.exists():
        return ["(binary not found)"]
    p = _bash(f"{_SANITIZE} {scbin} ./... 2>&1", sdir)
    out: list[str] = []
    for raw in p.stdout.splitlines():
        line = raw.strip()
        if not line:
            continue
        # 格式 "file.go:line:col: message (ruleid)" → 取 file:line 作稳定归因键。
        parts = line.split(":")
        if len(parts) >= 2:
            out.append(f"{parts[0]}:{parts[1]}")
    if p.returncode != 0 and not out:
        # 工具崩溃/进程失败但无逐条可归因：如实记一条，绝不静默放行。
        out.append("(exit non-zero, no attributable finding)")
    return out


def run_app_suite() -> dict:
    """app 面：gradlew test；用例数 = **/build/test-results/**/TEST-*.xml 的 tests 求和。"""
    start = time.monotonic()
    adir = REPO / "app"
    failures: list[dict] = []
    cases = 0

    # gradle 经 bash -lc（JAVA_HOME 在 profile）。-q 静默通过；失败时 stderr 有报错。
    p = _bash(f"{_SANITIZE} ./gradlew -q test 2>&1", adir)

    # 汇总 JUnit XML。glob 从 app 根递归：:terminal 等后续模块若被 include，其结果自动计入。
    for xml in sorted(adir.glob("**/build/test-results/**/TEST-*.xml")):
        try:
            root = ET.parse(str(xml)).getroot()
        except Exception:
            rel = xml.relative_to(adir)
            failures.append({"test": f"unparseable junit xml: {rel}", "category": "unclassified"})
            continue
        cases += int(root.attrib.get("tests", 0) or 0)
        for tc in root.iter("testcase"):
            name = tc.attrib.get("name", "")
            cls = tc.attrib.get("classname", "")
            label = f"{cls}.{name}" if cls else name
            for _bad in list(tc.findall("failure")) + list(tc.findall("error")):
                failures.append({"test": label, "category": "unclassified"})

    if p.returncode != 0:
        # gradle 整体失败：XML 若已暴露具体用例失败则清单已有，仍补一条构建级失败防静默。
        failures.append({"test": f"gradle test (exit {p.returncode})", "category": "unclassified"})

    # Android Lint（gate-static-analysis 接入）：AGP 自带，零新依赖。默认规则集不裁剪
    # （红线：不许为了让门禁变绿而降规则）。lintDebug 默认仅 error 使构建失败（AbortOnError），
    # 但为「暴露不挑 + 立账完整」，把 XML 报告里每条 finding（含 warning）都记为失败条目——
    # 存量未清前 app 面非绿属预期，四归因由上游 carry-over 标注。
    lint = _bash(f"{_SANITIZE} ./gradlew -q :app:lintDebug 2>&1", adir)
    lint_xml = adir / "app" / "build" / "reports" / "lint-results-debug.xml"
    if lint_xml.exists():
        try:
            root = ET.parse(str(lint_xml)).getroot()
            for issue in root.findall("issue"):
                iid = issue.get("id", "?")
                loc = issue.find("location")
                f = loc.get("file", "?") if loc is not None else "?"
                ln = loc.get("line", "?") if loc is not None else "?"
                # 仓库内路径取相对根（键稳定）；仓库外（如 ~/.gradle 缓存）保留绝对路径照记。
                if os.path.isabs(f):
                    try:
                        rel = os.path.relpath(f, REPO)
                        if not rel.startswith(".."):
                            f = rel
                    except ValueError:
                        pass  # Windows 盘符跨盘等罕见情况，保留原文
                failures.append({"test": f"lint:{iid}:{f}:{ln}", "category": "unclassified"})
        except Exception:
            failures.append({"test": "lint (unparseable report)", "category": "unclassified"})
    elif lint.returncode != 0:
        # 报告缺失且 gradle 非 0：工具没跑成，必须可见红而非当「干净」。
        failures.append({"test": "lint (no report, exit non-zero)", "category": "unclassified"})

    return {
        "name": "app",
        "ok": p.returncode == 0 and not failures,
        "skipped": False,
        "cases": cases,
        "failures": failures,
        "duration_ms": _ms(start),
    }


def run_archwiki_suite() -> dict:
    """archwiki 面：build_wiki.py 存在才跑，缺席跳过并显式标注 skipped（静默失效猎杀）。"""
    start = time.monotonic()
    aw = REPO / "tools" / "archwiki" / "build_wiki.py"
    if not aw.exists():
        return {
            "name": "archwiki",
            "ok": True,
            "skipped": True,
            "skip_reason": "tools/archwiki/build_wiki.py absent (arch-wiki 在途，缺席跳过不静默当 pass)",
            "cases": 0,
            "failures": [],
            "duration_ms": _ms(start),
        }
    p = _bash(f"{_SANITIZE} python3 tools/archwiki/build_wiki.py --check 2>&1", REPO)
    failures = []
    if p.returncode != 0:
        failures.append({"test": "archwiki --check", "category": "unclassified"})
    return {
        "name": "archwiki",
        "ok": p.returncode == 0,
        "skipped": False,
        "cases": 1,  # 单个原子检查（非多用例测试集），仅作通过/失败判据。
        "failures": failures,
        "duration_ms": _ms(start),
    }


# 套件定义表：min_cases>0 的面启用「空扫描(0 用例)必须红」。
# 知识基底 §2：该红测只对 server 面启用；app 面以当前实际值（0）起步，0 用例不算红。
SUITE_DEFS = [
    {"name": "server", "min_cases": 1, "run": run_server_suite},
    {"name": "app", "min_cases": 0, "run": run_app_suite},
    {"name": "archwiki", "min_cases": 0, "run": run_archwiki_suite},
]


def cmd_run(args: argparse.Namespace) -> int:
    """`run <suite>`：执行单面套件，原始结果 JSON 写 --out（缺省打 stdout）。"""
    for d in SUITE_DEFS:
        if d["name"] == args.suite:
            res = d["run"]()
            break
    else:
        names = ", ".join(d["name"] for d in SUITE_DEFS)
        print(f"unknown suite: {args.suite} (known: {names})", file=sys.stderr)
        return 2
    text = json.dumps(res, ensure_ascii=False, indent=2) + "\n"
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    else:
        print(text, end="")
    return 0


# ---------------------------------------------------------------------------
# 汇总 + 基线棘轮
# ---------------------------------------------------------------------------

def run_finalize(
    rundir: Path,
    report_path: Path,
    baseline_path: Path,
    accept_reason: str | None = None,
    prior_report_path: Path | None = None,
) -> tuple[str, list[str], dict]:
    """汇总 rundir 内三面原始结果 → 棘轮校验 → 写报告与基线，返回 (conclusion, issues, report)。

    棘轮规则（需求 013「测试数棘轮」）：
      - 本次用例数 < 基线 → 红，除非 --accept-baseline=<理由> 显式接受下行。
      - 增加 → 自动上行更新基线。
      - 首见套件 → 以当前值初始化基线。
      - 跳过（skipped）套件不触碰基线，报告显式标注，不静默当 pass。
    """
    baseline: dict = {}
    if baseline_path.exists():
        baseline = json.loads(baseline_path.read_text(encoding="utf-8"))

    # 上一轮报告的失败归因 carry-over：门不自动猜归因（知识基底 §2），
    # 但把人工/上游席位填好的 product|harness|baseline|flaky 保留到下轮。
    if prior_report_path is None:
        prior_report_path = report_path
    prior_cat: dict[tuple[str, str], str] = {}
    if prior_report_path.exists():
        try:
            prior = json.loads(prior_report_path.read_text(encoding="utf-8"))
            for s in prior.get("suites", []):
                for f in s.get("failures", []):
                    cat = f.get("category", "unclassified")
                    if cat != "unclassified":
                        prior_cat[(s.get("name"), f.get("test"))] = cat
        except Exception:
            prior_cat = {}

    defined = {d["name"]: d for d in SUITE_DEFS}
    issues: list[str] = []
    suites: list[dict] = []

    for d in SUITE_DEFS:
        name = d["name"]
        fp = rundir / f"{name}.json"
        if not fp.exists():
            # SUITE=all：运行器未产出结果（崩溃/超时）→ 显式红，绝不静默放行。
            issues.append(f"no result for suite {name} (runner produced no json)")
            suites.append({
                "name": name, "ok": False, "skipped": True,
                "skip_reason": "runner produced no result",
                "cases": 0, "failures": [], "ratchet": None, "duration_ms": 0,
            })
            continue
        res = json.loads(fp.read_text(encoding="utf-8"))
        entry = {
            "name": name,
            "ok": res.get("ok", False),
            "skipped": res.get("skipped", False),
            "cases": res.get("cases", 0),
            "duration_ms": res.get("duration_ms", 0),
            "failures": res.get("failures", []),
            "skip_reason": res.get("skip_reason"),
            "detail": res.get("detail"),
        }
        if res.get("skipped"):
            entry["ratchet"] = None
            suites.append(entry)
            continue

        cur = res.get("cases", 0)
        prev = baseline.get(name)
        ratchet = {"baseline": prev, "current": cur, "action": "same"}
        entry_ok = res.get("ok", False)
        if prev is None:
            baseline[name] = cur
            ratchet["action"] = "init"
        elif cur < prev:
            if accept_reason:
                baseline[name] = cur
                ratchet["action"] = "down-accepted"
            else:
                # SUITE=<name>：棘轮只增不减，下行即红（防删测试作弊）。
                ratchet["action"] = "down"
                issues.append(
                    f"ratchet-down: {name} cases {prev}->{cur} "
                    "(基线棘轮只增不减，显式接受需 --accept-baseline=<理由>)"
                )
                entry_ok = False
        elif cur > prev:
            baseline[name] = cur
            ratchet["action"] = "up"

        # SUITE=<name>：空扫描（0 用例）必须红——仅 min_cases>0 的面（目前仅 server）。
        min_cases = d.get("min_cases", 0)
        if min_cases > 0 and cur == 0:
            issues.append(f"empty-scan: {name} ran 0 cases (min_cases={min_cases})")
            entry_ok = False

        entry["ratchet"] = ratchet
        entry["ok"] = entry_ok
        # 归因 carry-over 应用到本套件失败清单。
        for f in entry["failures"]:
            if f.get("category", "unclassified") == "unclassified":
                carried = prior_cat.get((name, f.get("test")))
                if carried:
                    f["category"] = carried
        suites.append(entry)

    # 防御：rundir 出现未定义的 suite json → 提示但忽略（不参与结论）。
    for fp in sorted(rundir.glob("*.json")):
        if fp.stem not in defined:
            issues.append(f"unexpected suite result ignored: {fp.stem}")

    conclusion = "pass" if (not issues and all(s["ok"] for s in suites)) else "fail"
    total_cases = sum(s["cases"] for s in suites if not s.get("skipped"))
    report = {
        "schema": "agentmirror-gate-report-v1",
        "conclusion": conclusion,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "baseline_accept": {"applied": bool(accept_reason), "reason": accept_reason},
        "suites": suites,
        "issues": issues,
        "summary": (
            f"结论 {conclusion}：{len(suites)} 套件共 {total_cases} 用例。"
            + ("基线显式下行已接受。" if accept_reason else "")
            + ("红：本次非全绿，详见 suites/issues。" if conclusion == "fail" else "绿。")
        ),
    }
    baseline_path.write_text(json.dumps(baseline, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return conclusion, issues, report


def cmd_finalize(args: argparse.Namespace) -> int:
    conclusion, issues, report = run_finalize(
        rundir=Path(args.rundir),
        report_path=Path(args.report),
        baseline_path=Path(args.baseline),
        accept_reason=args.accept_baseline,
    )
    print(f"gate: {report['summary']}")
    for i in issues:
        print(f"gate: issue - {i}")
    return 0 if conclusion == "pass" else 1


# ---------------------------------------------------------------------------
# 自测（三条红测 + 绿路 + 棘轮上行 + 显式下行）
# ---------------------------------------------------------------------------

def cmd_selftest(_args: argparse.Namespace) -> int:
    """用手工构造的 fixture（selftest/fixtures/）验证门本身。验证不得自证（知识基底 §4）。"""
    fx = Path(__file__).parent / "selftest" / "fixtures"
    tmp = Path(tempfile.mkdtemp(prefix="gate-selftest-"))

    def load(fixdir: str) -> dict[str, dict]:
        return {
            p.stem: json.loads(p.read_text(encoding="utf-8"))
            for p in sorted((fx / fixdir).glob("*.json"))
        }

    scenarios = [
        # (名称, fixture 目录, 初始基线, accept理由, 期望exit, 期望基线, 期望issue关键词, 说明)
        # 红测断言精确到「判红必须由该场景的罪因触发」，而不是笼统「有 issues」——
        # 避免因「no result」等其他原因误红也算过。
        # 注：每场景 fixture 均含 app(5) 与 archwiki(skip)，故 app 首次出现会 init=5。
        ("红测1 用例数下降必须红", "ratchet_down", {"server": 13}, None, 1,
         {"server": 13, "app": 5}, ["ratchet-down"], "10<13 触发棘轮下行判红"),
        ("红测2 套件失败必须整体红", "suite_fail", {"app": 5}, None, 1,
         {"server": 5, "app": 5}, [], "用例数相同但存在用例失败必须整体红（failures 非空即红）"),
        ("红测3 空扫描(0用例)必须红", "empty_scan", {"server": 0}, None, 1,
         {"server": 0, "app": 5}, ["empty-scan"], "server min_cases=1：0 用例即红"),
        ("绿路 全绿通过且基线初始化", "green", {}, None, 0,
         {"server": 5, "app": 5}, [], "全绿 + 首见套件以当前值初始化基线"),
        ("棘轮上行 自动更新基线", "ratchet_up", {"server": 5}, None, 0,
         {"server": 7, "app": 5}, [], "5->7 上行自动记录到基线"),
        # 场景6 复用 ratchet_down 同一套 fixture（server 10 / app 5 / archwiki skip），
        # 仅差异在传入 --accept-baseline 理由；验证同一输入在「无理由=红 / 有理由=过」两态切换。
        ("显式下行 accept-baseline 需理由", "ratchet_down", {"server": 13},
         "selftest fixture: 允许下行验证", 0,
         {"server": 10, "app": 5}, [], "显式旗标+理由后下行允许，基线落新值"),
    ]
    failed = 0
    for idx, (title, fixdir, bl, accept, want_exit, want_bl, want_keys, why) in enumerate(scenarios):
        subdir = tmp / f"s{idx}-{fixdir}"
        subdir.mkdir()
        for fn, data in load(fixdir).items():
            (subdir / f"{fn}.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        blp = tmp / f"s{idx}-baseline.json"
        blp.write_text(json.dumps(bl, ensure_ascii=False), encoding="utf-8")
        rp = tmp / f"s{idx}-report.json"
        concl, issues, _report = run_finalize(subdir, rp, blp, accept_reason=accept, prior_report_path=None)
        got_exit = 0 if concl == "pass" else 1
        got_bl = json.loads(blp.read_text(encoding="utf-8"))
        ok = got_exit == want_exit and got_bl == want_bl
        # 红测必须由罪因触发：要求 issues 里出现期望关键词（红测1/3）；空列表表示不强制。
        for key in want_keys:
            if not any(key in i for i in issues):
                ok = False
        # 绿路/上行/显式下行不得带任何 issues（no result 等误判直接暴露）。
        if not want_keys and issues:
            ok = False
        print(f"{'PASS' if ok else 'FAIL'}  {title} —— {why}")
        if not ok:
            failed += 1
            print(f"     期望 exit={want_exit} 实际={got_exit}; 期望基线={want_bl} 实际={got_bl}")
            print(f"     issues={issues}")
    shutil.rmtree(tmp, ignore_errors=True)
    if failed:
        print(f"selftest: {len(scenarios) - failed}/{len(scenarios)} 场景通过，{failed} 失败")
        return 1
    print(f"selftest: {len(scenarios)}/{len(scenarios)} 场景全部通过")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="gate.py", description="全量回归门核心")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_run = sub.add_parser("run", help="run one suite, emit raw result json")
    p_run.add_argument("suite")
    p_run.add_argument("--out", default=None)
    p_run.set_defaults(fn=cmd_run)

    p_fin = sub.add_parser("finalize", help="aggregate rundir into report + baseline ratchet")
    p_fin.add_argument("rundir")
    p_fin.add_argument("--report", required=True)
    p_fin.add_argument("--baseline", required=True)
    p_fin.add_argument("--accept-baseline", default=None, metavar="REASON")
    p_fin.set_defaults(fn=cmd_finalize)

    p_self = sub.add_parser("selftest", help="self-test the gate with hand fixtures")
    p_self.set_defaults(fn=cmd_selftest)

    args = parser.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
