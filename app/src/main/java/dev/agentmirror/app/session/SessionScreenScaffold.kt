/**
 * ─────────────────────────────────────────────────────────────
 * SessionScreenScaffold.kt — 对话页顶层组合
 *
 * 对应设计稿：无顶栏（返回=系统边缘手势，由会话屏统一处理）；
 * 中间终端画布占满剩余空间（AndroidView{TermSurfaceView} 从
 * terminalCanvas 插槽传入，本文件不触碰其内容）；底部恒定两行 dock：
 * 常驻快捷键条（HotkeyRow）+ 最底行输入胶囊（CommandInputBar）。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import android.util.Log
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.Elevations
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.currentTerminalPalette
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val SESSION_DOCK_MOTION_TAG = "SessionDockMotion"

private fun recordImeEvent(message: String) {
    Log.d(SESSION_DOCK_MOTION_TAG, message)
    DiagLog.record("session-dock-motion", message)
}

internal val sourceImeAnimationSpec: FiniteAnimationSpec<Dp> = tween(
    durationMillis = SessionDockMotion.KeyboardPushMillis,
    easing = SessionDockMotion.Standard,
)

/**
 * System Back / IME-swipe can hide the keyboard without moving Compose focus.
 * Observe hide edges only within the latest explicit expansion request. Reopening while an old
 * hide is still in flight must invalidate its armed state before it can clear the new focus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClearFocusWhenImeHides(
    collapseRequested: Boolean,
    expansionRequest: State<Int>,
    onImeHideStarted: () -> Unit,
) {
    val density = LocalDensity.current
    // All three values belong to Foundation's current inset snapshot. A separately posted root
    // visibility callback can arrive after a new tap and must not revive the previous IME cycle.
    ObserveImeHide(
        WindowInsets.ime.getBottom(density),
        WindowInsets.imeAnimationTarget.getBottom(density),
        WindowInsets.isImeVisible,
        collapseRequested,
        expansionRequest,
        onImeHideStarted,
    )
}

/** Side-effect boundary shared by platform sampling and deterministic focus/IME ordering tests. */
@Composable
internal fun ObserveImeHide(
    imeCurrentPx: Int,
    imeTargetPx: Int,
    rootImeVisible: Boolean,
    collapseRequested: Boolean,
    expansionRequest: State<Int>,
    onImeHideStarted: () -> Unit,
) {
    val generation = expansionRequest.value
    val imeTargetVisible = imeTargetPx > 0
    var wasVisible by remember(generation) { mutableStateOf(false) }
    var hideNotified by remember(generation) { mutableStateOf(false) }
    LaunchedEffect(imeCurrentPx > 0, rootImeVisible, imeTargetVisible, collapseRequested, generation) {
        // A new tap can precede recomposition/cancellation of this old effect on the UI queue.
        if (generation != expansionRequest.value) return@LaunchedEffect
        val observation = observeImeVisibility(
            wasVisible = wasVisible,
            currentInsetPx = imeCurrentPx,
            rootVisible = rootImeVisible,
            targetInsetPx = imeTargetPx,
            collapseRequested = collapseRequested || hideNotified,
        )
        recordImeEvent(
            "ime-sample generation=$generation current=$imeCurrentPx target=$imeTargetPx " +
                "visible=$rootImeVisible armed=$wasVisible requested=$collapseRequested " +
                "notified=$hideNotified collapse=${observation.shouldCollapse}",
        )
        if (observation.shouldCollapse) {
            // System Back starts the inset transition before the current inset reaches zero.
            // Request the same-frame source collapse; do not wait for IME hidden.
            hideNotified = true
            onImeHideStarted()
        } else if (imeTargetVisible) {
            hideNotified = false
        }
        wasVisible = observation.wasVisible
    }
}

/**
 * Arm only after an open target has actual visible pixels (or a visible zero-inset floating IME).
 * Metadata/target alone during a cancelled normal show cannot arm a hide. Returning to a hidden
 * target consumes the edge even if an explicit user collapse already handled it.
 */
internal data class ImeVisibilityObservation(
    val wasVisible: Boolean,
    val shouldCollapse: Boolean,
)

internal fun observeImeVisibility(
    wasVisible: Boolean,
    currentInsetPx: Int,
    rootVisible: Boolean,
    targetInsetPx: Int,
    collapseRequested: Boolean,
): ImeVisibilityObservation {
    // Visibility metadata can turn true before the first occluding pixel of a normal IME show.
    // It only substitutes for pixels for a floating IME, whose current and target are both zero.
    val floatingVisible = currentInsetPx == 0 && targetInsetPx == 0 && rootVisible
    val actuallyVisible = currentInsetPx > 0 || floatingVisible
    val targetVisible = targetInsetPx > 0 || floatingVisible
    val shouldCollapse = wasVisible && !targetVisible && !collapseRequested
    // Consume a hide even when an explicit collapse already handled it. Old nonzero pixels
    // while target=0 cannot arm another hide against the next expansion request.
    val nextWasVisible = actuallyVisible && targetVisible
    return ImeVisibilityObservation(nextWasVisible, shouldCollapse)
}

/** Production IME inset animator shared by real-window wiring and deterministic motion tests. */
@Composable
internal fun SourceImeMotionLayout(
    targetBottom: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val animatedBottom by animateDpAsState(
        targetValue = targetBottom,
        animationSpec = sourceImeAnimationSpec,
        label = "sourceImeBottom",
    )
    Box(modifier.padding(bottom = animatedBottom)) {
        content()
    }
}

/** Source session layout: terminal slot above a constant two-row IME-aware dock (HotkeyRow + CommandInputBar). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionScreenScaffold(
    terminalCanvas: @Composable () -> Unit,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSendText: (String) -> Unit,
    onPickAttachment: () -> Unit,
    onKeyToken: (String) -> Unit,
    shortcutCommands: List<ShortcutCommand> = emptyList(),
    shortcutProvider: String = "unknown",
    onShortcutCommand: (ShortcutCommand) -> Unit = {},
    onShortcutError: (String) -> Unit = {},
    onShortcutMenuOpened: () -> Unit = {},
    onShortcutMenuOpenChange: (Boolean) -> Unit = {},
    imeHideRequested: Boolean? = null,
    collapseRequest: Int? = null,
    onDockCollapse: ((String) -> Unit)? = null,
    onInputExpanded: (() -> Unit)? = null,
    onInputFocusedChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    inputExpandedLines: Int = 3,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val imeSystemTargetBottom = with(density) {
        WindowInsets.imeAnimationTarget.getBottom(this).toDp()
    }
    var localImeHideRequested by remember { mutableStateOf(false) }
    var localCollapseRequest by remember { mutableStateOf(0) }
    val requestDockCollapse: (String) -> Unit = onDockCollapse ?: remember(
        focusManager,
        keyboardController,
        view,
    ) {
        { source ->
            if (!localImeHideRequested) {
                val inputStartNs = System.nanoTime()
                localImeHideRequested = true
                localCollapseRequest++
                Log.d(
                    SESSION_DOCK_MOTION_TAG,
                    "collapse source=$source count=$localCollapseRequest",
                )
                val imeStartNs = System.nanoTime()
                keyboardController?.hide()
                ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
                focusManager.clearFocus(force = true)
                DiagLog.record(
                    "session-dock-motion",
                    "collapse source=$source input_start_ns=$inputStartNs " +
                        "ime_hide_start_ns=$imeStartNs start_delta_ns=${imeStartNs - inputStartNs} " +
                        "input_duration_ms=${SessionDockMotion.InputHeightMillis} " +
                        "ime_duration_ms=${SessionDockMotion.KeyboardPushMillis} " +
                        "total_duration_ms=${maxOf(SessionDockMotion.InputHeightMillis, SessionDockMotion.KeyboardPushMillis)}",
                )
            }
        }
    }
    val effectiveImeHideRequested = imeHideRequested ?: localImeHideRequested
    val effectiveCollapseRequest = collapseRequest ?: localCollapseRequest
    val expansionRequest = remember { mutableIntStateOf(0) }
    val requestDockExpand: () -> Unit = {
        expansionRequest.intValue++
        recordImeEvent("expand generation=${expansionRequest.intValue}")
        if (onInputExpanded != null) {
            onInputExpanded()
        } else {
            localImeHideRequested = false
            localCollapseRequest = 0
        }
    }
    ClearFocusWhenImeHides(
        collapseRequested = effectiveImeHideRequested,
        expansionRequest = expansionRequest,
        onImeHideStarted = { requestDockCollapse("ime-hide") },
    )
    val palette = LocalAppPalette.current
    val terminalCard = currentTerminalPalette()
    SourceImeMotionLayout(
        targetBottom = if (effectiveImeHideRequested) 0.dp else imeSystemTargetBottom,
        modifier = modifier
            .fillMaxSize()
            // 会话页外框用全 App 中性底色，⛔ 不用 dock 主题的淡紫 background（F3F5FE / 161826），
            // 否则终端卡与底栏被一圈紫调包裹。dock 主题的 background 仅保留给浅/深判定。
            .background(palette.screenBackground),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Observe terminal pointer-down without consuming it: the real AndroidView keeps its
            // scroll/mouse gestures, while a genuine outside touch blurs the source textarea.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("session-terminal-canvas")
                    .pointerInput(requestDockCollapse) {
                        awaitEachGesture {
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            requestDockCollapse("focus-loss")
                        }
                    }
            ) {
                // Baseline SessionShellScreen terminal card (4dp / 14dp / clip / dark hairline).
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Dims.terminalCardMargin)
                        .testTag("session-terminal-card"),
                    shape = RoundedCornerShape(Radii.terminalCard),
                    color = terminalCard.background,
                    tonalElevation = Elevations.none,
                    shadowElevation = if (palette === DarkPalette) {
                        Elevations.terminalCardDark
                    } else {
                        Elevations.terminalCardLight
                    },
                    border = if (palette === DarkPalette) {
                        BorderStroke(Dims.hairline, palette.divider)
                    } else {
                        null
                    },
                ) {
                    terminalCanvas()
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 11.dp, end = 11.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 快捷键条常驻展示，无多余返回按钮
                HotkeyRow(
                    onKeyToken = onKeyToken,
                )
                CommandInputBar(
                    value = value,
                    onValueChange = onValueChange,
                    onSendText = { text ->
                        requestDockCollapse("send")
                        onSendText(text)
                    },
                    onPickAttachment = onPickAttachment,
                    shortcutCommands = shortcutCommands,
                    shortcutProvider = shortcutProvider,
                    onShortcutCommand = onShortcutCommand,
                    onShortcutError = onShortcutError,
                    onShortcutMenuOpened = onShortcutMenuOpened,
                    onShortcutMenuOpenChange = onShortcutMenuOpenChange,
                    expandedLines = inputExpandedLines,
                    collapseRequest = effectiveCollapseRequest,
                    onFocusedChanged = { onInputFocusedChanged?.invoke(it) },
                    onExpandRequested = requestDockExpand,
                    onCollapseRequested = { requestDockCollapse("editor-focus-loss") },
                )
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────

@Composable
private fun PreviewTerminalStub() {
    Box(
        Modifier.fillMaxSize().padding(12.dp).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text("TermSurfaceView 插槽", fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "SessionScreenScaffold · Light", showBackground = true, heightDp = 640)
@Composable
private fun PreviewScaffoldLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        SessionScreenScaffold(
            terminalCanvas = { PreviewTerminalStub() },
            value = TextFieldValue(""),
            onValueChange = {},
            onSendText = {},
            onPickAttachment = {},
            onKeyToken = {},
        )
    }
}

@Preview(name = "SessionScreenScaffold · Dark", showBackground = true, heightDp = 640,
    backgroundColor = 0xFF161826)
@Composable
private fun PreviewScaffoldDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        SessionScreenScaffold(
            terminalCanvas = { PreviewTerminalStub() },
            value = TextFieldValue("bazel test //..."),
            onValueChange = {},
            onSendText = {},
            onPickAttachment = {},
            onKeyToken = {},
        )
    }
}
