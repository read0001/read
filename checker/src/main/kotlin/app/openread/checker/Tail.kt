package app.openread.checker

import app.openread.engine.Book
import app.openread.engine.BookSource
import app.openread.engine.Http
import app.openread.engine.WebBook
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `checker tail <sources.json> [keyword]`: reads the last chapters of a book in every source and compares them with a chapter
 * from the middle, to show how the newest chapters differ (cut short, extra pages, repeated text, wrong chapter).
 */
fun tailReport(args: List<String>, http: Http) {
    val sources = BookSource.parseList(File(args[0]).readText()).filter { it.canSearch }
    val keyword = args.getOrNull(1) ?: "斗破苍穹"
    val pool = Executors.newFixedThreadPool(8) { r -> Thread(r).apply { isDaemon = true } }
    val futures = sources.map { src ->
        src to pool.submit<String> {
            try { probeTail(src, http.fork(), keyword) } catch (e: Throwable) { "ERR ${e.message?.take(80)}" }
        }
    }
    for ((src, f) in futures) {
        val out = try { f.get(120, TimeUnit.SECONDS) } catch (e: Exception) { "TIMEOUT" }
        println("### ${src.bookSourceName}  ${src.key}\n$out\n")
    }
    pool.shutdownNow()
}

private fun probeTail(src: BookSource, http: Http, keyword: String): String {
    val web = WebBook(src, http)
    val hit = web.search(keyword).firstOrNull { it.name == keyword } ?: return "no exact hit"
    val book = Book.from(hit)
    web.bookInfo(book)
    val toc = web.chapters(book)
    if (toc.size < 20) return "toc too short: ${toc.size}"
    val sb = StringBuilder("toc=${toc.size}  first='${toc.first().title}'  last='${toc.last().title}'  latestFromInfo='${book.latestChapterTitle}'\n")
    fun read(label: String, idx: Int) {
        val ch = toc[idx]
        val pages = ArrayList<String>()
        val text = runCatching { web.content(book, ch, toc.getOrNull(idx + 1)?.url, pages) }
        sb.append("  [$label #$idx '${ch.title}'] pages=${pages.size} ")
        text.onSuccess {
            val lines = it.lines()
            sb.append("chars=${it.length} lines=${lines.size}\n      head: ${it.take(40).replace('\n', ' ')}\n      tail: ${it.takeLast(60).replace('\n', ' ')}\n")
            if (pages.size > 1) sb.append("      pages: ${pages.joinToString(" | ") { u -> u.takeLast(40) }}\n")
            val dup = lines.groupingBy { l -> l }.eachCount().filter { (l, n) -> l.length > 15 && n > 1 }
            if (dup.isNotEmpty()) sb.append("      REPEATED lines: ${dup.size}\n")
        }.onFailure { sb.append("FAILED ${it.message?.take(80)}\n") }
    }
    read("middle", toc.size / 2)
    read("second-last", toc.size - 2)
    read("LAST", toc.size - 1)
    return sb.toString()
}
