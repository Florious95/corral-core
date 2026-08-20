package dev.agentmirror.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全部写死的数值集中在这里。换色板 / 调密度只改这一个文件。
 * 命名规则：语义名，不是外观名（idleChipBg 而不是 grey100），换主题时语义不变。
 */

// ─────────────────────────────────────────────────────────────
// 间距 · 尺寸
// ─────────────────────────────────────────────────────────────
/** 间距与控件尺寸。 */
object Dims {
    // 屏幕级
    val screenHPadding: Dp = 16.dp
    val listHPaddingStart: Dp = 6.dp      // 星标按钮自带 40dp 触控区，行首内缩小
    val listHPaddingEnd: Dp = 14.dp

    // 顶栏
    val topBarHeight: Dp = 54.dp
    val backButtonSize: Dp = 44.dp        // 触控区，图标视觉尺寸另计
    // 几何折线中心高于标题墨迹（d480 1.83dp / d420 1.90dp）。正值 = 折线下移。
    val topBarBackInkNudge: Dp = 2.dp
    val actionButtonHeight: Dp = 32.dp
    val actionButtonHPadding: Dp = 11.dp

    // 列表行
    val rowHeightWithSubtitle: Dp = 66.dp // 收藏页 / 工作区列表（双行）
    val rowHeightSingleLine: Dp = 60.dp   // 会话列表（单行 + 状态标）
    val rowHeightSheet: Dp = 58.dp        // 「查看」浮层内的行
    val rowGap: Dp = 11.dp
    val subtitleGap: Dp = 3.dp
    val tapTargetMin: Dp = 40.dp          // 星标 / 图标按钮最小触控区
    val providerIconBox: Dp = 32.dp
    val providerIconPad: Dp = 6.dp

    // 状态标
    val statusChipHeight: Dp = 24.dp
    val statusChipHPadding: Dp = 9.dp
    val statusDotSize: Dp = 5.dp

    // 工作区行首的 ❯ 方块
    val workspaceGlyphBox: Dp = 34.dp

    // 底部导航（3b 顶部指示轨）
    val navBarHeight: Dp = 60.dp
    val navRailWidth: Dp = 44.dp
    val navRailThickness: Dp = 2.dp
    val navIconLabelGap: Dp = 5.dp

    // 会话页 · 终端卡片（083 §3：外 8→4，与内 padding 合计 10dp）
    val terminalCardMargin: Dp = 4.dp

    // 会话页 · 功能键排
    val consoleVPadding: Dp = 8.dp
    val consoleHPadding: Dp = 10.dp
    val keyHeight: Dp = 36.dp
    val keyWidthText: Dp = 52.dp          // Esc / Tab
    val keyWidthDanger: Dp = 62.dp        // Ctrl-C
    val keyWidthArrow: Dp = 34.dp
    val keyHeightArrow: Dp = 32.dp
    val keyGap: Dp = 7.dp
    val arrowClusterGap: Dp = 3.dp
    val arrowClusterPadding: Dp = 2.dp

    // 会话页 · 输入条
    val composerRowGap: Dp = 8.dp
    val composerTopGap: Dp = 9.dp
    val plusButtonSize: Dp = 38.dp
    val inputHeight: Dp = 40.dp
    val inputHPadding: Dp = 12.dp
    val sendButtonSize: Dp = 40.dp

    // 设置页
    val cardPadding: Dp = 15.dp
    val cardGap: Dp = 12.dp
    val cardButtonHeight: Dp = 40.dp
    val chipHeight: Dp = 33.dp
    val chipGap: Dp = 5.dp
    val segmentedTrackPadding: Dp = 4.dp
    val segmentedItemHeight: Dp = 34.dp

    // 「查看」浮层
    val sheetGrabberWidth: Dp = 32.dp
    val sheetGrabberHeight: Dp = 4.dp
    val sheetGrabberRowHeight: Dp = 20.dp
    val sheetHeaderBottomPadding: Dp = 12.dp
    val sheetCurrentRailWidth: Dp = 3.dp

    val hairline: Dp = 1.dp
}

// ─────────────────────────────────────────────────────────────
// 圆角
// ─────────────────────────────────────────────────────────────
/** 圆角半径。 */
object Radii {
    val terminalCard: Dp = 14.dp
    val card: Dp = 14.dp
    val cardButton: Dp = 11.dp
    val key: Dp = 9.dp
    val keyArrow: Dp = 8.dp
    val arrowCluster: Dp = 11.dp
    val input: Dp = 13.dp
    val sendButton: Dp = 14.dp
    val plusButton: Dp = 12.dp
    val actionButton: Dp = 9.dp
    val statusChip: Dp = 7.dp
    val pill: Dp = 5.dp                   // LAN / PAIRED 等微型胶囊
    val chip: Dp = 9.dp
    val segmentedTrack: Dp = 12.dp
    val segmentedItem: Dp = 9.dp
    val workspaceGlyphBox: Dp = 10.dp
    val providerIconBox: Dp = 8.dp
    val sheetTop: Dp = 22.dp
    val previewBox: Dp = 10.dp
}

// ─────────────────────────────────────────────────────────────
// 字号 · 字重 · 行高
// 中文一律走系统默认字体（FontFamily.Default），等宽只用于 ASCII 标识符 / 数字 / 按键。
// ─────────────────────────────────────────────────────────────
/** 字号。 */
object TypeSizes {
    val screenTitle: TextUnit = 25.sp             // 收藏 / 工作区 / 设置
    val screenTitleSecondary: TextUnit = 23.sp    // 二级页标题（工作区名）
    val topBarTitle: TextUnit = 16.sp             // 会话显示名
    val rowTitle: TextUnit = 15.sp
    val rowSubtitle: TextUnit = 11.sp             // 目录路径
    val headerMeta: TextUnit = 11.sp              // 3 SESSIONS · 1 ACTIVE
    val statusChip: TextUnit = 11.sp
    val navLabel: TextUnit = 11.sp
    val navGlyph: TextUnit = 17.sp
    val cardTitle: TextUnit = 15.sp
    val cardBody: TextUnit = 12.5f.sp
    val cardButton: TextUnit = 13.5f.sp
    val segmentedItem: TextUnit = 12.5f.sp
    val chip: TextUnit = 12.sp
    val keyLabel: TextUnit = 12.sp
    val keyLabelDanger: TextUnit = 11.5f.sp
    val keyGlyphArrow: TextUnit = 14.sp
    val inputText: TextUnit = 13.5f.sp
    val actionButton: TextUnit = 13.5f.sp
    val microPill: TextUnit = 10.sp               // LAN / PAIRED
    val currentBadge: TextUnit = 9.5f.sp          // 「当前」
    val sheetTitle: TextUnit = 17.sp
    val footnote: TextUnit = 10.5f.sp

    // 行高（倍数，乘在对应字号上；Text 用 lineHeight = size * 倍数）
    const val bodyLineHeight = 1.65f
    const val titleLineHeight = 1.18f
    const val rowLineHeight = 1.35f
}

// ─────────────────────────────────────────────────────────────
// 高度 / 阴影
// Compose 没有 CSS 那种自由投影，这里给的是等效 elevation + 手绘描边策略。
// 深色下不使用外投影（会变成一圈黑边），改用顶部内高光 —— 见 AppPalette.keycapTopHighlight。
// ─────────────────────────────────────────────────────────────
/** Elevation 档位。 */
object Elevations {
    val terminalCardLight: Dp = 3.dp      // 浅色外壳下终端屏微微浮起
    val terminalCardDark: Dp = 0.dp       // 深色外壳下不投影，靠 1dp 描边区分
    val sheet: Dp = 8.dp
    val sendButton: Dp = 2.dp
    val chipSelected: Dp = 2.dp
    val none: Dp = 0.dp
}

// ─────────────────────────────────────────────────────────────
// 动效
// ─────────────────────────────────────────────────────────────
/** 动效曲线与时长。 */
object Motion {
    /** Material 3 emphasized decelerate —— 全站主曲线 */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** 浮层滑起用，末端轻微减速过冲感 */
    val sheetEnter: Easing = CubicBezierEasing(0.16f, 0.9f, 0.3f, 1f)
    val linear: Easing = LinearEasing

    // 页面转场
    const val pushEnter = 300               // 进入下一层：从右 28% 滑入 + 淡入
    const val popEnter = 260                // 返回：从左 22% 滑入 + 淡入
    const val fadeThrough = 300             // 同层 tab 切换总时长
    const val fadeThroughOutFraction = 0.3f // 前 30% 为旧内容淡出的留白
    const val pushOffsetFraction = 0.28f
    const val popOffsetFraction = 0.22f
    const val fadeThroughRiseDp = 8
    const val fadeThroughScaleFrom = 0.985f

    // 底部导航指示轨
    const val navRail = 320

    // 「查看」浮层
    const val sheetSlideIn = 320
    const val sheetSlideOut = 220
    const val scrimFade = 180
    const val sheetRow = 300                // 单行上浮
    const val sheetRowStagger = 34          // 逐行间隔
    const val sheetRowDelayBase = 40
    const val sheetRowRiseDp = 12

    // 微交互
    const val pressFeedback = 90
    const val statusDotPulse = 1900         // 「进行中」呼吸点一个来回
}

// ─────────────────────────────────────────────────────────────
// 语义色板（M3 ColorScheme 之外、设计里实际用到的那些）
// ─────────────────────────────────────────────────────────────
@Immutable
/** 语义色板（M3 ColorScheme 之外、设计实际用到的颜色）。 */
data class AppPalette(
    // 背景层次
    val screenBackground: Color,       // 页面底色（标题区 / 导航栏所在层）
    val listBackground: Color,         // 列表卡面
    val cardBackground: Color,         // 设置卡片
    val cardBorder: Color,
    val divider: Color,                // 行间发丝线
    val dividerStrong: Color,          // 导航栏顶部那条（略重）
    val rowPressed: Color,

    // 文字
    val titleText: Color,
    val rowTitleText: Color,
    val pathText: Color,               // 目录副标题（等宽）
    val metaText: Color,               // 页头计数 / 脚注
    val bodyText: Color,               // 设置卡片正文

    // 主色 / 强调
    val accent: Color,                 // 可点文字、选中态、指示轨
    val accentContainer: Color,        // 轻着色底（按钮 / 胶囊）
    val accentContainerPressed: Color,
    val onAccent: Color,

    // 星标
    val starOn: Color,
    val starOff: Color,

    // 状态标（Busy 绿 / Idle 灰 / Unknown 红 —— 未知灯色不得复用空闲灰）
    val busyChipBg: Color,
    val busyChipText: Color,
    val busyDot: Color,
    val idleChipBg: Color,
    val idleChipText: Color,
    val unknownChipBg: Color,
    val unknownChipText: Color,
    val unknownDot: Color,

    // LAN / PAIRED 微胶囊
    val statusPillBg: Color,
    val statusPillText: Color,

    // 底部导航（3b）
    val navBackground: Color,
    val navRail: Color,
    val navActive: Color,
    val navInactive: Color,

    // 会话页外壳
    val consoleBackground: Color,      // 功能键排 + 输入条所在的一整块
    val keycapBackground: Color,
    val keycapBorder: Color,
    val keycapTopHighlight: Color,     // 深色下代替外投影的顶部内高光
    val keycapText: Color,
    val keycapPressed: Color,
    val keycapDangerBorder: Color,
    val keycapDangerTopHighlight: Color,
    val keycapDangerText: Color,
    val keycapDangerPressed: Color,
    val arrowClusterTrack: Color,
    val inputBackground: Color,
    val inputBorder: Color,
    val inputText: Color,
    val inputPlaceholder: Color,
    val promptGlyph: Color,            // 输入框里的 ❯
    val sendEnabledBg: Color,
    val sendEnabledFg: Color,
    val sendDisabledBg: Color,
    val sendDisabledFg: Color,

    // 「查看」浮层
    val scrim: Color,
    val sheetBackground: Color,
    val sheetSurface: Color,           // 行所在的卡面
    val sheetGrabber: Color,
    val sheetRowPressed: Color,
    val sheetCurrentRowBg: Color,
    val sheetCurrentRail: Color,
    val currentBadgeText: Color,
    val currentBadgeBorder: Color,
    val providerIconWell: Color,

    // 设置页
    val segmentedTrack: Color,
    val segmentedSelectedBg: Color,
    val segmentedSelectedText: Color,
    val segmentedText: Color,
    val chipBg: Color,
    val chipText: Color,
    val chipPressed: Color,
    val chipSelectedBg: Color,
    val chipSelectedText: Color,
    val outlineButtonBorder: Color,
    val outlineButtonText: Color,
    val outlineButtonPressed: Color,
)

/** 浅色语义色板。 */
val LightPalette = AppPalette(
    screenBackground = Color(0xFFF4F5F8),
    listBackground = Color(0xFFFFFFFF),
    cardBackground = Color(0xFFFFFFFF),
    cardBorder = Color(0x12101828),
    divider = Color(0x12101828),
    dividerStrong = Color(0x1F101828),
    rowPressed = Color(0xFFF6F7FA),

    titleText = Color(0xFF101726),
    rowTitleText = Color(0xFF111827),
    pathText = Color(0xFF6B7486),
    metaText = Color(0xFF6B7486),
    bodyText = Color(0xFF6B7486),

    accent = Color(0xFF0B57D0),
    accentContainer = Color(0x170B57D0),
    accentContainerPressed = Color(0x300B57D0),
    onAccent = Color(0xFFFFFFFF),

    starOn = Color(0xFF0B57D0),
    starOff = Color(0x3D101828),

    busyChipBg = Color(0x2112A594),
    busyChipText = Color(0xFF0E6E63),
    busyDot = Color(0xFF12A594),
    idleChipBg = Color(0x0E101828),
    idleChipText = Color(0xFF5F6980),
    unknownChipBg = Color(0x21C03A62),
    unknownChipText = Color(0xFFB23A63),
    unknownDot = Color(0xFFC03A62),

    statusPillBg = Color(0x2112A594),
    statusPillText = Color(0xFF0F766E),

    navBackground = Color(0xFFF4F5F8),
    navRail = Color(0xFF0B57D0),
    navActive = Color(0xFF0B57D0),
    navInactive = Color(0xFF5F6980),

    consoleBackground = Color(0xFFEBEDF2),
    keycapBackground = Color(0xFFFCFCFD),
    keycapBorder = Color(0x1A101828),
    keycapTopHighlight = Color(0x00000000),   // 浅色下不需要，靠下边框 + 1dp 投影
    keycapText = Color(0xFF2B3446),
    keycapPressed = Color(0xFFEDEFF4),
    keycapDangerBorder = Color(0x57E8709A),
    keycapDangerTopHighlight = Color(0x00000000),
    keycapDangerText = Color(0xFFB23A63),
    keycapDangerPressed = Color(0xFFFCEFF3),
    arrowClusterTrack = Color(0x0D101828),
    inputBackground = Color(0xFFFFFFFF),
    inputBorder = Color(0x21101828),
    inputText = Color(0xFF111827),
    inputPlaceholder = Color(0xFF8A93A5),
    promptGlyph = Color(0xFF12A594),
    sendEnabledBg = Color(0xFF0B57D0),
    sendEnabledFg = Color(0xFFFFFFFF),
    sendDisabledBg = Color(0x12101828),
    sendDisabledFg = Color(0x47101828),

    scrim = Color(0x85060A12),
    sheetBackground = Color(0xFFF7F8FB),
    sheetSurface = Color(0xFFFFFFFF),
    sheetGrabber = Color(0x33101828),
    sheetRowPressed = Color(0xFFEEF2FA),
    sheetCurrentRowBg = Color(0x0B0B57D0),
    sheetCurrentRail = Color(0xFF0B57D0),
    currentBadgeText = Color(0xFF0B57D0),
    currentBadgeBorder = Color(0x470B57D0),
    providerIconWell = Color(0x14101828),

    segmentedTrack = Color(0x0D101828),
    segmentedSelectedBg = Color(0xFFFFFFFF),
    segmentedSelectedText = Color(0xFF0B57D0),
    segmentedText = Color(0xFF5E6879),
    chipBg = Color(0x0D101828),
    chipText = Color(0xFF5E6879),
    chipPressed = Color(0x1F101828),
    chipSelectedBg = Color(0xFF0B57D0),
    chipSelectedText = Color(0xFFFFFFFF),
    outlineButtonBorder = Color(0x24101828),
    outlineButtonText = Color(0xFF3D4761),
    outlineButtonPressed = Color(0xFFF1F3F7),
)

/** 深色语义色板。 */
val DarkPalette = AppPalette(
    screenBackground = Color(0xFF070B14),
    listBackground = Color(0xFF0D1422),
    cardBackground = Color(0xFF0F1725),
    cardBorder = Color(0x1A78A0FF),
    divider = Color(0x1478A0FF),
    dividerStrong = Color(0x2978A0FF),
    rowPressed = Color(0xFF131C2E),

    titleText = Color(0xFFEAF0FA),
    rowTitleText = Color(0xFFE4EBF7),
    pathText = Color(0xFF6E82A4),
    metaText = Color(0xFF7286A8),
    bodyText = Color(0xFF8497B8),

    accent = Color(0xFF77A6FF),
    accentContainer = Color(0x2477A6FF),
    accentContainerPressed = Color(0x3D77A6FF),
    onAccent = Color(0xFF00183D),

    starOn = Color(0xFF77A6FF),
    starOff = Color(0x4DC4D0E6),

    busyChipBg = Color(0x264FD1C0),
    busyChipText = Color(0xFF5FDCC9),
    busyDot = Color(0xFF4FD1C0),
    idleChipBg = Color(0x1778A0FF),
    idleChipText = Color(0xFF8497B8),
    unknownChipBg = Color(0x26F0879F),
    unknownChipText = Color(0xFFF0879F),
    unknownDot = Color(0xFFF0879F),

    statusPillBg = Color(0x244FD1C0),
    statusPillText = Color(0xFF4FD1C0),

    navBackground = Color(0xFF070B14),
    navRail = Color(0xFF77A6FF),
    navActive = Color(0xFF9CC0FF),
    navInactive = Color(0xFF8497B8),

    consoleBackground = Color(0xFF0E1421),
    keycapBackground = Color(0xFF1A2233),
    keycapBorder = Color(0x2978A0FF),
    keycapTopHighlight = Color(0x17A0BEFF),   // 🔴 深色下用它，不要外投影
    keycapText = Color(0xFFC4D0E6),
    keycapPressed = Color(0xFF232D42),
    keycapDangerBorder = Color(0x52E8709A),
    keycapDangerTopHighlight = Color(0x1AF0879F),
    keycapDangerText = Color(0xFFF0879F),
    keycapDangerPressed = Color(0xFF2E2029),
    arrowClusterTrack = Color(0x1278A0FF),
    inputBackground = Color(0xFF131A2A),
    inputBorder = Color(0x2978A0FF),
    inputText = Color(0xFFE4EBF7),
    inputPlaceholder = Color(0xFF7286A8),
    promptGlyph = Color(0xFF4FD1C0),
    sendEnabledBg = Color(0xFF3B7BF6),
    sendEnabledFg = Color(0xFFFFFFFF),
    sendDisabledBg = Color(0x1778A0FF),
    sendDisabledFg = Color(0x4DC4D0E6),

    scrim = Color(0x9E03060C),
    sheetBackground = Color(0xFF0F1725),
    sheetSurface = Color(0xFF0D1422),
    sheetGrabber = Color(0x42B4CDFF),
    sheetRowPressed = Color(0xFF162034),
    sheetCurrentRowBg = Color(0x1277A6FF),
    sheetCurrentRail = Color(0xFF77A6FF),
    currentBadgeText = Color(0xFF9CC0FF),
    currentBadgeBorder = Color(0x4D77A6FF),
    providerIconWell = Color(0x2478A0FF),

    segmentedTrack = Color(0x1478A0FF),
    segmentedSelectedBg = Color(0xFF1D2740),
    segmentedSelectedText = Color(0xFF9CC0FF),
    segmentedText = Color(0xFF95A8C8),
    chipBg = Color(0x1778A0FF),
    chipText = Color(0xFF95A8C8),
    chipPressed = Color(0x3378A0FF),
    chipSelectedBg = Color(0xFF3B7BF6),
    chipSelectedText = Color(0xFFFFFFFF),
    outlineButtonBorder = Color(0x3378A0FF),
    outlineButtonText = Color(0xFFAEBFDA),
    outlineButtonPressed = Color(0x1A78A0FF),
)

/** 当前语义色板 CompositionLocal。 */
val LocalAppPalette = staticCompositionLocalOf { LightPalette }
