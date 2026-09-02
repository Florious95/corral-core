/**
 * ─────────────────────────────────────────────────────────────
 * DockIcons.kt — dock 专用图标 + 通用小件
 *
 * 对应设计稿：三按钮菜单的「快捷键⌨/查看👁/会话▦」图形、行右侧
 * 「返回菜单」箭头、输入框内的「加号/发送上箭头」。
 *
 * 决策：material-icons-extended 是新依赖（⛔），core 集里没有
 * keyboard/eye/grid 这几枚，所以用 Phosphor 的 path data 手工构建
 * ImageVector（256 视口，填充色任意，Icon 的 tint 会覆盖）。
 * 这样零依赖、且与设计稿 HTML 用的是同一套 Phosphor 轮廓。
 * ─────────────────────────────────────────────────────────────
 */
package dev.agentmirror.app.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** 快捷键（键盘轮廓，Phosphor keyboard） */
val DockIconKeyboard: ImageVector by lazy {
    phosphor(
        "DockKeyboard",
        "M224,48H32A16,16,0,0,0,16,64V192a16,16,0,0,0,16,16H224a16,16,0,0,0,16-16V64A16,16,0,0,0,224,48Zm0,144H32V64H224V192ZM56,104a8,8,0,0,1,8-8h8a8,8,0,0,1,0,16H64A8,8,0,0,1,56,104Zm40,0a8,8,0,0,1,8-8h8a8,8,0,0,1,0,16h-8A8,8,0,0,1,96,104Zm40,0a8,8,0,0,1,8-8h8a8,8,0,0,1,0,16h-8A8,8,0,0,1,136,104Zm40,0a8,8,0,0,1,8-8h8a8,8,0,0,1,0,16h-8A8,8,0,0,1,176,104ZM56,144a8,8,0,0,1,8-8h8a8,8,0,0,1,0,16H64A8,8,0,0,1,56,144Zm128,0a8,8,0,0,1,8-8h8a8,8,0,0,1,0,16h-8A8,8,0,0,1,184,144Zm-24,8H96a8,8,0,0,1,0-16h64a8,8,0,0,1,0,16Z",
    )
}

/** 查看（眼睛，Phosphor eye） */
val DockIconEye: ImageVector by lazy {
    phosphor(
        "DockEye",
        "M247.31,124.76c-.35-.79-8.82-19.58-27.65-38.41C194.57,61.26,162.88,48,128,48S61.43,61.26,36.34,86.35C17.51,105.18,9,124,8.69,124.76a8,8,0,0,0,0,6.5c.35.79,8.82,19.57,27.65,38.4C61.43,194.74,93.12,208,128,208s66.57-13.26,91.66-38.34c18.83-18.83,27.3-37.61,27.65-38.4A8,8,0,0,0,247.31,124.76ZM128,192c-30.78,0-57.67-11.19-79.93-33.25A133.47,133.47,0,0,1,25,128,133.33,133.33,0,0,1,48.07,97.25C70.33,75.19,97.22,64,128,64s57.67,11.19,79.93,33.25A133.46,133.46,0,0,1,231.05,128C223.84,141.46,192.43,192,128,192Zm0-112a48,48,0,1,0,48,48A48.05,48.05,0,0,0,128,80Zm0,80a32,32,0,1,1,32-32A32,32,0,0,1,128,160Z",
    )
}

/** 会话（四方格，Phosphor squares-four） */
val DockIconGrid: ImageVector by lazy {
    phosphor(
        "DockGrid",
        "M104,40H56A16,16,0,0,0,40,56v48a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V56A16,16,0,0,0,104,40Zm0,64H56V56h48Zm96-64H152a16,16,0,0,0-16,16v48a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V56A16,16,0,0,0,200,40Zm0,64H152V56h48Zm-96,32H56a16,16,0,0,0-16,16v48a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V152A16,16,0,0,0,104,136Zm0,64H56V152h48Zm96-64H152a16,16,0,0,0-16,16v48a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V152A16,16,0,0,0,200,136Zm0,64H152V152h48Z",
    )
}

/** 返回菜单（U 形回转箭头，Phosphor arrow-u-up-left 变体） */
val DockIconReturn: ImageVector by lazy {
    phosphor(
        "DockReturn",
        "M232,144a64.07,64.07,0,0,1-64,64H80a8,8,0,0,1,0-16h88a48,48,0,0,0,0-96H51.31l34.35,34.34a8,8,0,0,1-11.32,11.32l-48-48a8,8,0,0,1,0-11.32l48-48A8,8,0,0,1,85.66,45.66L51.31,80H168A64.07,64.07,0,0,1,232,144Z",
    )
}

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

/**
 * 行右侧 40×40 描边图标钮（会话行/按键条共用的「返回菜单」承载）。
 * 决策：宽度锁 40dp 与行高一致成正方形；描边 outlineVariant，
 * 与快捷块同层级，不抢内容视觉。
 */
@Composable
fun DockIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(40.dp).fillMaxHeight(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon, contentDescription,
                modifier = Modifier.width(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
