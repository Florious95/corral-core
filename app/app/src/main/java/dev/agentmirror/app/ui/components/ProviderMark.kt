package dev.agentmirror.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.R
import dev.agentmirror.app.ui.model.ProviderMarkState
import dev.agentmirror.app.ui.model.ProviderPresentation

private val resources = mapOf(
    "claude_code" to R.drawable.provider_claude_color,
    "codex" to R.drawable.provider_codex_color,
    "copilot" to R.drawable.provider_copilot_color,
    "grok" to R.drawable.provider_grok,
    "cursor" to R.drawable.provider_cursor,
    "pi" to R.drawable.provider_pi,
)

/** 渲染已由状态轴投影完成的 Provider 官方标记与可访问状态文案。 */
@Composable
fun ProviderMark(presentation: ProviderPresentation, modifier: Modifier = Modifier) {
    val description = "${presentation.label}，${when (presentation.state) { ProviderMarkState.Running -> "运行中"; ProviderMarkState.Idle -> "空闲"; ProviderMarkState.Abnormal -> "异常"; ProviderMarkState.Unknown -> "未知" }}"
    Box(Modifier.size(40.dp).then(modifier).semantics { contentDescription = description }) {
        presentation.assetKey?.let { key ->
            Image(
                painter = painterResource(resources.getValue(key)),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = if (presentation.state == ProviderMarkState.Idle || presentation.state == ProviderMarkState.Unknown) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                modifier = Modifier.size(18.dp).alpha(presentation.emphasis),
            )
        } ?: Text("?", modifier = Modifier.size(18.dp), color = Color.Gray)
        if (presentation.state == ProviderMarkState.Abnormal) Text("!", modifier = Modifier.size(18.dp), color = Color.Gray)
        if (presentation.state == ProviderMarkState.Unknown) Text("?", modifier = Modifier.size(18.dp), color = Color.Gray)
    }
}

/** 保留行短按，并将唯一收藏切换动作限制在行长按菜单中。 */
@Composable
fun FavoriteLongPressMenu(
    starred: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    rowTag: String? = null,
    actionTag: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier.combinedClickable(onClick = onClick, onLongClick = { expanded = true }).then(if (rowTag != null) Modifier.testTag(rowTag) else Modifier),
    ) {
        content()
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (starred) "取消收藏" else "收藏") },
                onClick = { expanded = false; onToggle() },
                modifier = if (actionTag != null) Modifier.testTag(actionTag) else Modifier,
            )
        }
    }
}
