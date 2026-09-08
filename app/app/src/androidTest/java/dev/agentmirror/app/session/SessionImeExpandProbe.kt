/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.agentmirror.app.session

import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.ComposeTimeoutException
import org.junit.Assert.fail
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instrumentation-only IME expand probe. Product code must not depend on this.
 * Platform show/hide is [dumpsys input_method] `mInputShown`, not a one-shot
 * `decorView.rootWindowInsets` read inside a combined wait.
 */
internal class SessionImeExpandProbe<A : ComponentActivity>(
    private val compose: AndroidComposeTestRule<*, A>,
) {
    private val insetsImeVisible = AtomicBoolean(false)
    private var layoutListener: View.OnLayoutChangeListener? = null

    fun attach() {
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            insetsImeVisible.set(readInsetsIme(view))
            val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                insetsImeVisible.set(readInsetsIme(v))
            }
            layoutListener = listener
            view.addOnLayoutChangeListener(listener)
        }
    }

    fun detach() {
        compose.runOnUiThread {
            layoutListener?.let { compose.activity.window.decorView.removeOnLayoutChangeListener(it) }
            layoutListener = null
        }
    }

    fun clickEditorWhenImeHiddenThenAwaitHeightAndShow(
        heightReady: () -> Boolean,
        heightOperand: () -> String,
        editorTag: String = "session-command-editor",
        timeoutMs: Long = 5_000,
    ) {
        awaitNamed("ime-hidden", timeoutMs, heightOperand) { dumpsysInputShown() == false }
        compose.onNodeWithTag(editorTag).performTouchInput { click() }
        val heightMs = awaitNamed("input-height", timeoutMs, heightOperand, heightReady)
        val showMs = awaitNamed("platform-ime-show", timeoutMs, heightOperand) {
            dumpsysInputShown() == true
        }
        val snapshot = snapshot(heightOperand)
        Log.i(TAG, "ime-expand height_ms=$heightMs show_ms=$showMs $snapshot")
    }

    private fun awaitNamed(
        name: String,
        timeoutMs: Long,
        heightOperand: () -> String,
        condition: () -> Boolean,
    ): Long {
        val start = SystemClock.elapsedRealtime()
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (error: ComposeTimeoutException) {
            val elapsed = SystemClock.elapsedRealtime() - start
            fail("$name not satisfied after ${elapsed}ms; ${snapshot(heightOperand)}")
        }
        return SystemClock.elapsedRealtime() - start
    }

    private fun snapshot(heightOperand: () -> String): String {
        val shown = dumpsysInputShown()
        val tokens = dumpsysInputShownTokens()
        return "height=${heightOperand()} " +
            "focus=${focusOperand()} " +
            "insets_ime=${insetsImeVisible.get()} " +
            "dumpsys_mInputShown=$shown " +
            "dumpsys_tokens=$tokens"
    }

    private fun focusOperand(): String {
        var windowFocus = "none"
        compose.runOnUiThread {
            val focus = compose.activity.currentFocus
            windowFocus = if (focus == null) {
                "none"
            } else {
                "${focus.javaClass.simpleName} focused=${focus.isFocused}"
            }
        }
        val semanticsFocused = runCatching {
            compose.onNodeWithTag("session-command-editor")
                .fetchSemanticsNode()
                .config
                .getOrElse(SemanticsProperties.Focused) { false }
        }.getOrElse { "unavailable" }
        return "window=$windowFocus semantics=$semanticsFocused"
    }

    private fun dumpsysInputShown(): Boolean? {
        val tokens = dumpsysInputShownTokens()
        if (tokens.isEmpty()) return null
        return tokens.any { it }
    }

    private fun dumpsysInputShownTokens(): List<Boolean> {
        val text = dumpsysInputMethod()
        return INPUT_SHOWN.findAll(text).map { it.groupValues[1] == "true" }.toList()
    }

    private fun dumpsysInputMethod(): String {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand("dumpsys input_method")
        return pfd.use { fd ->
            FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }

    private fun readInsetsIme(view: View): Boolean =
        ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private companion object {
        const val TAG = "SessionImeExpandProbe"
        val INPUT_SHOWN = Regex("""\bmInputShown=(true|false)\b""")
    }
}
