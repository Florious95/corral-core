/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.overlay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 066：读 frames.jsonl（t.probe2）或最小夹具，逐帧喂真实 [OverlayEmulator]。
 */
class OverlayEmulatorFixtureTest {

    @Test
    fun fixtureFramesRenderWithoutCsiAppendOrScratch() {
        val frames = loadFrames()
        assertTrue("夹具至少 1 帧，got=${frames.size}", frames.isNotEmpty())

        val emu = OverlayEmulator(80, 24)
        var shown = ""
        var rows = 24
        for (frame in frames) {
            rows = if (frame.rows > 0) frame.rows else 24
            val cols = if (frame.cols > 0) frame.cols else 80
            emu.resize(cols, rows)
            emu.feed(frame.text)
            shown = dropScratchLines(emu.plainText())
        }

        for (bad in listOf("[?1049", "[?1049h", "[K", "(B[m", "[30m", "[43m", "\u001b[", "[H", "[2J")) {
            assertFalse("① 不得含裸控制序列 $bad，got=$shown", shown.contains(bad))
        }

        val raw = frames.joinToString("\n") { it.text }
        val target = TARGETS.firstOrNull { raw.contains(it) }
        if (target != null) {
            assertTrue("② 目标会话 $target 必须在，got=$shown", shown.contains(target))
        } else {
            assertTrue("② 渲染结果不能空，got=$shown", shown.isNotBlank())
        }
        for (tok in listOf("am-overlay", "ov-spin")) {
            assertFalse("② scratch $tok 不得在，got=$shown", shown.contains(tok))
        }
        assertFalse("② scratch tree 不得在，got=$shown", shown.contains("tree"))
        assertFalse("② scratch sleep 不得在，got=$shown", shown.contains("sleep"))

        val lines = if (shown.isEmpty()) 0 else shown.lines().size
        assertTrue("③ 行数有界 ≤ $rows，got=$lines text=$shown", lines <= rows)
        val branchCounts = Regex("├─[^\\n]+").findAll(shown)
            .groupingBy { it.value }
            .eachCount()
        assertTrue(
            "③ 同一棵树不得重复堆叠，got=$shown counts=$branchCounts",
            branchCounts.values.all { it == 1 },
        )
    }

    private data class Frame(val text: String, val rows: Int, val cols: Int)

    private fun loadFrames(): List<Frame> {
        val probe = File(repoRoot(), ".team/nodes/ov2-probe/frames.jsonl")
        val source: String = when {
            probe.isFile && probe.length() > 0L -> probe.readText(Charsets.UTF_8)
            else -> {
                val stream = checkNotNull(javaClass.getResourceAsStream("/overlay/frames.min.jsonl")) {
                    "缺夹具：ov2-probe/frames.jsonl 与 /overlay/frames.min.jsonl 都不在"
                }
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
        return source.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseFrame(it) }
            .filter { it.text.isNotEmpty() }
            .toList()
    }

    private fun parseFrame(line: String): Frame {
        val obj = Json.parseToJsonElement(line).jsonObject
        val src = obj["payload"]?.jsonObject ?: obj
        val text = src["text"]?.jsonPrimitive?.content
            ?: obj["text"]?.jsonPrimitive?.content
            ?: ""
        val rows = src["rows"]?.jsonPrimitive?.intOrNull
            ?: obj["rows"]?.jsonPrimitive?.intOrNull
            ?: 24
        val cols = src["cols"]?.jsonPrimitive?.intOrNull
            ?: obj["cols"]?.jsonPrimitive?.intOrNull
            ?: 80
        return Frame(text = text, rows = rows, cols = cols)
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            if (File(dir, "taskbook.yaml").isFile || File(dir, ".team/nodes").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return File(System.getProperty("user.dir"))
        }
        return File(System.getProperty("user.dir"))
    }

    private companion object {
        val TARGETS = listOf(
            "alpha-ov2", "sess-user", "sess-aaa", "ovp", "claude", "中文目录",
        )
    }
}
