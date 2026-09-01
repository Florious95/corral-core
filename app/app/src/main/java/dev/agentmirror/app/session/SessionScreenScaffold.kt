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
 * - Column + imePadding()：IME 弹出时整个 dock 被键盘顶起（对应
 *   HTML 版键盘推起动画，Android 交给系统 windowInsets，记得
 *   Activity 配 android:windowSoftInputMode="adjustResize"）；
 * - dock 水平内边距 12dp、行间距 8dp、底部 8dp + navigationBarsPadding
 *   由宿主 Scaffold 决定（此处不重复加，避免双倍 inset）。
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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Source session layout: terminal slot above a constant two-row IME-aware dock. */
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        // 终端画布：占满两行 dock 之上的全部空间。⛔ 不在 Compose 里画终端。
        Box(Modifier.weight(1f).fillMaxWidth()) {
            terminalCanvas()
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
                onSendText = onSendText,
                onPickAttachment = onPickAttachment,
                expandedLines = inputExpandedLines,
            )
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
