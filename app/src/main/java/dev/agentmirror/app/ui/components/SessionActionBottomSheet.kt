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

package dev.agentmirror.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.ui.model.SessionItem

/**
 * 现代长按会话底部操作弹层（ModalBottomSheet）。
 * 替代简陋小方框 DropdownMenu，提供：
 * - 头部展示会话名称与路径信息；
 * - 选项 1：收藏 / 取消收藏（星标图标，文案清晰，点击切换状态）；
 * - 选项 2：关闭会话（红色警示调，危险操作图标，文案“关闭会话”，说明“完全退出当前 Agent 并关闭该 pane”）；
 * - 底部独立“取消”按钮，支持轻触遮罩收起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionActionBottomSheet(
    session: SessionItem,
    tagPrefix: String = "l2",
    allowClose: Boolean = true,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCloseSession: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier.testTag("session-action-bottom-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            // 头部：会话名称 + 路径
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AppText(
                        text = session.displayName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("session-action-title"),
                    )
                    Spacer(Modifier.height(3.dp))
                    PathText(
                        path = session.path,
                        modifier = Modifier.testTag("session-action-path"),
                    )
                }
                Spacer(Modifier.width(10.dp))
                ProviderMark(
                    canonicalId = session.provider,
                    testTag = "session-action-provider-${session.id}",
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(
                thickness = 0.6.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
            Spacer(Modifier.height(14.dp))

            // 选项 1：收藏 / 取消收藏（星标图标，文案清晰）
            val starActionLabel = if (session.starred) "取消收藏" else "收藏"
            val starTint = if (session.starred) Color(0xFFEAB308) else Color(0xFFF59E0B)

            Surface(
                onClick = onToggleFavorite,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("$tagPrefix-favorite-action"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFEF3C7)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ActionStarIcon(
                            isStarred = session.starred,
                            tint = starTint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    AppText(
                        text = starActionLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (allowClose) {
                Spacer(Modifier.height(10.dp))

                // 选项 2：关闭会话（红色危险操作）
                val dangerColor = Color(0xFFDC2626)
                Surface(
                    onClick = onCloseSession,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, dangerColor.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("session-action-close"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFE4E6)),
                            contentAlignment = Alignment.Center,
                        ) {
                            ActionDangerCloseIcon(
                                tint = dangerColor,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            AppText(
                                text = "关闭会话",
                                color = dangerColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("$tagPrefix-close-action"),
                            )
                            Spacer(Modifier.height(2.dp))
                            AppText(
                                text = "完全退出当前 Agent 并关闭该 pane",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier.testTag("session-action-close-desc"),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 底部独立取消按钮
            Surface(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("session-action-cancel"),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        text = "取消",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** 五角星图标（实心已收藏 / 空心未收藏）。 */
@Composable
internal fun ActionStarIcon(
    isStarred: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val size = this.size.minDimension
        val midX = size / 2f
        val midY = size / 2f
        val radiusOuter = size * 0.46f
        val radiusInner = radiusOuter * 0.42f
        val angleStep = Math.PI / 5.0
        val path = Path().apply {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) radiusOuter else radiusInner
                val angle = i * angleStep - Math.PI / 2.0
                val x = (midX + r * kotlin.math.cos(angle)).toFloat()
                val y = (midY + r * kotlin.math.sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        if (isStarred) {
            drawPath(path, color = tint, style = Fill)
        } else {
            drawPath(path, color = tint, style = Stroke(width = size * 0.1f))
        }
    }
}

/** 危险操作图标（圆角十字叉）。 */
@Composable
internal fun ActionDangerCloseIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val size = this.size.minDimension
        val strokeWidth = size * 0.13f
        val inset = size * 0.24f
        drawLine(
            color = tint,
            start = Offset(inset, inset),
            end = Offset(size - inset, size - inset),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(size - inset, inset),
            end = Offset(inset, size - inset),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
