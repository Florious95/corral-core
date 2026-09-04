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

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.PathParser

/**
 * Exact path data from the installed HTML BRAND SVG resources. The raw
 * resources preserve the source structure; this Canvas path avoids a runtime
 * SVG parser and applies the app's one-color provider-mark token.
 *
 * @contract
 * @pre canonicalId is one of claude_code, codex, cursor
 * @post draw paints the exact BRAND path in the requested monochrome color
 * @err unsupported ids are ignored
 * @inv no generic fallback, question mark, letter circle, or X geometry
 */
internal object ExtractedProviderIcon {
    const val CLAUDE_BRAND_PATH = "M 4.709 15.955 l 4.72 -2.647 .08 -.23 -.08 -.128 H 9.2 l -.79 -.048 -2.698 -.073 -2.339 -.097 -2.266 -.122 -.571 -.121 L 0 11.784 l .055 -.352 .48 -.321 .686 .06 1.52 .103 2.278 .158 1.652 .097 2.449 .255 h .389 l .055 -.157 -.134 -.098 -.103 -.097 -2.358 -1.596 -2.552 -1.688 -1.336 -.972 -.724 -.491 -.364 -.462 -.158 -1.008 .656 -.722 .881 .06 .225 .061 .893 .686 1.908 1.476 2.491 1.833 .365 .304 .145 -.103 .019 -.073 -.164 -.274 -1.355 -2.446 -1.446 -2.49 -.644 -1.032 -.17 -.619 a 2.97 2.97 0 0 1 -.104 -.729 L 6.283 .134 6.696 0 l .996 .134 .42 .364 .62 1.414 1.002 2.229 1.555 3.03 .456 .898 .243 .832 .091 .255 h .158 V 9.01 l .128 -1.706 .237 -2.095 .23 -2.695 .08 -.76 .376 -.91 .747 -.492 .584 .28 .48 .685 -.067 .444 -.286 1.851 -.559 2.903 -.364 1.942 h .212 l .243 -.242 .985 -1.306 1.652 -2.064 .73 -.82 .85 -.904 .547 -.431 h 1.033 l .76 1.129 -.34 1.166 -1.064 1.347 -.881 1.142 -1.264 1.7 -.79 1.36 .073 .11 .188 -.02 2.856 -.606 1.543 -.28 1.841 -.315 .833 .388 .091 .395 -.328 .807 -1.969 .486 -2.309 .462 -3.439 .813 -.042 .03 .049 .061 1.549 .146 .662 .036 h 1.622 l 3.02 .225 .79 .522 .474 .638 -.079 .485 -1.215 .62 -1.64 -.389 -3.829 -.91 -1.312 -.329 h -.182 v .11 l 1.093 1.068 2.006 1.81 2.509 2.33 .127 .578 -.322 .455 -.34 -.049 -2.205 -1.657 -.851 -.747 -1.926 -1.62 h -.128 v .17 l .444 .649 2.345 3.521 .122 1.08 -.17 .353 -.608 .213 -.668 -.122 -1.374 -1.925 -1.415 -2.167 -1.143 -1.943 -.14 .08 -.674 7.254 -.316 .37 -.729 .28 -.607 -.461 -.322 -.747 .322 -1.476 .389 -1.924 .315 -1.53 .286 -1.9 .17 -.632 -.012 -.042 -.14 .018 -1.434 1.967 -2.18 2.945 -1.726 1.845 -.414 .164 -.717 -.37 .067 -.662 .401 -.589 2.388 -3.036 1.44 -1.882 .93 -1.086 -.006 -.158 h -.055 L 4.132 18.56 l -1.13 .146 -.487 -.456 .061 -.746 .231 -.243 1.908 -1.312 -.006 .006 z"
    const val CODEX_BRAND_PATH = "M 8.086 .457 a 6.105 6.105 0 0 1 3.046 -.415 c 1.333 .153 2.521 .72 3.564 1.7 a .117 .117 0 0 0 .107 .029 c 1.408 -.346 2.762 -.224 4.061 .366 l .063 .03 .154 .076 c 1.357 .703 2.33 1.77 2.918 3.198 .278 .679 .418 1.388 .421 2.126 a 5.655 5.655 0 0 1 -.18 1.631 .167 .167 0 0 0 .04 .155 5.982 5.982 0 0 1 1.578 2.891 c .385 1.901 -.01 3.615 -1.183 5.14 l -.182 .22 a 6.063 6.063 0 0 1 -2.934 1.851 .162 .162 0 0 0 -.108 .102 c -.255 .736 -.511 1.364 -.987 1.992 -1.199 1.582 -2.962 2.462 -4.948 2.451 -1.583 -.008 -2.986 -.587 -4.21 -1.736 a .145 .145 0 0 0 -.14 -.032 c -.518 .167 -1.04 .191 -1.604 .185 a 5.924 5.924 0 0 1 -2.595 -.622 6.058 6.058 0 0 1 -2.146 -1.781 c -.203 -.269 -.404 -.522 -.551 -.821 a 7.74 7.74 0 0 1 -.495 -1.283 6.11 6.11 0 0 1 -.017 -3.064 .166 .166 0 0 0 .008 -.074 .115 .115 0 0 0 -.037 -.064 5.958 5.958 0 0 1 -1.38 -2.202 5.196 5.196 0 0 1 -.333 -1.589 6.915 6.915 0 0 1 .188 -2.132 c .45 -1.484 1.309 -2.648 2.577 -3.493 .282 -.188 .55 -.334 .802 -.438 .286 -.12 .573 -.22 .861 -.304 a .129 .129 0 0 0 .087 -.087 A 6.016 6.016 0 0 1 5.635 2.31 C 6.315 1.464 7.132 .846 8.086 .457 z m -.804 7.85 a .848 .848 0 0 0 -1.473 .842 l 1.694 2.965 -1.688 2.848 a .849 .849 0 0 0 1.46 .864 l 1.94 -3.272 a .849 .849 0 0 0 .007 -.854 l -1.94 -3.393 z m 5.446 6.24 a .849 .849 0 0 0 0 1.695 h 4.848 a .849 .849 0 0 0 0 -1.696 h -4.848 z"
    const val CURSOR_BRAND_PATH = "M 22.106 5.68 L 12.5 .135 a .998 .998 0 0 0 -.998 0 L 1.893 5.68 a .84 .84 0 0 0 -.419 .726 v 11.186 c 0 .3 .16 .577 .42 .727 l 9.607 5.547 a .999 .999 0 0 0 .998 0 l 9.608 -5.547 a .84 .84 0 0 0 .42 -.727 V 6.407 a .84 .84 0 0 0 -.42 -.726 z m -.603 1.176 L 12.228 22.92 c -.063 .108 -.228 .064 -.228 -.061 V 12.34 a .59 .59 0 0 0 -.295 -.51 l -9.11 -5.26 c -.107 -.062 -.063 -.228 .062 -.228 h 18.55 c .264 0 .428 .286 .296 .514 z"

    /** Retained only as a regression sentinel: no runtime path may draw this. */
    const val GROK_ICON_SWITCH_X_FALLBACK =
        "M9 15.5 15 9M9.2 9.2l2.3 2.3M14.8 14.8l-2.3-2.3"

    val ClaudeTint = Color(0xFFD97757)
    val CursorTint = Color(0xFF6D6A63)

    fun draws(canonicalId: String): Boolean = when (canonicalId) {
        "claude_code", "codex", "cursor" -> true
        else -> false
    }

    private val claudePath = PathParser.createPathFromPathData(CLAUDE_BRAND_PATH)!!
    private val codexPath = PathParser.createPathFromPathData(CODEX_BRAND_PATH)!!
    private val cursorPath = PathParser.createPathFromPathData(CURSOR_BRAND_PATH)!!

    fun draw(scope: DrawScope, canonicalId: String, color: Color) {
        val path = when (canonicalId) {
            "claude_code" -> claudePath
            "codex" -> codexPath
            "cursor" -> cursorPath
            else -> return
        }
        val canvas = scope.drawContext.canvas.nativeCanvas
        val scale = scope.size.minDimension / 24f
        canvas.save()
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color.toArgb()
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
