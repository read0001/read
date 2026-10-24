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

/**
 * `checker compare <sources.json> <keyword> <chapter number>`: fetches the same chapter from every source that has the book
 * and prints, for each paragraph, in how many sources it appears. Paragraphs that only some sources carry are the suspects.
 */
fun compareChapter(args: List<String>, http: Http) {
    val sources = BookSource.parseList(File(args[0]).readText()).filter { it.canSearch }
    val keyword = args[1]
    val number = args[2].toInt()
    val pool = Executors.newFixedThreadPool(8) { r -> Thread(r).apply { isDaemon = true } }
    val futures = sources.map { src ->
        src to pool.submit<List<String>?> {
            try {
                val h = http.fork()
                val web = WebBook(src, h)
                val hit = web.search(keyword).firstOrNull { it.name == keyword } ?: return@submit null
                val book = Book.from(hit)
                web.bookInfo(book)
                val toc = web.chapters(book)
                val ch = toc.lastOrNull { app.openread.engine.ChapterNumbers.of(it.title) == number } ?: return@submit null
                web.content(book, ch, toc.getOrNull(ch.index + 1)?.url).lines().map { it.trim() }.filter { it.isNotEmpty() }
            } catch (e: Throwable) { null }
        }
    }
    val texts = futures.mapNotNull { (src, f) -> (try { f.get(120, TimeUnit.SECONDS) } catch (e: Exception) { null })?.let { src.bookSourceName to it } }
    pool.shutdownNow()
    println("sources with chapter $number: ${texts.size}")
    texts.forEach { (n, t) -> println("  $n: ${t.size} paragraphs, ${t.sumOf { it.length }} chars") }
    val count = HashMap<String, Int>()
    texts.forEach { (_, t) -> t.distinct().forEach { p -> count.merge(p, 1, Int::plus) } }
    val first = texts.maxByOrNull { it.second.size }?.second ?: return
    val ends = "。！？…”」』）)".toSet()
    val unpunctuated = first.withIndex().filter { (i, p) -> i > 0 && p.last() !in ends && !p.contains("本章未完") && !Regex("第.{1,8}页").containsMatchIn(p) }.map { it.index }
    println("\nUNPUNCTUATED paragraphs (candidate cut points): $unpunctuated of ${first.size}")
    if (args.getOrNull(3) == "brief") return
    println("\nparagraphs of the longest copy, with how many of ${texts.size} sources carry each:")
    first.forEachIndexed { i, p -> println("%2d [%d/%d] %s".format(i, count[p], texts.size, p.take(46))) }
}

/** `checker dump <sources.json> <source name part> <keyword> <out.json> <chapter numbers…>`: saves the paragraphs of those chapters from one source. */
fun dumpChapters(args: List<String>, http: Http) {
    val src = BookSource.parseList(File(args[0]).readText()).first { it.bookSourceName.contains(args[1]) || it.key.contains(args[1]) }
    val web = WebBook(src, http)
    val hit = web.search(args[2]).first { it.name == args[2] }
    val book = Book.from(hit)
    web.bookInfo(book)
    val toc = web.chapters(book)
    val out = StringBuilder("{")
    for ((k, n) in args.drop(4).map { it.toInt() }.withIndex()) {
        val ch = toc.lastOrNull { app.openread.engine.ChapterNumbers.of(it.title) == n } ?: continue
        val text = runCatching { web.content(book, ch, toc.getOrNull(ch.index + 1)?.url) }.getOrNull() ?: continue
        if (k > 0) out.append(',')
        out.append("\"$n\":").append(app.openread.engine.Values.toJson(text.lines().map { it.trim() }.filter { it.isNotEmpty() }).toString())
        println("chapter $n: ${text.length} chars, index ${ch.index} of ${toc.size}")
    }
    File(args[3]).writeText(out.append('}').toString())
}
