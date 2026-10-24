package app.openread.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Runs one [BookSource] through the reading flow: search or explore → book info → table of contents → chapter text.
 * Every call is blocking; callers run it on an IO dispatcher.
 */
class WebBook(val source: BookSource, private val http: Http) {

    // ---- search and explore --------------------------------------------------------------------------------------

    fun search(key: String, page: Int = 1): List<SearchBook> {
        val url = source.searchUrl?.takeIf { it.isNotBlank() } ?: throw SourceException("书源没有搜索地址")
        val rule = source.ruleSearch ?: throw SourceException("书源没有搜索规则")
        val au = AnalyzeUrl(url, key = key, page = page, source = source, http = http, data = RuleData())
        val resp = fetch(au)
        return parseBookList(resp, rule)
    }

    fun explore(url: String, page: Int = 1): List<SearchBook> {
        val rule = source.ruleExplore?.takeIf { !it.bookList.isNullOrBlank() }
            ?: source.ruleSearch ?: throw SourceException("书源没有发现规则")
        val au = AnalyzeUrl(url, page = page, source = source, http = http, data = RuleData())
        return parseBookList(fetch(au), rule)
    }

    /** The tabs of a source's discovery page. */
    fun exploreKinds(): List<ExploreKind> {
        var text = source.exploreUrl?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("@js:", true) || text.startsWith("<js>", true)) {
            val js = text.removePrefix("@js:").removePrefix("<js>").removeSuffix("</js>")
            text = Values.text(AnalyzeRule(RuleData(), source, http).evalJs(js, null)).trim()
        }
        if (text.startsWith("[")) {
            val arr = JsonCodec.parse(text) as? JsonArray ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val title = o.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ExploreKind(title, o.str("url")?.takeIf { it.isNotBlank() })
            }
        }
        return text.split("&&", "\n").mapNotNull { line ->
            val p = line.trim().split("::", limit = 2)
            if (p.size == 2 && p[0].isNotBlank()) ExploreKind(p[0].trim(), p[1].trim().takeIf { it.isNotEmpty() }) else null
        }
    }

    private fun parseBookList(resp: StrResponse, rule: SearchRule): List<SearchBook> {
        var listRule = rule.bookList?.trim().orEmpty()
        if (listRule.isEmpty()) throw SourceException("书源没有书籍列表规则")
        val reverse = listRule.startsWith("-")
        if (reverse || listRule.startsWith("+")) listRule = listRule.substring(1)

        val listAnalyzer = AnalyzeRule(RuleData(), source, http).setContent(resp.body, resp.url).setRedirectUrl(resp.url)
        val items = listAnalyzer.getElements(listRule)
        if (items.isEmpty()) {
            // Some sites jump straight to the book page when there is a single hit.
            val pattern = source.bookUrlPattern
            if (!pattern.isNullOrBlank() && runCatching { Regex(pattern).containsMatchIn(resp.url) }.getOrDefault(false)) {
                val book = Book(resp.url, resp.url, source.key, source.bookSourceName, "", "")
                analyzeBookInfo(book, resp)
                if (book.name.isNotBlank()) return listOf(toSearchBook(book))
            }
            return emptyList()
        }
        val out = ArrayList<SearchBook>()
        val seen = HashSet<String>()
        for (item in if (reverse) items.asReversed() else items) {
            val data = RuleData()
            val ar = listAnalyzer.child(item, data)
            val name = cleanName(ar.getString(rule.name))
            if (name.isEmpty()) continue
            val bookUrl = ar.getString(rule.bookUrl, isUrl = true).ifBlank { resp.url }
            if (!seen.add(bookUrl)) continue
            out.add(
                SearchBook(
                    name = name,
                    author = cleanAuthor(ar.getString(rule.author)),
                    bookUrl = bookUrl,
                    origin = source.key,
                    originName = source.bookSourceName,
                    kind = tags(ar.getStringList(rule.kind)),
                    coverUrl = ar.getString(rule.coverUrl, isUrl = true).ifBlank { null },
                    intro = HtmlFormatter.format(ar.getString(rule.intro)).ifBlank { null },
                    wordCount = ar.getString(rule.wordCount).ifBlank { null },
                    latestChapterTitle = ar.getString(rule.lastChapter).ifBlank { null },
                    data = data,
                ),
            )
        }
        return out
    }

    private fun toSearchBook(b: Book) = SearchBook(
        b.name, b.author, b.bookUrl, b.origin, b.originName, b.kind, b.coverUrl, b.intro, b.wordCount, b.latestChapterTitle, b.data,
    )

    // ---- book info -----------------------------------------------------------------------------------------------

    /** Fills [book] from its detail page. Sources without an info rule keep what the search already gave. */
    fun bookInfo(book: Book): Book {
        if (source.ruleBookInfo == null) return book
        val resp = fetch(AnalyzeUrl(book.bookUrl, source = source, http = http, data = book.data, book = book))
        analyzeBookInfo(book, resp)
        return book
    }

    private fun analyzeBookInfo(book: Book, resp: StrResponse) {
        val rule = source.ruleBookInfo ?: return
        val ar = AnalyzeRule(book.data, source, http, book).setContent(resp.body, book.bookUrl).setRedirectUrl(resp.url)
        if (!rule.init.isNullOrBlank()) ar.getElement(rule.init)?.let { ar.setContent(it) }
        ar.getString(rule.name).let(::cleanName).takeIf { it.isNotEmpty() }?.let { book.name = it }
        ar.getString(rule.author).let(::cleanAuthor).takeIf { it.isNotEmpty() }?.let { book.author = it }
        tags(ar.getStringList(rule.kind))?.let { book.kind = it }
        ar.getString(rule.coverUrl, isUrl = true).takeIf { it.isNotBlank() }?.let { book.coverUrl = it }
        HtmlFormatter.format(ar.getString(rule.intro)).takeIf { it.isNotBlank() }?.let { book.intro = it }
        ar.getString(rule.wordCount).takeIf { it.isNotBlank() }?.let { book.wordCount = it }
        ar.getString(rule.lastChapter).takeIf { it.isNotBlank() }?.let { book.latestChapterTitle = it }
        ar.getString(rule.updateTime).takeIf { it.isNotBlank() }?.let { book.updateTime = it }
        book.tocUrl = ar.getString(rule.tocUrl, isUrl = true).ifBlank { book.bookUrl }
    }

    // ---- table of contents ---------------------------------------------------------------------------------------

    fun chapters(book: Book): List<Chapter> {
        val rule = source.ruleToc ?: throw SourceException("书源没有目录规则")
        var listRule = rule.chapterList?.trim().orEmpty()
        if (listRule.isEmpty()) throw SourceException("书源没有章节列表规则")
        val reverse = listRule.startsWith("-")
        if (reverse || listRule.startsWith("+")) listRule = listRule.substring(1)

        val raw = ArrayList<Chapter>()
        val visited = HashSet<String>()
        var next: String? = book.tocUrl.ifBlank { book.bookUrl }
        var pages = 0
        while (next != null && visited.add(next) && pages++ < MAX_PAGES) {
            val resp = fetch(AnalyzeUrl(next, source = source, http = http, data = book.data, book = book))
            val pageAr = AnalyzeRule(book.data, source, http, book).setContent(resp.body, next).setRedirectUrl(resp.url)
            for (item in pageAr.getElements(listRule)) {
                val data = RuleData().also { it.variables.putAll(book.data.variables) }
                val ar = pageAr.child(item, data)
                val title = ar.getString(rule.chapterName).trim()
                if (title.isEmpty()) continue
                val isVolume = ar.getString(rule.isVolume).isTruthy()
                var url = ar.getString(rule.chapterUrl, isUrl = true)
                if (url.isBlank()) url = if (isVolume) title + raw.size else next
                raw.add(
                    Chapter(
                        index = raw.size, title = title, url = url, isVolume = isVolume,
                        isVip = ar.getString(rule.isVip).isTruthy(), isPay = ar.getString(rule.isPay).isTruthy(),
                        updateTime = ar.getString(rule.updateTime).ifBlank { null }, data = data,
                    ),
                )
            }
            next = pageAr.getStringList(rule.nextTocUrl, isUrl = true).firstOrNull { it !in visited && it != next }
        }
        val ordered = (if (reverse) raw.asReversed() else raw).distinctBy { it.url }
        return ordered.mapIndexed { i, c -> c.copy(index = i) }
    }

    // ---- chapter text --------------------------------------------------------------------------------------------

    fun content(book: Book, chapter: Chapter, nextChapterUrl: String? = null): String {
        val rule = source.ruleContent ?: throw SourceException("书源没有正文规则")
        val contentRule = rule.content?.takeIf { it.isNotBlank() } ?: throw SourceException("书源没有正文内容规则")
        val parts = ArrayList<String>()
        val visited = HashSet<String>()
        var url: String? = chapter.url
        var pages = 0
        while (url != null && visited.add(url) && pages++ < MAX_PAGES) {
            val resp = fetch(AnalyzeUrl(url, source = source, http = http, data = chapter.data, book = book, chapter = chapter))
            val ar = AnalyzeRule(chapter.data, source, http, book, chapter).setContent(resp.body, url).setRedirectUrl(resp.url)
            val text = ar.getString(contentRule)
            if (text.isNotBlank()) parts.add(text)
            url = if (rule.nextContentUrl.isNullOrBlank()) null
            else ar.getStringList(rule.nextContentUrl, isUrl = true)
                .firstOrNull { it !in visited && it != nextChapterUrl && it != chapter.url && !sameChapter(it, nextChapterUrl) }
        }
        var text = parts.joinToString("\n")
        rule.replaceRegex?.takeIf { it.isNotBlank() }?.let { text = applyReplace(text, it) }
        text = HtmlFormatter.format(text)
        if (text.isBlank()) throw SourceException("正文为空")
        return text
    }

    private fun sameChapter(a: String, b: String?) = b != null && a.substringBefore("#") == b.substringBefore("#")

    private fun applyReplace(text: String, rule: String): String {
        val p = rule.split("##")
        val (regex, replacement) = if (rule.startsWith("##")) p.getOrElse(1) { "" } to p.getOrElse(2) { "" } else p[0] to p.getOrElse(1) { "" }
        if (regex.isEmpty()) return text
        return runCatching { text.replace(Regex(regex), replacement) }.getOrElse { text.replace(regex, replacement) }
    }

    // ---- shared --------------------------------------------------------------------------------------------------

    private fun fetch(au: AnalyzeUrl): StrResponse {
        val resp = au.getStrResponse()
        if (resp.code >= 400 && resp.body.isBlank()) throw HttpException("HTTP ${resp.code}", resp.code)
        return resp
    }

    private fun String.isTruthy() = isNotBlank() && this != "false" && this != "0" && this != "null"

    companion object {
        private const val MAX_PAGES = 200
        private val spaces = Regex("[\\s\\u3000]+")
        private val authorPrefix = Regex("^\\s*(作\\s*者|著)\\s*[:：]?\\s*")

        /** Category tags, from a rule that may give "a,,b" or several lists: split, trim, drop blanks and repeats. */
        fun tags(raw: List<String>): String? =
            raw.flatMap { it.split(",", "，", "\n") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8).joinToString(",").ifEmpty { null }

        fun cleanName(s: String): String = s.replace(spaces, "").trim()
        fun cleanAuthor(s: String): String = s.replace(authorPrefix, "").replace(spaces, " ").trim()
    }
}
