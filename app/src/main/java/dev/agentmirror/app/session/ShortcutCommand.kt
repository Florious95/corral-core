package dev.agentmirror.app.session

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A user-defined command prefix with explicit per-provider text. */
@Serializable
data class ShortcutCommand(
    val id: String,
    val name: String,
    val providerCommands: Map<String, String> = emptyMap(),
)

sealed interface ShortcutResolution {
    data class Found(val text: String) : ShortcutResolution
    data object UnknownProvider : ShortcutResolution
    data class UnconfiguredProvider(val provider: String) : ShortcutResolution
}

/** Fail-closed provider lookup: there is no default or cross-provider fallback. */
fun resolveShortcutCommand(command: ShortcutCommand, provider: String): ShortcutResolution {
    val key = provider.trim().lowercase()
    if (key.isEmpty() || key == "unknown") return ShortcutResolution.UnknownProvider
    val text = command.providerCommands[key]
    return if (text.isNullOrEmpty()) {
        ShortcutResolution.UnconfiguredProvider(key)
    } else {
        ShortcutResolution.Found(text)
    }
}

interface ShortcutCommandStore {
    fun load(): List<ShortcutCommand>
    fun save(commands: List<ShortcutCommand>)
}

class MemoryShortcutCommandStore(initial: List<ShortcutCommand> = emptyList()) : ShortcutCommandStore {
    private var commands = initial.toList()

    override fun load(): List<ShortcutCommand> = commands

    override fun save(commands: List<ShortcutCommand>) {
        this.commands = commands.toList()
    }
}

class ShortcutCommandRepository(private val store: ShortcutCommandStore) {
    fun load(): List<ShortcutCommand> = store.load()

    fun upsert(command: ShortcutCommand) {
        val next = store.load().filterNot { it.id == command.id } + command
        store.save(next)
    }

    fun delete(id: String) {
        store.save(store.load().filterNot { it.id == id })
    }
}

class SharedPreferencesShortcutCommandStore(context: Context) : ShortcutCommandStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<ShortcutCommand> {
        val raw = prefs.getString(KEY_COMMANDS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ShortcutCommand.serializer()), raw)
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    override fun save(commands: List<ShortcutCommand>) {
        prefs.edit {
            putString(KEY_COMMANDS, json.encodeToString(ListSerializer(ShortcutCommand.serializer()), commands))
        }
    }

    private companion object {
        const val PREFS_NAME = "shortcut_commands"
        const val KEY_COMMANDS = "commands"
        val json = Json { ignoreUnknownKeys = true }
    }
}

enum class ShortcutProvider(val id: String, val label: String) {
    Pi("pi", "Pi"),
    Codex("codex", "Codex"),
    Grok("grok", "Grok"),
}

internal fun shortcutProviderError(resolution: ShortcutResolution): String = when (resolution) {
    ShortcutResolution.UnknownProvider -> "未识别当前会话 Provider"
    is ShortcutResolution.UnconfiguredProvider -> "未配置 ${resolution.provider} 对应的快捷指令"
    is ShortcutResolution.Found -> ""
}
