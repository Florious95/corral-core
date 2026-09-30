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

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 液态玻璃（24098b930 基线）：零变异恒等委托。
 * 色板、M3 色槽 / 字阶 / 形状直接引用 [LightPalette] / [DarkPalette]、[appLightScheme] / [appDarkScheme]、
 * [rootLightColorScheme] / [rootDarkColorScheme]、[appTypography] / [rootTypography]、[rootShapes] 同一对象；
 * 几何与字号直接引用 [Dims] / [TypeSizes] 原值或组件里原有的字面量。⛔ 不复制、不重算、不近似。
 */
object LiquidGlassThemeSuite : ThemeSuite {
    override val metadata = ThemeMetadata(ThemeId.LiquidGlass, title = "液态玻璃", englishTitle = "Liquid Glass")

    private val geometry = ThemeGeometry(
        sharpCorners = false,
        pageInset = Dims.screenHPadding,
        hairline = Dims.hairline,
        headerRule = Dims.hairline,
        listRowHMargin = Dims.cardHMargin,
        listRowGap = Dims.cardVGap,
        workspaceRowHeight = Dims.rowHeightWithSubtitle,
        sessionRowHeight = Dims.rowHeightWithSubtitle,
        numberColumnWidth = 26.dp,
        tabBarHeight = Dims.navBarHeight,
        tabBarBottomGap = Dims.navFloatMargin,
        sheetOuterPadding = 12.dp,
        rootMaterialShapes = rootShapes,
    )

    private val typography = ThemeTypography(
        pageTitle = TextToken(TypeSizes.screenTitle, FontWeight.Bold, (-0.5).sp, TypeSizes.titleLineHeight),
        sectionTitle = TextToken(TypeSizes.screenTitleSecondary, FontWeight.Bold, (-0.4).sp, 1.2f),
        eyebrow = TextToken(TypeSizes.headerMeta, FontWeight.Medium, 0.2.sp, 1f, FontFamily.Monospace),
        numericBadge = TextToken(14.sp, FontWeight.SemiBold, 0.sp, 1f, FontFamily.Monospace),
        material = appTypography,
        rootMaterial = rootTypography,
    )

    private val surfaces = ThemeSurfaces(SurfaceMaterial.LiquidGlass, ambientLight = true, shadows = true)
    private val recipes = ThemeRecipes(HeaderStyle.Compact, ListRowStyle.FloatingCard, TabBarStyle.FloatingCapsule)

    private fun suite(dark: Boolean): ResolvedThemeSuite {
        val palette = if (dark) DarkPalette else LightPalette
        return ResolvedThemeSuite(
            id = metadata.id,
            isDark = dark,
            colors = ThemeColors(
                palette = palette,
                materialScheme = if (dark) appDarkScheme else appLightScheme,
                rootMaterialScheme = if (dark) rootDarkColorScheme else rootLightColorScheme,
                headerRule = palette.divider,
                separator = palette.divider,
                eyebrow = palette.metaText,
                statusPending = palette.idleChipText,
            ),
            geometry = geometry,
            typography = typography,
            surfaces = surfaces,
            recipes = recipes,
        )
    }

    private val light = suite(dark = false)
    private val dark = suite(dark = true)

    override fun resolve(context: ThemeContext): ResolvedThemeSuite = if (context.isDark) dark else light
}
