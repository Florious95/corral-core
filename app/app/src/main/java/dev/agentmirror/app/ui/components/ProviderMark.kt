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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val MarkSize = 18.dp

/**
 * Right-side official Provider mark. HTML-extracted glyphs draw via Canvas;
 * prior-app PNG blobs use painterResource. No question-mark, no status overlay,
 * no runtime SVG parser. The mark is not clickable; the session row owns gestures.
 *
 * @contract
 * @pre [canonicalId] is the DTO provider field, already fail-closed to unknown
 * @post exact extract/PNG hit draws that mark; miss draws no node content
 * @err none
 * @inv no question-mark, status overlay, or click handler on the mark
 */
@Composable
fun ProviderMark(
    canonicalId: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val spec = CanonicalProviderMarks.of(canonicalId) ?: return
    val res = CanonicalProviderMarks.drawableRes(canonicalId) ?: return
    val tagged = if (testTag != null) modifier.testTag(testTag) else modifier
    val marked = tagged.size(MarkSize).semantics { contentDescription = spec.displayName }
    if (ExtractedProviderIcon.draws(canonicalId)) {
        Canvas(modifier = marked) {
            ExtractedProviderIcon.draw(this, canonicalId)
        }
    } else {
        Image(
            painter = painterResource(res),
            contentDescription = spec.displayName,
            contentScale = ContentScale.Fit,
            modifier = marked,
        )
    }
}
