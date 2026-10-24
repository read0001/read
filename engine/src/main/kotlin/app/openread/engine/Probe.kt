package app.openread.engine

class ProbeResult(
    val key: String,
    val name: String,
    val ok: Boolean,
    /** The last stage reached: search, info, toc or content. On success this is "content". */
    val stage: String,
    val error: String?,
    val searchMs: Long,
    val totalMs: Long,
    val keyword: String?,
    val book: String?,
    val chapters: Int,
    val contentChars: Int,
)

/**
 * Runs the whole reading flow against a live source: search, book info, table of contents, one chapter of text.
 * A source only counts as usable when every stage works, because a source that searches but cannot open a chapter
 * is no use to a reader.
 */
object Probe {
    private val junk = Regex("Just a moment|cloudflare|Access denied|403 Forbidden|请登录|请先登录|验证码|访问频繁|您的访问|Checking your browser", RegexOption.IGNORE_CASE)

    fun run(src: BookSource, parent: Http, keywords: List<String>): ProbeResult {
        val http = parent.fork()
        val web = WebBook(src, http)
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        var stage = "search"
        var searchMs = 0L
        var kw: String? = null
        var bookName: String? = null
        var chapters = 0
        fun fail(msg: String) = ProbeResult(src.key, src.bookSourceName, false, stage, msg.take(140), searchMs, ms(), kw, bookName, chapters, 0)
        return try {
            var hit: SearchBook? = null
            var searched = 0
            for (k in keywords) {
                kw = k
                val hits = web.search(k)
                searched += hits.size
                hit = hits.firstOrNull { it.name.contains(k) }
                if (hit != null) break
            }
            searchMs = ms()
            if (hit == null) return fail(if (searched == 0) "搜索没有结果" else "搜索结果里没有关键字")

            stage = "info"
            val book = Book.from(hit)
            bookName = book.name
            web.bookInfo(book)
            if (book.name.isBlank()) return fail("详情页没有书名")

            stage = "toc"
            val toc = web.chapters(book)
            chapters = toc.size
            if (toc.size < 5) return fail("目录只有 ${toc.size} 章")

            stage = "content"
            val readable = toc.filter { !it.isVolume && !it.isVip && !it.isPay }
            val chapter = readable.getOrNull(1) ?: readable.firstOrNull() ?: return fail("没有免费章节")
            val text = web.content(book, chapter, toc.getOrNull(chapter.index + 1)?.url)
            if (text.length < 120) return fail("正文只有 ${text.length} 字")
            if (junk.containsMatchIn(text.take(200))) return fail("正文是拦截页：${text.take(30)}")
            ProbeResult(src.key, src.bookSourceName, true, "content", null, searchMs, ms(), kw, bookName, chapters, text.length)
        } catch (e: Throwable) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }
}
