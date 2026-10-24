package app.openread.checker

import app.openread.engine.Book
import app.openread.engine.BookSource
import app.openread.engine.ChapterNumbers
import app.openread.engine.Http
import app.openread.engine.PreviewGuard
import app.openread.engine.WebBook
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class Quality(
    val name: String, val key: String, val toc: Int, val latestTitle: String, val latestNumber: Int,
    val earlyChars: Int, val latestChars: List<Int>, val padded: Int, val note: String?,
) {
    /** The newest chapters are about as long as the free ones and none of them is padding. */
    val full: Boolean get() = note == null && padded == 0 && latestChars.isNotEmpty() && latestChars.all { it >= 0.7 * earlyChars }
}

/**
 * `checker quality <a.json,b.json,…> <keyword> [concurrency]`: for every usable source that has the book, reads an early chapter
 * and the newest three, and says whether the newest ones are complete or padded teasers.
 */
fun qualityReport(args: List<String>, http: Http) {
    val sources = args[0].split(",").flatMap { f -> BookSource.parseList(File(f).readText()) }.distinctBy { it.key }.filter { usable(it, emptyList()) == null }
    val keyword = args[1]
    val pool = Executors.newFixedThreadPool(args.getOrNull(2)?.toInt() ?: 24) { r -> Thread(r).apply { isDaemon = true } }
    println("${sources.size} usable sources to try for 《$keyword》")
    val futures = sources.map { src ->
        src to pool.submit<Quality?> {
            try { measure(src, http.fork(), keyword) } catch (e: Throwable) { null }
        }
    }
    val found = ArrayList<Quality>()
    for ((i, p) in futures.withIndex()) {
        val q = try { p.second.get(150, TimeUnit.SECONDS) } catch (e: Exception) { null }
        if (q != null) { found.add(q); println("  + ${q.name}: toc ${q.toc}, latest ${q.latestNumber}, early ${q.earlyChars}, newest ${q.latestChars}, padded ${q.padded}${q.note?.let { " ($it)" } ?: ""}") }
        if ((i + 1) % 200 == 0) println("  … ${i + 1}/${futures.size} tried, ${found.size} have the book")
    }
    pool.shutdownNow()
    println("\n${found.size} sources have 《$keyword》\n")
    val ranked = found.sortedWith(compareByDescending<Quality> { it.full }.thenByDescending { it.latestNumber }.thenByDescending { it.toc })
    println("COMPLETE newest chapters (≥70% of an early chapter's length, no padding):")
    ranked.filter { it.full }.forEach { println("  ✓ ${it.name}  ${it.key}  latest ${it.latestNumber}  newest ${it.latestChars}") }
    println("\nNOT complete:")
    ranked.filter { !it.full }.forEach { println("  ✗ ${it.name}  latest ${it.latestNumber}  early ${it.earlyChars}  newest ${it.latestChars}  padded ${it.padded}${it.note?.let { n -> "  ($n)" } ?: ""}") }
}

private fun measure(src: BookSource, http: Http, keyword: String): Quality? {
    val web = WebBook(src, http)
    val hit = web.search(keyword).firstOrNull { it.name == keyword } ?: return null
    val book = Book.from(hit)
    web.bookInfo(book)
    val toc = web.chapters(book)
    if (toc.size < 30) return Quality(src.bookSourceName, src.key, toc.size, toc.lastOrNull()?.title.orEmpty(), -1, 0, emptyList(), 0, "目录只有 ${toc.size} 章")
    val others = toc.asSequence().map { it.url }.toSet()
    fun read(i: Int) = web.content(book, toc[i], toc.getOrNull(i + 1)?.url, otherChapterUrls = others - toc[i].url)
    val early = (listOf(10, 14).mapNotNull { i -> runCatching { read(i) }.getOrNull() })
    if (early.isEmpty()) return Quality(src.bookSourceName, src.key, toc.size, toc.last().title, ChapterNumbers.of(toc.last().title) ?: -1, 0, emptyList(), 0, "读不了早期章节")
    val ref = PreviewGuard.reference(early)
    val earlyChars = early.sumOf { it.count { c -> c.code in 0x4E00..0x9FFF } } / early.size
    val newest = (1..3).mapNotNull { k -> runCatching { read(toc.size - k) }.getOrNull() }
    var padded = 0
    val lens = newest.map { t ->
        if (ref != null && PreviewGuard.trim(t, ref).trimmed) padded++
        t.count { c -> c.code in 0x4E00..0x9FFF }
    }
    return Quality(src.bookSourceName, src.key, toc.size, toc.last().title, ChapterNumbers.of(toc.last().title) ?: -1, earlyChars, lens, padded, if (newest.isEmpty()) "读不了最新章" else null)
}

/** `checker latest <a.json,b.json,…> <keyword> [concurrency]`: runs the app's own [LatestCheck] on every source that has the book. */
fun latestReport(args: List<String>, http: Http) {
    val sources = args[0].split(",").flatMap { f -> BookSource.parseList(File(f).readText()).filter { it.enabled } }.distinctBy { it.key }.filter { usable(it, emptyList()) == null }
    val keyword = args[1]
    val pool = Executors.newFixedThreadPool(args.getOrNull(2)?.toInt() ?: 16) { r -> Thread(r).apply { isDaemon = true } }
    val futures = sources.map { src ->
        src to pool.submit<String?> {
            try {
                val t0 = System.currentTimeMillis()
                val h = http.fork()
                val web = WebBook(src, h)
                val hit = web.search(keyword).firstOrNull { it.name == keyword } ?: return@submit null
                val book = Book.from(hit)
                web.bookInfo(book)
                val toc = web.chapters(book)
                val r = app.openread.engine.LatestCheck.run(web, book, toc)
                "${src.bookSourceName}\t${src.key}\ttoc ${toc.size}\tlatest ${toc.lastOrNull()?.title}\t" +
                    (if (r == null) "UNKNOWN" else if (r.complete) "COMPLETE ${r.completeCount}" else "TEASER after ${r.completeCount} (${r.completeTitle})") + "\t${System.currentTimeMillis() - t0}ms"
            } catch (e: Throwable) { "${src.bookSourceName}\t${src.key}\tERROR ${e.message}" }
        }
    }
    for ((_, f) in futures) { val line = try { f.get(200, TimeUnit.SECONDS) } catch (e: Exception) { null }; if (line != null) println(line) }
    pool.shutdownNow()
}
