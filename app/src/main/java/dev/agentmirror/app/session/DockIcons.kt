/**
 * ─────────────────────────────────────────────────────────────
 * DockIcons.kt — 输入条专用图标
 *
 * 对应设计稿：输入框内的「加号/发送上箭头」。
 * 决策：用 Phosphor 的 path data 手工构建 ImageVector，零外部依赖。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun phosphor(name: String, d: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 256f, viewportHeight = 256f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()

/** 添加附件（加号，Phosphor plus） */
val DockIconPlus: ImageVector by lazy {
    phosphor(
        "DockPlus",
        "M224,128a8,8,0,0,1-8,8H136v80a8,8,0,0,1-16,0V136H40a8,8,0,0,1,0-16h80V40a8,8,0,0,1,16,0v80h80A8,8,0,0,1,224,128Z",
    )
}

/** 发送（上箭头，Phosphor arrow-up） */
val DockIconArrowUp: ImageVector by lazy {
    phosphor(
        "DockArrowUp",
        "M205.66,117.66a8,8,0,0,1-11.32,0L136,59.31V216a8,8,0,0,1-16,0V59.31L61.66,117.66a8,8,0,0,1-11.32-11.32l72-72a8,8,0,0,1,11.32,0l72,72A8,8,0,0,1,205.66,117.66Z",
    )
}
