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

package dev.agentmirror.app.perf

import dev.agentmirror.app.diag.DiagLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * PerfTrace 链路红测（任务一 t.red）。方法名即契约，⛔ 不许改名。
 *
 * 先验红：骨架不发日志 ⇒ 开路径的两条必须 FAILED；关路径零行与骨架碰巧同形，
 * 允许绿（实现后仍须绿）。
 */
class PerfTraceChainTest {

    private val captured = ConcurrentLinkedQueue<Pair<String, String>>()
    private val clockMs = AtomicLong(1_000L)

    @Before
    fun setUp() {
        DiagLog.resetForTest()
        PerfTrace.resetForTest()
        captured.clear()
        clockMs.set(1_000L)
        PerfTrace.setClockForTest(DiagLog.Clock { clockMs.getAndAdd(10L) })
        PerfTrace.setSinkForTest(PerfTrace.Sink { tag, line -> captured.add(tag to line) })
    }

    @After
    fun tearDown() {
        PerfTrace.resetForTest()
        DiagLog.resetForTest()
    }

    @Test
    fun perfTrace_关闭时零行() {
        PerfTrace.setEnabledForTest(false)
        assertFalse("关开关后 isEnabled 必须为 false", PerfTrace.isEnabled())

        val openId = PerfTrace.beginOpen()
        emitFullChain(
            openId = openId,
            rows = 24,
            cols = 80,
            kind = "snapshot",
            bytes = 128,
            seq = 7L,
            alt = 1,
            glyphs = 42,
            quietMs = PerfTrace.LAYOUT_SETTLED_QUIET_MS,
            lastReflowSrc = "resize",
        )

        assertEquals(
            "关时假出口必须零行，实际=${captured.toList()}",
            0,
            captured.size,
        )
        val diag = DiagLog.snapshotForTest().filter { it.contains("[${PerfTrace.TAG}]") }
        assertTrue("关时 DiagLog 不得出现 PerfTrace 行，实际=$diag", diag.isEmpty())
    }

    @Test
    fun perfTrace_一次打开产出八事件且open_id一致且时间单调() {
        assertTrue("默认必须开", PerfTrace.isEnabled())

        val openId = PerfTrace.beginOpen()
        assertTrue("beginOpen 必须给出非空 open_id，实际='$openId'", openId.isNotBlank())

        emitFullChain(
            openId = openId,
            rows = 24,
            cols = 80,
            kind = "snapshot",
            bytes = 128,
            seq = 7L,
            alt = 1,
            glyphs = 42,
            quietMs = PerfTrace.LAYOUT_SETTLED_QUIET_MS,
            lastReflowSrc = "resize",
        )

        val rows = captured.toList()
        assertTrue(
            "每条必须 tag=${PerfTrace.TAG}，实际=$rows",
            rows.all { it.first == PerfTrace.TAG },
        )
        val lines = rows.map { it.second }
        assertEquals("一次打开必须恰好 8 行，实际=$lines", 8, lines.size)

        val parsed = lines.map { parseKv(it) }
        parsed.forEachIndexed { i, kv ->
            val keys = keyOrder(lines[i])
            assertEquals(
                "行 $i 首三字段必须是 open_id ev t，实际 keys=$keys line=${lines[i]}",
                listOf("open_id", "ev", "t"),
                keys.take(3),
            )
            assertEquals("行 $i open_id 必须贯穿 beginOpen 的值", openId, kv["open_id"])
        }

        val evs = parsed.map { it["ev"] }
        assertEquals(
            "八事件名与顺序即契约",
            listOf(
                "tap",
                "route_enter",
                "subscribe_sent",
                "geom_seed",
                "first_frame_recv",
                "snapshot_applied",
                "first_draw",
                "layout_settled",
            ),
            evs,
        )

        val times = parsed.map { kv ->
            val raw = kv["t"]
            assertTrue("t 必须是毫秒整数，实际='$raw' line=${kv}", raw != null && raw.toLongOrNull() != null)
            raw!!.toLong()
        }
        assertEquals("第一条 t 必须取自注入时钟的初值", 1_000L, times.first())
        for (i in 1 until times.size) {
            assertTrue(
                "t 必须单调递增 times=$times",
                times[i] > times[i - 1],
            )
        }

        assertEquals("24", parsed[3]["rows"])
        assertEquals("80", parsed[3]["cols"])
        assertEquals("snapshot", parsed[4]["kind"])
        assertEquals("128", parsed[4]["bytes"])
        assertEquals("7", parsed[5]["seq"])
        assertEquals("1", parsed[5]["alt"])
        assertEquals("42", parsed[6]["glyphs"])
        assertEquals(PerfTrace.LAYOUT_SETTLED_QUIET_MS.toString(), parsed[7]["quiet_ms"])
        assertEquals("resize", parsed[7]["last_reflow_src"])
    }

    @Test
    fun perfTrace_两次并发打开open_id不串() {
        val barrier = CyclicBarrier(2)
        val done = CountDownLatch(2)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val openIds = arrayOfNulls<String>(2)

        fun worker(slot: Int, rows: Int, seq: Long) {
            Thread {
                try {
                    barrier.await(5, TimeUnit.SECONDS)
                    val id = PerfTrace.beginOpen()
                    openIds[slot] = id
                    emitFullChain(
                        openId = id,
                        rows = rows,
                        cols = 80,
                        kind = "snapshot",
                        bytes = 64 + slot,
                        seq = seq,
                        alt = slot,
                        glyphs = 10 + slot,
                        quietMs = PerfTrace.LAYOUT_SETTLED_QUIET_MS,
                        lastReflowSrc = "reflow-$slot",
                    )
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    done.countDown()
                }
            }.start()
        }

        worker(0, rows = 24, seq = 1L)
        worker(1, rows = 30, seq = 2L)
        assertTrue("两线程必须在 5s 内结束", done.await(5, TimeUnit.SECONDS))
        assertTrue("并发线程异常 $errors", errors.isEmpty())

        val idA = openIds[0]
        val idB = openIds[1]
        assertTrue("两次 beginOpen 都必须给出非空 open_id a='$idA' b='$idB'", !idA.isNullOrBlank() && !idB.isNullOrBlank())
        assertTrue("两次打开的 open_id 不得相同 a=$idA", idA != idB)

        val lines = captured.map { it.second }
        assertEquals("两次打开合计 16 行，实际=$lines", 16, lines.size)

        val byId = lines.groupBy { parseKv(it)["open_id"] }
        assertEquals("日志里必须恰好两个 open_id，实际=${byId.keys}", 2, byId.size)
        assertTrue("日志必须含 a=$idA", byId.containsKey(idA))
        assertTrue("日志必须含 b=$idB", byId.containsKey(idB))

        byId.forEach { (id, evLines) ->
            assertEquals("open_id=$id 必须 8 行，实际=$evLines", 8, evLines.size)
            val evs = evLines.map { parseKv(it)["ev"] }
            assertEquals(
                "同一 open_id 的八事件不得串到另一条打开 id=$id evs=$evs",
                listOf(
                    "tap",
                    "route_enter",
                    "subscribe_sent",
                    "geom_seed",
                    "first_frame_recv",
                    "snapshot_applied",
                    "first_draw",
                    "layout_settled",
                ).toSet(),
                evs.toSet(),
            )
            evLines.forEach { line ->
                assertEquals("行内 open_id 不得串", id, parseKv(line)["open_id"])
            }
        }
    }

    private fun emitFullChain(
        openId: String,
        rows: Int,
        cols: Int,
        kind: String,
        bytes: Int,
        seq: Long,
        alt: Int,
        glyphs: Int,
        quietMs: Int,
        lastReflowSrc: String,
    ) {
        PerfTrace.tap(openId)
        PerfTrace.routeEnter(openId)
        PerfTrace.subscribeSent(openId)
        PerfTrace.geomSeed(openId, rows, cols)
        PerfTrace.firstFrameRecv(openId, kind, bytes)
        PerfTrace.snapshotApplied(openId, seq, alt)
        PerfTrace.firstDraw(openId, glyphs)
        PerfTrace.layoutSettled(openId, quietMs, lastReflowSrc)
    }

    private fun parseKv(line: String): Map<String, String> =
        line.trim().split(Regex("\\s+")).mapNotNull { tok ->
            val i = tok.indexOf('=')
            if (i <= 0) null else tok.substring(0, i) to tok.substring(i + 1)
        }.toMap()

    private fun keyOrder(line: String): List<String> =
        line.trim().split(Regex("\\s+")).map { it.substringBefore('=') }
}
