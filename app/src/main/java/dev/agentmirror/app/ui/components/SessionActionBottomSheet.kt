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

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes

/**
 * 长按会话的液态玻璃底部操作弹层。
 * 从底部滑出（[Motion.sheetSlideIn]），面板折射其下的列表页；面板内两项动作与取消按钮
 * 再采样面板自身（玻璃上的玻璃）：
 * - 头部：会话名称、路径、官方 Provider 图标；
 * - 选项 1：收藏 / 取消收藏（琥珀星标）；
 * - 选项 2：关闭会话（危险项——红色 Hue 调色 + 内发红光 + 红高光，说明「完全退出当前 Agent 并关闭该 pane」）；
 * - 底部独立「取消」，轻触遮罩 / 返回键同样收起。
 * 动作项先触发业务回调，再播放退场；会话行自身的关闭退场动效不在此处。
 */
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
    GlassModalLayer(onDismiss = onDismiss) {
        val p = LocalAppPalette.current
        val panelBackdrop = rememberLayerBackdrop()
        val requestDismiss = ::dismiss
        Column(
            modifier = modifier
                .align(Alignment.BottomCenter)
                .animateEnterExit(
                    enter = slideInVertically(tween(Motion.sheetSlideIn, easing = Motion.sheetEnter)) { it },
                    exit = slideOutVertically(tween(Motion.sheetSlideOut)) { it },
                )
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .fillMaxWidth()
                .pointerInput(Unit) {} // 面板内空白处的点击不落到遮罩
                .glassPanel(
                    backdrop = LocalGlassBackdrop.current,
                    shape = PanelShape,
                    surface = p.glassSurface.glassReadable(),
                    exportedBackdrop = panelBackdrop,
                )
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp)
                .testTag("session-action-bottom-sheet"),
        ) {
            // 抓手
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(Dims.sheetGrabberWidth)
                    .height(Dims.sheetGrabberHeight)
                    .clip(CircleShape)
                    .background(p.sheetGrabber),
            )
            Spacer(Modifier.height(14.dp))

            // 头部：会话名称 + 路径 + Provider 图标
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AppText(
                        text = session.displayName,
                        color = p.rowTitleText,
                        fontSize = TypeSizes.sheetTitle,
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
            Box(Modifier.fillMaxWidth().height(Dims.hairline).background(p.glassStroke))
            Spacer(Modifier.height(14.dp))

            CompositionLocalProvider(LocalGlassBackdrop provides panelBackdrop) {
                // 选项 1：收藏 / 取消收藏
                val starTint = if (session.starred) Color(0xFFEAB308) else Color(0xFFF59E0B)
                GlassActionRow(
                    onClick = {
                        onToggleFavorite()
                        requestDismiss()
                    },
                    modifier = Modifier.testTag("$tagPrefix-favorite-action"),
                    icon = {
                        ActionStarIcon(
                            isStarred = session.starred,
                            tint = starTint,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    iconWell = Color(0xFFFEF3C7),
                ) {
                    AppText(
                        text = if (session.starred) "取消收藏" else "收藏",
                        color = p.rowTitleText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (allowClose) {
                    Spacer(Modifier.height(10.dp))
                    // 选项 2：关闭会话（危险项，红光液态质感）
                    GlassActionRow(
                        onClick = {
                            onCloseSession()
                            requestDismiss()
                        },
                        modifier = Modifier.testTag("session-action-close"),
                        icon = {
                            ActionDangerCloseIcon(
                                tint = DangerColor,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        iconWell = Color(0xFFFFE4E6),
                        danger = true,
                    ) {
                        Column {
                            AppText(
                                text = "关闭会话",
                                color = DangerColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("$tagPrefix-close-action"),
                            )
                            Spacer(Modifier.height(2.dp))
                            AppText(
                                text = "完全退出当前 Agent 并关闭该 pane",
                                color = p.metaText,
                                fontSize = 12.sp,
                                modifier = Modifier.testTag("session-action-close-desc"),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                GlassButton(
                    text = "取消",
                    onClick = { requestDismiss() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("session-action-cancel"),
                )
            }
        }
    }
}

private val PanelShape = RoundedRectangle(Radii.glassPanel)
private val ActionShape = RoundedRectangle(Radii.glassControl)
private val DangerColor = Color(0xFFDC2626)

/**
 * 弹层里的一行玻璃动作：左侧圆形图标井 + 右侧内容。
 * [danger] 时整行红色 Hue 调色并内发红光。
 */
@Composable
private fun GlassActionRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    iconWell: Color,
    danger: Boolean = false,
    content: @Composable () -> Unit,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .glassControl(
                backdrop = LocalGlassBackdrop.current,
                shape = ActionShape,
                surface = if (danger) Color.Unspecified else p.glassSurface.copy(alpha = 0.35f),
                tint = if (danger) DangerColor else Color.Unspecified,
                tintAlpha = 0.14f,
                glow = if (danger) DangerColor else Color.Unspecified,
                pressProgress = press,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(iconWell),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Box(Modifier.weight(1f)) { content() }
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
