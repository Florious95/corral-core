package dev.agentmirror.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 终端正文（SurfaceView + Canvas 逐字形绘制）需要的全部数值。
 * 这里 ⛔ 不提供任何 Composable —— 正文归你的自绘层，本文件只交色板和度量。
 */

// ─────────────────────────────────────────────────────────────
// 色板
// ─────────────────────────────────────────────────────────────
/**
 * ansi 顺序固定为标准 16 色：
 * 0 black  1 red  2 green  3 yellow  4 blue  5 magenta  6 cyan  7 white
 * 8 brightBlack … 15 brightWhite
 */
data class TerminalPalette(
    val background: Color,
    val foreground: Color,
    /** 用户自己发出的消息块底色（❯ 那一段） */
    val userBlockBackground: Color,
    val userBlockForeground: Color,
    val cursor: Color,
    val selection: Color,
    val ansi: List<Color>,
) {
    init { require(ansi.size == 16) { "ANSI palette must have exactly 16 entries" } }
}

/** 深色外壳下的 APP 终端兜底色板（目录损坏时 [TermPalette.Dark] 回退用）。 */
val TerminalPaletteDark = TerminalPalette(
    background = Color(0xFF0A1120),
    foreground = Color(0xFFC4D0E6),
    userBlockBackground = Color(0xFF10241F),
    userBlockForeground = Color(0xFFDCF3EF),
    cursor = Color(0xFF4FD1C0),
    selection = Color(0x3D4FD1C0),
    ansi = listOf(
        Color(0xFF16203A), // 0 black
        Color(0xFFF0879F), // 1 red
        Color(0xFF6FD79B), // 2 green
        Color(0xFFE6C07B), // 3 yellow
        Color(0xFF8FA9F5), // 4 blue
        Color(0xFFC792EA), // 5 magenta
        Color(0xFF5EDDCB), // 6 cyan
        Color(0xFFC4D0E6), // 7 white
        Color(0xFF5A6A88), // 8 bright black
        Color(0xFFFF9DB1), // 9 bright red
        Color(0xFF8CE5B4), // 10 bright green
        Color(0xFFF3D18C), // 11 bright yellow
        Color(0xFFA9BEFF), // 12 bright blue
        Color(0xFFDDAFFF), // 13 bright magenta
        Color(0xFF7FEEDE), // 14 bright cyan
        Color(0xFFEAF0FA), // 15 bright white
    ),
)

/**
 * 浅色方案下的终端色板。
 * ⚠️ 取舍说明：设计稿里浅色主题的终端正文仍然是深色屏（只有外壳变浅），
 * 因为 ANSI 16 色在浅底上很难同时保证可读与可辨。这一套是给「用户强制全亮」时的兜底，
 * 默认请继续用 TerminalPaletteDark。切到这套会明显破坏视觉一致性。
 */
val TerminalPaletteLight = TerminalPalette(
    background = Color(0xFFF7F8FB),
    foreground = Color(0xFF1E2637),
    userBlockBackground = Color(0xFFE6F5F2),
    userBlockForeground = Color(0xFF0E3B35),
    cursor = Color(0xFF0E7F72),
    selection = Color(0x3312A594),
    ansi = listOf(
        Color(0xFFE7EAF0), // 0 black（浅底局部暗格；整屏 40m/index0 由 TermPalette 改走 background）
        Color(0xFFC03A62), // 1 red
        Color(0xFF1F7A4D), // 2 green
        Color(0xFF8A6212), // 3 yellow
        Color(0xFF2A4FB8), // 4 blue
        Color(0xFF7B3BA8), // 5 magenta
        Color(0xFF0E7F72), // 6 cyan
        Color(0xFF3D4761), // 7 white
        Color(0xFF98A2B4), // 8 bright black
        Color(0xFFD4527A), // 9 bright red
        Color(0xFF2E9463), // 10 bright green
        Color(0xFFA57A22), // 11 bright yellow
        Color(0xFF3A63D6), // 12 bright blue
        Color(0xFF9350C4), // 13 bright magenta
        Color(0xFF149B8B), // 14 bright cyan
        Color(0xFF101726), // 15 bright white
    ),
)

// ─────────────────────────────────────────────────────────────
// 度量
// ─────────────────────────────────────────────────────────────
/** 终端画布字号档、行高与边距。 */
object TerminalMetrics {
    /** 设置页字号档位，与 SettingsScreen 的 chip 一一对应 */
    val fontSizeSteps: List<Int> = listOf(4, 6, 8, 10, 12, 14, 16, 18, 20)
    val defaultFontSizeSp: Int = 14

    /** 行高倍数：行距 = fontSizePx * 1.62，向上取整成整数像素后作为行步进 */
    const val lineHeightMultiplier = 1.62f

    /**
     * 🔴 首列被裁掉半个字符的修复值。
     * 字形绘制原点必须是 x = paddingLeft（不是 0，也不是 -0.5f）。
     * 083 §3：内 14→6dp。6dp×density3=18px，仍盖得住 t.clip 实测 11–13px 左溢。
     * 屏幕边到首字符 = [Dims.terminalCardMargin] + paddingLeft = 4+6 = 10dp。
     */
    val paddingLeft: Dp = 6.dp
    val paddingRight: Dp = 6.dp
    val paddingTop: Dp = 6.dp
    val paddingBottom: Dp = 6.dp

    /**
     * 列数上限（083 §3 与 padding 减量联动）。
     *
     * 依据：参考真机宽 1260px、density 3 上旧几何（外 8+内 14）约 112 列；
     * padding 减量后若不封顶会涨到 ~120 列，CLI（Claude Code Tips）会切双栏。
     * 112 钉在改前的列数。CJK 占 2 格：4.5 寸（~360dp，内容宽 340dp）上
     * 格宽 ≈ 3.0dp、CJK ≈ 6.1dp（density 3 时 18px），不低于 t.clip 仍需覆盖的溢出。
     */
    const val maxCols: Int = 112

    /**
     * 字符网格步进。
     * cellWidth 必须用等宽字体实测，不要写死：
     *   val advance = paint.measureText("M")          // 单宽 ASCII 一格
     *   cellWidth = ceil(advance)                     // 取整避免亚像素累积漂移
     *   cellHeight = ceil(fontSizePx * lineHeightMultiplier)
     * CJK / 全角字符占 2 格：绘制时 x 前进 2 * cellWidth，
     * 并且 ⛔ 不要对 CJK 单独 measureText 后按实测宽推进 —— 会与网格错位。
     * 判定用 Character.UnicodeBlock 或简易区间：
     *   0x1100..0x115F, 0x2E80..0xA4CF, 0xAC00..0xD7A3, 0xF900..0xFAFF,
     *   0xFE30..0xFE6F, 0xFF00..0xFF60, 0xFFE0..0xFFE6, 0x20000..0x3FFFD
     */
    const val cjkCellSpan = 2
    const val asciiCellSpan = 1

    /** 可视行数由高度反推：rows = (height - paddingTop - paddingBottom) / cellHeight，向下取整 */
    fun rowsFor(heightPx: Float, cellHeightPx: Float, paddingTopPx: Float, paddingBottomPx: Float): Int =
        maxOf(1, ((heightPx - paddingTopPx - paddingBottomPx) / cellHeightPx).toInt())

    /** 可视列数同理，⚠️ 用 floor，宁可右边空半格也不要裁字；再夹 [maxCols] */
    fun colsFor(widthPx: Float, cellWidthPx: Float, paddingLeftPx: Float, paddingRightPx: Float): Int {
        if (cellWidthPx <= 0f) return 1
        val raw = maxOf(1, ((widthPx - paddingLeftPx - paddingRightPx) / cellWidthPx).toInt())
        return minOf(raw, maxCols)
    }

    /** 光标 */
    val cursorWidthFraction = 1f      // 块状光标占满一格；改 0.12f 变成竖线光标
    const val cursorBlinkMs = 1000    // 与外壳里输入框的 ▌ 同频

    /** 用户消息块的圆角与内边距（如果自绘层要给 ❯ 段落画底） */
    val userBlockRadius: Dp = 4.dp
    val userBlockHPadding: Dp = 6.dp

    /** 终端卡片本身（外壳侧）的几何 —— 与 SessionShellScreen 共用 */
    val cardMargin: Dp = Dims.terminalCardMargin
    val cardRadius: Dp = Radii.terminalCard

    /** 状态行（Ctx / bypass 两行）也是转播内容，用同一份等宽度量，字号略小 */
    val statusLineFontSize: TextUnit = 10.5f.sp
    const val statusLineHeightMultiplier = 1.55f
}
