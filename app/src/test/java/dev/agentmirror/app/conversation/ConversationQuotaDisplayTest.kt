package dev.agentmirror.app.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class ConversationQuotaDisplayTest {
    @Test
    fun explicitNativeZeroIsAValidUsedPercentageNotMissing() {
        val quota = Json.parseToJsonElement("""{"weekly":{"usedPercent":0}}""").jsonObject
        assertEquals(0.0, quotaPercent(quota, "weekly")!!, 0.0)
        assertEquals("0%", percentText(quotaPercent(quota, "weekly")!!))
        assertNull(quotaPercent(quota, "fiveHour"))
    }

    @Test
    fun unreportedStatusDoesNotHideAValidWeeklyZeroInTheActualSnapshotParser() {
        val stats = Json.parseToJsonElement("""{"agentProvider":"grok","sessionId":"current","grokQuota":{"source":"native_acp_billing","windowsStatus":"unreported","weekly":{"usedPercent":0,"resetsAt":"2026-10-11T16:40:34Z"}}}""").jsonObject
        val snapshot = usageSnapshot(stats, 1)
        assertEquals(0.0, quotaPercent(snapshot.grokQuota, "weekly")!!, 0.0)
        assertEquals("0%", percentText(quotaPercent(snapshot.grokQuota, "weekly")!!))
    }

    @Test
    fun reportedAuthorizedDefaultRetainsItsBasisInTheActualSnapshotParser() {
        val stats = Json.parseToJsonElement("""{"agentProvider":"grok","sessionId":"current","grokQuota":{"windowsStatus":"reported","weekly":{"usedPercent":0,"basis":"authorized_tui_default","usedPercentDefaulted":true,"resetsAt":"2026-10-11T16:40:34Z","resetBasis":"currentPeriod.end"}}}""").jsonObject
        val snapshot = usageSnapshot(stats, 1)
        assertEquals(0.0, quotaPercent(snapshot.grokQuota, "weekly")!!, 0.0)
        val weekly = snapshot.grokQuota!!["weekly"]!!.jsonObject
        assertEquals("\"authorized_tui_default\"", weekly["basis"].toString())
        assertEquals("true", weekly["usedPercentDefaulted"].toString())
        assertEquals("\"currentPeriod.end\"", weekly["resetBasis"].toString())
    }

    @Test
    fun billingMetadataAndClockOnlyObservationCannotInventQuotaOrResetDate() {
        val quota = Json.parseToJsonElement("""{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","end":"2026-10-11T16:40:34Z"},"onDemand":{"usedPercent":0}}""").jsonObject
        assertNull(quotaPercent(quota, "weekly"))
        assertNull(quotaTimeText("10:40", ZoneId.of("UTC")))
    }

    @Test
    fun provedResetInstantDisplaysItsDateClockAndPhoneTimezone() {
        assertEquals("2026-10-11 16:40 Z", quotaTimeText("2026-10-11T16:40:34Z", ZoneId.of("UTC")))
        assertEquals("2026-10-12 00:40 +08:00", quotaTimeText("2026-10-11T16:40:34Z", ZoneId.of("Asia/Shanghai")))
    }
}
