/**
 * ─────────────────────────────────────────────────────────────
 * SessionScreenScaffold.kt — 对话页顶层组合
 *
 * 对应设计稿：无顶栏（返回=系统边缘手势，由宿主处理 onBack）；
 * 中间终端画布占满剩余空间（AndroidView{TermSurfaceView} 从
 * terminalCanvas 插槽传入，本文件不触碰其内容）；底部恒定两行 dock：
 * 倒数第二行三态（DockSecondRow）+ 最底行输入胶囊（CommandInputBar）。
 *
 * 布局决策：
 * - 读取真实 imeAnimationTarget，以源码 `.3s cubic-bezier(.4,0,.2,1)`
 *   独立动画底部 inset；不依赖系统 IME 动画的厂商时长/曲线；
 * - 终端容器复用基线 SessionShellScreen 卡片：外 4dp、圆角 14dp、浅色投影 /
 *   深色 1dp 描边，内容 clip 在圆角内；底边 4dp 呼吸 + hairline，避免直边硬拼 dock；
 * - dock 水平内边距 11dp、行间距 8dp、底部 8dp，宿主锁定源码 24dp 底部安全区。
 *
 * ── ConversationPageColors 映射表（由你们接线，⛔ 本代码不硬编码）──
 * 深色（Nocturne 令牌实测值 → colorScheme 槽位）：
 *   #161826 页面底         → background
 *   #232532 卡片面         → surface
 *   #292b31 行块/胶囊底    → surfaceVariant（或 surfaceContainerHigh）
 *   #3f424d 常规描边       → outlineVariant
 *   #595d6c 强描边/空闲点  → outline
 *   #e9e9ed 主文字         → onBackground / onSurface
 *   #b2b6ca 次文字         → onSurfaceVariant
 *   #9184d9 accent 主色    → primary
 *   #2b2741 选中块底       → primaryContainer
 *   #d2cefd 选中块文字     → onPrimaryContainer
 *   #d98aa6 Ctrl-C 中断    → error（若嫌语义重可新增扩展色 interrupt）
 *   #7dd3a0 运行中状态点   → 需新增扩展色 success；临时映射 tertiary
 * 浅色（用户浅色截图取样，近似值，请按你们浅色主题校准）：
 *   #eef0f7 页面底         → background
 *   #ffffff 键/块面        → surface / surfaceVariant
 *   #1b2430 主文字         → onBackground
 *   #5b6472 次文字         → onSurfaceVariant
 *   #d5dbe8 描边           → outlineVariant
 *   #2456e6 发送蓝         → primary
 *   #dde5f7 加号/选中底    → primaryContainer
 *   #c25b7c Ctrl-C 粉红    → error / interrupt 扩展色
 *   #1a7f74 终端青（tailnet 绿系）→ 终端画布自绘，不入主题
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import android.view.ViewTreeObserver
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
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

internal val sourceImeAnimationSpec: FiniteAnimationSpec<Dp> = tween(
    durationMillis = SessionDockMotion.KeyboardPushMillis,
    easing = SessionDockMotion.Standard,
)

/**
 * System Back / IME-swipe can hide the keyboard without moving Compose focus.
 * Collapse the source capsule when the *current* IME inset or root window IME
 * visibility transitions to hidden. The animation target is observed to catch
 * system Back at hide start; a cancelled show still cannot clear focus by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClearFocusWhenImeHides(
    collapseRequested: Boolean,
    onImeHideStarted: () -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val imeCurrentPx = WindowInsets.ime.getBottom(density)
    val imeTargetPx = WindowInsets.imeAnimationTarget.getBottom(density)
    var rootImeVisible by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        fun readRootIme(): Boolean =
            ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        rootImeVisible = readRootIme()
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = readRootIme()
            view.post { rootImeVisible = visible }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }
    val imeVisible = rootImeVisible || imeCurrentPx > 0
    val imeTargetVisible = imeTargetPx > 0
    var wasVisible by remember { mutableStateOf(false) }
    var hideNotified by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, imeTargetVisible, collapseRequested) {
        if (imeTargetVisible) {
            wasVisible = true
            hideNotified = false
        } else if (wasVisible && !collapseRequested && !hideNotified) {
            // System Back starts the inset transition before the current inset reaches zero.
            // Request the same-frame source collapse; do not wait for IME hidden.
            hideNotified = true
            onImeHideStarted()
        }
        if (!imeVisible && !imeTargetVisible) wasVisible = false
    }
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

/** Source session layout: terminal slot above a constant two-row IME-aware dock. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionScreenScaffold(
    terminalCanvas: @Composable () -> Unit,
    dockMode: DockRowMode,
    onDockModeChange: (DockRowMode) -> Unit,
    sessions: List<SessionChipUi>,
    sessionListState: LazyListState,
    onSessionSelect: (String) -> Unit,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSendText: (String) -> Unit,
    onPickAttachment: () -> Unit,
    onKeyToken: (String) -> Unit,
    onBack: () -> Unit, // 无顶栏：仅供宿主接 PredictiveBack/边缘手势，UI 上不出现
    onOpenViewMenu: () -> Unit,
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
    var imeHideRequested by remember { mutableStateOf(false) }
    var collapseRequest by remember { mutableStateOf(0) }
    val requestDockCollapse: (String) -> Unit = remember(focusManager, keyboardController, view) {
        { source ->
            if (!imeHideRequested) {
                val inputStartNs = System.nanoTime()
                imeHideRequested = true
                collapseRequest++
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
    ClearFocusWhenImeHides(
        collapseRequested = imeHideRequested,
        onImeHideStarted = { requestDockCollapse("system-back") },
    )
    val palette = LocalAppPalette.current
    val terminalCard = currentTerminalPalette()
    val source = sessionDockSourceTokens()
    SourceImeMotionLayout(
        targetBottom = if (imeHideRequested) 0.dp else imeSystemTargetBottom,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Observe terminal pointer-down without consuming it: the real AndroidView keeps its
            // scroll/mouse gestures, while a genuine outside touch blurs the source textarea.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("session-terminal-canvas")
                    .pointerInput(focusManager, requestDockCollapse) {
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
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(Dims.hairline)
                        .background(source.neutral800)
                        // Keep the rule above the elevated card's edge shadow at the screen boundary.
                        .zIndex(1f)
                        .testTag("session-terminal-dock-rule"),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 11.dp, end = 11.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DockSecondRow(
                    mode = dockMode,
                    onModeChange = onDockModeChange,
                    sessions = sessions,
                    sessionListState = sessionListState,
                    onSessionSelect = onSessionSelect,
                    onKeyToken = onKeyToken,
                    onOpenViewMenu = onOpenViewMenu,
                )
                CommandInputBar(
                    value = value,
                    onValueChange = onValueChange,
                    onSendText = { text ->
                        requestDockCollapse("send")
                        onSendText(text)
                    },
                    onPickAttachment = onPickAttachment,
                    expandedLines = inputExpandedLines,
                    collapseRequest = collapseRequest,
                    onExpandRequested = {
                        imeHideRequested = false
                        collapseRequest = 0
                    },
                    onCollapseRequested = { requestDockCollapse("system-back") },
                )
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────

private val previewSessions = listOf(
    SessionChipUi("1", "编排开发", isActive = true, isRunning = true),
    SessionChipUi("2", "讨论team-agent", isActive = false, isRunning = false),
    SessionChipUi("3", "bugfix/merkle", isActive = false, isRunning = true),
)

@Composable
private fun PreviewTerminalStub() {
    // 仅预览用：正式接线时替换为 AndroidView { TermSurfaceView }
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
            dockMode = DockRowMode.Sessions, onDockModeChange = {},
            sessions = previewSessions,
            sessionListState = androidx.compose.foundation.lazy.rememberLazyListState(),
            onSessionSelect = {},
            value = TextFieldValue(""), onValueChange = {},
            onSendText = {}, onPickAttachment = {}, onKeyToken = {},
            onBack = {}, onOpenViewMenu = {},
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
            dockMode = DockRowMode.Hotkeys, onDockModeChange = {},
            sessions = previewSessions,
            sessionListState = androidx.compose.foundation.lazy.rememberLazyListState(),
            onSessionSelect = {},
            value = TextFieldValue("bazel test //..."), onValueChange = {},
            onSendText = {}, onPickAttachment = {}, onKeyToken = {},
            onBack = {}, onOpenViewMenu = {},
        )
    }
}
