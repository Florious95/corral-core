/* Copyright 2026 AgentMirror Project Authors */
package dev.agentmirror.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.SessionChromeColors
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.workspace.FavoriteRow

/** Existing terminal key mapping exposed by the session hotkey row. */
enum class TerminalKey(val label: String, val danger: Boolean = false) {
    Esc("Esc"), Tab("Tab"), Up("↑"), Down("↓"), Left("←"), Right("→"), CtrlC("Ctrl-C", true),
}

/** Mutually-exclusive local presentation state for the session dock. */
enum class SessionDockMode { Menu, Hotkeys, Sessions }

/** 097 shell: one stable terminal host above a controlled input and one replaceable dock row. */
@Composable
fun SessionShellScreen(
    sessionDisplayName: String,
    status: dev.agentmirror.app.ui.model.SessionStatus,
    connectionPath: ConnectionPath? = null,
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onOpenSwitcher: () -> Unit,
    onKeyPress: (TerminalKey) -> Unit,
    onAttach: () -> Unit,
    favoriteRows: List<FavoriteRow> = emptyList(),
    currentRef: String = "",
    onOpenFavorite: (FavoriteRow) -> Unit = {},
    modifier: Modifier = Modifier,
    sendEnabled: Boolean = true,
    connectionBanner: String? = null,
    terminalContent: @Composable () -> Unit,
) {
    val scheme = TermPalette.of(dark = LocalAppPalette.current === DarkPalette)
    val chrome = remember(scheme) { SessionChromeColors.from(scheme) }
    var mode by remember { mutableStateOf(SessionDockMode.Menu) }
    Column(
        modifier
            .fillMaxSize()
            .background(chrome.page)
            .testTag("session-shell")
            .semantics {
                contentDescription = "scheme=${scheme.source} chrome=${chrome.asStateString()}"
            },
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().testTag("session-terminal")) {
            terminalContent()
            if (connectionBanner != null) {
                Text(connectionBanner, color = chrome.text, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
            }
        }
        InputCapsule(draft, onDraftChange, onSend, onAttach, sendEnabled, chrome)
        when (mode) {
            SessionDockMode.Hotkeys -> KeyPanel(chrome, onKeyPress) { mode = SessionDockMode.Menu }
            SessionDockMode.Sessions -> FavoritePanel(chrome, favoriteRows, currentRef, onOpenFavorite) { mode = SessionDockMode.Menu }
            SessionDockMode.Menu -> Dock(chrome, { mode = it }, onOpenSwitcher)
        }
    }
}

@Composable
private fun Dock(c: SessionChromeColors, onMode: (SessionDockMode) -> Unit, onView: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(8.dp).testTag("session-dock-menu"),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TextButton(
            onClick = { onMode(SessionDockMode.Hotkeys) },
            modifier = Modifier.testTag("dock_hotkeys").semantics { contentDescription = "快捷键" },
        ) { Text("快捷键", color = c.text) }
        TextButton(
            onClick = onView,
            modifier = Modifier.testTag("dock_view").semantics { contentDescription = "查看" },
        ) { Text("查看", color = c.text) }
        TextButton(
            onClick = { onMode(SessionDockMode.Sessions) },
            modifier = Modifier.testTag("dock_sessions").semantics { contentDescription = "会话" },
        ) { Text("会话", color = c.text) }
    }
}

@Composable
private fun KeyPanel(c: SessionChromeColors, onKey: (TerminalKey) -> Unit, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 8.dp).testTag("session-dock-hotkeys"),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TerminalKey.entries.forEach { key ->
            TextButton(onClick = { onKey(key) }, modifier = Modifier.testTag("hotkey-${key.name}")) {
                Text(key.label, color = c.text)
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.testTag("hotkeys-back")) { Text("返回", color = c.accent) }
    }
}

@Composable
private fun FavoritePanel(
    c: SessionChromeColors,
    rows: List<FavoriteRow>,
    currentRef: String,
    onOpen: (FavoriteRow) -> Unit,
    onBack: () -> Unit,
) {
    val candidates = otherFavoriteRows(rows, currentRef)
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(8.dp).testTag("session-dock-sessions"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (candidates.isEmpty()) Text("暂无收藏", color = c.text)
                candidates.forEach { row ->
                    val enabled = row.isOnline
                    TextButton(
                        onClick = { if (enabled) onOpen(row) },
                        enabled = enabled,
                        modifier = Modifier
                            .testTag("favorite-${row.ref}")
                            .semantics {
                                contentDescription = "${row.ref}|${row.identityLabel}|${if (enabled) "online" else "offline"}"
                            },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.identityLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.text)
                            if (!enabled) Text("不在线", color = c.muted)
                        }
                    }
                }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.testTag("sessions-back")) { Text("返回菜单", color = c.text) }
    }
}

internal fun otherFavoriteRows(rows: List<FavoriteRow>, currentRef: String): List<FavoriteRow> =
    rows.filter { it.ref != currentRef }

@Composable
private fun InputCapsule(
    draft: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    enabled: Boolean,
    c: SessionChromeColors,
) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(c.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onAttach,
            modifier = Modifier.testTag("session-attach").semantics { contentDescription = "添加图片附件" },
        ) { Text("＋", color = c.accent, fontSize = 22.sp) }
        BasicTextField(
            value = draft,
            onValueChange = onChange,
            singleLine = !focused,
            maxLines = if (focused) 4 else 1,
            textStyle = LocalTextStyle.current.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() }),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp, max = if (focused) 140.dp else 48.dp)
                .onFocusChanged { focused = it.isFocused }
                .background(c.input, RoundedCornerShape(24.dp))
                .border(1.dp, c.outline, RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("session-draft"),
        )
        TextButton(
            onClick = onSend,
            enabled = enabled,
            modifier = Modifier.testTag("session-send").semantics { contentDescription = "发送" },
        ) { Text("↑", color = c.accent, fontSize = 20.sp) }
    }
}

private fun SessionChromeColors.asStateString(): String = listOf(
    page,
    surface,
    text,
    accent,
    success,
    interrupt,
).joinToString(",") { (it.toArgb().toLong() and 0xffffffffL).toString(16) }
