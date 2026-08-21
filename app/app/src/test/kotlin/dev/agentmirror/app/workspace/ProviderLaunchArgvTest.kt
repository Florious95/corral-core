package dev.agentmirror.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A-088-cmd：组装 argv 纯函数。先验红 = 本文件编译失败（仓库无 ProviderLaunchStore）。
 */
class ProviderLaunchArgvTest {

    @Test
    fun grokLocalBypassAlwaysApprove() {
        val launch = ProviderLaunch(
            providerId = "grok",
            command = "grok-local",
            bypassFlag = "--always-approve",
        )
        assertEquals(listOf("grok-local", "--always-approve"), buildArgv(launch, bypass = true))
        assertEquals(listOf("grok-local"), buildArgv(launch, bypass = false))
    }

    @Test
    fun piBypassAddsNoFlag() {
        val launch = ProviderLaunchDefaults.byId("pi")
        assertEquals(listOf("pi"), buildArgv(launch, bypass = true))
        assertEquals(listOf("pi"), buildArgv(launch, bypass = false))
    }

    @Test
    fun grokDefaultBypassMustCarryAlwaysApprove() {
        val launch = ProviderLaunchDefaults.byId("grok")
        assertEquals(listOf("grok", "--always-approve"), buildArgv(launch, bypass = true))
    }

    @Test
    fun bypassFlagAlreadyInCommandIsNotDuplicated() {
        val launch = ProviderLaunch(
            providerId = "grok",
            command = "grok --always-approve",
            bypassFlag = "--always-approve",
        )
        assertEquals(listOf("grok", "--always-approve"), buildArgv(launch, bypass = true))
    }

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
            val launch = ProviderLaunchDefaults.byId(id)
            assertEquals(id, argv, buildArgv(launch, bypass = true))
            assertEquals(id, listOf(launch.command.trim()), buildArgv(launch, bypass = false))
        }
    }
}
