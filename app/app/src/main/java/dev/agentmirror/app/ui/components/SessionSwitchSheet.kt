/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.agentmirror.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.session.SessionDockMotion
import dev.agentmirror.app.session.SessionDockSans
import dev.agentmirror.app.session.sessionDockSourceTokens

/** Claude Design source “查看” overlay: scrim plus the 230px placeholder card. */
@Composable
fun SessionSwitchSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = sessionDockSourceTokens()
    val risePx = with(LocalDensity.current) { 10.dp.roundToPx() }
    val shape = RoundedCornerShape(8.dp)
    val noRipple = remember { MutableInteractionSource() }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(durationMillis = 0)),
            exit = fadeOut(tween(durationMillis = 0)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x73000000))
                    .testTag("session-overlay-scrim")
                    .clickable(
                        interactionSource = noRipple,
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // Source screen has a 1px frame; 15dp from the app root reproduces x=145.
                .padding(end = 15.dp, bottom = 131.dp),
            enter = fadeIn(
                tween(SessionDockMotion.PopInMillis, easing = SessionDockMotion.Ease),
            ) + slideInVertically(
                tween(SessionDockMotion.PopInMillis, easing = SessionDockMotion.Ease),
                initialOffsetY = { risePx },
            ) + scaleIn(
                tween(SessionDockMotion.PopInMillis, easing = SessionDockMotion.Ease),
                initialScale = 0.97f,
            ),
            exit = fadeOut(tween(durationMillis = 0)),
        ) {
            Column(
                Modifier
                    .width(230.dp)
                    .height(94.09.dp)
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .background(source.surface)
                    .border(1.dp, source.neutral800, shape)
                    .testTag("session-overlay")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "查看弹出菜单（原生实现，此处仅占位）",
                    style = TextStyle(
                        fontFamily = SessionDockSans,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 20.15.sp,
                    ),
                    color = source.neutral300,
                )
                Text(
                    text = "点任意处关闭",
                    modifier = Modifier.padding(top = 6.dp),
                    style = TextStyle(
                        fontFamily = SessionDockSans,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 17.825.sp,
                    ),
                    color = source.neutral500,
                )
            }
        }
    }
}
