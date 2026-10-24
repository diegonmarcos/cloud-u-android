package com.diegonmarcos.superapp.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One block of a Markdown answer. Pure data, so the parser is testable without drawing. */
sealed interface AiMdBlock {
    data class Heading(val level: Int, val text: String) : AiMdBlock
    data class Paragraph(val text: String) : AiMdBlock
    /** [marker] is "•" for a bullet or "1." for a numbered item; [depth] is the indent level. */
    data class Item(val marker: String, val text: String, val depth: Int) : AiMdBlock
    data class Quote(val text: String) : AiMdBlock
    data class Code(val lang: String, val text: String) : AiMdBlock
    object Rule : AiMdBlock
}

/** The inline span kinds [AiMarkdown.inline] finds. */
enum class AiMdSpan { PLAIN, BOLD, ITALIC, CODE, LINK }

/** A piece of inline text with its kind ([url] only for a link). */
data class AiMdRun(val text: String, val span: AiMdSpan, val url: String = "")

/**
 * A small Markdown reader for chat answers: headings, paragraphs, bullet and numbered lists, quotes, fenced code, rules;
 * inline bold, italic, code and links. It never throws: anything it does not know is plain text, and an unclosed fence
 * (an answer still streaming in) is a code block that has not ended yet.
 */
object AiMarkdown {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun blocks(src: String): List<AiMdBlock> {
        val out = ArrayList<AiMdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotEmpty()) { out += AiMdBlock.Paragraph(para.toString().trim()); para.clear() } }
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            if (t.startsWith("```")) {
                flush()
                val lang = t.removePrefix("```").trim()
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code += lines[i]; i++ }
                out += AiMdBlock.Code(lang, code.joinToString("\n"))
                i++
                continue
            }
            val h = heading.matchEntire(t)
            val b = bullet.matchEntire(line)
            val n = numbered.matchEntire(line)
            when {
                line.isBlank() -> flush()
                h != null -> { flush(); out += AiMdBlock.Heading(h.groupValues[1].length, h.groupValues[2].trim()) }
                rule.matches(line) -> { flush(); out += AiMdBlock.Rule }
                b != null -> { flush(); out += AiMdBlock.Item("•", b.groupValues[2], b.groupValues[1].length / 2) }
                n != null -> { flush(); out += AiMdBlock.Item(n.groupValues[2] + ".", n.groupValues[3], n.groupValues[1].length / 2) }
                t.startsWith(">") -> { flush(); out += AiMdBlock.Quote(t.removePrefix(">").trim()) }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line.trim()) }
            }
            i++
        }
        flush()
        return out
    }

    /** Inline runs of one text: `code`, **bold**, *italic* / _italic_, [label](url). An unmatched marker stays literal. */
    fun inline(text: String): List<AiMdRun> {
        val out = ArrayList<AiMdRun>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += AiMdRun(plain.toString(), AiMdSpan.PLAIN); plain.clear() } }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            fun closing(marker: String, from: Int): Int = text.indexOf(marker, from).takeIf { it > from } ?: -1
            when {
                c == '`' -> {
                    val e = closing("`", i + 1)
                    if (e < 0) { plain.append(c); i++ } else { flush(); out += AiMdRun(text.substring(i + 1, e), AiMdSpan.CODE); i = e + 1 }
                }
                text.startsWith("**", i) -> {
                    val e = closing("**", i + 2)
                    if (e < 0) { plain.append("**"); i += 2 } else { flush(); out += AiMdRun(text.substring(i + 2, e), AiMdSpan.BOLD); i = e + 2 }
                }
                (c == '*' || c == '_') && (i == 0 || !text[i - 1].isLetterOrDigit()) && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                    val e = closing(c.toString(), i + 1)
                    if (e < 0) { plain.append(c); i++ } else { flush(); out += AiMdRun(text.substring(i + 1, e), AiMdSpan.ITALIC); i = e + 1 }
                }
                c == '[' -> {
                    val mid = text.indexOf("](", i + 1)
                    val end = if (mid > 0) text.indexOf(')', mid + 2) else -1
                    if (mid < 0 || end < 0) { plain.append(c); i++ } else {
                        flush(); out += AiMdRun(text.substring(i + 1, mid), AiMdSpan.LINK, text.substring(mid + 2, end)); i = end + 1
                    }
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return out
    }
}

/** The inline runs as one styled string: code in monospace on [codeBg], links underlined in [link]. */
fun aiMarkdownInline(text: String, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
    for (r in AiMarkdown.inline(text)) when (r.span) {
        AiMdSpan.PLAIN -> append(r.text)
        AiMdSpan.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(r.text) }
        AiMdSpan.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(r.text) }
        AiMdSpan.CODE -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(r.text) }
        AiMdSpan.LINK -> withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(r.text) }
    }
}

/** An answer drawn as Markdown, in the page's colours and type (the assistant's bubble when [AiTheme.markdown] is on). */
@Composable
fun AiMarkdownText(text: String, theme: AiTheme, modifier: Modifier = Modifier) {
    val c = theme.colors
    val m = theme.metrics
    val body = theme.type.style(theme.type.chat).copy(color = c.text)
    val codeBg = c.field
    val blocks = remember(text) { AiMarkdown.blocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.gap)) {
        for (b in blocks) when (b) {
            is AiMdBlock.Heading -> Text(
                aiMarkdownInline(b.text, codeBg, c.accent),
                style = body.copy(fontSize = (theme.type.chat.value + (4 - b.level).coerceAtLeast(0) * 2).sp, fontWeight = FontWeight.Bold),
            )
            is AiMdBlock.Paragraph -> Text(aiMarkdownInline(b.text, codeBg, c.accent), style = body)
            is AiMdBlock.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                Text(b.marker, style = body, modifier = Modifier.padding(end = m.gap))
                Text(aiMarkdownInline(b.text, codeBg, c.accent), style = body)
            }
            is AiMdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                Text("▎", style = body.copy(color = c.accent), modifier = Modifier.padding(end = m.gap))
                Text(aiMarkdownInline(b.text, codeBg, c.accent), style = body.copy(color = c.text2))
            }
            is AiMdBlock.Code -> Text(
                b.text,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = (theme.type.chat.value - 2).sp, color = c.text),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(m.cardRadius)).background(codeBg)
                    .horizontalScroll(rememberScrollState()).padding(m.cardPad),
            )
            AiMdBlock.Rule -> Text("─────", style = body.copy(color = c.text2))
        }
    }
}
