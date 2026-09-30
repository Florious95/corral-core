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

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Theme Kit 契约：注册表、ID 回退、液态玻璃恒等委托、现代主义交付令牌。 */
class ThemeSuiteTest {

    private val light = ThemeContext(isDark = false)
    private val dark = ThemeContext(isDark = true)

    // ── 注册表 / ID ──

    @Test
    fun registryListsBothSuitesInStableOrderAndDefaultsToLiquidGlass() {
        assertEquals(
            listOf(ThemeId("liquid_glass"), ThemeId("modernist")),
            ThemeRegistry.suites.map { it.metadata.id },
        )
        assertSame(LiquidGlassThemeSuite, ThemeRegistry.default)
        assertSame(LiquidGlassThemeSuite, ThemeRegistry[ThemeId.LiquidGlass])
        assertSame(ModernistThemeSuite, ThemeRegistry[ThemeId.Modernist])
        assertEquals("液态玻璃", LiquidGlassThemeSuite.metadata.title)
        assertEquals("现代主义", ModernistThemeSuite.metadata.title)
    }

    @Test
    fun unknownOrMissingIdFallsBackToDefault() {
        assertSame(LiquidGlassThemeSuite, ThemeRegistry[ThemeId("neon")])
        assertEquals(ThemeId.LiquidGlass, ThemeRegistry.idOrDefault(null))
        assertEquals(ThemeId.LiquidGlass, ThemeRegistry.idOrDefault(""))
        assertEquals(ThemeId.LiquidGlass, ThemeRegistry.idOrDefault("Modernist"))
        assertEquals(ThemeId.Modernist, ThemeRegistry.idOrDefault("modernist"))
        assertEquals(ThemeId.LiquidGlass, ThemeRegistry.idOrDefault("liquid_glass"))
        assertEquals(ThemeId.LiquidGlass, ThemeRegistry.resolve(ThemeId("neon"), light).id)
    }

    @Test
    fun duplicateIdsOrUnregisteredDefaultAreRegistrationErrors() {
        assertThrows(IllegalArgumentException::class.java) {
            checkRegistry(listOf(LiquidGlassThemeSuite, LiquidGlassThemeSuite), ThemeId.LiquidGlass)
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkRegistry(listOf(LiquidGlassThemeSuite), ThemeId.Modernist)
        }
        assertSame(
            ModernistThemeSuite,
            checkRegistry(listOf(LiquidGlassThemeSuite, ModernistThemeSuite), ThemeId.Modernist),
        )
    }

    @Test
    fun resolveIsPureAndDeterministic() {
        for (suite in ThemeRegistry.suites) {
            for (context in listOf(light, dark)) {
                val a = suite.resolve(context)
                val b = suite.resolve(ThemeContext(isDark = context.isDark))
                assertSame("${suite.metadata.id} 同一 context 必须返回同一份令牌", a, b)
                assertEquals(suite.metadata.id, a.id)
                assertEquals(context.isDark, a.isDark)
            }
        }
    }

    // ── 液态玻璃：零变异恒等委托 ──

    @Test
    fun liquidGlassDelegatesToBaselineObjectsByIdentity() {
        val l = LiquidGlassThemeSuite.resolve(light)
        val d = LiquidGlassThemeSuite.resolve(dark)
        assertSame(LightPalette, l.colors.palette)
        assertSame(DarkPalette, d.colors.palette)
        assertSame(appLightScheme, l.colors.materialScheme)
        assertSame(appDarkScheme, d.colors.materialScheme)
        assertSame(rootLightColorScheme, l.colors.rootMaterialScheme)
        assertSame(rootDarkColorScheme, d.colors.rootMaterialScheme)
        assertSame(appTypography, l.typography.material)
        assertSame(rootTypography, l.typography.rootMaterial)
        assertSame(rootShapes, l.geometry.rootMaterialShapes)
        assertEquals(LightPalette.divider, l.colors.headerRule)
        assertEquals(DarkPalette.divider, d.colors.headerRule)
        assertEquals(LightPalette.metaText, l.colors.eyebrow)
    }

    @Test
    fun liquidGlassBaselinePaletteValuesAreUntouched() {
        // 24098b930 基线抽样：改动 Theme Kit 不得回写旧色板。
        assertEquals(Color(0xFFF4F5F8), LightPalette.screenBackground)
        assertEquals(Color(0xFF070B14), DarkPalette.screenBackground)
        assertEquals(Color(0xFF0B57D0), LightPalette.accent)
        assertEquals(Color(0xFF77A6FF), DarkPalette.accent)
        assertEquals(Color(0xADFFFFFF), LightPalette.glassCardFill)
        assertEquals(Color(0x85131C2E), DarkPalette.glassCardFill)
        assertEquals(Color(0xB3FFFFFF), LightPalette.glassSurface)
        assertEquals(Color(0x4DFAFBFD), LightPalette.navBackground)
    }

    @Test
    fun liquidGlassGeometryAndTypeReuseExistingTokens() {
        val g = LiquidGlassThemeSuite.resolve(light).geometry
        assertFalse(g.sharpCorners)
        assertEquals(Dims.hairline, g.hairline)
        assertEquals(Dims.hairline, g.headerRule)
        assertEquals(Dims.cardHMargin, g.listRowHMargin)
        assertEquals(Dims.cardVGap, g.listRowGap)
        assertEquals(Dims.rowHeightWithSubtitle, g.workspaceRowHeight)
        assertEquals(Dims.rowHeightWithSubtitle, g.sessionRowHeight)
        assertEquals(Dims.navBarHeight, g.tabBarHeight)
        assertEquals(Dims.navFloatMargin, g.tabBarBottomGap)
        assertEquals(12.dp, g.sheetOuterPadding)
        assertEquals(Radii.card, g.corner(Radii.card))
        val shape = RoundedCornerShape(Radii.card)
        assertSame("液态玻璃不得替换任何形状", shape, g.shape(shape))
        assertSame(CircleShape, g.shape(CircleShape))

        val t = LiquidGlassThemeSuite.resolve(light).typography
        assertEquals(TypeSizes.screenTitle, t.pageTitle.size)
        assertEquals(FontWeight.Bold, t.pageTitle.weight)
        assertEquals((-0.5).sp, t.pageTitle.tracking)
        assertEquals(TypeSizes.titleLineHeight, t.pageTitle.lineHeight)
        assertEquals(TypeSizes.screenTitleSecondary, t.sectionTitle.size)
        assertEquals((-0.4).sp, t.sectionTitle.tracking)
        assertEquals(1.2f, t.sectionTitle.lineHeight)
        assertEquals(FontFamily.Default, t.sectionTitle.family)
        assertFalse(t.sectionTitle.uppercase)

        val s = LiquidGlassThemeSuite.resolve(light).surfaces
        assertTrue(s.isGlass)
        assertTrue(s.ambientLight)
        assertTrue(s.shadows)
        val r = LiquidGlassThemeSuite.resolve(light).recipes
        assertEquals(ThemeRecipes(HeaderStyle.Compact, ListRowStyle.FloatingCard, TabBarStyle.FloatingCapsule), r)
    }

    // ── 现代主义令牌 ──

    @Test
    fun modernistColorsMatchHandoffTokens() {
        val l = ModernistThemeSuite.resolve(light)
        val d = ModernistThemeSuite.resolve(dark)
        assertSame(ModernistLightPalette, l.colors.palette)
        assertSame(ModernistDarkPalette, d.colors.palette)

        val lp = l.colors.palette
        assertEquals(Color(0xFFF3F2F2), lp.screenBackground)
        assertEquals(Color(0xFFEAE9E9), lp.inputBackground)
        assertEquals(Color(0xFFF8F4F4), lp.cardBackground)
        assertEquals(Color(0xFF201E1D), lp.titleText)
        assertEquals(Color(0xFF605D5D), lp.metaText)
        assertEquals(Color(0xFF7D7979), lp.pathText)
        assertEquals(Color(0xFFEC3013), lp.accent)
        assertEquals(Color(0xFFF3F2F2), lp.onAccent)
        assertEquals(Color(0xFFBAB6B6), lp.cardBorder)
        assertEquals(Color(0xFF201E1D), l.colors.headerRule)
        assertEquals(Color(0xFFAE1800), l.colors.eyebrow)
        assertEquals(Color(0x66201E1D), l.colors.separator)

        val dp = d.colors.palette
        assertEquals(Color(0xFF201E1D), dp.screenBackground)
        assertEquals(Color(0xFF2D2B2B), dp.inputBackground)
        assertEquals(Color(0xFF2D2B2B), dp.cardBackground)
        assertEquals(Color(0xFFF3F2F2), dp.titleText)
        assertEquals(Color(0xFFBAB6B6), dp.metaText)
        assertEquals(Color(0xFFEC3013), dp.accent)
        assertEquals(Color(0xFF605D5D), dp.cardBorder)
        assertEquals(Color(0xFFF3F2F2), d.colors.headerRule)
        assertEquals(Color(0xFFFF563C), d.colors.eyebrow)
        assertEquals(Color(0x47F3F2F2), d.colors.separator)

        // 瑞士纸白 / 墨色：不用纯白、纯黑底；surface tint 关闭（平面不着色）。
        assertNotEquals(Color.White, lp.screenBackground)
        assertNotEquals(Color.Black, dp.screenBackground)
        assertEquals(Color.Transparent, l.colors.materialScheme.surfaceTint)
        assertEquals(Color(0xFFEC3013), l.colors.materialScheme.primary)
        assertSame(l.colors.materialScheme, l.colors.rootMaterialScheme)
    }

    @Test
    fun modernistGeometryIsSharpFlatAndRuled() {
        val s = ModernistThemeSuite.resolve(light)
        val g = s.geometry
        assertTrue(g.sharpCorners)
        assertEquals(0.dp, g.corner(Radii.glassPanel))
        assertSame(RectangleShape, g.shape(CircleShape))
        assertSame(RectangleShape, g.shape(RoundedCornerShape(Radii.card)))
        assertEquals(1.dp, g.hairline)
        assertEquals(2.dp, g.headerRule)
        assertEquals(0.dp, g.listRowHMargin)
        assertEquals(0.dp, g.listRowGap)
        assertEquals(68.dp, g.workspaceRowHeight)
        assertEquals(66.dp, g.sessionRowHeight)
        assertEquals(56.dp, g.numberColumnWidth)
        assertEquals(20.dp, g.pageInset)
        assertEquals(0.dp, g.sheetOuterPadding)

        val density = Density(3f)
        val box = Size(300f, 120f)
        listOf(g.rootMaterialShapes.extraSmall, g.rootMaterialShapes.small, g.rootMaterialShapes.medium,
            g.rootMaterialShapes.large, g.rootMaterialShapes.extraLarge).forEach { shape ->
            assertEquals(0f, shape.topStart.toPx(box, density))
            assertEquals(0f, shape.bottomEnd.toPx(box, density))
        }

        assertEquals(SurfaceMaterial.Ruled, s.surfaces.material)
        assertFalse(s.surfaces.isGlass)
        assertFalse("无模糊背景光斑", s.surfaces.ambientLight)
        assertFalse("无外投影", s.surfaces.shadows)
        assertEquals(ThemeRecipes(HeaderStyle.Display, ListRowStyle.RuledStrip, TabBarStyle.RuledBar), s.recipes)
    }

    @Test
    fun modernistTypographyIsHeavySwissHierarchy() {
        val t = ModernistThemeSuite.resolve(light).typography
        assertEquals(44.sp, t.pageTitle.size)
        assertEquals(FontWeight.Black, t.pageTitle.weight)
        assertEquals((-0.8).sp, t.pageTitle.tracking)
        assertEquals(36.sp, t.sectionTitle.size)
        assertEquals(FontWeight.Black, t.sectionTitle.weight)
        assertEquals(11.sp, t.eyebrow.size)
        assertEquals(1.3.sp, t.eyebrow.tracking)
        assertTrue(t.eyebrow.uppercase)
        assertEquals(22.sp, t.numericBadge.size)
        assertEquals(FontFamily.Monospace, t.numericBadge.family)
    }

    @Test
    fun darkDetectionCoversBothSuitesAndMatchesLegacyIdentityCheck() {
        assertFalse(LightPalette.isDark)
        assertTrue(DarkPalette.isDark)
        assertFalse(ModernistLightPalette.isDark)
        assertTrue(ModernistDarkPalette.isDark)
        for (suite in ThemeRegistry.suites) {
            assertFalse(suite.resolve(light).colors.palette.isDark)
            assertTrue(suite.resolve(dark).colors.palette.isDark)
        }
    }
}
