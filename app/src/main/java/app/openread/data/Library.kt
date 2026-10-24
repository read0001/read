package app.openread.data

import android.content.Context
import androidx.room.withTransaction
import app.openread.engine.Book
import app.openread.engine.BookSource
import app.openread.engine.Chapter
import app.openread.engine.Http
import app.openread.engine.JsonCodec
import app.openread.engine.Mirrors
import app.openread.engine.Probe
import app.openread.engine.ProbeResult
import app.openread.engine.RuleData
import app.openread.engine.SearchBook
import app.openread.engine.Signing
import app.openread.BuildConfig
import app.openread.engine.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** All sources, how they are parsed, and how they get in: by hand, or from the subscription. */
class SourceRepo(private val dao: SourceDao) {
    val all: Flow<List<SourceRow>> = dao.all()
    private val parsed = ConcurrentHashMap<String, Pair<Int, BookSource?>>()

    fun parse(row: SourceRow): BookSource? {
        val cached = parsed[row.key]
        if (cached != null && cached.first == row.json.hashCode()) return cached.second
        val src = BookSource.parseList(row.json).firstOrNull()
        parsed[row.key] = row.json.hashCode() to src
        return src
    }

    suspend fun get(key: String): SourceRow? = dao.get(key)
    suspend fun enabled(): List<SourceRow> = dao.enabled()
    suspend fun count(): Int = dao.count()
    suspend fun setEnabled(key: String, enabled: Boolean) = dao.setEnabled(key, enabled)
    suspend fun delete(key: String) = dao.delete(key)

    private fun row(src: BookSource, subscribed: Boolean, order: Int) =
        SourceRow(src.key, src.bookSourceName, src.bookSourceGroup, src.toJson(), src.enabled, subscribed, order)

    /** Sources imported by hand: they replace a subscribed source with the same key and are never removed by an update. */
    suspend fun import(text: String): Int {
        val list = BookSource.parseList(text)
        val base = dao.count()
        dao.upsert(list.mapIndexed { i, s -> row(s, subscribed = false, order = -1000 + base + i) })
        return list.size
    }

    suspend fun replaceSubscribed(list: List<BookSource>) =
        dao.replaceSubscribed(list.mapIndexed { i, s -> row(s, subscribed = true, order = i) })

    suspend fun saveCheck(key: String, r: ProbeResult) = dao.setCheck(key, System.currentTimeMillis(), r.ok, r.totalMs, if (r.ok) "${r.chapters} 章" else "${r.stage}：${r.error}")
}

/** Talks to the sources: search across many at once, open one book, run the health probe. */
class Engine(val http: Http, private val sources: SourceRepo) {
    fun web(row: SourceRow): WebBook? = sources.parse(row)?.let { WebBook(it, http) }

    suspend fun web(key: String): WebBook? = sources.get(key)?.let(::web)

    /** Emits each source's hits as soon as that source answers; slow or broken sources are dropped after [timeoutMs]. */
    fun search(keyword: String, rows: List<SourceRow>, timeoutMs: Long = 25_000): Flow<List<SearchBook>> = channelFlow {
        val gate = Semaphore(6)
        rows.forEach { row ->
            launch(Dispatchers.IO) {
                gate.withPermit {
                    val hits = withTimeoutOrNull(timeoutMs) { runCatching { web(row)?.search(keyword) }.getOrNull() }
                    if (!hits.isNullOrEmpty()) send(hits)
                }
            }
        }
    }

    suspend fun probe(row: SourceRow): ProbeResult = withContext(Dispatchers.IO) {
        val src = sources.parse(row) ?: return@withContext ProbeResult(row.key, row.name, false, "search", "书源无法解析", 0, 0, null, null, 0, 0)
        Probe.run(src, http, listOf("斗破苍穹", "诡秘之主", "斗罗大陆")).also { sources.saveCheck(row.key, it) }
    }
}

private fun varsOf(json: String): RuleData {
    val d = RuleData()
    (JsonCodec.parse(json) as? JsonObject)?.forEach { (k, v) -> d.put(k, (v as? JsonPrimitive)?.content.orEmpty()) }
    return d
}

private fun RuleData.toJson(): String = JsonObject(variables.mapValues { JsonPrimitive(it.value) }).toString()

fun BookRow.toBook() = Book(
    bookUrl, tocUrl, origin, originName, name, author, kind, coverUrl, intro, wordCount, latestChapter, null, varsOf(vars),
)

fun ChapterRow.toChapter() = Chapter(idx, title, url, isVolume, data = varsOf(vars))

fun Book.toRow(old: BookRow? = null, now: Long = System.currentTimeMillis()) = BookRow(
    bookUrl = bookUrl, origin = origin, originName = originName, name = name, author = author, coverUrl = coverUrl,
    intro = intro, kind = kind, tocUrl = tocUrl, latestChapter = latestChapterTitle, wordCount = wordCount,
    vars = data.toJson(), addedAt = old?.addedAt ?: now, readAt = now,
    chapterIndex = old?.chapterIndex ?: 0, chapterOffset = old?.chapterOffset ?: 0, chapterTitle = old?.chapterTitle,
    totalChapters = old?.totalChapters ?: 0, hasUpdate = false, checkedAt = now,
)

fun Chapter.toRow(bookUrl: String) = ChapterRow(bookUrl, index, title, url, isVolume, data.variables.let { m -> JsonObject(m.mapValues { JsonPrimitive(it.value) }).toString() })

/** The bookshelf, table-of-contents cache, chapter text cache and source switching. */
class Library(private val context: Context, private val db: AppDb, private val engine: Engine, private val sources: SourceRepo) {
    val shelf: Flow<List<BookRow>> = db.books().shelf()

    fun observe(bookUrl: String) = db.books().observe(bookUrl)
    suspend fun book(bookUrl: String) = db.books().get(bookUrl)
    suspend fun chapters(bookUrl: String) = db.chapters().list(bookUrl)
    fun observeChapters(bookUrl: String) = db.chapters().observe(bookUrl)

    suspend fun add(book: Book, chapters: List<Chapter>) {
        val old = db.books().get(book.bookUrl)
        db.books().upsert(book.toRow(old).copy(totalChapters = chapters.size, latestChapter = chapters.lastOrNull()?.title ?: book.latestChapterTitle))
        db.chapters().replace(book.bookUrl, chapters.map { it.toRow(book.bookUrl) })
    }

    suspend fun remove(bookUrl: String) {
        db.books().delete(bookUrl)
        db.chapters().clear(bookUrl)
        cacheDir(bookUrl).deleteRecursively()
    }

    suspend fun saveProgress(bookUrl: String, index: Int, offset: Int, title: String?) =
        db.books().saveProgress(bookUrl, index, offset, title, System.currentTimeMillis())

    /** Fetches a book's detail page and table of contents from its source. */
    suspend fun open(search: SearchBook): Pair<Book, List<Chapter>> = withContext(Dispatchers.IO) {
        val web = engine.web(search.origin) ?: error("书源已被删除")
        val book = Book.from(search)
        web.bookInfo(book)
        book to web.chapters(book)
    }

    suspend fun reloadToc(row: BookRow): List<ChapterRow> = withContext(Dispatchers.IO) {
        val web = engine.web(row.origin) ?: error("书源已被删除")
        val book = row.toBook()
        val toc = web.chapters(book)
        if (toc.isEmpty()) error("目录为空")
        val rows = toc.map { it.toRow(row.bookUrl) }
        db.withTransaction {
            // The book may have been removed or moved to another source while the table of contents was downloading.
            val latest = db.books().get(row.bookUrl) ?: return@withTransaction
            db.chapters().replace(row.bookUrl, rows)
            val news = latest.hasUpdate || (latest.totalChapters > 0 && rows.size > latest.totalChapters)
            db.books().saveUpdate(row.bookUrl, rows.size, rows.last().title, hasUpdate = news, at = System.currentTimeMillis())
        }
        rows
    }

    /** Refreshes every book's table of contents and flags the ones with new chapters. Returns how many have news. */
    suspend fun checkUpdates(): Int = coroutineScope {
        val gate = Semaphore(4)
        db.books().snapshot().map { b ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching { withTimeoutOrNull(40_000) { reloadToc(b) } }
                    db.books().get(b.bookUrl)?.hasUpdate == true
                }
            }
        }.count { it.await() }
    }

    // ---- chapter text cache --------------------------------------------------------------------------------------

    private fun cacheDir(bookUrl: String): File {
        val h = MessageDigest.getInstance("MD5").digest(bookUrl.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "chapters/$h")
    }

    /** Keyed by the chapter's address, not its index, so a table of contents that shifts never shows the wrong text. */
    private fun cacheFile(bookUrl: String, chapterUrl: String): File {
        val h = MessageDigest.getInstance("MD5").digest(chapterUrl.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir(bookUrl), "$h.txt")
    }

    fun isCached(bookUrl: String, ch: ChapterRow) = cacheFile(bookUrl, ch.url).exists()

    /** Chapter text from disk, or from the book's source (and then saved). [refresh] skips and replaces the saved copy. */
    suspend fun text(row: BookRow, ch: ChapterRow, refresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val f = cacheFile(row.bookUrl, ch.url)
        if (!refresh && f.exists()) return@withContext f.readText()
        val web = engine.web(row.origin) ?: error("书源已被删除")
        val next = db.chapters().list(row.bookUrl).getOrNull(ch.idx + 1)?.url
        val text = web.content(row.toBook(), ch.toChapter(), next)
        f.parentFile?.mkdirs()
        // Write beside the target and rename, so a reader never sees half a chapter.
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        text
    }

    // ---- changing source -----------------------------------------------------------------------------------------

    /** Other sources that have the same book (same title, and same author when both are known). */
    suspend fun alternatives(row: BookRow): List<SearchBook> = coroutineScope {
        val rows = sources.enabled().filter { it.key != row.origin }
        val found = ArrayList<SearchBook>()
        engine.search(row.name, rows, 20_000).collect { hits ->
            hits.filter { it.name == row.name && (row.author.isBlank() || it.author.isBlank() || it.author == row.author) }
                .firstOrNull()?.let { found.add(it) }
        }
        found
    }

    /** Moves a shelf book to another source, keeping the reading position by chapter title, then by index. */
    suspend fun switchTo(stale: BookRow, target: SearchBook): BookRow = withContext(Dispatchers.IO) {
        // The caller's copy may be hours old; the position that counts is the one in the database now.
        val row = db.books().get(stale.bookUrl) ?: stale
        val (book, toc) = open(target)
        if (toc.isEmpty()) error("新书源的目录为空")
        val oldTitle = row.chapterTitle
        val index = toc.indexOfFirst { it.title == oldTitle }.takeIf { it >= 0 } ?: row.chapterIndex.coerceIn(0, toc.lastIndex)
        val fresh = book.toRow(row).copy(
            chapterIndex = index, chapterOffset = if (toc[index].title == oldTitle) row.chapterOffset else 0,
            chapterTitle = toc[index].title, totalChapters = toc.size, latestChapter = toc.last().title,
        )
        db.withTransaction {
            db.books().delete(row.bookUrl)
            db.chapters().clear(row.bookUrl)
            db.books().upsert(fresh)
            db.chapters().replace(fresh.bookUrl, toc.map { it.toRow(fresh.bookUrl) })
        }
        cacheDir(row.bookUrl).deleteRecursively()
        fresh
    }
}

/** Fetches the published `booksources.json` through several GitHub mirrors and swaps it in for the subscribed sources. */
class SubscriptionUpdater(private val context: Context, private val prefs: Prefs, private val sources: SourceRepo, private val http: Http) {
    class Outcome(val ok: Boolean, val message: String)

    suspend fun isStale(): Boolean = System.currentTimeMillis() - prefs.current().lastSubscriptionUpdate > STALE_MS

    /** Put the bundled list in place on first launch so the app is usable offline. */
    suspend fun seedIfEmpty() {
        if (sources.count() > 0) return
        val text = context.assets.open("booksources.json").bufferedReader().use { it.readText() }
        sources.replaceSubscribed(BookSource.parseList(text))
    }

    suspend fun update(): Outcome = withContext(Dispatchers.IO) {
        val base = prefs.current().subscriptionBase.trim().let { if (it.endsWith("/")) it else "$it/" }
        prefsBaseIsCustom = base != Settings.DEFAULT_BASE
        val resp = runCatching {
            Mirrors.fetch(http, base + "booksources.json", verify = signatureCheck(), valid = { BookSource.parseList(it).isNotEmpty() })
        }
            .getOrElse { return@withContext Outcome(false, "下载失败：${it.message ?: it.javaClass.simpleName}") }
        val list = BookSource.parseList(resp.body)
        if (list.isEmpty()) return@withContext Outcome(false, "订阅里没有可用的书源")
        sources.replaceSubscribed(list)
        prefs.update { it.copy(lastSubscriptionUpdate = System.currentTimeMillis()) }
        Outcome(true, "已更新 ${list.size} 个书源")
    }

    /**
     * With a public key built in, a copy only counts when the `.sig` served next to it, from the same address, verifies.
     * Without one (a self-hosted subscription) nothing is checked.
     */
    private fun signatureCheck(): ((String, String) -> Boolean)? {
        val key = BuildConfig.SUBSCRIPTION_PUBKEY
        if (key.isBlank() || prefsBaseIsCustom) return null
        return { body, from ->
            val sig = runCatching { http.execute("$from.sig", retry = 1) }.getOrNull()
            sig != null && sig.code == 200 && Signing.verify(key, body.toByteArray(), sig.body)
        }
    }

    /** A subscription address the user typed in is theirs to trust; only the built-in default is signature-checked. */
    private var prefsBaseIsCustom = false

    companion object {
        const val STALE_MS = 12 * 3600_000L
    }
}
