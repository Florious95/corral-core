#!/usr/bin/env python3
"""test_check.py — build_wiki.py 的判据红测与幂等性测试。

准入纪律（知识基底 §1/§4）：
  * 每条判据必须自带红测 fixture（testdata/），写不出红测的不准入。
  * 验证不得自证：红测 fixture 手工构造，判据结果走 CLI 黑盒（subprocess）
    断言，绝不用生成器自身产出做互证。
  * 空扫描视为失败（N=0 不健康），断言 exit 2。

运行（净化前缀照旧）：
  env -u TEAM_AGENT_WORKSPACE -u TEAM_AGENT_ID -u TEAM_AGENT_OWNER_TEAM_ID \
      -u TEAM_AGENT_AGENT_ID python3 tools/archwiki/test_check.py

无外部依赖，仅标准库（unittest）。不需要 go 工具链：判据 fixture 全部走
--go-source 源码轻解析。
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest

# 本文件位于 <root>/tools/archwiki/，故三级上级即仓库根。
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, "tools", "archwiki", "build_wiki.py")
TESTDATA = os.path.join(ROOT, "tools", "archwiki", "testdata")

# 净化前缀：测试必须与真实环境隔离（同 server/ 惯例）。
SANITIZED_ENV = dict(os.environ)
for key in (
    "TEAM_AGENT_WORKSPACE",
    "TEAM_AGENT_ID",
    "TEAM_AGENT_OWNER_TEAM_ID",
    "TEAM_AGENT_AGENT_ID",
):
    SANITIZED_ENV.pop(key, None)


def run_tool(root, extra=None):
    """黑盒调用 build_wiki.py，返回 (exit_code, stdout+stderr)。"""
    argv = [sys.executable, SCRIPT, "--root", root] + (extra or [])
    proc = subprocess.run(argv, capture_output=True, text=True, env=SANITIZED_ENV)
    return proc.returncode, proc.stdout + proc.stderr


class TestRedFixtures(unittest.TestCase):
    """判据红测：fixture 必须让 --check 红。"""

    def test_t1_1_cycle_is_red(self):
        """环依赖 fixture（aa⇄bb）→ T1-1 FAIL、exit 1。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "cycle"), ["--check", "--go-source"]
        )
        self.assertNotEqual(code, 0, out)
        self.assertIn("T1-1", out)
        self.assertIn("FAIL", out)
        self.assertIn("环", out)

    def test_t1_2_missing_doc_is_red(self):
        """缺 doc 注释 fixture → T1-2 FAIL、exit 1。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "missingdoc"), ["--check", "--go-source"]
        )
        self.assertNotEqual(code, 0, out)
        self.assertIn("T1-2", out)
        self.assertIn("FAIL", out)
        self.assertIn("nodoc", out)

    def test_empty_scan_is_failure(self):
        """空扫描 fixture（0 包）→ 视为失败、exit 2。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "empty"), ["--check", "--go-source"]
        )
        self.assertEqual(code, 2, out)
        self.assertIn("空扫描", out)

    def test_t3_1_missing_symbol_doc_is_red(self):
        """T3-1 红测：顶层导出符号缺 doc（Go+Kotlin 各一）→ strict-t3 exit 1。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "missingdoc-symbol"),
            ["--check", "--go-source", "--strict-t3"],
        )
        self.assertNotEqual(code, 0, out)
        self.assertIn("T3-1", out)
        self.assertIn("Undocumented", out)          # Go 漏 doc 的导出符号
        self.assertIn("缺紧邻", out)

    def test_t3_2_lying_ref_is_red(self):
        """T3-2 红测：谎称符号/路径/flag（覆盖全部注释形态）→ strict-t3 exit 1。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "lying-ref"),
            ["--check", "--go-source", "--strict-t3"],
        )
        self.assertNotEqual(code, 0, out)
        self.assertIn("T3-2", out)
        # 原始 doc/KDoc 面
        self.assertIn("MissingInterface", out)      # Go 反引号谎称符号
        self.assertIn("GhostHelper", out)
        self.assertIn("--no-such-flag", out)        # Go KDoc 谎称 flag
        self.assertIn("docs/never-created.md", out)  # Go 谎称路径
        self.assertIn("config/settings.yaml", out)   # Kotlin KDoc 谎称路径
        # 新扩展面：函数体内普通 // 注释（旧扫描器完全不可见）
        self.assertIn("GhostBody", out)              # Go 函数体 // 谎称符号
        self.assertIn("docs/fake-body.md", out)      # Go 函数体 // 谎称路径
        self.assertIn("MissingBodyComment", out)     # Kotlin 函数体 // 谎称符号
        self.assertIn("config/bad-body.yaml", out)   # Kotlin 函数体 // 谎称路径
        # 新扩展面：Kotlin KDoc 内谎称 flag（Kotlin 侧对齐 flag 判定）
        self.assertIn("--no-such-plain-flag", out)

    def test_t3_3_incomplete_contract_is_red(self):
        """T3-3 红测：@contract 符号缺标签（Go 缺 @err/@inv、Kotlin 缺 @post）
        → strict-t3 exit 1。用与扫真仓库完全相同的代码路径（scan_t3 → _check_t3_3_*），
        这就是那个「0 必须自证」的必红 fixture。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "contract-incomplete"),
            ["--check", "--go-source", "--strict-t3"],
        )
        self.assertNotEqual(code, 0, out)
        self.assertIn("T3-3", out)
        self.assertIn("@err, @inv", out)   # Go 缺两项
        self.assertIn("@post", out)         # Kotlin 缺 @post
        self.assertIn("契约标签完备", out)

    def test_t3_3_multi_contract_symbols_in_one_file_is_red(self):
        """T3-3 回归红测（返工 #1，w-t3c-verify 实证）：同文件多个 @contract 符号，
        其中一个残缺 → 必须红。旧实现按文件 union 标签，前一个完整 @contract 的
        @err/@inv 掩盖后一个残缺符号的缺失——单符号四格 fixture 撞不到这条。
        Go 侧缺 @err/@inv、Kotlin 侧缺 @post 都要抓。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "contract-multi"),
            ["--check", "--go-source", "--strict-t3"],
        )
        self.assertNotEqual(code, 0, out)
        self.assertIn("T3-3", out)
        self.assertIn("@err, @inv", out)          # Go Half 残缺
        self.assertIn("@post", out)               # Kotlin HalfClient 残缺
        self.assertIn("server/multi/multi.go", out)
        self.assertIn("Multi.kt", out)

    def test_t3_4_consumes_drift_is_red(self):
        """T3-4 红测：@consumes 与 import 图不一致（声明了没 import + import 了
        没声明，Go/Kotlin 双侧）→ strict-t3 exit 1。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "consumes-drift"),
            ["--check", "--go-source", "--strict-t3"],
        )
        self.assertNotEqual(code, 0, out)
        self.assertIn("T3-4", out)
        self.assertIn("internal/config", out)          # Go 声明了没 import（drift 包）
        self.assertIn("import 了却未声明", out)          # 架构漂移方向
        self.assertIn("dev.agentmirror.fixture.ktused", out)  # Kotlin 侧
        self.assertIn("跨层声明一致", out)

    def test_pkg_dirty_is_red(self):
        """--pkg 单包硬判：指向 dirty 包必须红，且不扫到 clean 包。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "pkg-filter"),
            ["--check", "--go-source", "--strict-t3", "--pkg", "dirty"],
        )
        self.assertNotEqual(code, 0, out)
        self.assertIn("T3-1", out)
        self.assertIn("dirty", out)
        self.assertNotIn("server/clean", out)       # 精确到单包

    def test_pkg_ktdirty_is_red(self):
        """--pkg 单包硬判（Kotlin 侧）：指向 ktdirty 包必须红。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "pkg-filter"),
            ["--check", "--go-source", "--strict-t3",
             "--pkg", "dev.agentmirror.fixture.ktdirty"],
        )
        self.assertNotEqual(code, 0, out)
        self.assertIn("T3-1", out)
        self.assertIn("KotlinUndoc", out)


class TestPositiveControl(unittest.TestCase):
    """阳性对照：真实仓库必须绿；缺一条判据都算失败。"""

    def test_real_repo_check_passes(self):
        """真实仓库 --check → exit 0；T1 判据必须 PASS（阳性对照）。

        注意：T3 默认报告模式列清单、不改退出码，所以 T1 之外可能有 T3 FAIL 行
        （真仓库 18 包尚未刷注释），FAIL 断言只限定在 T1 行。
        """
        code, out = run_tool(ROOT, ["--check"])
        self.assertEqual(code, 0, out)
        self.assertIn("T1-1", out)
        self.assertIn("T1-2", out)
        self.assertIn("T3-1", out)
        self.assertIn("T3-2", out)
        t1_lines = [l for l in out.splitlines() if "T1-" in l]
        self.assertTrue(t1_lines, "缺少 T1 输出: " + out)
        for l in t1_lines:
            self.assertIn("PASS", l, "T1 判据必须保持绿: " + l)
        # 扫描量 > 0（阳性对照铁律）。
        m = [line for line in out.splitlines() if line.startswith("扫描") ]
        self.assertTrue(m, "缺少扫描量输出: " + out)
        self.assertNotIn("0 包", m[0], m[0])

    def test_real_repo_t3_report_nonempty_with_coverage(self):
        """阳性对照铁律：T3 报告非空 + 扫描量 > 0，防「没扫到」被当「很干净」。"""
        with tempfile.TemporaryDirectory() as tmp:
            report = os.path.join(tmp, "t3-report.md")
            code, out = run_tool(ROOT, ["--check", "--t3-report", report])
            self.assertEqual(code, 0, out)
            self.assertTrue(os.path.isfile(report), out)
            with open(report, encoding="utf-8") as fh:
                text = fh.read()
            self.assertIn("T3-1", text)
            self.assertIn("T3-2", text)
            self.assertIn("T3 扫描覆盖", text)
            self.assertIn("导出符号索引", text)
            self.assertIn("Go CLI flag 索引", text)

    def test_t3_1_documented_symbol_is_green(self):
        """T3-1 阳性对照：全符号有 doc → strict-t3 exit 0（防「没扫到」当「干净」）。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "documented-symbol"),
            ["--check", "--go-source", "--strict-t3"],
        )
        self.assertEqual(code, 0, out)
        self.assertIn("T3-1", out)
        self.assertIn("PASS", out)

    def test_t3_2_truthful_ref_is_green(self):
        """T3-2 阳性对照：doc/注释引用全真实 → strict-t3 exit 0（防「没扫到」当「干净」）。

        覆盖全部注释形态的真实引用（含函数体内 // 注释引用真实符号/路径/flag
        `--listen`）都不得误报——这是扩展扫描面后的"宁可漏不可吵"回归防线。
        """
        code, out = run_tool(
            os.path.join(TESTDATA, "truthful-ref"),
            ["--check", "--go-source", "--strict-t3"],
        )
        self.assertEqual(code, 0, out)
        self.assertIn("T3-2", out)
        self.assertIn("PASS", out)

    def test_t3_3_complete_contract_is_green(self):
        """T3-3 阳性对照：@contract 符号四标签齐全（含显式 none）→ strict-t3 exit 0
        （防「没扫到」当「干净」——fixture 里确有 @contract，判据必须 PASS 才算扫到）。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "contract-complete"),
            ["--check", "--go-source", "--strict-t3"],
        )
        self.assertEqual(code, 0, out)
        self.assertIn("T3-3", out)
        self.assertIn("PASS", out)

    def test_t3_4_consumes_consistent_is_green(self):
        """T3-4 阳性对照：@consumes 与 import 图一致 → strict-t3 exit 0
        （防「没扫到」当「干净」——fixture 里确有 @consumes，判据必须 PASS 才算扫到）。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "consumes-consistent"),
            ["--check", "--go-source", "--strict-t3"],
        )
        self.assertEqual(code, 0, out)
        self.assertIn("T3-4", out)
        self.assertIn("PASS", out)

    def test_real_repo_t3_report_has_contract_coverage_numbers(self):
        """那个 0 必须自证：真仓库报告含 @contract 符号总数 / @consumes 声明总数 /
        import 边数三项覆盖量数字。contract_symbols=0 是「真没有标注」的诚实呈现，
        其「判据真扫得到」已由 contract-incomplete/consumes-drift 两个必红 fixture 用
        同一 scan_t3 路径自证（本套件的红测用例）。"""
        with tempfile.TemporaryDirectory() as tmp:
            report = os.path.join(tmp, "t3-report.md")
            code, out = run_tool(ROOT, ["--check", "--t3-report", report])
            self.assertEqual(code, 0, out)
            with open(report, encoding="utf-8") as fh:
                text = fh.read()
            self.assertIn("@contract", text)
            self.assertIn("T3-3", text)
            self.assertIn("T3-4", text)
            # 三项覆盖量数字必须在报告里出现（含数值 0 的项）。
            self.assertRegex(text, r"@contract.*符号总数.*\|\s*\d+\s*\|")
            self.assertRegex(text, r"@consumes.*声明总数.*\|\s*\d+\s*\|")
            self.assertRegex(text, r"参与比对的 import 边数.*\|\s*\d+\s*\|")

    def test_pkg_clean_is_green(self):
        """--pkg 单包硬判阳性对照：指向 clean 包 → exit 0。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "pkg-filter"),
            ["--check", "--go-source", "--strict-t3", "--pkg", "clean"],
        )
        self.assertEqual(code, 0, out)

    def test_report_mode_does_not_affect_exit(self):
        """报告模式分级开关：有违规的 fixture 不带 --strict-t3 → 列清单但 exit 0。"""
        code, out = run_tool(
            os.path.join(TESTDATA, "missingdoc-symbol"),
            ["--check", "--go-source"],
        )
        self.assertEqual(code, 0, out)
        self.assertIn("T3-1", out)
        self.assertIn("FAIL", out)  # 清单仍如实列出违规
        self.assertIn("报告模式", out)


class TestPureFunctions(unittest.TestCase):
    """纯函数单测：环检测与首句提取（不依赖 CLI，快且隔离）。"""

    def test_find_cycle_detects_simple_cycle(self):
        import build_wiki
        graph = {"a": ["b"], "b": ["a"], "c": []}
        cyc = build_wiki.find_cycle(graph)
        self.assertIsNotNone(cyc)
        self.assertEqual(len(cyc) - 1, len(set(cyc)))  # 首尾重复 = 闭环

    def test_find_cycle_none_for_dag(self):
        import build_wiki
        graph = {"a": ["b", "c"], "b": ["c"], "c": [], "d": []}
        self.assertIsNone(build_wiki.find_cycle(graph))

    def test_first_sentence_cuts_at_punctuation(self):
        import build_wiki
        doc = "Package config loads settings.  It has no deps."
        self.assertEqual(build_wiki.first_sentence(doc), "Package config loads settings.")

    def test_go_package_comment_requires_adjacency(self):
        import build_wiki
        good = "// Package a does things.\npackage a\n"
        bad = "// unrelated\n\npackage a\n"
        self.assertEqual(build_wiki._go_package_comment(good, "a"), "Package a does things.")
        self.assertEqual(build_wiki._go_package_comment(bad, "a"), "")


class TestGeneration(unittest.TestCase):
    """生成物：写盘、头部标注、幂等。"""

    def _generate(self, out_dir):
        proc = subprocess.run(
            [sys.executable, SCRIPT, "--root", ROOT, "--out", out_dir],
            capture_output=True,
            text=True,
            env=SANITIZED_ENV,
        )
        return proc.returncode, proc.stdout + proc.stderr

    def test_generates_wiki_with_warning_header(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = self._generate(tmp)
            self.assertEqual(code, 0, out)
            path = os.path.join(tmp, "README.md")
            self.assertTrue(os.path.isfile(path), out)
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            self.assertIn("生成物，勿手改", text)
            self.assertIn("mermaid", text)
            self.assertIn("架构维基", text)

    def test_generation_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            code1, _ = self._generate(tmp)
            self.assertEqual(code1, 0)
            with open(os.path.join(tmp, "README.md"), encoding="utf-8") as fh:
                first = fh.read()
            code2, _ = self._generate(tmp)  # 覆盖重写
            self.assertEqual(code2, 0)
            with open(os.path.join(tmp, "README.md"), encoding="utf-8") as fh:
                second = fh.read()
            self.assertEqual(first, second, "重跑产生 diff → 生成不幂等")


if __name__ == "__main__":
    sys.exit(unittest.main(verbosity=2))
