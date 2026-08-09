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
 * 字形渲染槽位：同一码点最终用哪个字体槽绘制。
 *
 * 槽位与字体一一对应（Android 层由 [GlyphFontProvider] 给每槽造一支 Paint）：
 * - [MONO]：主等宽字体（Typeface.MONOSPACE）。等宽栅格保底槽位，绝大多数码点落这里，
 *   整段一次 drawText（网格对齐不破坏）。
 * - [SYSTEM_FALLBACK]：系统默认字体（Typeface.DEFAULT）。系统字体 fallback 链（fontconfig）
 *   覆盖盲文 U+2800-28FF、框线 U+2500-257F、块元素 U+2580-259F、几何/符号/箭头、
 *   CJK/emoji/全角等 MONO 缺失的区段（Field 实证：MONOSPACE 对这些区段覆盖率≈0）。
 * - [POWERLINE]：内置 PowerlineSymbols 字体（Powerline 私有区 U+E0A0+，系统字体不覆盖）。
 */
enum class GlyphSlot { MONO, SYSTEM_FALLBACK, POWERLINE }
