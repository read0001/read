package app.openread.engine

import org.jsoup.parser.Parser

/** Turns the HTML a content rule returns into plain paragraphs, one per line. */
object HtmlFormatter {
    private val lineBreak = Regex("(?i)<br\\s*/?>|</p\\s*>|</div\\s*>|</li\\s*>|</h[1-6]\\s*>|</tr\\s*>")
    private val scriptStyle = Regex("(?is)<(script|style)[^>]*>.*?</\\1\\s*>")
    private val comment = Regex("(?s)<!--.*?-->")
    private val tag = Regex("<[a-zA-Z/!?][^>]*>")
    private val invisible = Regex("[\\u200B-\\u200D\\uFEFF\\u2060]")

    fun format(html: String?): String {
        if (html.isNullOrBlank()) return ""
        var s = html.replace(scriptStyle, "").replace(comment, "").replace(lineBreak, "\n").replace(tag, "")
        s = Parser.unescapeEntities(s, false).replace(' ', ' ').replace(invisible, "")
        return s.lineSequence()
            .map { it.trim { c -> c.isWhitespace() || c == '　' } }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
