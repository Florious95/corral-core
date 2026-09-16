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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.components.SessionSwitchSheet
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Appearance
import dev.agentmirror.app.workspace.ProductionOverlayFixture

/** Debug-only host used for Mobile MCP source UI inspection without pairing credentials. */
class MobileSessionFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            var value by remember { mutableStateOf(TextFieldValue("")) }
            var overlayOpen by remember { mutableStateOf(false) }
            var overlayCurrent by remember { mutableStateOf(ProductionOverlayFixture.CURRENT_REF) }
            var inputFocused by remember { mutableStateOf(false) }
            var lastKeyToken by remember { mutableStateOf<String?>(null) }
            val overlayItems = remember { ProductionOverlayFixture.overlayItems() }
            val focusManager = LocalFocusManager.current

            AppTheme(appearance = Appearance.Light) {
                SessionDockTheme(dark = false) {
                    val source = sessionDockSourceTokens()
                    Box(Modifier.fillMaxSize()) {
                        SessionScreenBackHandler(
                            focused = { inputFocused },
                            onCollapseFocused = {
                                inputFocused = false
                                focusManager.clearFocus(force = true)
                            },
                            overlayOpen = { overlayOpen },
                            onCloseOverlay = { overlayOpen = false },
                            onBack = {},
                        )
                        SessionScreenScaffold(
                            terminalCanvas = {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .background(source.cliGround)
                                        .padding(16.dp),
                                ) {
                                    Text(
                                        "agent@mobile ~/workspace",
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        "$ git status\nOn branch feat/session-bar-simplify\nworking tree clean",
                                        color = source.neutral300,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    if (lastKeyToken != null) {
                                        Text(
                                            "key: $lastKeyToken",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.testTag("fixture-last-key"),
                                        )
                                    }
                                }
                            },
                            value = value,
                            onValueChange = { value = it },
                            onSendText = {
                                value = TextFieldValue("")
                                focusManager.clearFocus()
                            },
                            onPickAttachment = {},
                            onKeyToken = { token ->
                                lastKeyToken = token
                            },
                            onInputFocusedChanged = { inputFocused = it },
                            modifier = Modifier.padding(top = 42.667.dp, bottom = 24.dp),
                        )
                        SessionSwitchSheet(
                            visible = overlayOpen,
                            workspaceName = ProductionOverlayFixture.workspaceLabel(),
                            sessions = overlayItems,
                            currentSessionId = overlayCurrent,
                            onDismiss = { overlayOpen = false },
                            onSelect = { item ->
                                overlayCurrent = item.id
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
