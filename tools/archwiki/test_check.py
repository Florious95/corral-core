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


class TestPositiveControl(unittest.TestCase):
    """阳性对照：真实仓库必须绿；缺一条判据都算失败。"""

    def test_real_repo_check_passes(self):
        """真实仓库 --check → exit 0，且打印非零扫描量（空扫描≠健康）。"""
        code, out = run_tool(ROOT, ["--check"])
        self.assertEqual(code, 0, out)
        self.assertIn("T1-1", out)
        self.assertIn("T1-2", out)
        self.assertNotIn("FAIL", out)
        # 扫描量 > 0（阳性对照铁律）。
        m = [line for line in out.splitlines() if line.startswith("扫描") ]
        self.assertTrue(m, "缺少扫描量输出: " + out)
        self.assertNotIn("0 包", m[0], m[0])


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
