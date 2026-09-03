/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.agentmirror.app.session

import android.view.KeyEvent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MobileSessionFixtureBackInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MobileSessionFixtureActivity>()

    @Test
    fun fixtureKeycodeBackReturnsFromHotkeysWithoutFinishingActivity() {
        compose.onNodeWithContentDescription("返回菜单", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("dock-open-hotkeys").performClick()
        compose.onNodeWithText("Esc").assertIsDisplayed()

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Esc").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("favorite-session-list").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Esc").assertCountEquals(0)
        compose.onNodeWithTag("favorite-session-list").assertIsDisplayed()
        assertTrue(
            "fixture must remain resumed after dock Back",
            compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        assertTrue("fixture must not finish after dock Back", !compose.activity.isFinishing)
        assertTrue("fixture must not be destroyed after dock Back", !compose.activity.isDestroyed)
    }
}
