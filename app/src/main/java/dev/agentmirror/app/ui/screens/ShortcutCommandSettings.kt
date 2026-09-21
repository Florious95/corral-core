package dev.agentmirror.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import dev.agentmirror.app.session.ShortcutCommand
import dev.agentmirror.app.session.ShortcutProvider
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.CardOutlineButton
import dev.agentmirror.app.ui.components.CardTonalButton
import dev.agentmirror.app.ui.components.SettingsCard
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes

@Composable
internal fun ShortcutCommandSettingsCard(
    commands: List<ShortcutCommand>,
    onSave: (ShortcutCommand) -> Unit,
    onDelete: (String) -> Unit,
) {
    val p = LocalAppPalette.current
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var pi by remember { mutableStateOf("") }
    var codex by remember { mutableStateOf("") }
    var grok by remember { mutableStateOf("") }

    fun edit(command: ShortcutCommand?) {
        editorOpen = true
        editingId = command?.id
        name = command?.name.orEmpty()
        pi = command?.providerCommands?.get("pi").orEmpty()
        codex = command?.providerCommands?.get("codex").orEmpty()
        grok = command?.providerCommands?.get("grok").orEmpty()
    }

    SettingsCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText(
                "快捷命令管理",
                p.rowTitleText,
                TypeSizes.cardTitle,
                modifier = Modifier.weight(1f),
            )
            CardOutlineButton("新增", onClick = { edit(null) })
        }
        Box(Modifier.padding(top = 6.dp)) {
            AppText(
                "按 Provider 保存独立指令，不识别或未配置时不会自动回退。",
                p.bodyText,
                TypeSizes.cardBody,
            )
        }
        if (commands.isEmpty() && !editorOpen) {
            Box(Modifier.padding(top = 10.dp)) {
                AppText("暂无快捷命令", p.pathText, TypeSizes.footnote)
            }
        }
        commands.forEach { command ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    AppText(command.name, p.rowTitleText, TypeSizes.cardBody)
                    AppText(
                        command.providerCommands.keys.sorted().joinToString(" · "),
                        p.pathText,
                        TypeSizes.footnote,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                CardOutlineButton("编辑", onClick = { edit(command) })
                CardOutlineButton("删除", onClick = { onDelete(command.id) })
            }
        }
        if (editorOpen) {
            ShortcutEditorField("名称", name, { name = it }, "shortcut-command-name")
            ShortcutProvider.entries.forEach { provider ->
                val value = when (provider) {
                    ShortcutProvider.Pi -> pi
                    ShortcutProvider.Codex -> codex
                    ShortcutProvider.Grok -> grok
                }
                ShortcutEditorField(
                    provider.label,
                    value,
                    { next ->
                        when (provider) {
                            ShortcutProvider.Pi -> pi = next
                            ShortcutProvider.Codex -> codex = next
                            ShortcutProvider.Grok -> grok = next
                        }
                    },
                    "shortcut-command-${provider.id}",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardTonalButton(
                    "保存",
                    onClick = {
                        val cleanName = name.trim()
                        if (cleanName.isNotEmpty()) {
                            onSave(
                                ShortcutCommand(
                                    id = editingId ?: "shortcut-${System.currentTimeMillis()}",
                                    name = cleanName,
                                    providerCommands = mapOf(
                                        "pi" to pi,
                                        "codex" to codex,
                                        "grok" to grok,
                                    ).filterValues { it.isNotEmpty() },
                                ),
                            )
                            editorOpen = false
                            editingId = null
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                CardOutlineButton("取消", onClick = { editorOpen = false; editingId = null }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShortcutEditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    tag: String,
) {
    val p = LocalAppPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        AppText(label, p.pathText, TypeSizes.footnote)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = p.rowTitleText,
                fontSize = TypeSizes.cardBody,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier
                .fillMaxWidth()
                .background(p.screenBackground.copy(alpha = 0.45f), RoundedCornerShape(Radii.cardButton))
                .border(0.5.dp, p.outlineButtonBorder, RoundedCornerShape(Radii.cardButton))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag(tag),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) AppText("未配置", p.pathText, TypeSizes.cardBody); inner() }
            },
        )
    }
}
