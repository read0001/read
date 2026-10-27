package app.openread.data

import android.content.Context
import androidx.room.withTransaction
import app.openread.engine.Book
import app.openread.engine.BookSource
import app.openread.engine.Chapter
import app.openread.engine.Freshness
import app.openread.engine.PreviewGuard
import app.openread.engine.Http
import app.openread.engine.JsonCodec
import app.openread.engine.Mirrors
import app.openread.engine.Probe
import app.openread.engine.ProbeResult
import app.openread.engine.RuleData
import app.openread.engine.SearchBook
import app.openread.engine.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

class SearchProgress(val hits: List<SearchBook>, val counted: Boolean = true, val rested: Int = 0)

/** A source whose newest chapter is complete, found while checking others against one whose newest chapters are padding. */
class BetterSource(val source: SearchBook, val result: app.openread.engine.LatestCheck.Result)

/** What the reader is asked once a better source has been found: [message] says why, [target] is where to go. */
class SwitchOffer(val target: SearchBook, val message: String)

/** What a shelf refresh found: how many books have new chapters, and the books whose current source could not be read. */
class UpdateCheck(val news: Int, val unreachable: Set<String>)

/** A shelf book for which a clearly better source was found; the reader is asked before anything moves. */
class ShelfOffer(val book: BookRow, val offer: SwitchOffer)

/** What to tell the reader about a source whose newest chapters are padding with other novels. */
fun truncatedNote(originName: String, mine: app.openread.engine.LatestCheck.Result) =
    "「$originName」的最新几章不完整（完整到「${mine.completeTitle}」，之后是别的小说混进来的内容）。"

/** The sentence about the source that is better. */
fun betterLine(better: BetterSource) = "「${better.source.originName}」的最新章是完整的，到「${better.result.completeTitle}」。"

/** The outcome of checking other sources: the best one that is clearly better (or null), and how many could be checked at all. */
class OthersChecked(val better: BetterSource?, val checked: Int)

private const val REST_AFTER = 3
private const val REST_MS = 24 * 3600_000L
private const val UNKNOWN_MS = 6_000L
private const val ENOUGH_HITS = 10
private const val LATEST_CHECK_TTL_MS = 3600_000L
private const val DECLINE_MS = 24 * 3600_000L

/** Talks to the sources: search across many at once, open one book, run the health probe. */
class Engine(val http: Http, private val sources: SourceRepo, private val health: HealthDao) {
    fun web(row: SourceRow): WebBook? = sources.parse(row)?.let { WebBook(it, http) }

    suspend fun web(key: String): WebBook? = sources.get(key)?.let(::web)

    /**
     * Emits each source's hits as soon as that source answers. A source is blocking code that cannot be interrupted, so
     * the timeout works by walking away from it: the work carries on in [abandoned] while this search moves on and gives
     * its slot to the next source. Without that, a few hung sites would use up every slot and stall the whole search.
     */
    fun search(keyword: String, rows: List<SourceRow>, timeoutMs: Long = 20_000): Flow<SearchProgress> = channelFlow {
        val now = System.currentTimeMillis()
        val past = health.all().associateBy { it.key }
        // A source that failed three searches in a row is left out for a day; it is asked again only if the rest found little.
        val (rested, active) = rows.partition { r -> past[r.key]?.let { it.failStreak >= REST_AFTER && now - it.lastFailAt < REST_MS } == true }
        // Sources that proved fast go first, so the first results arrive quickly; unknown ones sit in the middle.
        val ordered = active.sortedBy { r -> past[r.key]?.takeIf { it.lastOkAt > 0 }?.avgMs ?: UNKNOWN_MS }
        if (rested.isNotEmpty()) send(SearchProgress(emptyList(), counted = false, rested = rested.size))
        val learned = ConcurrentHashMap<String, SourceHealth>()
        val reachable = java.util.concurrent.atomic.AtomicBoolean(false)
        val found = java.util.concurrent.atomic.AtomicInteger(0)
        suspend fun wave(list: List<SourceRow>) {
            val gate = Semaphore(16)
            kotlinx.coroutines.coroutineScope {
                list.forEach { row ->
                    launch(Dispatchers.IO) {
                        gate.withPermit {
                            val t0 = System.currentTimeMillis()
                            val work = abandoned.async { runCatching { web(row)?.search(keyword) } }
                            val outcome = withTimeoutOrNull(timeoutMs) { work.await() }
                            if (outcome == null) work.cancel()
                            val hits = outcome?.getOrNull().orEmpty()
                            val old = past[row.key]
                            when {
                                // An answer, even an empty one, proves the network is up.
                                outcome != null && outcome.isSuccess -> {
                                    reachable.set(true)
                                    if (hits.isNotEmpty()) {
                                        val ms = System.currentTimeMillis() - t0
                                        learned[row.key] = SourceHealth(row.key, 0, System.currentTimeMillis(), old?.lastFailAt ?: 0, old?.avgMs?.takeIf { it > 0 }?.let { (it * 2 + ms) / 3 } ?: ms)
                                    } else if (old != null && old.failStreak > 0) {
                                        // It answered, just not about this book: the failures are over, so the streak starts again.
                                        learned[row.key] = old.copy(failStreak = 0)
                                    }
                                }
                                else -> learned[row.key] = SourceHealth(row.key, (old?.failStreak ?: 0) + 1, old?.lastOkAt ?: 0, System.currentTimeMillis(), old?.avgMs ?: 0)
                            }
                            if (hits.isNotEmpty()) found.addAndGet(hits.size)
                            send(SearchProgress(hits))
                        }
                    }
                }
            }
        }
        try {
            wave(ordered)
            if (found.get() < ENOUGH_HITS && rested.isNotEmpty()) {
                send(SearchProgress(emptyList(), counted = false, rested = 0)) // asking them after all
                wave(rested)
            }
        } finally {
            // Failures only count when something else answered: with no network every source "fails" and none should be rested.
            val rows2 = learned.values.filter { it.failStreak == 0 || reachable.get() }
            if (rows2.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { health.upsert(rows2) }
        }
    }

    /** Work that was given up on keeps running here; the cap keeps hung sites from taking every thread the reader and database need. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val abandoned = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO.limitedParallelism(48))

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

    /** Checks of the newest chapter by book address, kept for an hour: reopening the page, or switching source, must not redo them. */
    private val latestChecks = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, app.openread.engine.LatestCheck.Result>>()

    private suspend fun latestOf(key: String, force: Boolean, load: suspend () -> app.openread.engine.LatestCheck.Result?): app.openread.engine.LatestCheck.Result? = withContext(Dispatchers.IO) {
        if (!force) latestChecks[key]?.takeIf { System.currentTimeMillis() - it.first < LATEST_CHECK_TTL_MS }?.let { return@withContext it.second }
        runCatching { load() }.getOrNull()?.also { latestChecks[key] = System.currentTimeMillis() to it }
    }

    /**
     * Whether [search]'s newest chapter is complete or a teaser, and the last complete one if not; null when it cannot be told.
     * [force] makes the check afresh instead of reusing one from the last hour.
     */
    suspend fun checkLatest(search: SearchBook, force: Boolean = false) = checkLatest(search.origin, search.bookUrl, force) { Book.from(search) }

    /** The same for a book on the shelf, from the source it is on now. */
    suspend fun checkLatest(row: BookRow, force: Boolean = false) = checkLatest(row.origin, row.bookUrl, force) { row.toBook() }

    private suspend fun checkLatest(origin: String, bookUrl: String, force: Boolean, book: () -> Book): app.openread.engine.LatestCheck.Result? =
        latestOf(origin + "\u0000" + bookUrl, force) {
            val web = engine.web(origin) ?: return@latestOf null
            val b = book()
            web.bookInfo(b)
            app.openread.engine.LatestCheck.run(web, b, web.chapters(b))
        }

    /**
     * Checks every source in [others] against [mine], the check of a source whose newest chapters are padding, quietly and a few
     * at a time ([onProgress] hears how far it is). Reprint sites of one book share their padding, so the freshest few are
     * usually all teasers; the sources that stop at different chapters are the ones most worth reading, so up to two per
     * distinct newest chapter go first and the rest follow. Only a source whose newest chapter is itself complete counts, and
     * only if it is at least three chapters further than [mine].
     */
    suspend fun findCompleteSource(
        mine: app.openread.engine.LatestCheck.Result, others: List<SearchBook>, force: Boolean = false, onProgress: (done: Int, total: Int) -> Unit,
    ): OthersChecked = coroutineScope {
        val distinct = Freshness.newestFirst(others.distinctBy { it.origin })
        val diverse = distinct.groupBy { it.latestChapterTitle.orEmpty() }.values.flatMap { it.take(2) }.toSet()
        val candidates = distinct.filter { it in diverse } + distinct.filter { it !in diverse }
        onProgress(0, candidates.size)
        val gate = Semaphore(6)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val checked = candidates.map { cand ->
            async {
                gate.withPermit {
                    val r = withTimeoutOrNull(90_000) { checkLatest(cand, force) }
                    onProgress(done.incrementAndGet(), candidates.size)
                    cand to r
                }
            }
        }.awaitAll()
        val best = checked.filter { it.second?.complete == true }.maxWithOrNull(compareBy({ it.second!!.completeCount }, { Freshness.of(it.first) }))
        OthersChecked(
            best?.takeIf { it.second!!.completeCount >= mine.completeCount + 3 }?.let { BetterSource(it.first, it.second!!) },
            checked.count { it.second != null },
        )
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

    /**
     * Refreshes every book's table of contents from its current source and flags the ones with new chapters. Reports how many
     * have news, and which books' current source could not be read at all (down, or too slow).
     */
    suspend fun checkUpdates(): UpdateCheck = coroutineScope {
        val gate = Semaphore(4)
        val results = db.books().snapshot().map { b ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val read = runCatching { withTimeoutOrNull(40_000) { reloadToc(b) } }.getOrNull() != null
                    Triple(b.bookUrl, read, db.books().get(b.bookUrl)?.hasUpdate == true)
                }
            }
        }.map { it.await() }
        UpdateCheck(results.count { it.third }, results.filter { !it.second }.map { it.first }.toSet())
    }

    suspend fun shelfSnapshot(): List<BookRow> = db.books().snapshot()

    /**
     * The second half of a shelf refresh, once [checkUpdates] has read every current source: for each book, the current
     * source's newest chapter is checked first, then every source is searched for the book and the ones that could be better
     * are checked, all quietly and one book at a time. A source that could not be read, or whose newest chapters are padding
     * with other novels, is checked against all the others; one that is fine only against those that list three or more
     * chapters beyond it, which are the only ones that could be better. [onOffer] hears each book for which a clearly better
     * source was found; nothing is switched here. Checks are made afresh, not taken from the one-hour memory.
     */
    suspend fun verifyShelf(
        books: List<BookRow>, unreachable: Set<String>,
        onProgress: (done: Int, total: Int) -> Unit, onOffer: (ShelfOffer) -> Unit,
    ) {
        for ((i, b) in books.withIndex()) {
            onProgress(i, books.size)
            // One book that cannot be checked (a source that throws, the database) must not stop the rest.
            try {
                verifyBook(b, b.bookUrl in unreachable)?.let(onOffer)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        onProgress(books.size, books.size)
    }

    private suspend fun verifyBook(b: BookRow, unreachable: Boolean): ShelfOffer? {
        val row = db.books().get(b.bookUrl) ?: return null // removed, or moved to another source, meanwhile
        val mine = if (unreachable) null else checkLatest(row, force = true)
        if (!unreachable && mine == null) return null // the source answers but its chapters cannot be judged: no verdict
        val others = alternativesFlow(row).lastOrNull().orEmpty().filter { !isDeclined(row, it) }
        if (others.isEmpty()) return null
        // A source that could not be read counts for as many chapters as the book had, so only a source at least that long is
        // offered (a short mirror would lose the reader's place).
        val baseline = mine ?: app.openread.engine.LatestCheck.Result(row.totalChapters, false, row.chapterTitle.orEmpty(), (row.totalChapters - 3).coerceAtLeast(0))
        val candidates = if (mine == null || !mine.complete) others else {
            val mineNumber = app.openread.engine.ChapterNumbers.of(row.latestChapter.orEmpty()) ?: -1
            if (mineNumber >= 0) others.filter { Freshness.of(it) >= mineNumber + 3 }.take(12)
            else Freshness.newestFirst(others).take(8)
        }
        if (candidates.isEmpty()) return null
        val better = findCompleteSource(baseline, candidates, force = true) { _, _ -> }.better ?: return null
        val why = when {
            mine == null -> "「${row.originName}」现在读不出来（打不开或太慢）。"
            !mine.complete -> truncatedNote(row.originName, mine)
            else -> "「${row.originName}」只到「${row.latestChapter.orEmpty()}」，更新较慢。"
        }
        return ShelfOffer(row, SwitchOffer(better.source, why + betterLine(better) + "\n\n要切换过去吗？"))
    }

    /** Offers the reader turned down are not made again for a day (until the app is restarted, at the latest). */
    private val declined = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun declineKey(book: BookRow, target: SearchBook) = book.bookUrl + "\u0000" + target.bookUrl

    fun declineOffer(offer: ShelfOffer) {
        declined[declineKey(offer.book, offer.offer.target)] = System.currentTimeMillis()
    }

    private fun isDeclined(book: BookRow, target: SearchBook) =
        declined[declineKey(book, target)]?.let { System.currentTimeMillis() - it < DECLINE_MS } == true

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

    /**
     * Chapter text from disk, or from the book's source (and then saved). [refresh] skips and replaces the saved copy.
     * With [guard], a short chapter that turns out to be a teaser padded with other novels' sentences is trimmed (see
     * [PreviewGuard]); the early chapters used as reference are read with it off.
     */
    suspend fun text(row: BookRow, ch: ChapterRow, refresh: Boolean = false, guard: Boolean = true): String = withContext(Dispatchers.IO) {
        val f = cacheFile(row.bookUrl, ch.url)
        if (!refresh && f.exists()) return@withContext f.readText()
        val web = engine.web(row.origin) ?: error("书源已被删除")
        val all = db.chapters().list(row.bookUrl)
        val next = all.getOrNull(ch.idx + 1)?.url
        var text = web.content(row.toBook(), ch.toChapter(), next, otherChapterUrls = all.asSequence().filter { it.idx != ch.idx }.map { it.url }.toSet())
        if (guard && PreviewGuard.isCandidate(text)) {
            val ref = referenceFor(row, all, except = ch.idx)
            val r = ref?.let { PreviewGuard.trim(text, it) }
            if (r != null && r.trimmed) text = r.text + "\n" + PreviewGuard.NOTICE
        }
        f.parentFile?.mkdirs()
        // Write beside the target and rename, so a reader never sees half a chapter.
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        text
    }

    private val referenceLock = Mutex()
    private val references = HashMap<String, PreviewGuard.Reference?>()

    /** What this book's free chapters look like, from two early ones (usually already on disk); built once per book. */
    private suspend fun referenceFor(row: BookRow, all: List<ChapterRow>, except: Int): PreviewGuard.Reference? = referenceLock.withLock {
        if (references.containsKey(row.bookUrl)) return@withLock references[row.bookUrl]
        val early = all.filter { !it.isVolume && it.idx != except && it.idx in 3..24 }.take(40).let { c -> listOfNotNull(c.getOrNull(2), c.getOrNull(12)) }
        val texts = early.mapNotNull { runCatching { text(row, it, guard = false) }.getOrNull() }
        PreviewGuard.reference(texts).also { references[row.bookUrl] = it }
    }

    // ---- changing source -----------------------------------------------------------------------------------------

    /**
     * Other sources that have the same book (same title, and same author when both are known), freshest first. Every source
     * that answers adds to the list at once, so a picker can show the first ones while the slow sources are still being asked
     * (waiting for all of them takes as long as the slowest source's timeout).
     */
    fun alternativesFlow(row: BookRow): Flow<List<SearchBook>> = flow {
        val rows = sources.enabled().filter { it.key != row.origin }
        val found = ArrayList<SearchBook>()
        engine.search(row.name, rows, 20_000).collect { progress ->
            val hit = progress.hits.filter { it.name == row.name && (row.author.isBlank() || it.author.isBlank() || it.author == row.author) }.firstOrNull()
            if (hit != null) {
                found.add(hit)
                emit(Freshness.newestFirst(found))
            }
        }
    }

    /** The complete list of [alternativesFlow], for callers that have no use for a partial one. */
    suspend fun alternatives(row: BookRow): List<SearchBook> = alternativesFlow(row).lastOrNull().orEmpty()

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
    suspend fun seedIfEmpty() = seedLock.withLock {
        // App start-up and the first search both call this; the second must wait for the first to commit, not race it.
        if (sources.count() > 0) return@withLock
        val text = context.assets.open("booksources.json").bufferedReader().use { it.readText() }
        sources.replaceSubscribed(BookSource.parseList(text))
    }

    private val seedLock = kotlinx.coroutines.sync.Mutex()

    suspend fun update(): Outcome = withContext(Dispatchers.IO) {
        val base = prefs.current().subscriptionBase.trim().let { if (it.endsWith("/")) it else "$it/" }
        val resp = runCatching { Mirrors.fetch(http, base + "booksources.json") { BookSource.parseList(it).isNotEmpty() } }
            .getOrElse { return@withContext Outcome(false, "下载失败：${it.message ?: it.javaClass.simpleName}") }
        val list = BookSource.parseList(resp.body)
        if (list.isEmpty()) return@withContext Outcome(false, "订阅里没有可用的书源")
        sources.replaceSubscribed(list)
        prefs.update { it.copy(lastSubscriptionUpdate = System.currentTimeMillis()) }
        Outcome(true, "已更新 ${list.size} 个书源")
    }

    companion object {
        const val STALE_MS = 12 * 3600_000L
    }
}
