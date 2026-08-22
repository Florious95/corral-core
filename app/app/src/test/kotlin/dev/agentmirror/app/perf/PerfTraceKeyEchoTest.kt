/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
import java.util.concurrent.atomic.AtomicLong

/**
 * 按键回显量具：key_send / key_echo 必须凭 (seq, char) 配对；关时零行。
 */
class PerfTraceKeyEchoTest {

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
    fun keyEcho_关闭时零行() {
        PerfTrace.setEnabledForTest(false)
        assertFalse(PerfTrace.isEnabled())
        PerfTrace.keySend("s1", "a")
        PerfTrace.notePrintableEcho('a')
        PerfTrace.flushKeyEchoAfterDraw("s1")
        assertEquals("关时零行 actual=${captured.toList()}", 0, captured.size)
    }

    @Test
    fun keyEcho_同字符FIFO配对且带seq() {
        PerfTrace.bind("s1", "oid1")
        PerfTrace.keySend("s1", "a")
        PerfTrace.keySend("s1", "b")
        PerfTrace.notePrintableEcho('a')
        PerfTrace.notePrintableEcho('b')
        PerfTrace.flushKeyEchoAfterDraw("s1")
        val lines = captured.map { it.second }
        val sendA = lines.first { it.contains("ev=key_send") && it.contains("char=a") }
        val echoA = lines.first { it.contains("ev=key_echo") && it.contains("char=a") }
        val seqA = Regex("""seq=(\d+)""").find(sendA)!!.groupValues[1]
        assertTrue("echo 必须带同一 seq=$seqA：echoA=$echoA", echoA.contains("seq=$seqA"))
        val tSend = Regex("""t=(\d+)""").find(sendA)!!.groupValues[1].toLong()
        val tEcho = Regex("""t=(\d+)""").find(echoA)!!.groupValues[1].toLong()
        assertTrue("echo 必须晚于 send：$tSend $tEcho", tEcho > tSend)
    }

    @Test
    fun keyEcho_快照字母不配对不发echo() {
        PerfTrace.bind("s1", "oid1")
        PerfTrace.notePrintableEcho('z')
        PerfTrace.flushKeyEchoAfterDraw("s1")
        assertTrue(
            "没有 key_send 时快照字母不得发 key_echo actual=${captured.toList()}",
            captured.none { it.second.contains("ev=key_echo") },
        )
    }

    @Test
    fun keyEcho_非az单字符不发send() {
        PerfTrace.bind("s1", "oid1")
        PerfTrace.keySend("s1", "A")
        PerfTrace.keySend("s1", "hello")
        PerfTrace.keySend("s1", "")
        assertTrue(
            "非 a-z 单字符不发 key_send actual=${captured.toList()}",
            captured.none { it.second.contains("ev=key_send") },
        )
    }
}
