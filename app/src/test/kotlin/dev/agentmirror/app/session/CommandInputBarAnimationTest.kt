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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression contract for the expanding source dock.
 *
 * The shortcut trigger is an auxiliary control above the attachment button. It must not be
 * fully visible, nor overlap the expanding input capsule, while the 32dp -> 72dp height tween
 * is still in flight. The current implementation composes the trigger immediately from
 * `editorExpanded` and therefore this test is intentionally RED on the current baseline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommandInputBarAnimationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shortcutStaysHiddenAndOutsideCapsuleUntilExpansionSettles() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SessionDockTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    CommandInputBar(
                        value = TextFieldValue(""),
                        onValueChange = {},
                        onSendText = {},
                        onPickAttachment = {},
                        onShortcutMenuOpenChange = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("session-command-editor").performClick()
        compose.waitForIdle()
        // Sample during the 250ms height tween, before the 32dp -> 72dp target is reached.
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()

        val field = compose.onNodeWithTag("session-command-input-field").getUnclippedBoundsInRoot()
        val capsule = compose.onNodeWithTag("session-command-input").getUnclippedBoundsInRoot()
        val height = field.bottom.value - field.top.value
        assertTrue("50ms sample must still be in the 72dp expansion tween: height=$height", height < 71.5f)

        val shortcutVisible = compose.onAllNodesWithTag("session-shortcut-button")
            .fetchSemanticsNodes()
            .isNotEmpty()
        val overlaps = if (shortcutVisible) {
            val shortcut = compose.onNodeWithTag("session-shortcut-button").getUnclippedBoundsInRoot()
            shortcut.left.value < capsule.right.value && capsule.left.value < shortcut.right.value &&
                shortcut.top.value < capsule.bottom.value && capsule.top.value < shortcut.bottom.value
        } else {
            false
        }

        // Both checks are intentionally reported together so the RED run records the complete
        // failure: current code shows an alpha=1 trigger and its bounds enter the live capsule.
        assertFalse(
            "shortcut must remain hidden during expansion (visible=$shortcutVisible, " +
                "fieldHeight=$height, overlapsCapsule=$overlaps)",
            shortcutVisible,
        )
        assertFalse(
            "shortcut must not overlap the expanding capsule (fieldHeight=$height)",
            overlaps,
        )
    }
}
