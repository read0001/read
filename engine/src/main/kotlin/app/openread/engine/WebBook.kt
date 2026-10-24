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

    /** One fetched page of the table of contents. */
    private class TocPage(val items: List<Chapter>, val next: List<String>)

    /** [trace], when given, receives the address of every table-of-contents page that was fetched (for diagnostics). */
    fun chapters(book: Book, trace: MutableList<String>? = null): List<Chapter> {
        val rule = source.ruleToc ?: throw SourceException("书源没有目录规则")
        var listRule = rule.chapterList?.trim().orEmpty()
        if (listRule.isEmpty()) throw SourceException("书源没有章节列表规则")
        val reverse = listRule.startsWith("-")
        if (reverse || listRule.startsWith("+")) listRule = listRule.substring(1)

        fun loadPage(url: String): TocPage {
            if (trace != null) synchronized(trace) { trace.add(url) }
            val resp = fetch(AnalyzeUrl(url, source = source, http = http, data = book.data, book = book))
            val pageAr = AnalyzeRule(book.data, source, http, book).setContent(resp.body, url).setRedirectUrl(resp.url)
            val items = ArrayList<Chapter>()
            for (item in pageAr.getElements(listRule)) {
                val data = RuleData().also { it.variables.putAll(book.data.variables) }
                val ar = pageAr.child(item, data)
                val title = ar.getString(rule.chapterName).trim()
                if (title.isEmpty()) continue
                val isVolume = ar.getString(rule.isVolume).isTruthy()
                var chapterUrl = ar.getString(rule.chapterUrl, isUrl = true)
                if (chapterUrl.isBlank()) chapterUrl = if (isVolume) title + items.size else url
                items.add(
                    Chapter(
                        index = -1, title = title, url = chapterUrl, isVolume = isVolume,
                        isVip = ar.getString(rule.isVip).isTruthy(), isPay = ar.getString(rule.isPay).isTruthy(),
                        updateTime = ar.getString(rule.updateTime).ifBlank { null }, data = data,
                    ),
                )
            }
            // No next-page rule: many mobile sites still paginate, so look for an ordinary "下一页" link instead.
            val next = if (rule.nextTocUrl.isNullOrBlank()) listOfNotNull(TocPaging.nextLink(resp.body, resp.url))
            else pageAr.getStringList(rule.nextTocUrl, isUrl = true)
            return TocPage(items, next)
        }

        val start = book.tocUrl.ifBlank { book.bookUrl }
        val visited = HashSet<String>().apply { add(start) }
        val raw = ArrayList<Chapter>()
        val seenUrls = HashSet<String>()
        val first = loadPage(start)
        first.items.forEach { if (seenUrls.add(it.url)) raw.add(it) }

        val more = first.next.filter { it !in visited }.distinct()
        if (more.size > 1) {
            // The rule listed every remaining page at once (often a script that builds the addresses from "page 1 of N"):
            // fetch them all, in order, and do not chain from them. Chaining would ask the rule again on page 2, where it
            // often cannot produce the list.
            val pages = more.take(MAX_PAGES)
            pages.forEach { visited.add(it) }
            loadAll(pages, ::loadPage).forEach { p -> p.items.forEach { if (seenUrls.add(it.url)) raw.add(it) } }
        } else {
            var next = more.firstOrNull()
            var pages = 0
            while (next != null && visited.add(next) && pages++ < MAX_PAGES) {
                val p = loadPage(next)
                val before = raw.size
                p.items.forEach { if (seenUrls.add(it.url)) raw.add(it) }
                if (raw.size == before) break // a "next" link that adds nothing new is not a table-of-contents page
                next = p.next.firstOrNull { it !in visited }
            }
        }
        val ordered = TocFix.apply(if (reverse) raw.asReversed() else raw)
        return ordered.mapIndexed { i, c -> c.copy(index = i) }
    }

    /** Fetches [urls] with a few requests at a time and returns the pages in the order given. A page that fails twice fails the whole list. */
    private fun <T> loadAll(urls: List<String>, load: (String) -> T): List<T> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8) { r -> Thread(r).apply { isDaemon = true } }
        try {
            val futures = urls.map { u ->
                pool.submit<T> { try { load(u) } catch (e: Exception) { load(u) } }
            }
            return futures.mapIndexed { i, f ->
                try { f.get(60, java.util.concurrent.TimeUnit.SECONDS) }
                catch (e: java.util.concurrent.ExecutionException) { throw SourceException("第 ${i + 2} 页加载失败：${e.cause?.message}", e.cause) }
                catch (e: java.util.concurrent.TimeoutException) { throw SourceException("第 ${i + 2} 页加载超时") }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- chapter text --------------------------------------------------------------------------------------------

    /**
     * [trace], when given, receives the address of every page that was fetched for this chapter (for diagnostics).
     * [otherChapterUrls] are the addresses of the book's other chapters: a "next page" link that leads to one of them, or to
     * the book's own page, is the end of this chapter, not another page of it. That matters most for the newest chapter, which
     * has no next chapter to compare with and whose last "next" link often points back at the table of contents.
     */
    fun content(
        book: Book, chapter: Chapter, nextChapterUrl: String? = null, trace: MutableList<String>? = null,
        otherChapterUrls: Set<String> = emptySet(),
    ): String {
        val rule = source.ruleContent ?: throw SourceException("书源没有正文规则")
        val contentRule = rule.content?.takeIf { it.isNotBlank() } ?: throw SourceException("书源没有正文内容规则")
        fun key(u: String) = u.substringBefore("#").trimEnd('/')
        val stop = HashSet<String>().apply {
            otherChapterUrls.forEach { add(key(it)) }
            nextChapterUrl?.let { add(key(it)) }
            add(key(book.tocUrl)); add(key(book.bookUrl)); add(key(source.baseUrl))
            remove(key(chapter.url))
        }

        class Page(val text: String, val next: List<String>)
        fun load(url: String): Page {
            if (trace != null) synchronized(trace) { trace.add(url) }
            val resp = fetch(AnalyzeUrl(url, source = source, http = http, data = chapter.data, book = book, chapter = chapter))
            val ar = AnalyzeRule(chapter.data, source, http, book, chapter).setContent(resp.body, url).setRedirectUrl(resp.url)
            val next = if (rule.nextContentUrl.isNullOrBlank()) emptyList() else ar.getStringList(rule.nextContentUrl, isUrl = true)
            return Page(ar.getString(contentRule), next)
        }

        val parts = ArrayList<String>()
        val visited = HashSet<String>().apply { add(key(chapter.url)) }
        val first = load(chapter.url)
        if (first.text.isNotBlank()) parts.add(first.text)
        val more = first.next.filter { key(it) !in visited && key(it) !in stop }.distinctBy(::key)
        if (more.size > 1) {
            // Every remaining page was listed at once (see the table of contents): take them all, in order, without chaining.
            val pages = more.take(MAX_PAGES)
            pages.forEach { visited.add(key(it)) }
            loadAll(pages, ::load).forEach { if (it.text.isNotBlank()) parts.add(it.text) }
        } else {
            var next = more.firstOrNull()
            var n = 0
            while (next != null && visited.add(key(next)) && n++ < MAX_PAGES) {
                val p = load(next)
                if (p.text.isNotBlank()) parts.add(p.text)
                next = p.next.firstOrNull { key(it) !in visited && key(it) !in stop }
            }
        }
        var text = parts.joinToString("\n")
        rule.replaceRegex?.takeIf { it.isNotBlank() }?.let { text = applyReplace(text, it) }
        text = HtmlFormatter.format(text)
        if (text.isBlank()) throw SourceException("正文为空")
        return text
    }

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
