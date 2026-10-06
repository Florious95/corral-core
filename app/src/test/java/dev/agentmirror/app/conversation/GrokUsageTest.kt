package dev.agentmirror.app.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GrokUsageTest {
    @Test
    fun nativeCliTotalsDoNotDoubleCountCacheOrInventCostAndHitRate() {
        val data = Json.parseToJsonElement("""{"sessionId":"s","agentProvider":"grok","source":"native_cli+native_slash","tokens":{"input":42,"output":20,"cacheRead":15,"cacheWrite":5,"reasoning":3,"total":62},"turnCount":1,"modelCalls":2,"grokUsage":{"sessionId":"s","session":{"modelUsage":{"grok-4.7":{"totalTokens":62}}},"turns":[{"turnNumber":1,"totalTokens":62}]},"contextUsage":{"tokens":1359,"contextWindow":256000}}""").jsonObject
        val s = usageSnapshot(data, 1)
        assertEquals(42L, s.promptTokens)
        assertEquals(62L, s.total)
        assertEquals(1359L, s.contextTokens)
        assertEquals(3L, s.reasoning)
        assertEquals(2L, s.modelCalls)
        assertEquals(1L, s.turnCount)
        assertNotNull(s.nativeUsage)
        assertNull(s.cacheHitPercent)
        assertNull(s.cost)
    }

    @Test
    fun nativeCostTicksDecimalSurvivesProjectionWithoutFloatRounding() {
        val s = usageSnapshot(Json.parseToJsonElement("""{"sessionId":"s","agentProvider":"grok","source":"native_cli+native_slash","cost":0.0277705200,"grokUsage":{"session":{"costUsdTicks":277705200}}}""").jsonObject, 1)
        assertEquals("0.0277705200", s.cost)
        assertEquals(277705200L, s.nativeUsage!!.obj("session")!!.long("costUsdTicks"))
    }

    @Test
    fun nativeCliFailureIsVisibleAndNeverLooksLikeZeroConsumption() {
        val s = usageSnapshot(Json.parseToJsonElement("""{"sessionId":"s","agentProvider":"grok","usageError":"No native record"}""").jsonObject, 1)
        assertEquals("No native record", s.usageError)
        assertNull(s.promptTokens)
        assertNull(s.total)
        assertNull(s.cost)
    }
}
