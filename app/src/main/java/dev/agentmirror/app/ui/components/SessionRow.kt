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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.sessionRowMotion
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes

/**
 * Unified ordinary/favorite session row (title + cwd path, 66dp).
 * Left: existing CLI working lamp (animated iff online+working) or「不在线」.
 * Middle: display name and path. Right: official Provider mark only.
 * The row is the sole gesture owner: short-press opens when online; long-press
 * shows modern ModalBottomSheet actions. Icons and the lamp are not clickable.
 *
 * @contract
 * @pre item.provider/status are fail-closed DTO fields; health remains metadata only
 * @post 66dp title+path; lamp motion from activity+online; mark has no gestures
 * @err none
 * @inv short-press opens only when online; long-press opens SessionActionBottomSheet
 * @consumes dev.agentmirror.app
 * @consumes dev.agentmirror.app.tsnet
 * @consumes dev.agentmirror.app.ui.model
 * @consumes dev.agentmirror.app.ui.theme
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionRow(
    item: SessionItem,
    tagPrefix: String,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    unfavoriteOnly: Boolean,
    onCloseSession: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var showSheet by remember { mutableStateOf(false) }
    val motion = sessionRowMotion(item.status, item.isOnline)
    val rowHeight = Dims.rowHeightWithSubtitle
    Box {
        Row(
            modifier = Modifier
                .padding(horizontal = Dims.cardHMargin)
                .fillMaxWidth()
                .height(rowHeight)
                .alpha(if (item.isOnline) 1f else 0.45f)
                .glassCard(
                    shape = RoundedCornerShape(Radii.card),
                    fill = if (pressed) p.glassCardFillPressed else p.glassCardFill,
                    stroke = p.glassStroke,
                    specular = p.glassSpecular,
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { if (item.isOnline) onClick() },
                    onLongClick = {
                        if (onLongClick != null) {
                            onLongClick()
                        } else {
                            showSheet = true
                        }
                    },
                )
                .padding(start = Dims.listHPaddingStart, end = Dims.listHPaddingEnd)
                .testTag("$tagPrefix-row-${item.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dims.rowGap),
        ) {
            if (item.isOnline) {
                CliWorkingLampTagged(
                    motion = motion,
                    prefix = tagPrefix,
                    id = item.id,
                )
            } else {
                OfflineChip(prefix = tagPrefix, id = item.id)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(rowHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    SessionNameText(
                        item.displayName,
                        Modifier.testTag("$tagPrefix-id-${item.id}"),
                    )
                    Box(Modifier.height(Dims.subtitleGap))
                    PathText(
                        item.path,
                        Modifier.testTag("$tagPrefix-path-${item.id}"),
                    )
                }
            }
            ProviderMark(
                canonicalId = item.provider,
                testTag = "$tagPrefix-provider-${item.id}",
            )
        }

        if (showSheet) {
            // 动作回调即刻触发；弹层自己播完退场再回 onDismiss，这里才卸载它。
            SessionActionBottomSheet(
                session = item,
                tagPrefix = tagPrefix,
                allowClose = !unfavoriteOnly && onCloseSession != null,
                onDismiss = { showSheet = false },
                onToggleFavorite = onToggleFavorite,
                onCloseSession = { onCloseSession?.invoke() },
            )
        }
    }
}

@Composable
private fun OfflineChip(prefix: String, id: String) {
    val p = LocalAppPalette.current
    Row(
        modifier = Modifier
            .height(Dims.statusChipHeight)
            .clip(RoundedCornerShape(Radii.statusChip))
            .background(p.idleChipBg)
            .padding(horizontal = Dims.statusChipHPadding)
            .testTag("$prefix-offline-$id"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = "不在线",
            color = p.idleChipText,
            fontSize = TypeSizes.statusChip,
            fontWeight = FontWeight.Medium,
            lineHeightMultiplier = 1f,
        )
    }
}
