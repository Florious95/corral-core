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

package dev.agentmirror.app.termview

/**
 * 字形能力探针：运行时告诉 [GlyphFallbackPolicy] 某码点在某槽位字体里有没有字形
 * （即 "paint.hasGlyph" 的判定）。
 *
 * 策略只依赖这个接口做回退决策（纯 JVM 可测）；Android 层由 [GlyphFontProvider] 用真实
 * Paint.hasGlyph 实现，单测用假探针编码真机字体覆盖事实（Field 实证）。
 */
fun interface GlyphProbe {
    /** 码点 [codepoint] 在槽位 [slot] 的字体中是否字形齐全。 */
    fun hasGlyph(codepoint: Int, slot: GlyphSlot): Boolean
}
