# Compose 落位包 · agentmirror

包名 `dev.agentmirror.app`，源码在 `kotlin/dev/agentmirror/app/` 下，按目录直接拷进 `app/src/main/java/` 即可。
⛔ 未引入任何新依赖：只用到 compose-bom（foundation / material3 / animation）+ activity-compose。

## 文件对位

| 文件 | 对应 |
|---|---|
| `ui/theme/DesignTokens.kt` | 全部写死数值：`Dims` / `Radii` / `TypeSizes` / `Elevations` / `Motion` / `AppPalette`（`LightPalette` + `DarkPalette`） |
| `ui/theme/Theme.kt` | `AppTheme(appearance)`，深浅两套 ColorScheme + `isSystemInDarkTheme()`，可被设置页覆盖 |
| `ui/theme/TerminalSpec.kt` | 终端自绘层的色板与度量（⛔ 无 Composable） |
| `ui/model/Models.kt` | `WorkspaceItem` / `SessionItem` / `SessionStatus` / `NavTab` |
| `ui/components/CommonUi.kt` | `AppText` / `PathText` / `SessionNameText` / `StatusChip` / `LanPill` / `StarButton` / `ScreenHeader` / `BackAffordance` / 各类按钮 / `SettingsCard` |
| `ui/components/AppBottomNav.kt` | 底部导航栏（只有 3b 顶部指示轨一案） |
| `ui/components/SessionSwitchSheet.kt` | 「查看」二级菜单浮层 |
| `ui/components/NavTransitions.kt` | 页面转场（配合 `AnimatedContent`） |
| `ui/screens/WorkspaceListScreen.kt` | 工作区列表（一级） |
| `ui/screens/SessionListScreen.kt` | 会话列表（二级，含状态标） |
| `ui/screens/FavoritesScreen.kt` | 收藏页（星在行首） |
| `ui/screens/SettingsScreen.kt` | 设置页（主机配对 / 字体大小 / 诊断日志 / 外观） |
| `ui/screens/SessionShellScreen.kt` | 会话页外壳（顶栏 + 终端槽位 + 功能键排 + 输入条） |

全部 Composable 纯展示：数据走参数、动作走 lambda，⛔ 无 ViewModel、无网络、无业务 `remember`。
唯一的 `remember` 是交互与动画状态（`MutableInteractionSource`、逐行上浮的播放标志）。

## 会话页装配示例

```kotlin
Box(Modifier.fillMaxSize()) {
    SessionShellScreen(
        sessionDisplayName = session.displayName,   // 可能是中文
        running = session.status == SessionStatus.Busy,
        lanConnected = lan,
        draft = draft,
        onDraftChange = onDraftChange,
        onSend = onSend,
        onBack = onBack,
        onOpenSwitcher = { switcherVisible = true },
        onKeyPress = ::sendKey,
        onAttach = onAttach,
    ) {
        AndroidView(factory = { ctx -> TerminalSurfaceView(ctx) }, modifier = Modifier.fillMaxSize())
    }
    SessionSwitchSheet(
        visible = switcherVisible,
        workspaceName = workspace.name,
        sessions = sessions,
        currentSessionId = session.id,
        onDismiss = { switcherVisible = false },
        onSelect = ::switchTo,
        onToggleStar = ::toggleStar,
    )
}
```

终端 SurfaceView 侧读色板与度量：

```kotlin
val palette = currentTerminalPalette()          // @Composable，传给 view
// 或非 Compose 侧直接用 TerminalPaletteDark / TerminalMetrics
val leftPx = with(density) { TerminalMetrics.paddingLeft.toPx() }   // 🔴 首列裁切的修复点
```
