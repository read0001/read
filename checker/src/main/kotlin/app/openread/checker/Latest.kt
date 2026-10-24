package app.openread.checker

import app.openread.engine.Book
import app.openread.engine.BookSource
import app.openread.engine.Http
import app.openread.engine.WebBook
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
