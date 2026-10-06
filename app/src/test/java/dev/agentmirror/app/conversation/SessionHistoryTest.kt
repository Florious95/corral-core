package dev.agentmirror.app.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryTest {
    private fun event(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun catalogRetainsNativeIdTitleFirstSentenceAndActivity() {
        val rows = sessionHistoryChoices(event("""{"sessions":[
            {"session_id":"a","name":"Named history","first_message":"first sentence","modified_ms":123,"current":true},
            {"session_id":"b","first_message":"Unnamed first sentence","modified_ms":456,"current":false}
        ]}"""))
        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertEquals("Named history", rows[0].title)
        assertEquals("first sentence", rows[0].firstMessage)
        assertEquals(123L, rows[0].modifiedMs)
        assertTrue(rows[0].current)
        assertEquals("Unnamed first sentence", rows[1].title)
        assertFalse(rows[1].current)
    }

    @Test
    fun nativeHistoryReplacementDropsOldEchoesAndReplaysOriginalThoughtToolAndUserTurns() {
        var state = ConversationState(sessionName = "Old title").withLocalEcho("old", "unsent old context", 0, 1)
            .reset("target-stream", false)
            .apply(10, 10, event("""{"type":"session_reset","replace":true,"sessionId":"target"}"""))
        assertTrue(state.items.isEmpty())
        assertEquals("target", state.sessionId)
        state = state.apply(11, 11, event("""{"type":"message_start","message":{"role":"user","content":"first original user","timestamp":1}}"""))
            .apply(12, 12, event("""{"type":"message_start","message":{"role":"user","content":"second original user","timestamp":1}}"""))
            .apply(13, 13, event("""{"type":"message_end","message":{"role":"assistant","content":[
                {"type":"thinking","thinking":"original thought"},
                {"type":"toolCall","id":"call","name":"read","arguments":{"path":"file.txt"}}
            ]}}"""))
            .apply(14, 14, event("""{"type":"tool_execution_end","toolCallId":"call","toolName":"read","isError":false,"result":{"content":[{"type":"text","text":"original tool output"}]}}"""))
            .apply(15, 15, event("""{"type":"response","command":"get_state","success":true,"data":{"sessionId":"target","sessionName":"Selected history","isStreaming":false}}"""))
        assertEquals(listOf("first original user", "second original user"), state.items.filterIsInstance<UserTurn>().map { it.text })
        assertEquals("original thought", state.items.filterIsInstance<Reasoning>().single().text)
        val tool = state.items.filterIsInstance<ToolCall>().single()
        assertEquals("original tool output", tool.output)
        assertEquals(ToolPhase.Succeeded, tool.phase)
        assertEquals("Selected history", state.sessionName)
        assertEquals("target", state.sessionId)
        assertEquals("target-stream", state.stream)
        assertEquals(15L, state.lastSeq)
        assertFalse(state.running)
    }

    @Test
    fun boundedHistoryDisclosureSurvivesResetAndDoesNotInventFullReplay() {
        val state = ConversationState().reset("large", true)
            .apply(1, 1, event("""{"type":"session_reset","replace":true,"sessionId":"large-id"}"""))
            .apply(2, 2, event("""{"type":"history_window","loaded_items":1500,"older_omitted":true,"content_clipped":true}"""))
        assertTrue(state.historyTruncated)
        assertEquals(1500, state.historyLoadedItems)
        assertTrue(state.historyContentClipped)
        val normal = ConversationState().apply(1, 1, event("""{"type":"history_window","loaded_items":10,"older_omitted":false,"content_clipped":false}"""))
        assertFalse(normal.historyTruncated)
        assertFalse(normal.historyContentClipped)
    }
}
