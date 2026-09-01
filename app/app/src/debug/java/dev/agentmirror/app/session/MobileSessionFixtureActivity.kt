/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.agentmirror.app.session

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.components.SessionSwitchSheet
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance

/** Debug-only host used for Mobile MCP source UI inspection without pairing credentials. */
class MobileSessionFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var activeId by remember { mutableStateOf("0") }
            var mode by remember { mutableStateOf(DockRowMode.Sessions) }
            var value by remember { mutableStateOf(TextFieldValue("")) }
            var overlayOpen by remember { mutableStateOf(false) }
            val listState = rememberLazyListState()
            val focusManager = LocalFocusManager.current
            val sessions = (0..8).map { index ->
                SessionChipUi(
                    id = index.toString(),
                    name = "收藏会话-$index",
                    isActive = activeId == index.toString(),
                    isRunning = index % 2 == 0,
                )
            }
            val overlaySessions = sessions.map { item ->
                SessionItem(
                    id = item.id,
                    displayName = item.name,
                    path = "/workspace/${item.id}",
                    status = if (item.isRunning) SessionStatus.Busy else SessionStatus.Idle,
                    starred = true,
                )
            }

            AppTheme(appearance = Appearance.Light) {
                SessionDockTheme(dark = false) {
                    Box(Modifier.fillMaxSize()) {
                        SessionScreenScaffold(
                            terminalCanvas = {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(16.dp),
                                ) {
                                    Text(
                                        "agent@mobile ~/workspace",
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        "$ git status\nOn branch feat/agent-cli-mobile-source-ui\nworking tree clean",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            },
                            dockMode = mode,
                            onDockModeChange = { mode = it },
                            sessions = sessions,
                            sessionListState = listState,
                            onSessionSelect = { activeId = it },
                            value = value,
                            onValueChange = { value = it },
                            onSendText = {
                                value = TextFieldValue("")
                                focusManager.clearFocus()
                            },
                            onPickAttachment = {},
                            onKeyToken = {},
                            onBack = {},
                            onOpenViewMenu = { overlayOpen = true },
                        )
                        SessionSwitchSheet(
                            visible = overlayOpen,
                            workspaceName = "Agent CLI Mobile",
                            sessions = overlaySessions,
                            currentSessionId = activeId,
                            onDismiss = { overlayOpen = false },
                            onSelect = {
                                activeId = it.id
                                overlayOpen = false
                            },
                            onToggleStar = {},
                        )
                    }
                }
            }
        }
    }
}
