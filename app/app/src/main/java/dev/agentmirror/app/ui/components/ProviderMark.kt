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

import android.graphics.Picture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import dev.agentmirror.app.ui.theme.LocalAppPalette

private val MarkSize = 18.dp
private const val Viewport = 24

/**
 * Right-side official Provider mark. Renders the vendored SVG bytes as-is.
 * Unrecognized canonical ids draw nothing (no question mark, no fallback brand).
 *
 * @contract
 * @pre [canonicalId] is the DTO provider field, already fail-closed to unknown
 * @post exact table hit draws that mark; miss draws no node content
 * @err none
 */
@Composable
fun ProviderMark(
    canonicalId: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val spec = CanonicalProviderMarks.of(canonicalId)
    if (spec == null) return
    val context = LocalContext.current
    val tint = LocalAppPalette.current.rowTitleText.toArgb()
    val picture: Picture = remember(spec.assetPath, spec.currentColor, tint) {
        val raw = context.assets.open(spec.assetPath).bufferedReader().use { it.readText() }
        val svgText = if (spec.currentColor) {
            val hex = String.format("#%06X", tint and 0xFFFFFF)
            raw.replace("currentColor", hex)
        } else {
            raw
        }
        val svg = SVG.getFromString(svgText)
        svg.setDocumentWidth(Viewport.toFloat())
        svg.setDocumentHeight(Viewport.toFloat())
        svg.renderToPicture(Viewport, Viewport)
    }
    val tagged = if (testTag != null) modifier.testTag(testTag) else modifier
    Canvas(
        tagged
            .size(MarkSize)
            .semantics { contentDescription = spec.displayName },
    ) {
        val scale = size.minDimension / Viewport.toFloat()
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.scale(scale, scale)
            canvas.nativeCanvas.drawPicture(picture)
            canvas.restore()
        }
    }
}
