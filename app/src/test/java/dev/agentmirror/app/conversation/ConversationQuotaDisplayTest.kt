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
