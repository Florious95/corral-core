/**
 * ─────────────────────────────────────────────────────────────
 * CommandInputBar.kt — 最底行 · 输入胶囊
 *
 * 对应设计稿：输入框「完全包裹」加号与发送——一只微水润 FlatGlass 胶囊
 * （见 [dockFlatGlass]：半透底 + 0.5dp 发丝边 + 顶亮/底暗，⛔ 不采样背景），
 * 内部从左到右：中性 GlassIconButton 加号 · 受控 BasicTextField · 主色
 * GlassIconButton 发送（tint=accent，与弹窗「创建」同材质）。键帽见 HotkeyRow。
 *
 * 交互（已验收，⛔ 视觉改造不得触碰）：
 * - 单行起步；获得焦点（IME 弹出）时文本区高度 animateDpAsState 膨胀到
 *   expandedLines 行（默认 3），失焦收回单行；
 * - 发送后由宿主清空 value 并可收起焦点（见接线说明）；发送钮在文本非空
 *   时底色薄染 primary 作可用暗示；
 * - 胶囊发丝边聚焦时 animateColorAsState 过渡到 primary。
 *
 * 尺寸严格对应源码 CSS：
 * - 胶囊圆角 22dp；Compose 内边距含源码 1dp border inset，视觉值仍是 4/6/6/6；
 * - 未聚焦 32dp；聚焦高度 = 20dp × expandLines + 12dp（expandLines 2–5）；
 * - 加号 36×32dp 纯图标；发送 32dp 圆钮。
 * - 等宽字体：终端指令语境，FontFamily.Monospace。
 * 仅有的本地 remember 是「焦点视觉态」（非业务状态，仓库规范允许）。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import android.os.Trace
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.emptyBackdrop
import dev.agentmirror.app.ui.components.GlassIconButton
import dev.agentmirror.app.ui.theme.LocalAppPalette

/** Source textarea height in dp: collapsed 32; focused `20 * expandLines + 12`. */
internal fun sourceInputFieldHeightDp(focused: Boolean, expandedLines: Int): Int =
    if (focused) 20 * expandedLines.coerceIn(2, 5) + 12 else 32

/** Source input capsule with attachment, expanding editor, and send action. */
@Composable
fun CommandInputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSendText: (String) -> Unit,
    onPickAttachment: () -> Unit,
    shortcutCommands: List<ShortcutCommand> = emptyList(),
    shortcutProvider: String = "unknown",
    onShortcutCommand: (ShortcutCommand) -> Unit = {},
    onShortcutError: (String) -> Unit = {},
    onShortcutMenuOpened: () -> Unit = {},
    modifier: Modifier = Modifier,
    expandedLines: Int = 3,
    collapseRequest: Int = 0,
    onFocusedChanged: (Boolean) -> Unit = {},
    onExpandRequested: () -> Unit = {},
    onCollapseRequested: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val source = sessionDockSourceTokens()
    val glass = sessionDockGlassTokens()
    // 强调色一律走全 App 调色板的科技蓝（p.accent），⛔ 不用 dock 主题遗留的紫色 primary / accent*
    val p = LocalAppPalette.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val previousAutofillMode = hostView.importantForAutofill
        hostView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { hostView.importantForAutofill = previousAutofillMode }
    }
    var shortcutMenuOpen by remember { mutableStateOf(false) }
    val shortcutGlass = sessionDockGlassTokens()
    // 焦点视觉态（非业务状态）：驱动膨胀、描边高亮与真实 IME 开合。
    var focused by remember { mutableStateOf(false) }
    // GLOBAL_ACTION_BACK during IME attach can fail hide/clearFocus while the field
    // stays system-focused; ignore that gain so the capsule still returns to 46dp.
    var suppressFocusGain by remember { mutableStateOf(false) }
    LaunchedEffect(collapseRequest) {
        if (collapseRequest > 0) {
            suppressFocusGain = true
            focused = false
        }
    }
    LaunchedEffect(focused) {
        if (focused) {
            // The text-input session is attached on the next frame; showing before that is ignored.
            withFrameNanos { }
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }
    val sourceExpandedLines = expandedLines.coerceIn(2, 5)
    val editorExpanded = focused && collapseRequest == 0
    val onSend = { onSendText(value.text) }
    val fieldHeight by animateDpAsState(
        targetValue = sourceInputFieldHeightDp(editorExpanded, sourceExpandedLines).dp,
        animationSpec = tween(
            durationMillis = SessionDockMotion.InputHeightMillis,
            easing = SessionDockMotion.Standard,
        ),
        label = "inputFieldHeight",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) p.accent.copy(alpha = 0.85f) else glass.hairline,
        animationSpec = tween(
            durationMillis = SessionDockMotion.InputBorderMillis,
            easing = SessionDockMotion.Ease,
        ),
        label = "inputBorder",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .dockFlatGlass(
                shape = RoundedCornerShape(22.dp),
                fill = glass.fill,
                hairline = borderColor,
                topGlint = glass.topGlint,
                bottomShade = glass.bottomShade,
            )
            .testTag("session-command-input"),
    ) {
        Row(
            // 底对齐：膨胀时加号/发送钉在底边，与主流 Chat App 一致
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 5.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
        ) {
            // 加号：36×32 触控槽不变，32dp 标杆中性 GlassIconButton（emptyBackdrop，不采样）。
            Box(
                modifier = Modifier.size(width = 36.dp, height = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Overlay the shortcut control above (+) without changing the dock's measured
                // height. It never requests focus, so tapping it leaves the IME untouched.
                GlassIconButton(
                    onClick = {
                        Trace.beginSection("shortcut/open")
                        try {
                            onShortcutMenuOpened()
                            shortcutMenuOpen = true
                        } finally {
                            Trace.endSection()
                        }
                    },
                    size = 32.dp,
                    backdrop = emptyBackdrop(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-36).dp)
                        .testTag("session-shortcut-button")
                        .semantics { contentDescription = "快捷命令" },
                ) {
                    Text(">_", color = source.neutral400, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                GlassIconButton(
                    onClick = onPickAttachment,
                    size = 32.dp,
                    backdrop = emptyBackdrop(),
                ) {
                    Icon(
                        DockIconPlus, contentDescription = "添加附件",
                        modifier = Modifier.width(20.dp), tint = source.neutral400,
                    )
                }
                if (shortcutMenuOpen) {
                    Popup(
                        popupPositionProvider = ShortcutMenuPositionProvider(
                            with(LocalDensity.current) { 8.dp.roundToPx() },
                        ),
                        onDismissRequest = { shortcutMenuOpen = false },
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true,
                        ),
                    ) {
                        ShortcutGlassMenu(
                            commands = shortcutCommands,
                            glass = shortcutGlass,
                            onSelect = { command ->
                                Trace.beginSection("shortcut/select")
                                try {
                                    shortcutMenuOpen = false
                                    when (val result = resolveShortcutCommand(command, shortcutProvider)) {
                                        is ShortcutResolution.Found -> onShortcutCommand(command)
                                        else -> onShortcutError(shortcutProviderError(result))
                                    }
                                } finally {
                                    Trace.endSection()
                                }
                            },
                        )
                    }
                }
            }
            Box(
                Modifier.weight(1f).height(fieldHeight).testTag("session-command-input-field"),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { newValue ->
                        onValueChange(newValue)
                    },
                    singleLine = false,
                    maxLines = if (editorExpanded) sourceExpandedLines else 1,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { onSend() },
                    ),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 20.sp,
                        color = cs.onSurface,
                    ),
                    cursorBrush = SolidColor(p.accent),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.text.isEmpty()) {
                                Text(
                                    "输入指令…",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Normal,
                                        lineHeight = 20.sp,
                                    ),
                                    color = cs.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(fieldHeight)
                        .padding(vertical = 6.dp)
                        .testTag("session-command-editor")
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                onSend()
                                true
                            } else if (event.key == Key.Enter) {
                                true
                            } else {
                                false
                            }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                suppressFocusGain = false
                                onExpandRequested()
                                focused = true
                                onFocusedChanged(true)
                            }
                        }
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                if (!suppressFocusGain) {
                                    onExpandRequested()
                                    focused = true
                                    onFocusedChanged(true)
                                }
                            } else {
                                if (focused && !suppressFocusGain) onCollapseRequested?.invoke()
                                suppressFocusGain = false
                                focused = false
                                onFocusedChanged(false)
                            }
                        },
                )
            }
            val hasText = value.text.isNotBlank()
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(fieldHeight)
                    .zIndex(2f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSend() },
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // 主色 GlassIconButton（与弹窗「创建」同款 tint）；外层全高 zIndex 触控盒锁死首击发送
                GlassIconButton(
                    onClick = { onSend() },
                    tint = p.accent,
                    size = 32.dp,
                    backdrop = emptyBackdrop(),
                    modifier = Modifier.testTag("session-send-button"),
                ) {
                    Icon(
                        DockIconArrowUp, contentDescription = "发送",
                        modifier = Modifier.width(16.dp),
                        tint = if (hasText) p.onAccent else p.onAccent.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

private class ShortcutMenuPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        anchorBounds.left,
        (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0),
    )
}

@Composable
private fun ShortcutGlassMenu(
    commands: List<ShortcutCommand>,
    glass: SessionDockGlassTokens,
    onSelect: (ShortcutCommand) -> Unit,
) {
    val p = LocalAppPalette.current
    Column(
        modifier = Modifier
            .width(190.dp)
            .dockFlatGlass(
                shape = RoundedCornerShape(16.dp),
                fill = glass.fill,
                hairline = glass.hairline,
                topGlint = glass.topGlint,
                bottomShade = glass.bottomShade,
            )
            .padding(vertical = 4.dp)
            .testTag("session-shortcut-menu"),
    ) {
        if (commands.isEmpty()) {
            Text(
                "暂无快捷命令",
                color = p.rowTitleText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        } else {
            commands.forEach { command ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(command) },
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(command.name, color = p.rowTitleText)
                }
            }
        }
    }
}

@Preview(name = "CommandInputBar · Light", showBackground = true)
@Composable
private fun PreviewInputLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        CommandInputBar(TextFieldValue(""), {}, {}, {}, modifier = Modifier.padding(8.dp))
    }
}

@Preview(name = "CommandInputBar · Dark", showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun PreviewInputDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CommandInputBar(TextFieldValue("git status"), {}, {}, {}, modifier = Modifier.padding(8.dp))
    }
}
