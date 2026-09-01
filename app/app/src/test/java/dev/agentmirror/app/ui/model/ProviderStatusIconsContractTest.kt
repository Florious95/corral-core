package dev.agentmirror.app.ui.model

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ProviderStatusIconsContractTest {
    @Test fun canonicalProvidersUsePinnedPresentation() {
        val expected = mapOf("claude_code" to "Claude Code", "codex" to "Codex", "copilot" to "Copilot", "grok" to "Grok Code", "cursor" to "Cursor", "pi" to "Pi")
        expected.forEach { (id, label) ->
            val p = providerPresentation(id, "working", "normal")
            assertEquals(label, p.label)
            assertEquals(id, p.assetKey)
            assertEquals(ProviderMarkState.Running, p.state)
        }
        assertNull(providerPresentation("future", "working", "normal").assetKey)
    }

    @Test fun fourAxisProjectionFailsClosedAndAbnormalWins() {
        assertEquals(ProviderMarkState.Running, providerPresentation("codex", "working", "normal").state)
        assertEquals(ProviderMarkState.Idle, providerPresentation("codex", "idle", "normal").state)
        assertEquals(ProviderMarkState.Abnormal, providerPresentation("codex", "working", "abnormal").state)
        listOf("", "unknown", "future").forEach { activity ->
            assertEquals(ProviderMarkState.Unknown, providerPresentation("codex", activity, "normal").state)
        }
        listOf("", "unknown", "future").forEach { health ->
            assertEquals(ProviderMarkState.Unknown, providerPresentation("codex", "working", health).state)
        }
    }

    @Test fun rightChipProjectionMatchesProviderMark() {
        listOf("working" to "normal", "idle" to "normal", "working" to "abnormal", "unknown" to "unknown").forEach { (activity, health) ->
            val mark = providerPresentation("pi", activity, health).state
            val chip = sessionStatusFromAxes(activity, health)
            assertEquals(mark, when (chip) {
                SessionStatus.Busy -> ProviderMarkState.Running
                SessionStatus.Idle -> ProviderMarkState.Idle
                SessionStatus.Abnormal -> ProviderMarkState.Abnormal
                SessionStatus.Unknown -> ProviderMarkState.Unknown
            })
        }
    }
}
