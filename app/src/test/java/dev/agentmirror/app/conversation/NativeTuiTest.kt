package dev.agentmirror.app.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class NativeTuiTest {
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    @Test fun newAndResumeTitleAreBoundToSessionIdentity() {
        val old = ConversationState(sessionId = "old", sessionName = "OLD TITLE")
        val fresh = old.apply(1, 1, json("""{"type":"session_reset","sessionId":"new","sessionName":"","replace":true}"""))
        assertEquals("new", fresh.sessionId); assertNull(fresh.sessionName)
        val stale = fresh.apply(2, 2, json("""{"type":"response","command":"get_state","success":true,"data":{"sessionId":"old","sessionName":"POLLUTING TITLE"}}"""))
        assertEquals("new", stale.sessionId); assertNull(stale.sessionName)
        val restored = stale.apply(3, 3, json("""{"type":"session_reset","sessionId":"target","sessionName":"TARGET TITLE","replace":true}"""))
        assertEquals("TARGET TITLE", restored.sessionName)
        val ignored = restored.apply(4, 4, json("""{"type":"session_info_changed","sessionId":"old","name":"OLD TITLE"}"""))
        assertEquals("TARGET TITLE", ignored.sessionName)
        val renamed = ignored.apply(5, 5, json("""{"type":"session_info_changed","sessionId":"target","name":"RENAMED"}"""))
        assertEquals("RENAMED", renamed.sessionName)
    }
    @Test fun dialogsAreInteractiveBoundedAndDoNotDefaultApprove() {
        var state = ConversationState(sessionId = "sid")
        val token = "0123456789abcdef0123456789abcdef"
        state = state.apply(1, 1, json("""{"type":"extension_ui_request","id":"$token","method":"confirm","message":"real string message","sessionId":"sid","expiresAt":9999999999999}"""))
        assertEquals(1, state.interactions.size); assertEquals("real string message", state.interactions.single().message)
        assertTrue(state.items.isEmpty())
        state = state.apply(2, 2, json("""{"type":"interaction_resolved","id":"$token","reason":"决定已提交；不代表工具已执行"}"""))
        assertTrue(state.interactions.isEmpty()); assertFalse(state.running)
        val other = state.apply(3, 3, json("""{"type":"extension_ui_request","id":"$token","method":"confirm","sessionId":"other","expiresAt":9999999999999}"""))
        assertTrue(other.interactions.isEmpty())
    }
    @Test fun decisionExpiryUsesHostClockNotPhoneWallClock() {
        assertEquals(10000L, nativeInteractionRemainingMs(130000L, 100000L, 20000L))
        assertEquals(30000L, nativeInteractionRemainingMs(130000L, 200000L, -100000L))
        assertEquals(0L, nativeInteractionRemainingMs(130000L, 200000L, 0L))
    }
    @Test fun permissionOptionsKeepNativeOpaqueIdsAndOrder() {
        val token = "0123456789abcdef0123456789abcdef"
        val request = nativeInteraction(json("""{"id":"$token","method":"permission","expiresAt":100,"options":[{"optionId":"always-native","name":"Full native scope","kind":"allow_always"},{"optionId":"deny-id","name":"Reject","kind":"reject_once"}]}"""))!!
        assertEquals("always-native", request.options[0].id); assertEquals("allow_always", request.options[0].kind)
        assertEquals("deny-id", request.options[1].id)
    }
    @Test fun workflowAndGoalUseDifferentBudgetsAndExactSyntax() {
        assertEquals("/workflow review --agent-budget 256 {\"target\":\"main\"}", nativeTaskCommand("workflow", "start", "review", "256", """{"target":"main"}"""))
        assertEquals("/workflow stop review-2", nativeTaskCommand("workflow", "stop", "review-2", "", ""))
        assertEquals("/workflow runs", nativeTaskCommand("workflow", "runs", "", "", ""))
        assertEquals("/goal Fix current task --budget 10000", nativeTaskCommand("goal", "start", "Fix current task", "10000", ""))
        assertEquals("/goal status", nativeTaskCommand("goal", "status", "", "", ""))
        assertEquals("/deep-research native query", nativeTaskCommand("deep-research", "start", "native query", "", ""))
    }
    @Test fun workflowRejectsBudgetConflictsInvalidJsonAndEmptyHandles() {
        for ((budget, args) in listOf("0" to "", "1025" to "", "256" to """{"agent_budget":32}""", "" to "[1]")) {
            assertThrows(IllegalArgumentException::class.java) { nativeTaskCommand("workflow", "start", "review", budget, args) }
        }
        assertThrows(IllegalArgumentException::class.java) { nativeTaskCommand("workflow", "stop", "", "", "") }
        assertThrows(IllegalArgumentException::class.java) { nativeTaskCommand("goal", "start", "", "", "") }
    }
    @Test fun extensionTitleNeverBecomesSessionTitleAndEditorPreservesNewlines() {
        val state = ConversationState(sessionId = "sid", sessionName = "Trusted title")
            .apply(1, 1, json("""{"type":"extension_ui_request","method":"setTitle","title":"Extension chrome"}"""))
            .apply(2, 2, json("""{"type":"extension_ui_request","method":"set_editor_text","text":"Line 1\nLine 2"}"""))
        assertEquals("Trusted title", state.sessionName); assertEquals("Extension chrome", state.extensionTitle)
        assertEquals("Line 1\nLine 2", state.extensionEditor!!.second)
    }
}
