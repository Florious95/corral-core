package dev.agentmirror.app.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class OverflowSheetsTest {
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    // ---- action registry ----

    @Test
    fun historyIsAlwaysFirstAndUnsupportedActionsAreHiddenNotFaked() {
        val pi = resolveActions("pi", connected = true, restoring = false, compacting = false)
        assertEquals(ConversationAction.History, pi.first().action)
        assertEquals(
            listOf(ConversationAction.History, ConversationAction.Compact, ConversationAction.NewSession, ConversationAction.Export, ConversationAction.Usage, ConversationAction.Terminal),
            pi.map { it.action },
        )
        assertTrue(pi.all { it.enabled })

        val grok = resolveActions("grok", connected = true, restoring = false, compacting = false)
        assertEquals(ConversationAction.History, grok.first().action)
        assertTrue("native list/load history is connected", grok.first().enabled)
        assertTrue(grok.any { it.action == ConversationAction.Usage && it.enabled })
        assertTrue(grok.any { it.action == ConversationAction.Export && it.enabled })
        val unknown = resolveActions("unknown", connected = true, restoring = false, compacting = false)
        assertFalse("unsupported history stays visible with a reason", unknown.first().enabled)
        assertFalse(unknown.any { it.action == ConversationAction.Usage || it.action == ConversationAction.Export })
        assertFalse(AgentAbilities.of("grok").compactInstructions)
    }

    @Test
    fun offlineAndRestoringDisableWithAReason() {
        val offline = resolveActions("pi", connected = false, restoring = false, compacting = false)
        assertTrue(offline.none { it.enabled })
        assertTrue(offline.all { it.detail == "连接后可用" })
        val restoring = resolveActions("pi", connected = false, restoring = true, compacting = false)
        assertTrue(restoring.all { it.detail == "恢复历史完成后可用" })
    }

    // ---- compaction ----

    @Test
    fun compactPhaseFollowsFactsNotTimers() {
        val run = CompactRun(id = 1, baseline = 5)
        assertEquals(CompactPhase.Configure, compactPhase(null, false, null))
        assertEquals(CompactPhase.Submitting, compactPhase(run, false, null))
        // Pi answers only after compacting; a 15 s timeout while it runs is not a failure.
        assertEquals(CompactPhase.Running, compactPhase(run.copy(replied = false, reason = "The host did not confirm in time"), true, null))
        val done = CompactionOutcome(seq = 9, before = 21_630, after = 7_222, error = null, aborted = false)
        assertEquals(CompactPhase.Done(done), compactPhase(run.copy(replied = false), false, done))
        // An outcome that predates the request is not this request's result.
        val old = done.copy(seq = 5)
        assertEquals(CompactPhase.Rejected("Nothing to compact"), compactPhase(run.copy(replied = false, reason = "Nothing to compact"), false, old))
        assertEquals(CompactPhase.Accepted, compactPhase(run.copy(replied = true), false, old))
        // Compaction started from the composer still shows as running.
        assertEquals(CompactPhase.Running, compactPhase(null, true, null))
    }

    @Test
    fun savingIsOnlyStatedFromComparableEstimates() {
        assertEquals("估算减少约 67%", compactionDelta(21_630, 7_222))
        assertEquals("估算未减少", compactionDelta(1_000, 1_000))
        assertEquals("估算未减少", compactionDelta(1_000, 1_200))
        assertNull(compactionDelta(null, 7_222))
        assertNull(compactionDelta(0, 0))
    }

    @Test
    fun compactionEndRecordsOutcomeAndHonestNotice() {
        val state = ConversationState()
            .apply(1, 1, json("""{"type":"compaction_start","reason":"manual"}"""))
            .apply(2, 2, json("""{"type":"compaction_end","result":{"summary":"s","tokensBefore":21630,"estimatedTokensAfter":7222}}"""))
        assertFalse(state.compacting)
        assertEquals(CompactionOutcome(2, 21_630, 7_222, null, false), state.compaction)
        val notice = state.items.filterIsInstance<Notice>().single()
        assertEquals("上下文已压缩", notice.title)
        assertEquals("21k → 7k tokens · 估算减少约 67%", notice.detail)

        val failed = ConversationState().apply(3, 3, json("""{"type":"compaction_end","errorMessage":"Already compacted"}""")).compaction!!
        assertEquals("Already compacted", failed.error)
        val aborted = ConversationState().apply(4, 4, json("""{"type":"compaction_end","aborted":true}""")).compaction!!
        assertTrue(aborted.aborted)
        assertNull(aborted.error)
    }

    @Test
    fun nativeSlashCompletionShowsFeedbackWithoutPromisingCompression() {
        val state = ConversationState()
            .apply(1, 1, json("""{"type":"compaction_start"}"""))
            .apply(2, 2, json("""{"type":"compaction_end","result":{"commandOnly":true,"summary":"No compaction needed"}}"""))
        assertFalse(state.compacting)
        assertTrue(state.compaction!!.commandOnly)
        assertEquals("No compaction needed", state.compaction!!.feedback)
        assertNull(state.compaction!!.before)
        assertEquals("原生压缩命令已完成", state.items.filterIsInstance<Notice>().single().title)
        assertEquals("No compaction needed", state.items.filterIsInstance<Notice>().single().detail)
    }

    // ---- usage ----

    @Test
    fun statsKeepAbsentApartFromZeroAndDeriveOnlyFromOneSnapshot() {
        val s = usageSnapshot(json("""{"sessionId":"sid","userMessages":1,"assistantMessages":1,"toolCalls":1,"toolResults":1,"totalMessages":3,
            "tokens":{"input":1016,"output":228,"cacheRead":340,"cacheWrite":452,"total":2036},"cost":0.02036,
            "contextUsage":{"tokens":1916,"contextWindow":128000,"percent":1.496875}}"""), 7)
        assertEquals(1_808L, s.promptTokens)
        assertEquals(18.8, s.cacheHitPercent!!, 0.05)
        assertEquals(1.496875, s.contextPercent!!, 1e-9)
        assertEquals("USD 0.02036", usdText(s.cost))
        assertEquals("1,916", groupedCount(s.contextTokens!!))

        val compacted = usageSnapshot(json("""{"sessionId":"sid","tokens":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0},"cost":0,
            "contextUsage":{"tokens":null,"contextWindow":128000,"percent":null}}"""), 8)
        assertTrue(compacted.hasContext)
        assertNull("post-compaction context is unknown, not 0%", compacted.contextPercent)
        assertNull("no input yet: no hit ratio", compacted.cacheHitPercent)
        assertEquals("USD 0", usdText(compacted.cost))

        val bare = usageSnapshot(json("""{"sessionId":"sid"}"""), 9)
        assertFalse(bare.hasContext)
        assertNull(bare.promptTokens)
        assertNull(usdText(bare.cost))
    }

    @Test
    fun grokContextSnapshotDoesNotInventCumulativeUsage() {
        val s = usageSnapshot(json("""{"sessionId":"grok-sid","source":"native_slash","modelName":"grok-4.6","turnCount":0,
            "contextUsage":{"tokens":1359,"contextWindow":500000,"percent":0.2718}}"""), 10)
        assertTrue(s.nativeSlash)
        assertEquals("grok-4.6", s.modelName)
        assertEquals(0L, s.turnCount)
        assertEquals(1_359L, s.contextTokens)
        assertEquals(500_000L, s.contextWindow)
        assertNull(s.promptTokens)
        assertNull(s.cost)
        assertNull(s.cacheHitPercent)
        val unknown = usageSnapshot(json("""{"sessionId":"grok-sid","source":"native_slash"}"""), 11)
        assertNull(unknown.turnCount)
        assertFalse(unknown.hasContext)
    }

    @Test
    fun moneyAndPercentNeverInventPrecision() {
        assertEquals("< USD 0.0001", usdText("0.00002"))
        assertEquals("USD 12.35", usdText("12.3456"))
        assertEquals("USD 0.1235", usdText("0.123456"))
        assertNull(usdText("not-a-number"))
        assertEquals("< 0.1%", percentText(0.04))
        assertEquals("1.5%", percentText(1.496875))
        assertEquals("120.0%", percentText(120.0))
    }

    // ---- history ----

    @Test
    fun relativeTimeReadsLikeAHuman() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis
        val now = at(2026, 10, 6, 15, 0)
        assertEquals("刚刚", relativeTime(now - 20_000, now, zone))
        assertEquals("12 分钟前", relativeTime(now - 12 * 60_000, now, zone))
        assertEquals("今天 09:05", relativeTime(at(2026, 10, 6, 9, 5), now, zone))
        assertEquals("昨天 23:59", relativeTime(at(2026, 10, 5, 23, 59), now, zone))
        assertEquals("10月3日 18:40", relativeTime(at(2026, 10, 3, 18, 40), now, zone))
        assertEquals("2025年12月1日", relativeTime(at(2025, 12, 1, 8, 0), now, zone))
        assertEquals("", relativeTime(0, now, zone))
    }

    @Test
    fun historyExcerptNeverRepeatsTheTitle() {
        assertEquals("first", SessionHistoryChoice("a", "Named", "first", 1, false).excerpt)
        assertNull(SessionHistoryChoice("b", "", "first", 1, false).excerpt)
        assertNull(SessionHistoryChoice("c", "same", "same", 1, false).excerpt)
    }
}
