package app.openread.ui.reader

import androidx.compose.ui.text.AnnotatedString
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

object Paginator {
    private const val INDENT = "　　"

    /** Title, a blank line, then paragraphs with a two-character first-line indent. */
    fun display(title: String, body: String, titleSize: TextUnit): AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = titleSize)) { append(title) }
        append("\n\n")
        append(body(body))
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
    fun body(body: String): AnnotatedString = buildAnnotatedString {
        body.lineSequence().filter { it.isNotBlank() }.forEachIndexed { i, line ->
            if (i > 0) append('\n')
            append(INDENT)
            append(line.trim())
        }
    }

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
