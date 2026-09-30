/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 瑞士现代主义（iOS 交付令牌）：瑞士纸白 / 墨色、猩红强调、全局直角、1dp / 2dp 单侧直线、
 * 不透明平面（无模糊 / 透镜 / 高光 / 投影），重字重大标题与等宽计数。
 */

// 交付令牌原值（Light / Dark）
private val PaperLight = Color(0xFFF3F2F2)
private val SurfaceLight = Color(0xFFEAE9E9)
private val ElevatedLight = Color(0xFFF8F4F4)
private val InkLight = Color(0xFF201E1D)
private val SecondaryLight = Color(0xFF605D5D)
private val MutedLight = Color(0xFF7D7979)
private val BorderLight = Color(0xFFBAB6B6)
private val SeparatorLight = Color(0x66201E1D)
private val AccentTextLight = Color(0xFFAE1800)
private val Accent800Light = Color(0xFF7C1405)
private val Accent100Light = Color(0xFFFFF2EF)

private val PaperDark = Color(0xFF201E1D)
private val SurfaceDark = Color(0xFF2D2B2B)
private val InkDark = Color(0xFFF3F2F2)
private val SecondaryDark = Color(0xFFBAB6B6)
private val BorderDark = Color(0xFF605D5D)
private val SeparatorDark = Color(0x47F3F2F2)
private val AccentTextDark = Color(0xFFFF563C)
private val Accent800Dark = Color(0xFFFF9783)
private val Accent100Dark = Color(0xFF4D170E)

private val SwissRed = Color(0xFFEC3013)
private val OnSwissRed = Color(0xFFF3F2F2)
private val StatusPending = Color(0xFF9B9797)

val ModernistLightPalette = AppPalette(
    screenBackground = PaperLight,
    listBackground = PaperLight,
    cardBackground = ElevatedLight,
    cardBorder = BorderLight,
    divider = SeparatorLight,
    dividerStrong = InkLight,
    rowPressed = SurfaceLight,

    titleText = InkLight,
    rowTitleText = InkLight,
    pathText = MutedLight,
    metaText = SecondaryLight,
    bodyText = SecondaryLight,
    providerMarkColor = InkLight,
    workingLampActive = SwissRed,
    workingLampInactive = BorderLight,

    accent = SwissRed,
    accentContainer = Accent100Light,
    accentContainerPressed = Color(0xFFFFDDD5),
    onAccent = OnSwissRed,

    starOn = SwissRed,
    starOff = SeparatorLight,

    busyChipBg = Accent100Light,
    busyChipText = Accent800Light,
    busyDot = SwissRed,
    idleChipBg = SurfaceLight,
    idleChipText = SecondaryLight,
    unknownChipBg = Color(0x1FAE1800),
    unknownChipText = AccentTextLight,
    unknownDot = AccentTextLight,

    statusPillBg = SurfaceLight,
    statusPillText = InkLight,

    navBackground = PaperLight,
    navRail = SwissRed,
    navActive = SwissRed,
    navInactive = InkLight,

    glassSurface = ElevatedLight,
    glassStroke = SeparatorLight,
    glassSpecular = Color.Transparent,
    glassCardFill = PaperLight,
    glassCardFillPressed = SurfaceLight,

    consoleBackground = SurfaceLight,
    keycapBackground = PaperLight,
    keycapBorder = SeparatorLight,
    keycapTopHighlight = Color.Transparent,
    keycapText = InkLight,
    keycapPressed = SurfaceLight,
    keycapDangerBorder = SwissRed,
    keycapDangerTopHighlight = Color.Transparent,
    keycapDangerText = AccentTextLight,
    keycapDangerPressed = Accent100Light,
    arrowClusterTrack = Color.Transparent,
    inputBackground = SurfaceLight,
    inputBorder = BorderLight,
    inputText = InkLight,
    inputPlaceholder = MutedLight,
    promptGlyph = SwissRed,
    sendEnabledBg = SwissRed,
    sendEnabledFg = OnSwissRed,
    sendDisabledBg = SurfaceLight,
    sendDisabledFg = MutedLight,

    scrim = Color(0x66000000),
    sheetBackground = PaperLight,
    sheetSurface = PaperLight,
    sheetGrabber = BorderLight,
    sheetRowPressed = SurfaceLight,
    sheetCurrentRowBg = Accent100Light,
    sheetCurrentRail = SwissRed,
    currentBadgeText = AccentTextLight,
    currentBadgeBorder = AccentTextLight,

    segmentedTrack = SurfaceLight,
    segmentedSelectedBg = ElevatedLight,
    segmentedSelectedText = AccentTextLight,
    segmentedText = SecondaryLight,
    chipBg = SurfaceLight,
    chipText = SecondaryLight,
    chipPressed = Color(0x1F201E1D),
    chipSelectedBg = SwissRed,
    chipSelectedText = OnSwissRed,
    outlineButtonBorder = InkLight,
    outlineButtonText = InkLight,
    outlineButtonPressed = SurfaceLight,
)

val ModernistDarkPalette = AppPalette(
    screenBackground = PaperDark,
    listBackground = PaperDark,
    cardBackground = SurfaceDark,
    cardBorder = BorderDark,
    divider = SeparatorDark,
    dividerStrong = InkDark,
    rowPressed = SurfaceDark,

    titleText = InkDark,
    rowTitleText = InkDark,
    pathText = SecondaryDark,
    metaText = SecondaryDark,
    bodyText = SecondaryDark,
    providerMarkColor = InkDark,
    workingLampActive = SwissRed,
    workingLampInactive = BorderDark,

    accent = SwissRed,
    accentContainer = Accent100Dark,
    accentContainerPressed = Color(0xFF6A2014),
    onAccent = OnSwissRed,

    starOn = SwissRed,
    starOff = SeparatorDark,

    busyChipBg = Accent100Dark,
    busyChipText = Accent800Dark,
    busyDot = SwissRed,
    idleChipBg = SurfaceDark,
    idleChipText = SecondaryDark,
    unknownChipBg = Color(0x33FF563C),
    unknownChipText = AccentTextDark,
    unknownDot = AccentTextDark,

    statusPillBg = SurfaceDark,
    statusPillText = InkDark,

    navBackground = PaperDark,
    navRail = SwissRed,
    navActive = SwissRed,
    navInactive = InkDark,

    glassSurface = SurfaceDark,
    glassStroke = SeparatorDark,
    glassSpecular = Color.Transparent,
    glassCardFill = PaperDark,
    glassCardFillPressed = SurfaceDark,

    consoleBackground = SurfaceDark,
    keycapBackground = PaperDark,
    keycapBorder = SeparatorDark,
    keycapTopHighlight = Color.Transparent,
    keycapText = InkDark,
    keycapPressed = SurfaceDark,
    keycapDangerBorder = SwissRed,
    keycapDangerTopHighlight = Color.Transparent,
    keycapDangerText = AccentTextDark,
    keycapDangerPressed = Accent100Dark,
    arrowClusterTrack = Color.Transparent,
    inputBackground = SurfaceDark,
    inputBorder = BorderDark,
    inputText = InkDark,
    inputPlaceholder = SecondaryDark,
    promptGlyph = SwissRed,
    sendEnabledBg = SwissRed,
    sendEnabledFg = OnSwissRed,
    sendDisabledBg = SurfaceDark,
    sendDisabledFg = SecondaryDark,

    scrim = Color(0x99000000),
    sheetBackground = PaperDark,
    sheetSurface = PaperDark,
    sheetGrabber = BorderDark,
    sheetRowPressed = SurfaceDark,
    sheetCurrentRowBg = Accent100Dark,
    sheetCurrentRail = SwissRed,
    currentBadgeText = AccentTextDark,
    currentBadgeBorder = AccentTextDark,

    segmentedTrack = SurfaceDark,
    segmentedSelectedBg = SurfaceDark,
    segmentedSelectedText = AccentTextDark,
    segmentedText = SecondaryDark,
    chipBg = SurfaceDark,
    chipText = SecondaryDark,
    chipPressed = Color(0x33F3F2F2),
    chipSelectedBg = SwissRed,
    chipSelectedText = OnSwissRed,
    outlineButtonBorder = InkDark,
    outlineButtonText = InkDark,
    outlineButtonPressed = SurfaceDark,
)

internal val modernistLightScheme = lightColorScheme(
    primary = SwissRed,
    onPrimary = OnSwissRed,
    primaryContainer = Accent100Light,
    onPrimaryContainer = Accent800Light,
    inversePrimary = AccentTextDark,
    secondary = SecondaryLight,
    onSecondary = PaperLight,
    secondaryContainer = SurfaceLight,
    onSecondaryContainer = InkLight,
    tertiary = InkLight,
    onTertiary = PaperLight,
    tertiaryContainer = SurfaceLight,
    onTertiaryContainer = InkLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = MutedLight,
    surfaceTint = Color.Transparent,
    inverseSurface = InkLight,
    inverseOnSurface = PaperLight,
    error = AccentTextLight,
    onError = PaperLight,
    errorContainer = Accent100Light,
    onErrorContainer = Accent800Light,
    outline = BorderLight,
    outlineVariant = SeparatorLight,
    scrim = Color(0x66000000),
    surfaceBright = ElevatedLight,
    surfaceDim = SurfaceLight,
    surfaceContainerLowest = ElevatedLight,
    surfaceContainerLow = PaperLight,
    surfaceContainer = SurfaceLight,
    surfaceContainerHigh = SurfaceLight,
    surfaceContainerHighest = Color(0xFFE0DEDE),
)

internal val modernistDarkScheme = darkColorScheme(
    primary = SwissRed,
    onPrimary = OnSwissRed,
    primaryContainer = Accent100Dark,
    onPrimaryContainer = Accent800Dark,
    inversePrimary = AccentTextLight,
    secondary = SecondaryDark,
    onSecondary = PaperDark,
    secondaryContainer = SurfaceDark,
    onSecondaryContainer = InkDark,
    tertiary = InkDark,
    onTertiary = PaperDark,
    tertiaryContainer = SurfaceDark,
    onTertiaryContainer = InkDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = SecondaryDark,
    surfaceTint = Color.Transparent,
    inverseSurface = InkDark,
    inverseOnSurface = PaperDark,
    error = AccentTextDark,
    onError = PaperDark,
    errorContainer = Accent100Dark,
    onErrorContainer = Accent800Dark,
    outline = BorderDark,
    outlineVariant = SeparatorDark,
    scrim = Color(0x99000000),
    surfaceBright = SurfaceDark,
    surfaceDim = PaperDark,
    surfaceContainerLowest = PaperDark,
    surfaceContainerLow = PaperDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceDark,
    surfaceContainerHighest = Color(0xFF3A3737),
)

private val Square = RoundedCornerShape(0.dp)
internal val modernistShapes = Shapes(
    extraSmall = Square,
    small = Square,
    medium = Square,
    large = Square,
    extraLarge = Square,
)

object ModernistThemeSuite : ThemeSuite {
    override val metadata = ThemeMetadata(ThemeId.Modernist, title = "现代主义", englishTitle = "Modernism")

    private val geometry = ThemeGeometry(
        sharpCorners = true,
        pageInset = 20.dp,
        hairline = 1.dp,
        headerRule = 2.dp,
        listRowHMargin = 0.dp,
        listRowGap = 0.dp,
        workspaceRowHeight = 68.dp,
        sessionRowHeight = 66.dp,
        numberColumnWidth = 56.dp,
        // 2dp 顶部重线 + 58dp 标签行；通栏贴底，无悬浮留白。
        tabBarHeight = 60.dp,
        tabBarBottomGap = 0.dp,
        sheetOuterPadding = 0.dp,
        rootMaterialShapes = modernistShapes,
    )

    private val typography = ThemeTypography(
        pageTitle = TextToken(44.sp, FontWeight.Black, (-0.8).sp, 1.1f),
        sectionTitle = TextToken(36.sp, FontWeight.Black, (-0.7).sp, 1.15f),
        eyebrow = TextToken(11.sp, FontWeight.Bold, 1.3.sp, 1f, uppercase = true),
        numericBadge = TextToken(22.sp, FontWeight.Black, (-0.5).sp, 1f, FontFamily.Monospace),
        material = appTypography,
        rootMaterial = rootTypography,
    )

    private val surfaces = ThemeSurfaces(SurfaceMaterial.Ruled, ambientLight = false, shadows = false)
    private val recipes = ThemeRecipes(HeaderStyle.Display, ListRowStyle.RuledStrip, TabBarStyle.RuledBar)

    private fun suite(dark: Boolean) = ResolvedThemeSuite(
        id = metadata.id,
        isDark = dark,
        colors = ThemeColors(
            palette = if (dark) ModernistDarkPalette else ModernistLightPalette,
            materialScheme = if (dark) modernistDarkScheme else modernistLightScheme,
            rootMaterialScheme = if (dark) modernistDarkScheme else modernistLightScheme,
            headerRule = if (dark) InkDark else InkLight,
            separator = if (dark) SeparatorDark else SeparatorLight,
            eyebrow = if (dark) AccentTextDark else AccentTextLight,
            statusPending = StatusPending,
        ),
        geometry = geometry,
        typography = typography,
        surfaces = surfaces,
        recipes = recipes,
    )

    private val light = suite(dark = false)
    private val dark = suite(dark = true)

    override fun resolve(context: ThemeContext): ResolvedThemeSuite = if (context.isDark) dark else light
}
