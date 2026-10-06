/* Copyright 2026 AgentMirror Project Authors
 * Licensed under the Apache License, Version 2.0.
 * See http://www.apache.org/licenses/LICENSE-2.0 for terms.
 */
package dev.agentmirror.app.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ConversationAnimationTest {
    @Test fun noToolPhaseAnimatesWhenNativeSessionIsIdle() {
        for (phase in ToolPhase.entries) {
            val tool = ToolCall("tool", "call", "read", phase = phase)
            assertFalse("idle $phase", tool.shouldAnimate(false))
        }
    }

    @Test fun onlyActualComposingAndRunningAnimateDuringAnActiveTurn() {
        for (phase in ToolPhase.entries) {
            val tool = ToolCall("tool", "call", "read", phase = phase)
            assertEquals("streaming $phase", phase == ToolPhase.Composing || phase == ToolPhase.Running, tool.shouldAnimate(true))
        }
    }

    @Test fun abortedHistoricalPendingStaysUnfinishedButStaticEvenDuringTheNextLiveTurn() {
        fun event(raw: String) = Json.parseToJsonElement(raw).jsonObject
        val idle = ConversationState().reset("owned-history", false)
            .apply(1, 1, event("""{"type":"message_end","message":{"role":"assistant","stopReason":"aborted","content":[{"type":"toolCall","id":"unfinished-owned-call","name":"read","arguments":{"path":"fixture.txt"}}]}}"""))
            .apply(2, 2, event("""{"type":"response","command":"get_state","success":true,"data":{"isStreaming":false,"isCompacting":false,"pendingMessageCount":0}}"""))
        val tool = idle.items.filterIsInstance<ToolCall>().single()
        assertFalse(idle.running)
        assertEquals(ToolPhase.Pending, tool.phase)
        assertFalse(tool.finished) // no fabricated completion/settle to suppress animation
        assertFalse(tool.shouldAnimate(idle.running))
        val live = idle.apply(3, 3, event("""{"type":"agent_start"}"""))
        assertTrue(live.running)
        assertFalse(live.items.filterIsInstance<ToolCall>().single().shouldAnimate(live.running))
    }
}
