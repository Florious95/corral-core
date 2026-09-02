/**
 * ─────────────────────────────────────────────────────────────
 * CommandInputBar.kt — 最底行 · 输入胶囊
 *
 * 对应设计稿：输入框「完全包裹」加号与发送——一只圆角胶囊 Surface，
 * 内部从左到右：加号图标钮（无底色）· 受控 BasicTextField · 发送小圆钮
 * （primary 描边圆形，主流 Chat App 式样）。
 *
 * 交互（已验收）：
 * - 单行起步；获得焦点（IME 弹出）时文本区高度 animateDpAsState 膨胀到
 *   expandedLines 行（默认 3），失焦收回单行；
 * - 发送后由宿主清空 value 并可收起焦点（见接线说明）；发送钮在文本非空
 *   时底色填 primaryContainer 作可用暗示；
 * - 胶囊描边聚焦时 animateColorAsState 过渡到 primary。
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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

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
    modifier: Modifier = Modifier,
    expandedLines: Int = 3,
    collapseRequest: Int = 0,
    onExpandRequested: () -> Unit = {},
    onCollapseRequested: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val source = sessionDockSourceTokens()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    // 焦点视觉态（非业务状态）：驱动膨胀、描边高亮与真实 IME 开合。
    var focused by remember { mutableStateOf(false) }
    // GLOBAL_ACTION_BACK during IME attach can fail hide/clearFocus while the field
    // stays system-focused; ignore that gain so the capsule still returns to 46dp.
    var suppressFocusGain by remember { mutableStateOf(false) }
    fun collapseEditor() {
        onCollapseRequested?.invoke()
        suppressFocusGain = true
        focused = false
        keyboardController?.hide()
        view.windowToken?.let { token ->
            view.context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(token, 0)
        }
        activity?.let {
            WindowCompat.getInsetsController(it.window, view)
                .hide(WindowInsetsCompat.Type.ime())
        }
        focusManager.clearFocus(force = true)
    }
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
    // System Back while the editor is focused must hide IME and blur (source onBlur → 46dp)
    // instead of leaving the capsule expanded or consuming the host session-pop BackHandler.
    BackHandler(enabled = focused) { collapseEditor() }
    // Register on the Activity dispatcher (always present) at OVERLAY so a BACK that
    // arrives while IME is attaching is not lost to view.findOnBackInvokedDispatcher()==null
    // or to IME's DEFAULT callback. Do not key this on keyboardController (re-register gap).
    DisposableEffect(focused, activity) {
        if (!focused || activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return@DisposableEffect onDispose { }
        }
        val dispatcher = activity.onBackInvokedDispatcher
        val callback = OnBackInvokedCallback { collapseEditor() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }
    DisposableEffect(focused, view) {
        if (!focused) return@DisposableEffect onDispose { }
        val listener = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                collapseEditor()
                true
            } else {
                false
            }
        }
        view.setOnKeyListener(listener)
        onDispose { view.setOnKeyListener(null) }
    }
    // Accessibility GLOBAL_ACTION_BACK injects KEYCODE_BACK at the window when
    // predictive-back callbacks are not the active path (IME attaching).
    DisposableEffect(focused, activity) {
        if (!focused || activity == null) return@DisposableEffect onDispose { }
        val window = activity.window
        val previous = window.callback ?: return@DisposableEffect onDispose { }
        val wrapped = object : Window.Callback by previous {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) collapseEditor()
                    return true
                }
                return previous.dispatchKeyEvent(event)
            }
        }
        window.callback = wrapped
        onDispose {
            if (window.callback === wrapped) window.callback = previous
        }
    }
    val sourceExpandedLines = expandedLines.coerceIn(2, 5)
    val editorExpanded = focused && collapseRequest == 0
    val fieldHeight by animateDpAsState(
        targetValue = sourceInputFieldHeightDp(editorExpanded, sourceExpandedLines).dp,
        animationSpec = tween(
            durationMillis = SessionDockMotion.InputHeightMillis,
            easing = SessionDockMotion.Standard,
        ),
        label = "inputFieldHeight",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) source.accent700 else source.neutral800,
        animationSpec = tween(
            durationMillis = SessionDockMotion.InputBorderMillis,
            easing = SessionDockMotion.Ease,
        ),
        label = "inputBorder",
    )
    Surface(
        modifier = modifier.fillMaxWidth().testTag("session-command-input"),
        shape = RoundedCornerShape(22.dp),
        color = source.neutral900,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            // 底对齐：膨胀时加号/发送钉在底边，与主流 Chat App 一致
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 5.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
        ) {
            Surface(
                onClick = onPickAttachment,
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier.size(width = 36.dp, height = 32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        DockIconPlus, contentDescription = "添加附件",
                        modifier = Modifier.width(20.dp), tint = source.neutral400,
                    )
                }
            }
            Box(
                Modifier.weight(1f).height(fieldHeight).testTag("session-command-input-field"),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    maxLines = if (editorExpanded) sourceExpandedLines else 1,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 20.sp,
                        color = cs.onSurface,
                    ),
                    cursorBrush = SolidColor(cs.primary),
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
                            if (event.key == Key.Back && event.type == KeyEventType.KeyUp) {
                                collapseEditor()
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
                            }
                        }
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                if (!suppressFocusGain) {
                                    onExpandRequested()
                                    focused = true
                                }
                            } else {
                                if (focused && !suppressFocusGain) onCollapseRequested?.invoke()
                                suppressFocusGain = false
                                focused = false
                            }
                        },
                )
            }
            val hasText = value.text.isNotBlank()
            val sendBackground by animateColorAsState(
                targetValue = if (hasText) source.accent900 else Color.Transparent,
                animationSpec = tween(
                    durationMillis = SessionDockMotion.InputBorderMillis,
                    easing = SessionDockMotion.Ease,
                ),
                label = "sendBackground",
            )
            val sendForeground by animateColorAsState(
                targetValue = if (hasText) source.accent200 else source.accent,
                animationSpec = tween(
                    durationMillis = SessionDockMotion.InputBorderMillis,
                    easing = SessionDockMotion.Ease,
                ),
                label = "sendForeground",
            )
            Surface(
                onClick = { if (hasText) onSendText(value.text) },
                shape = CircleShape,
                color = sendBackground,
                border = BorderStroke(1.dp, source.accent),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        DockIconArrowUp, contentDescription = "发送",
                        modifier = Modifier.width(16.dp),
                        tint = sendForeground,
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(name = "CommandInputBar · Light", showBackground = true)
@Composable
private fun PreviewInputLight() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        CommandInputBar(TextFieldValue(""), {}, {}, {}, Modifier.padding(8.dp))
    }
}

@Preview(name = "CommandInputBar · Dark", showBackground = true, backgroundColor = 0xFF161826)
@Composable
private fun PreviewInputDark() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        CommandInputBar(TextFieldValue("git status"), {}, {}, {}, Modifier.padding(8.dp))
    }
}
