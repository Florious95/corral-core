package dev.agentmirror.app.tsnet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** R2 key lifecycle: READY is stable until the next connection generation consumes staging. */
class TsnetKeyRotationTest {
    private class Backend : TsnetBackend {
        var starts = 0
        var closes = 0
        var lastKey: String? = null
        override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy {
            starts++
            lastKey = authKey
            return TsnetProxy("127.0.0.1", 40000 + starts, "cred")
        }
        override fun close() { closes++ }
    }

    private val direct = Executor { it.run() }

    @After
    fun tearDown() {
        TsnetWire.resetForTest()
    }

    @Test
    fun stagedKeyDoesNotInterruptReadyNodeUntilApplied() {
        val old = Backend()
        val next = Backend()
        val backends = ArrayDeque<TsnetBackend>(listOf(old, next))
        TsnetWire.environment = TsnetWire.Environment("test-state", "agentmirror-test")
        TsnetWire.backendFactory = { backends.removeFirst() }
        TsnetWire.executorForTest = direct

        TsnetWire.ensureStarted("tskey-old")
        TsnetWire.stagePendingKey("tskey-new")

        assertTrue(TsnetWire.state is TsnetState.Up)
        assertEquals(1, old.starts)
        assertEquals(0, old.closes)
        assertEquals(0, next.starts)
        assertEquals(null, next.lastKey)

        TsnetWire.applyPendingKey()

        assertEquals(1, old.closes)
        assertEquals(1, next.starts)
        assertEquals("tskey-new", next.lastKey)
        assertTrue(TsnetWire.state is TsnetState.Up)
    }

    @Test
    fun emptyKeyDisablesOldNodeOnlyAtNextGeneration() {
        val backend = Backend()
        TsnetWire.environment = TsnetWire.Environment("test-state", "agentmirror-test")
        TsnetWire.backendFactory = { backend }
        TsnetWire.executorForTest = direct

        TsnetWire.ensureStarted("tskey-old")
        TsnetWire.stagePendingKey("")
        assertTrue(TsnetWire.hasActiveNode())
        assertEquals(0, backend.closes)

        TsnetWire.applyPendingKey()

        assertEquals(1, backend.closes)
        assertFalse(TsnetWire.hasActiveNode())
        assertTrue(TsnetWire.state is TsnetState.Idle)
    }

    @Test
    fun latestStagedKeyWinsRapidSwitches() {
        val old = Backend()
        val final = Backend()
        val backends = ArrayDeque<TsnetBackend>(listOf(old, final))
        TsnetWire.environment = TsnetWire.Environment("test-state", "agentmirror-test")
        TsnetWire.backendFactory = { backends.removeFirst() }
        TsnetWire.executorForTest = direct

        TsnetWire.ensureStarted("tskey-old")
        TsnetWire.stagePendingKey("tskey-new")
        TsnetWire.stagePendingKey("")
        TsnetWire.stagePendingKey("tskey-final")
        TsnetWire.applyPendingKey()

        assertEquals(1, old.closes)
        assertEquals(1, final.starts)
        assertEquals("tskey-final", final.lastKey)
        assertTrue(TsnetWire.state is TsnetState.Up)
    }
}
