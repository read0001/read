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

/** One screenful of a chapter: the slice of the chapter's display text that fits. */
class Page(val start: Int, val end: Int, val text: AnnotatedString)

class ChapterPages(val title: String, val pages: List<Page>) {
    /** The page that contains [offset]; the last page for a huge offset. */
    fun pageAt(offset: Int): Int =
        if (offset == Int.MAX_VALUE) pages.lastIndex else pages.indexOfLast { it.start <= offset }.coerceAtLeast(0)
}

object Paginator {
    private const val INDENT = "　　"

    /** Title, a blank line, then paragraphs with a two-character first-line indent. */
    fun display(title: String, body: String, titleSize: TextUnit): AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = titleSize)) { append(title) }
        append("\n\n")
        append(body(body))
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
