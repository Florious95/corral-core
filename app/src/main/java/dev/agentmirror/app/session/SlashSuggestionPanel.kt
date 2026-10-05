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

package dev.agentmirror.app.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.ui.components.ruledSurface
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.LocalThemeSuite
import dev.agentmirror.app.ui.theme.MonoFontFamily

/**
 * Non-modal suggestions floating on the dock's top edge: no scrim, no focus request, so the editor
 * keeps the keyboard and the user keeps typing while the list narrows. Rows only fill the draft.
 */
@Composable
internal fun SlashSuggestionPanel(
    visible: Boolean,
    query: String,
    candidates: List<SlashCandidate>,
    hint: String?,
    onPick: (SlashCandidate) -> Unit,
) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = 600f), 0.96f, TransformOrigin(0.5f, 1f)),
        exit = fadeOut(tween(100)) + scaleOut(tween(110), 0.98f, TransformOrigin(0.5f, 1f)),
    ) {
        val shape = kit.geometry.shape(RoundedCornerShape(16.dp))
        val glass = sessionDockGlassTokens()
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (kit.surfaces.isGlass) {
                        // Opaque underlay: terminal glyphs must not read through the list.
                        Modifier
                            .background(p.screenBackground, shape)
                            .dockFlatGlass(shape = shape, fill = glass.fill, hairline = glass.hairline, topGlint = glass.topGlint, bottomShade = glass.bottomShade)
                    } else {
                        Modifier.ruledSurface(fill = p.sheetBackground, stroke = kit.colors.headerRule, strokeWidth = kit.geometry.hairline)
                    },
                )
                .testTag("session-slash-panel"),
        ) {
            LazyColumn(Modifier.heightIn(max = 248.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(candidates, key = { it.key }) { candidate -> SlashRow(candidate, query) { onPick(candidate) } }
            }
            if (hint != null) {
                Text(
                    hint,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = p.metaText),
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (candidates.isEmpty()) 10.dp else 2.dp, bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SlashRow(candidate: SlashCandidate, query: String, onClick: () -> Unit) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(kit.geometry.shape(RoundedCornerShape(10.dp)))
            .background(if (pressed) p.rowPressed else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("session-slash-${candidate.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val title = buildAnnotatedString {
                val split = if (candidate.match > 0) (candidate.match + 1).coerceAtMost(candidate.title.length) else 0
                withStyle(SpanStyle(color = p.accent)) { append(candidate.title.take(split)) }
                append(candidate.title.drop(split))
            }
            Text(
                title,
                style = TextStyle(
                    fontFamily = if (candidate.shortcut) null else MonoFontFamily,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = p.rowTitleText,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (candidate.detail.isNotBlank()) {
                Text(
                    candidate.detail,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = p.metaText, fontFamily = if (candidate.shortcut) MonoFontFamily else null),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (candidate.shortcut) {
            Text(
                "快捷",
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = p.accent),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(kit.geometry.shape(RoundedCornerShape(6.dp)))
                    .background(p.accentContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
