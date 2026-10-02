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

import java.util.UUID

/** Large pastes stay local even when live keyboard sync is enabled. */
internal fun needsTextUpload(text: String): Boolean =
    text.length >= 4_000 || text.lineSequence().take(100).count() >= 100

internal fun textFileAttachment(text: String) = Attachment(
    name = "upload-text-${UUID.randomUUID()}.txt",
    mimeType = "text/plain",
    bytes = text.toByteArray(Charsets.UTF_8),
)

/** Quote a Unix host path, including spaces/apostrophes, without forcing the agent to read it. */
internal fun textFileReference(path: String): String = "'${path.replace("'", "'\\''")}'"
