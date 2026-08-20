package dev.agentmirror.app.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * 会话行长按菜单。收藏页 [showClose]=false。
 *
 * @contract
 * @pre expanded 时已组合
 * @post showClose=false 不组 menu-close
 * @err none
 */
@Composable
fun SessionOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    menuTag: String,
    starred: Boolean,
    showClose: Boolean,
    onFavorite: () -> Unit,
    onUnfavorite: () -> Unit,
    onClose: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(menuTag),
    ) {
        if (starred) {
            DropdownMenuItem(
                text = { Text("取消收藏") },
                onClick = {
                    onDismiss()
                    onUnfavorite()
                },
                modifier = Modifier.testTag("menu-unfavorite"),
            )
        } else {
            DropdownMenuItem(
                text = { Text("收藏") },
                onClick = {
                    onDismiss()
                    onFavorite()
                },
                modifier = Modifier.testTag("menu-favorite"),
            )
        }
        if (showClose) {
            DropdownMenuItem(
                text = { Text("关闭") },
                onClick = {
                    onDismiss()
                    onClose()
                },
                modifier = Modifier.testTag("menu-close"),
            )
        }
    }
}
