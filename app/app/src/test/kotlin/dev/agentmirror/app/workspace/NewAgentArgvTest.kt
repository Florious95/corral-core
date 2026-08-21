package dev.agentmirror.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class NewAgentArgvTest {
    @Test
    fun sixProvidersMatchContractTable() {
        val wantBypass = mapOf(
            "claude_code" to listOf("claude", "--dangerously-skip-permissions"),
            "codex" to listOf("codex", "--dangerously-bypass-approvals-and-sandbox"),
            "copilot" to listOf("copilot", "--yolo"),
            "cursor" to listOf("cursor-agent", "--force"),
            "grok" to listOf("grok", "--always-approve"),
            "pi" to listOf("pi"),
        )
        for ((id, argv) in wantBypass) {
            assertEquals(id, argv, buildNewAgentArgv(id, bypass = true))
            assertEquals(id, listOf(wantBypass.getValue(id).first()), buildNewAgentArgv(id, bypass = false))
        }
    }
}
