package app.openread.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import app.openread.tts.ReadAloud

/** One screenful of a chapter: the slice of the chapter's display text that fits. */
class Page(val start: Int, val end: Int, val text: AnnotatedString)

/**
 * A chapter cut into [pages]. [paragraphs] and [starts] describe the same text paragraph by paragraph (see
 * [Paginator.paragraphs]), which is how the reader and reading aloud agree on where a spoken sentence is on screen.
 */
class ChapterPages(val title: String, val pages: List<Page>, val paragraphs: List<String> = emptyList(), val starts: IntArray = IntArray(0)) {
    /** The page that contains [offset]; the last page for a huge offset. */
    fun pageAt(offset: Int): Int =
        if (offset == Int.MAX_VALUE) pages.lastIndex else pages.indexOfLast { it.start <= offset }.coerceAtLeast(0)

    /** Offset in the chapter's display text of character [char] of paragraph [paragraph]. */
    fun offsetOf(paragraph: Int, char: Int): Int = if (starts.isEmpty()) 0 else starts[paragraph.coerceIn(0, starts.lastIndex)] + char

    fun copyWith(paragraphs: List<String>, starts: IntArray) = ChapterPages(title, pages, paragraphs, starts)
}

/**
 * 排版：段首缩进和段间距（单位 sp，0 = 不额外留空）。
 *
 * 不论怎么排，显示文字里每个字的位置都不变：不缩进时用两个零宽字符占住缩进的位置，段间距用一个单独成段、行高等于
 * 段间距的零宽字符代替换行符。所以保存的阅读位置、书签、听书高亮都不受排版设置影响。
 */
data class Typeset(val indent: Boolean = true, val gapSp: Float = 0f)

object Paginator {
    private const val INDENT = "　　"
    private const val NO_INDENT = "\u200B\u200B"

    /** 行高样式：每一行（包括每段的首行和末行）都留同样的行距，分段排版时段落之间才不会挤在一起。 */
    val LineHeights = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

    /** Title, a blank line, then paragraphs with a two-character first-line indent. */
    fun display(title: String, body: String, titleSize: TextUnit, typeset: Typeset = Typeset()): AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = titleSize)) { append(title) }
        append("\n\n")
        append(body(body, typeset))
    }

    /**
     * What a chapter says, paragraph by paragraph: the title, then every non-blank line of [body], trimmed. Both the displayed
     * text ([display], [body]) and reading aloud are made from these, so paragraph numbers mean the same to both.
     */
    fun paragraphs(title: String, body: String): List<String> = ReadAloud.paragraphs(title, body)

    /** Where each of [paragraphs] starts in the text [display] builds (after the indent); the title is at 0. */
    fun paragraphStarts(paragraphs: List<String>): IntArray {
        val starts = IntArray(paragraphs.size)
        var pos = paragraphs[0].length + 2 // the title and the blank line after it
        for (i in 1 until paragraphs.size) {
            pos += INDENT.length
            starts[i] = pos
            pos += paragraphs[i].length + 1 // the text and the line break
        }
        return starts
    }

    /** The paragraph, and the character in it, that offset [at] of the display text falls on; the next paragraph's start for a gap. */
    fun locate(paragraphs: List<String>, starts: IntArray, at: Int): Pair<Int, Int> {
        for (i in paragraphs.indices) {
            if (at < starts[i] + paragraphs[i].length) return i to (at - starts[i]).coerceAtLeast(0)
        }
        return paragraphs.lastIndex to paragraphs.last().length
    }

    /** Paragraphs only, each with the first-line indent. */
    fun body(body: String, typeset: Typeset = Typeset()): AnnotatedString = buildAnnotatedString {
        val indent = if (typeset.indent) INDENT else NO_INDENT
        body.lineSequence().filter { it.isNotBlank() }.forEachIndexed { i, line ->
            if (i > 0) {
                if (typeset.gapSp > 0f) {
                    // 段间距：一个单独成段的零宽字符，行高就是段间距（和换行符一样占一个字符）
                    withStyle(ParagraphStyle(lineHeight = typeset.gapSp.sp, lineHeightStyle = LineHeights)) {
                        withStyle(SpanStyle(fontSize = 1.sp)) { append('\u200B') }
                    }
                } else append('\n')
            }
            append(indent)
            append(line.trim())
        }
    }

    /** 显示文字去掉排版用的零宽字符（复制、分享用）。 */
    fun plain(text: CharSequence): String = text.toString().replace("\u200B", "")

    /** Splits the chapter into pages that fit [widthPx] x [heightPx], whatever the line heights are. */
    fun paginate(measurer: TextMeasurer, text: AnnotatedString, style: TextStyle, widthPx: Int, heightPx: Int, title: String): ChapterPages {
        if (widthPx <= 0 || heightPx <= 0) return ChapterPages(title, listOf(Page(0, text.length, text)))
        val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx))
        val pages = ArrayList<Page>()
        var first = 0
        val lines = layout.lineCount
        var line = 0
        while (line < lines) {
            val top = layout.getLineTop(first)
            if (layout.getLineBottom(line) - top > heightPx && line > first) {
                pages.add(slice(layout, text, first, line - 1))
                first = line
            }
            line++
        }
        if (first < lines) pages.add(slice(layout, text, first, lines - 1))
        return ChapterPages(title, pages.ifEmpty { listOf(Page(0, 0, AnnotatedString(""))) })
    }

    private fun slice(layout: androidx.compose.ui.text.TextLayoutResult, text: AnnotatedString, from: Int, to: Int): Page {
        val start = layout.getLineStart(from)
        val end = layout.getLineEnd(to, visibleEnd = true).coerceAtLeast(start)
        return Page(start, end, text.subSequence(start, end))
    }
}
