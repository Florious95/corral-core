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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * Left: existing CLI working lamp (animated iff working+normal) or「不在线」.
 * Middle: display name and path. Right: official Provider mark only.
 * The row is the sole gesture owner: short-press opens when online; long-press
 * shows exactly one favorite action. Icons and the lamp are not clickable.
 *
 * @contract
 * @pre item.provider/health/status are fail-closed DTO fields
 * @post 66dp title+path; lamp motion from sessionRowMotion; mark has no gestures
 * @err none
 * @inv short-press opens only when online; long-press is the single favorite action
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
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var menu by remember { mutableStateOf(false) }
    val motion = sessionRowMotion(item.status, item.health, item.isOnline)
    val rowHeight = Dims.rowHeightWithSubtitle
    val actionLabel = if (unfavoriteOnly || item.starred) "取消收藏" else "收藏"
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .alpha(if (item.isOnline) 1f else 0.45f)
                .background(if (pressed) p.rowPressed else Color.Transparent)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { if (item.isOnline) onClick() },
                    onLongClick = { menu = true },
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
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            modifier = Modifier.testTag("$tagPrefix-favorite-menu"),
        ) {
            DropdownMenuItem(
                text = {
                    AppText(
                        text = actionLabel,
                        color = p.rowTitleText,
                        fontSize = TypeSizes.rowTitle,
                        fontWeight = FontWeight.Medium,
                        lineHeightMultiplier = 1f,
                    )
                },
                onClick = {
                    menu = false
                    onToggleFavorite()
                },
                modifier = Modifier.testTag("$tagPrefix-favorite-action"),
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
