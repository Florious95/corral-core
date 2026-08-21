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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.terminal.TerminalColor
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 终端自绘色板的取色入口（078 §2 / 080 / 083 §2 / 085 §1.5）。
 *
 * Scheme 的 16+fg/bg 来自当前槽的上游主题；[userBlockBg] 仍按外壳深浅取 APP 值。
 * [Light]/[Dark] 保留为目录损坏时的缺省回退，不是用户可选的「原厂绿」。
 *
 * 083 §2 触发保留：索引 0/16 背景→纸色、254/近白→userBlock、真彩背景亮度守卫。
 * 未命中的 256 立方/灰阶与 24 位真彩按 OKLab 投影到当前色板（契约 085）。
 */
object TermPalette {

    const val SOURCE = "app-theme"

    /** Claude Code 用户消息/recap 块用的 256 色索引（SGR 48;5;254）。 */
    const val USER_MESSAGE_INDEX = 254

    /** xterm 色立方原点，常被当成「整屏黑」（SGR 48;5;16）。 */
    const val CUBE_BLACK_INDEX = 16

    /** 近白 256 色：立方白 / 灰阶顶端。浅底上当作用户块，不留纯白。 */
    private val NEAR_WHITE_INDEXES = setOf(231, 253, 255)

    /** 原色亮度 ≤ 此值视为「终端黑」（整屏底），映射到 [Scheme.defaultBg]。 */
    const val SCREEN_BLACK_LUMA_MAX = 32

    /** 原色亮度 ≥ 此值视为「高亮白块」，浅底上压到用户块。 */
    const val HIGHLIGHT_WHITE_LUMA_MIN = 220

    /** 无色相：max-min ≤ 此值的近白走 userBlock，不按色相缩放。 */
    const val ACHROMA_MAX = 8

    private const val CHROMA_INPUT = 0.08
    private const val CHROMA_SLOT = 0.06
    private const val TIE_EPS = 1e-6
    private const val CONTRAST_MIN = 3.0

    data class Scheme(
        val defaultBg: Int,
        val defaultFg: Int,
        val userBlockBg: Int,
        val ansi16: Map<Int, Int>,
        val source: String = SOURCE,
        val cursor: Int? = null,
        val selection: Int? = null,
    ) {
        val xterm256: IntArray = IntArray(256) { i ->
            fun cube(v: Int): Int = if (v == 0) 0 else 55 + 40 * v
            when {
                i < 16 -> ansi16[i] ?: pack(128, 128, 128)
                i == USER_MESSAGE_INDEX -> userBlockBg
                i < 232 -> {
                    val c = i - 16
                    pack(cube(c / 36), cube(c / 6 % 6), cube(c % 6))
                }
                else -> {
                    val v = 8 + 10 * (i - 232)
                    pack(v, v, v)
                }
            }
        }

        internal val fgSlots: List<Slot> = roleSlots(background = false)
        internal val bgSlots: List<Slot> = roleSlots(background = true)
        internal val fgChroma: List<Slot> = fgSlots.filter { it.chroma >= CHROMA_SLOT }
        internal val fgAchroma: List<Slot> = fgSlots.filter { it.chroma < CHROMA_SLOT }
        internal val bgChroma: List<Slot> = bgSlots.filter { it.chroma >= CHROMA_SLOT }
        internal val bgAchroma: List<Slot> = bgSlots.filter { it.chroma < CHROMA_SLOT }

        fun slotArgbSet(): Set<Int> = buildSet {
            add(defaultBg)
            add(defaultFg)
            add(userBlockBg)
            ansi16.values.forEach { add(it) }
        }

        private fun roleSlots(background: Boolean): List<Slot> {
            val out = ArrayList<Slot>(17)
            if (background) {
                out += Slot(defaultBg, toOkLab(defaultBg), ansiIndex = null, defaultFg = false, defaultBg = true)
            } else {
                out += Slot(defaultFg, toOkLab(defaultFg), ansiIndex = null, defaultFg = true, defaultBg = false)
            }
            for (i in 0..15) {
                val argb = ansi16[i] ?: continue
                out += Slot(argb, toOkLab(argb), ansiIndex = i, defaultFg = false, defaultBg = false)
            }
            return out
        }
    }

    internal data class Slot(
        val argb: Int,
        val lab: OkLab,
        val ansiIndex: Int?,
        val defaultFg: Boolean,
        val defaultBg: Boolean,
    ) {
        val chroma: Double get() = hypot(lab.a, lab.b)
        fun tieRank(): Int = when {
            ansiIndex != null -> ansiIndex
            defaultFg -> 16
            defaultBg -> 17
            else -> 18
        }
    }

    internal data class OkLab(val L: Double, val a: Double, val b: Double)

    val Light: Scheme = schemeFrom(TerminalPaletteLight)
    val Dark: Scheme = schemeFrom(TerminalPaletteDark)

    private val lock = Any()
    private var store: TermThemeStore? = null
    private var overrideSelection: TermThemeSelection? = null
    private var cachedKey: TermThemeSelection? = null
    private var cachedLight: Scheme? = null
    private var cachedDark: Scheme? = null
    @Volatile private var tablesLight: RemapTables? = null
    @Volatile private var tablesDark: RemapTables? = null

    /** 256 索引预计算 + 真彩 memo。主题键变了整体换表，不改投影结果。 */
    private class RemapTables(
        val indexedFg: IntArray,
        val indexedBg: IntArray,
        val rgbFg: HashMap<Int, Int>,
        val rgbBg: HashMap<Int, Int>,
    )

    fun bind(store: TermThemeStore) {
        synchronized(lock) {
            this.store = store
            cachedKey = null
            tablesLight = null
            tablesDark = null
        }
    }

    fun bindSelectionForTest(lightFamilyId: String, darkFamilyId: String) {
        synchronized(lock) {
            overrideSelection = TermThemeSelection(lightFamilyId, darkFamilyId)
            cachedKey = null
            tablesLight = null
            tablesDark = null
        }
    }

    fun resetBindingForTest() {
        synchronized(lock) {
            store = null
            overrideSelection = null
            cachedKey = null
            cachedLight = null
            cachedDark = null
            tablesLight = null
            tablesDark = null
        }
    }

    fun invalidate() {
        synchronized(lock) {
            cachedKey = null
            tablesLight = null
            tablesDark = null
        }
    }

    fun of(dark: Boolean): Scheme = synchronized(lock) {
        val sel = overrideSelection ?: store?.load() ?: TermThemeSelection.DEFAULT
        if (sel != cachedKey) {
            cachedKey = sel
            cachedLight = assembleSlot(dark = false, familyId = sel.lightFamilyId, slot = "light")
            cachedDark = assembleSlot(dark = true, familyId = sel.darkFamilyId, slot = "dark")
            tablesLight = buildTables(cachedLight!!)
            tablesDark = buildTables(cachedDark!!)
        }
        if (dark) cachedDark!! else cachedLight!!
    }

    fun token(dark: Boolean): String {
        val pal = of(dark)
        val prefix = if (dark) "term-theme-dark" else "term-theme-light"
        return "$prefix source=${pal.source}"
    }

    fun asTerminalPalette(dark: Boolean): TerminalPalette {
        val pal = of(dark)
        val app = if (dark) TerminalPaletteDark else TerminalPaletteLight
        val colors = TermSchemeCatalog.colorsBySourceFile[pal.source]
        if (colors == null) return if (dark) TerminalPaletteDark else TerminalPaletteLight
        return TerminalPalette(
            background = argbColor(pal.defaultBg),
            foreground = argbColor(pal.defaultFg),
            userBlockBackground = app.userBlockBackground,
            userBlockForeground = app.userBlockForeground,
            cursor = argbColor(colors.cursor),
            selection = colors.selection?.let { argbColor(it) } ?: app.selection,
            ansi = (0..15).map { i -> argbColor(pal.ansi16[i] ?: pack(128, 128, 128)) },
        )
    }

    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * 终端色 → ARGB。083 特例先命中；其余 256/真彩投影到当前 Scheme 色板。
     * [againstBg] 仅前景对比度修补对照；绘制层未传入时对照纸色。
     */
    fun colorFor(
        color: TerminalColor,
        background: Boolean,
        dark: Boolean,
        againstBg: Int? = null,
    ): Int {
        val pal = of(dark)
        val tables = if (dark) tablesDark else tablesLight
        val against = againstBg ?: pal.defaultBg
        val defaultAgainst = against == pal.defaultBg
        return when (color) {
            TerminalColor.Default -> if (background) pal.defaultBg else pal.defaultFg
            is TerminalColor.Indexed -> {
                val i = color.index
                if (tables != null && defaultAgainst && i in 0..255) {
                    if (background) tables.indexedBg[i] else tables.indexedFg[i]
                } else {
                    indexed(i, background, pal, againstBg)
                }
            }
            is TerminalColor.Rgb -> {
                val raw = pack(color.r, color.g, color.b)
                if (tables != null && defaultAgainst) {
                    val map = if (background) tables.rgbBg else tables.rgbFg
                    val hit = map[raw]
                    if (hit != null) hit
                    else {
                        val v = if (background) {
                            guardRgbBg(raw, pal, againstBg)
                        } else {
                            project(raw, background = false, pal, againstBg)
                        }
                        map[raw] = v
                        v
                    }
                } else if (background) {
                    guardRgbBg(raw, pal, againstBg)
                } else {
                    project(raw, background = false, pal, againstBg)
                }
            }
        }
    }

    private fun buildTables(pal: Scheme): RemapTables {
        val fg = IntArray(256)
        val bg = IntArray(256)
        for (i in 0..255) {
            fg[i] = indexed(i, background = false, pal, againstBg = null)
            bg[i] = indexed(i, background = true, pal, againstBg = null)
        }
        return RemapTables(fg, bg, HashMap(64), HashMap(64))
    }

    private fun indexed(index: Int, background: Boolean, pal: Scheme, againstBg: Int?): Int {
        if (background && (index == 0 || index == CUBE_BLACK_INDEX)) return pal.defaultBg
        if (index == USER_MESSAGE_INDEX) return pal.userBlockBg
        if (background && index in NEAR_WHITE_INDEXES) return pal.userBlockBg
        if (index in 0..15) return pal.ansi16[index] ?: pack(128, 128, 128)
        val raw = xtermCubeOrGray(index)
        return if (background) guardRgbBg(raw, pal, againstBg) else project(raw, background = false, pal, againstBg)
    }

    /**
     * 真彩 / 256 扩展底：083 亮度守卫触发条件不动；else 与浅底 scaleLuma 改为投影 / userBlock。
     */
    private fun guardRgbBg(raw: Int, pal: Scheme, againstBg: Int?): Int {
        val y = luma(raw)
        val r = (raw shr 16) and 0xFF
        val g = (raw shr 8) and 0xFF
        val b = raw and 0xFF
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        return when {
            y <= SCREEN_BLACK_LUMA_MAX -> pal.defaultBg
            y >= HIGHLIGHT_WHITE_LUMA_MIN && chroma <= ACHROMA_MAX -> pal.userBlockBg
            y >= HIGHLIGHT_WHITE_LUMA_MIN -> pal.userBlockBg
            else -> project(raw, background = true, pal, againstBg)
        }
    }

    private fun project(argb: Int, background: Boolean, pal: Scheme, againstBg: Int?): Int {
        val lab = toOkLab(argb)
        val role = if (background) pal.bgSlots else pal.fgSlots
        val gated = chromaGate(lab, pal, background)
        val slots = if (gated.isEmpty()) {
            DiagLog.record(
                "term-remap-chroma",
                "source=${pal.source} rgb=${rgbTriple(argb)} inputC=${hypot(lab.a, lab.b)} " +
                    "empty=chroma_gate role=${if (background) "bg" else "fg"}",
                coalesceKey = "chroma|${pal.source}|$argb|${if (background) "bg" else "fg"}",
            )
            role
        } else {
            gated
        }
        var picked = nearest(lab, slots)
        if (!background) {
            val ref = againstBg ?: pal.defaultBg
            picked = contrastRepair(lab, picked, slots, pal, ref)
        }
        return picked.argb
    }

    private fun chromaGate(lab: OkLab, pal: Scheme, background: Boolean): List<Slot> {
        val c = hypot(lab.a, lab.b)
        return if (c >= CHROMA_INPUT) {
            if (background) pal.bgChroma else pal.fgChroma
        } else {
            if (background) pal.bgAchroma else pal.fgAchroma
        }
    }

    private fun nearest(lab: OkLab, slots: List<Slot>): Slot {
        var best = slots.first()
        var bestD = dist(lab, best.lab)
        for (i in 1 until slots.size) {
            val s = slots[i]
            val d = dist(lab, s.lab)
            val better = when {
                d + TIE_EPS < bestD -> true
                kotlin.math.abs(d - bestD) < TIE_EPS && s.tieRank() < best.tieRank() -> true
                else -> false
            }
            if (better) {
                best = s
                bestD = d
            }
        }
        return best
    }

    private fun contrastRepair(
        input: OkLab,
        fg0: Slot,
        slots: List<Slot>,
        pal: Scheme,
        bg: Int,
    ): Slot {
        val before = contrast(fg0.argb, bg)
        if (before >= CONTRAST_MIN) return fg0
        val pair = pairSlot(fg0, slots)
        val pairOk = pair.filter { contrast(it.argb, bg) >= CONTRAST_MIN }
            .minWithOrNull(compareBy({ dist(input, it.lab) }, { it.tieRank() }))
        if (pairOk != null) return pairOk
        val readable = slots.filter { contrast(it.argb, bg) >= CONTRAST_MIN }
        val after = if (readable.isEmpty()) {
            pal.fgSlots.first { it.defaultFg }
        } else {
            nearest(input, readable)
        }
        DiagLog.record(
            "term-remap-contrast",
            "source=${pal.source} fg_rgb=${rgbTriple(fg0.argb)} bg=0x${hex(bg)} " +
                "contrast_before=$before contrast_after=${contrast(after.argb, bg)}",
            coalesceKey = "contrast|${pal.source}|${fg0.argb}|$bg|${after.argb}",
        )
        return after
    }

    private fun pairSlot(fg0: Slot, slots: List<Slot>): List<Slot> {
        val n = fg0.ansiIndex
        val targets = when {
            n != null && n in 0..7 -> listOf(n + 8)
            n != null && n in 8..15 -> listOf(n - 8)
            fg0.defaultFg -> listOf(7, 15)
            else -> emptyList()
        }
        return slots.filter { it.ansiIndex in targets }
    }

    private fun assembleSlot(dark: Boolean, familyId: String, slot: String): Scheme {
        val family = resolveFamily(familyId, slot)
        val sourceFile = if (dark) family.darkSource else family.lightSource
        val colors = TermSchemeCatalog.colorsBySourceFile[sourceFile]
        val app = if (dark) TerminalPaletteDark else TerminalPaletteLight
        if (colors == null || colors.ansi.size != 16) {
            DiagLog.record(
                "term-theme",
                "raw=$familyId catalogHit=false slot=$slot sourceFile=$sourceFile fallback=app-theme",
            )
            return if (dark) Dark else Light
        }
        return Scheme(
            defaultBg = colors.background,
            defaultFg = colors.foreground,
            userBlockBg = app.userBlockBackground.toArgb(),
            ansi16 = colors.ansi.mapIndexed { i, c -> i to c }.toMap(),
            source = colors.sourceFile,
            cursor = colors.cursor,
            selection = colors.selection,
        )
    }

    private fun resolveFamily(familyId: String, slot: String): TermThemeFamilyDef {
        val hit = TermSchemeCatalog.families.find { it.id == familyId }
        DiagLog.record(
            "term-theme",
            "raw=$familyId catalogHit=${hit != null} slot=$slot",
        )
        if (hit != null) return hit
        val vesper = TermSchemeCatalog.families.find { it.id == TermThemeStore.DEFAULT_FAMILY_ID }
        if (vesper != null) return vesper
        return TermThemeFamilyDef(
            id = TermThemeStore.DEFAULT_FAMILY_ID,
            title = "Vesper",
            lightSource = "Vesper.itermcolors",
            darkSource = "Vesper.itermcolors",
        )
    }

    private fun schemeFrom(p: TerminalPalette): Scheme = Scheme(
        defaultBg = p.background.toArgb(),
        defaultFg = p.foreground.toArgb(),
        userBlockBg = p.userBlockBackground.toArgb(),
        ansi16 = p.ansi.mapIndexed { i, c -> i to c.toArgb() }.toMap(),
        cursor = p.cursor.toArgb(),
        selection = p.selection.toArgb(),
    )

    private fun xtermCubeOrGray(index: Int): Int {
        fun cube(v: Int): Int = if (v == 0) 0 else 55 + 40 * v
        return when {
            index < 16 -> pack(128, 128, 128)
            index < 232 -> {
                val c = index - 16
                pack(cube(c / 36), cube(c / 6 % 6), cube(c % 6))
            }
            else -> {
                val v = 8 + 10 * (index - 232)
                pack(v, v, v)
            }
        }
    }

    internal fun toOkLab(argb: Int): OkLab {
        val r = linearSrgb((argb shr 16) and 0xFF)
        val g = linearSrgb((argb shr 8) and 0xFF)
        val b = linearSrgb(argb and 0xFF)
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
        val l_ = cbrt(l)
        val m_ = cbrt(m)
        val s_ = cbrt(s)
        return OkLab(
            L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
            a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
            b = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_,
        )
    }

    private fun linearSrgb(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun dist(a: OkLab, b: OkLab): Double {
        val dL = a.L - b.L
        val da = a.a - b.a
        val db = a.b - b.b
        return hypot(dL, hypot(da, db))
    }

    private fun relativeLuma(argb: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = relativeLuma(a)
        val l2 = relativeLuma(b)
        val hi = max(l1, l2)
        val lo = min(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun rgbTriple(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return "$r,$g,$b"
    }

    private fun hex(argb: Int): String = (argb.toLong() and 0xffffffffL).toString(16)

    private fun argbColor(argb: Int): Color = Color(argb.toLong() and 0xFFFFFFFFL)

    private fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
