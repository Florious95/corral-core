package dev.agentmirror.app.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShortcutCommandTest {
    private val command = ShortcutCommand(
        id = "handoff",
        name = "交接",
        providerCommands = mapOf(
            "pi" to "/skill:handoff ",
            "codex" to "\$handoff",
            "grok" to "/handoff",
        ),
    )

    @Test
    fun repositorySupportsCreateReadUpdateDelete() {
        val repository = ShortcutCommandRepository(MemoryShortcutCommandStore())
        repository.upsert(command)
        assertEquals(listOf(command), repository.load())

        val updated = command.copy(name = "交接并归档", providerCommands = mapOf("pi" to "/archive "))
        repository.upsert(updated)
        assertEquals(listOf(updated), repository.load())

        repository.delete(command.id)
        assertTrue(repository.load().isEmpty())
    }

    @Test
    fun providerResolutionIsExplicitAndCaseInsensitive() {
        assertEquals(ShortcutResolution.Found("/skill:handoff "), resolveShortcutCommand(command, "pi"))
        assertEquals(ShortcutResolution.Found("\$handoff"), resolveShortcutCommand(command, "CODEX"))
        assertEquals(ShortcutResolution.Found("/handoff"), resolveShortcutCommand(command, "grok"))
    }

    @Test
    fun unknownAndUnconfiguredProviderNeverFallback() {
        assertEquals(ShortcutResolution.UnknownProvider, resolveShortcutCommand(command, ""))
        assertEquals(ShortcutResolution.UnknownProvider, resolveShortcutCommand(command, "unknown"))
        assertEquals(
            ShortcutResolution.UnconfiguredProvider("claude_code"),
            resolveShortcutCommand(command, "claude_code"),
        )
    }

    @Test
    fun sharedPreferencesStoreRoundTripsCommands() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("shortcut_commands", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SharedPreferencesShortcutCommandStore(context)
        store.save(listOf(command))
        assertEquals(listOf(command), SharedPreferencesShortcutCommandStore(context).load())
        context.getSharedPreferences("shortcut_commands", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
