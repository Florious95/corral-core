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

package dev.agentmirror.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle

/*
 * A small CommonMark subset rendered with native Compose text: the theme palette reaches every
 * span (no TextView, no global colours), and each block is its own Text so a streaming token
 * re-lays out the last paragraph only.
 */

sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullets(val items: List<String>, val ordered: Boolean, val start: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock
    data object Rule : MdBlock
}

private val orderedItem = Regex("""^\s{0,3}(\d{1,3})[.)]\s+(.*)$""")
private val bulletItem = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
private val heading = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
private val fence = Regex("""^\s{0,3}(```+|~~~+)\s*([\w+#.-]*).*$""")

fun parseMarkdown(text: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = text.lines()
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim('\n'))
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val f = fence.matchEntire(line)
        when {
            f != null -> {
                flush()
                val marker = f.groupValues[1]
                val body = StringBuilder()
                i++
                var closed = false
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith(marker)) { closed = true; break }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                out += MdBlock.Code(f.groupValues[2], body.toString(), closed)
            }
            line.isBlank() -> flush()
            rule.matches(line) -> { flush(); out += MdBlock.Rule }
            heading.matchEntire(line) != null -> {
                flush()
                val h = heading.matchEntire(line)!!
                out += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2])
            }
            bulletItem.matches(line) || orderedItem.matches(line) -> {
                flush()
                val ordered = orderedItem.matches(line)
                val start = orderedItem.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val items = ArrayList<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val m = (if (ordered) orderedItem else bulletItem).matchEntire(l)
                    when {
                        m != null -> items += m.groupValues[if (ordered) 2 else 1]
                        l.isNotBlank() && l.startsWith("  ") && items.isNotEmpty() -> items[items.lastIndex] += "\n" + l.trim()
                        else -> break
                    }
                    i++
                }
                out += MdBlock.Bullets(items, ordered, start)
                continue
            }
            line.trimStart().startsWith(">") -> {
                flush()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                out += MdBlock.Quote(quote.toString())
                continue
            }
            line.trimStart().startsWith("|") -> {
                // Tables keep their columns in a monospaced, horizontally scrollable block.
                flush()
                val table = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    if (table.isNotEmpty()) table.append('\n')
                    table.append(lines[i].trim())
                    i++
                }
                out += MdBlock.Code("table", table.toString(), closed = true)
                continue
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flush()
    return out
}

/** Inline spans: **bold**, *italic*, `code`, ~~strike~~, [label](url) and bare URLs. */
fun inlineMarkdown(text: String, p: ConversationPalette, codeBackground: androidx.compose.ui.graphics.Color = p.code): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(style = SpanStyle(color = p.accentInk, textDecoration = TextDecoration.Underline))
    var i = 0
    val n = text.length
    val plain = StringBuilder()
    fun flushPlain() {
        if (plain.isNotEmpty()) { append(plain.toString()); plain.clear() }
    }
    while (i < n) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < n && text[i + 1] in "\\`*_[]()~#>|-" -> { plain.append(text[i + 1]); i += 2 }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) { plain.append(c); i++ } else {
                    flushPlain()
                    withStyle(SpanStyle(fontFamily = ConversationMono, fontSize = 0.88.em, background = codeBackground, color = p.codeInk)) {
                        append(" " + text.substring(i + 1, end) + " ")
                    }
                    i = end + 1
                }
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end < 0) { plain.append(marker); i += 2 } else {
                    flushPlain()
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inlineMarkdown(text.substring(i + 2, end), p, codeBackground)) }
                    i = end + 2
                }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end < 0) { plain.append("~~"); i += 2 } else {
                    flushPlain()
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inlineMarkdown(text.substring(i + 2, end), p, codeBackground)) }
                    i = end + 2
                }
            }
            (c == '*' || c == '_') && i + 1 < n && !text[i + 1].isWhitespace() && (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val end = text.indexOf(c, i + 1)
                if (end < 0 || text[end - 1].isWhitespace()) { plain.append(c); i++ } else {
                    flushPlain()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineMarkdown(text.substring(i + 1, end), p, codeBackground)) }
                    i = end + 1
                }
            }
            c == '[' -> {
                val close = text.indexOf("](", i + 1)
                val end = if (close > 0) text.indexOf(')', close + 2) else -1
                if (close < 0 || end < 0 || text.substring(i + 1, close).contains('\n')) { plain.append(c); i++ } else {
                    flushPlain()
                    withLink(LinkAnnotation.Url(text.substring(close + 2, end), linkStyles)) { append(text.substring(i + 1, close)) }
                    i = end + 1
                }
            }
            text.startsWith("https://", i) || text.startsWith("http://", i) -> {
                var end = i
                while (end < n && !text[end].isWhitespace() && text[end] !in "<>\"'）」】") end++
                while (end > i && text[end - 1] in ".,;:!?)") end--
                flushPlain()
                withLink(LinkAnnotation.Url(text.substring(i, end), linkStyles)) { append(text.substring(i, end)) }
                i = end
            }
            else -> { plain.append(c); i++ }
        }
    }
    flushPlain()
}

/**
 * Every line box is exactly its lineHeight, whatever script fills it. The default trim follows
 * the first/last line's font metrics, so a status flipping Latin ↔ CJK (or a streaming line
 * gaining its first 汉字) changed a row's height by a few px and nudged everything above it.
 */
internal val StableLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

val BodyStyle = TextStyle(fontFamily = ConversationSans, fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.05.sp, lineHeightStyle = StableLines)

@Composable
fun MarkdownText(text: String, p: ConversationPalette, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block -> MarkdownBlock(block, p) }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, p: ConversationPalette) {
    when (block) {
        is MdBlock.Paragraph -> Text(remember(block.text, p) { inlineMarkdown(block.text, p) }, style = BodyStyle.copy(color = p.ink))
        is MdBlock.Heading -> Text(
            remember(block.text, p) { inlineMarkdown(block.text, p) },
            style = BodyStyle.copy(
                color = p.ink,
                fontWeight = if (block.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = when (block.level) { 1 -> 19.sp; 2 -> 17.sp; else -> 15.5.sp },
                lineHeight = when (block.level) { 1 -> 26.sp; 2 -> 24.sp; else -> 22.sp },
                letterSpacing = (-0.1).sp,
            ),
            modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 2.dp),
        )
        is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Box(Modifier.width(if (block.ordered) 24.dp else 18.dp), contentAlignment = Alignment.TopStart) {
                        if (block.ordered) {
                            Text("${block.start + index}.", style = BodyStyle.copy(color = p.inkSoft, fontWeight = FontWeight.Medium))
                        } else {
                            Box(Modifier.padding(top = 9.5.dp, start = 4.dp).size(5.dp).clip(lookShape(3.dp)).background(p.inkSoft))
                        }
                    }
                    Text(remember(item, p) { inlineMarkdown(item, p) }, style = BodyStyle.copy(color = p.ink))
                }
            }
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(lookShape(2.dp)).background(p.accent.copy(alpha = 0.45f)))
            Spacer(Modifier.width(12.dp))
            Text(remember(block.text, p) { inlineMarkdown(block.text, p) }, style = BodyStyle.copy(color = p.inkSoft, fontStyle = FontStyle.Italic))
        }
        is MdBlock.Code -> CodeBlock(block.language, block.code, p)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(p.inkFaint))
    }
}

/** Code panel: language tab, copy, horizontal scroll; never wraps code. */
@Composable
fun CodeBlock(language: String, code: String, p: ConversationPalette, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    val shape = lookShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.code)
            .hairlineBorder(LocalConversationLook.current, p, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language.ifBlank { "code" },
                style = TextStyle(fontFamily = ConversationSans, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = p.codeSoft, letterSpacing = 0.3.sp),
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clip(lookShape(10.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                GlyphIcon(if (copied) Glyph.Check else Glyph.Copy, if (copied) p.success else p.codeSoft, 14.dp)
                Text(if (copied) "已复制" else "复制", style = TextStyle(fontFamily = ConversationSans, fontSize = 11.5.sp, color = if (copied) p.success else p.codeSoft))
            }
        }
        SelectionContainer {
            Text(
                code,
                style = TextStyle(fontFamily = ConversationMono, fontSize = 12.5.sp, lineHeight = 19.sp, color = p.codeInk),
                softWrap = false,
                maxLines = maxLines,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp)
                    .widthIn(min = 1.dp),
            )
        }
    }
}
