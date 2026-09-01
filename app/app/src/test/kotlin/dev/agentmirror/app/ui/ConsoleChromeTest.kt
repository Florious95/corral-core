package dev.agentmirror.app.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.session.InputStatus
import dev.agentmirror.app.session.OverlayTestHarness
import dev.agentmirror.app.session.SessionScreen
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.components.BackChevronGeometry
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.screens.SessionListScreen
import dev.agentmirror.app.ui.screens.SessionShellScreen
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.TerminalMetrics
import dev.agentmirror.app.ui.theme.TypeSizes
import dev.agentmirror.app.ui.theme.appDarkScheme
import dev.agentmirror.app.ui.theme.appLightScheme
import dev.agentmirror.app.workspace.L2Status
import dev.agentmirror.app.workspace.MemoryFavoriteStore
import dev.agentmirror.app.workspace.WorkspaceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 083 chrome：间距 / 已发送 / 光标 / 连接胶囊 / 重连条 / 光学对齐 / 色槽 / 顶栏灯。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsoleChromeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ConsoleChromeGapTotalPaddingIsTenDpAndColsCapped() {
        val outer = Dims.terminalCardMargin.value
        val inner = TerminalMetrics.paddingLeft.value
        assertEquals(4f, outer)
        assertEquals(6f, inner)
        assertEquals(10f, outer + inner)
        assertEquals(TerminalMetrics.paddingLeft, TerminalMetrics.paddingRight)
        val uncapped = ((20000f - 18f - 18f) / 10f).toInt()
        assertTrue("未封顶会远超上限", uncapped > TerminalMetrics.maxCols)
        assertEquals(
            TerminalMetrics.maxCols,
            TerminalMetrics.colsFor(20000f, 10f, 18f, 18f),
        )
        assertEquals(112, TerminalMetrics.maxCols)
    }

    @Test
    fun ConsoleChromeCursorKeepsSelectionAfterExternalUpdate() {
        var draft by mutableStateOf(TextFieldValue("abcdefgh", TextRange(4)))
        var extra by mutableStateOf(0)
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionShellScreen(
                    sessionDisplayName = "远控 leader$extra",
                    status = SessionStatus.Idle,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {},
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                ) {}
            }
        }
        compose.waitForIdle()
        assertEquals(4, draft.selection.start)
        assertEquals(4, draft.selection.end)
        compose.runOnIdle { extra = 1 }
        compose.waitForIdle()
        assertEquals("外部状态更新不得把光标打回末尾", 4, draft.selection.start)
        assertEquals(4, draft.selection.end)
        assertEquals("abcdefgh", draft.text)
    }

    @Test
    fun ConsoleChromeSentHidesSuccessKeepsFailure() {
        val h = OverlayTestHarness()
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionScreen(viewModel = h.vm, name = "远控 leader", onBack = {})
            }
        }
        repeat(3) {
            compose.runOnIdle { h.vm.inputStatus = InputStatus.Sent }
            compose.waitForIdle()
        }
        assertEquals(
            0,
            compose.onAllNodesWithText("已发送", substring = true).fetchSemanticsNodes().size,
        )
        compose.runOnIdle { h.vm.inputStatus = InputStatus.Failed("发送失败：超时") }
        compose.waitForIdle()
        compose.onNodeWithText("发送失败：超时").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("已发送", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun ConsoleChromeNetPillUsesRealConnectionPath() {
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionShellScreen(
                    sessionDisplayName = "远控 leader",
                    status = SessionStatus.Idle,
                    connectionPath = ConnectionPath.TAILNET,
                    draft = TextFieldValue(""),
                    onDraftChange = {},
                    onSend = {},
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                ) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("LAN").assertDoesNotExist()
        compose.onNodeWithText("tailnet").assertIsDisplayed()
        compose.onNodeWithTag("lan-pill").assertIsDisplayed()
    }

    @Test
    fun ConsoleChromeNetPillShowsLanOnlyOnLanPath() {
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionShellScreen(
                    sessionDisplayName = "远控 leader",
                    status = SessionStatus.Idle,
                    connectionPath = ConnectionPath.LAN,
                    draft = TextFieldValue(""),
                    onDraftChange = {},
                    onSend = {},
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                ) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("LAN").assertIsDisplayed()
        compose.onNodeWithText("tailnet").assertDoesNotExist()
    }

    @Test
    fun ConsoleChromeBannerSitsBelowTitleWithoutMovingFirstRow() {
        val item = SessionItem(
            id = "s1",
            displayName = "advisor",
            path = "/tmp/ws",
            status = SessionStatus.Idle,
            starred = false,
        )
        var banner by mutableStateOf<String?>(null)
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionListScreen(
                    workspaceName = "远程Agent安卓",
                    workspacePath = "/tmp/ws",
                    sessions = listOf(item),
                    onBack = {},
                    onSessionClick = {},
                    onToggleStar = {},
                    connectionBanner = banner,
                )
            }
        }
        compose.waitForIdle()
        val rowBefore = compose.onNodeWithTag("l2-row-s1").getUnclippedBoundsInRoot()
        compose.runOnIdle { banner = "重连中…" }
        compose.waitForIdle()
        val titleBar = compose.onNodeWithTag("session-list-topbar").getUnclippedBoundsInRoot()
        val bannerBox = compose.onNodeWithTag("connection-banner").getUnclippedBoundsInRoot()
        val rowAfter = compose.onNodeWithTag("l2-row-s1").getUnclippedBoundsInRoot()
        assertTrue(
            "横幅顶边必须 ≥ 标题栏底边，banner.top=${bannerBox.top} title.bottom=${titleBar.bottom}",
            bannerBox.top.value + 0.5f >= titleBar.bottom.value,
        )
        assertEquals(rowBefore.top, rowAfter.top)
        compose.runOnIdle { banner = null }
        compose.waitForIdle()
        val rowGone = compose.onNodeWithTag("l2-row-s1").getUnclippedBoundsInRoot()
        assertEquals(rowBefore.top, rowGone.top)
    }

    @Test
    fun ConsoleChromeAlignBackInkCenterMatchesTitle() {
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionShellScreen(
                    sessionDisplayName = "远控 leader",
                    status = SessionStatus.Idle,
                    draft = TextFieldValue(""),
                    onDraftChange = {},
                    onSend = {},
                    onBack = {},
                    onOpenSwitcher = {},
                    onKeyPress = {},
                    onAttach = {},
                ) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("‹").assertDoesNotExist()
        compose.onNodeWithTag("session-back").assertIsDisplayed()
        compose.onNodeWithTag("session-title").assertIsDisplayed()

        val density = 3f
        val barPx = Dims.topBarHeight.value * density
        val backPx = Dims.backButtonSize.value * density
        val chevronPx = 22f * density
        val backTop = (barPx - backPx) / 2f
        val chevronTop = backTop + (backPx - chevronPx) / 2f
        val chevronInkY = chevronTop + BackChevronGeometry.inkCenterY(chevronPx)

        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.textSize = TypeSizes.topBarTitle.value * density
        paint.typeface = android.graphics.Typeface.DEFAULT
        val text = "远控 leader"
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val layoutH = TypeSizes.topBarTitle.value * density * 1.2f
        val layoutTop = (barPx - layoutH) / 2f
        val baseline = layoutTop - paint.fontMetrics.ascent
        val titleInkY = baseline + (bounds.top + bounds.bottom) / 2f
        val deltaDp = abs(chevronInkY - titleInkY) / density
        assertTrue(
            "墨迹中心 y 差必须 ≤ 1dp，deltaDp=$deltaDp chevron=$chevronInkY title=$titleInkY",
            deltaDp <= 1.1f,
        )
    }

    @Test
    fun ConsoleChromeSchemeEveryMaterial3SlotDiffersFromFrameworkDefault() {
        assertNoDefaultSlots("light", appLightScheme, lightColorScheme())
        assertNoDefaultSlots("dark", appDarkScheme, darkColorScheme())
    }

    @Test
    fun ConsoleChromeFavoriteChipFollowsLiveStatusWithoutUnknownLabel() {
        val h = OverlayTestHarness()
        val idle = session(h.vm.ref, L2Status.IDLE)
        val busy = session(h.vm.ref, L2Status.WORKING)
        val unknown = session(h.vm.ref, L2Status.UNKNOWN)
        var overlay by mutableStateOf(listOf(idle))
        compose.setContent {
            AppTheme(appearance = Appearance.Light) {
                SessionScreen(
                    viewModel = h.vm,
                    name = "远控 leader",
                    onBack = {},
                    overlaySessions = overlay,
                    overlayFavorited = setOf(dev.agentmirror.app.workspace.FavoriteKey(h.vm.ref)),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Idle").assertIsDisplayed()
        compose.runOnIdle { overlay = listOf(busy) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Running").assertIsDisplayed()
        compose.runOnIdle { overlay = listOf(unknown) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Idle").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unknown").assertDoesNotExist()
    }

    @Test
    fun ConsoleChromeLampDataSourceIsViewMenuPush() {
        val wvm = WorkspaceViewModel(favoriteStore = MemoryFavoriteStore())
        wvm.enterLevel2("/tmp/ws")
        wvm.onFrame(
            Level2Frame(
                workspace = "/tmp/ws",
                seq = 1,
                sessions = listOf(wireSession("idle")),
            ),
        )
        assertEquals(L2Status.IDLE, wvm.viewMenuSource("ref-1").sessions.single().status)
        wvm.onFrame(
            Level2Frame(
                workspace = "/tmp/ws",
                seq = 2,
                sessions = listOf(wireSession("working")),
            ),
        )
        assertEquals(L2Status.WORKING, wvm.viewMenuSource("ref-1").sessions.single().status)
    }

    private fun session(ref: String, status: L2Status) = dev.agentmirror.app.workspace.L2Entry(
        ref = ref,
        name = "n",
        title = "t",
        rows = 24,
        cols = 80,
        status = status,
        cwd = "/tmp/ws",
        sessionName = "n",
        windowIndex = "1",
        windowName = "n",
    )

    private fun wireSession(status: String) = Session(
        ref = "ref-1",
        name = "n",
        cwd = "/tmp/ws",
        rows = 24,
        cols = 80,
        status = status,
    )
}

private fun assertNoDefaultSlots(label: String, ours: ColorScheme, def: ColorScheme) {
    val slots = listOf(
        "primary" to (ours.primary to def.primary),
        "onPrimary" to (ours.onPrimary to def.onPrimary),
        "primaryContainer" to (ours.primaryContainer to def.primaryContainer),
        "onPrimaryContainer" to (ours.onPrimaryContainer to def.onPrimaryContainer),
        "inversePrimary" to (ours.inversePrimary to def.inversePrimary),
        "secondary" to (ours.secondary to def.secondary),
        "onSecondary" to (ours.onSecondary to def.onSecondary),
        "secondaryContainer" to (ours.secondaryContainer to def.secondaryContainer),
        "onSecondaryContainer" to (ours.onSecondaryContainer to def.onSecondaryContainer),
        "tertiary" to (ours.tertiary to def.tertiary),
        "onTertiary" to (ours.onTertiary to def.onTertiary),
        "tertiaryContainer" to (ours.tertiaryContainer to def.tertiaryContainer),
        "onTertiaryContainer" to (ours.onTertiaryContainer to def.onTertiaryContainer),
        "background" to (ours.background to def.background),
        "onBackground" to (ours.onBackground to def.onBackground),
        "surface" to (ours.surface to def.surface),
        "onSurface" to (ours.onSurface to def.onSurface),
        "surfaceVariant" to (ours.surfaceVariant to def.surfaceVariant),
        "onSurfaceVariant" to (ours.onSurfaceVariant to def.onSurfaceVariant),
        "surfaceTint" to (ours.surfaceTint to def.surfaceTint),
        "inverseSurface" to (ours.inverseSurface to def.inverseSurface),
        "inverseOnSurface" to (ours.inverseOnSurface to def.inverseOnSurface),
        "error" to (ours.error to def.error),
        "onError" to (ours.onError to def.onError),
        "errorContainer" to (ours.errorContainer to def.errorContainer),
        "onErrorContainer" to (ours.onErrorContainer to def.onErrorContainer),
        "outline" to (ours.outline to def.outline),
        "outlineVariant" to (ours.outlineVariant to def.outlineVariant),
        "scrim" to (ours.scrim to def.scrim),
        "surfaceBright" to (ours.surfaceBright to def.surfaceBright),
        "surfaceDim" to (ours.surfaceDim to def.surfaceDim),
        "surfaceContainer" to (ours.surfaceContainer to def.surfaceContainer),
        "surfaceContainerHigh" to (ours.surfaceContainerHigh to def.surfaceContainerHigh),
        "surfaceContainerHighest" to (ours.surfaceContainerHighest to def.surfaceContainerHighest),
        "surfaceContainerLow" to (ours.surfaceContainerLow to def.surfaceContainerLow),
        "surfaceContainerLowest" to (ours.surfaceContainerLowest to def.surfaceContainerLowest),
    )
    val whitelist = setOf(
        "onPrimary",
        "onSecondary",
        "onTertiary",
        "onError",
        "surfaceContainerLowest",
    )
    val hits = slots.mapNotNull { (name, pair) ->
        if (name !in whitelist && pair.first == pair.second) name else null
    }
    assertTrue("$label 仍等于框架默认的槽: $hits", hits.isEmpty())
}
