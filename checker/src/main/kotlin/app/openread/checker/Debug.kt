package app.openread.checker

import app.openread.engine.AnalyzeUrl
import app.openread.engine.Book
import app.openread.engine.BookSource
import app.openread.engine.EngineLog
import app.openread.engine.Http
import app.openread.engine.Mirrors
import app.openread.engine.RuleData
import app.openread.engine.WebBook
import kotlinx.coroutines.runBlocking
import java.io.File

/** `checker debug <file-or-url> <name-substring> [keyword]`: walks one source through every stage and prints what each one saw. */
fun debugSource(args: List<String>, http: Http) {
    val (where, needle) = args[0] to args[1]
    val keyword = args.getOrNull(2) ?: "斗破苍穹"
    val text = if (File(where).exists()) File(where).readText() else runBlocking { Mirrors.fetch(http, where).body }
    val src = BookSource.parseList(text).firstOrNull { it.bookSourceName.contains(needle) || it.key.contains(needle) }
        ?: run { println("no source matches '$needle'"); return }
    EngineLog.sink = { println("  [js] $it") }
    println("source: ${src.bookSourceName}  ${src.key}\nsearchUrl: ${src.searchUrl}")
    val h = http.fork()
    val web = WebBook(src, h)

    val au = AnalyzeUrl(src.searchUrl!!, key = keyword, page = 1, source = src, http = h, data = RuleData())
    println("request: ${au.describe()}")
    val resp = au.getStrResponse()
    println("response: ${resp.code} ${resp.url} (${resp.body.length} chars)\n${resp.body.take(500).replace("\n", " ")}\n")

    val hits = runCatching { web.search(keyword) }.onFailure { println("search failed: $it"); it.printStackTrace(System.out) }.getOrNull() ?: return
    println("search: ${hits.size} hits")
    hits.take(3).forEach { println("  ${it.name} / ${it.author} / ${it.bookUrl} / cover=${it.coverUrl}") }
    val hit = hits.firstOrNull { it.name.contains(keyword) } ?: hits.firstOrNull() ?: return

    val book = Book.from(hit)
    runCatching { web.bookInfo(book) }.onFailure { println("info failed: $it"); it.printStackTrace(System.out); return }
    println("info: ${book.name} / ${book.author} / toc=${book.tocUrl} / intro=${book.intro?.take(60)}")

    val toc = runCatching { web.chapters(book) }.onFailure { println("toc failed: $it"); it.printStackTrace(System.out); return }.getOrThrow()
    println("toc: ${toc.size} chapters")
    toc.take(3).forEach { println("  ${it.index} ${it.title} ${it.url} vip=${it.isVip}") }
    val ch = toc.filter { !it.isVolume && !it.isVip }.getOrNull(1) ?: toc.firstOrNull() ?: return

    val body = runCatching { web.content(book, ch, toc.getOrNull(ch.index + 1)?.url) }.onFailure { println("content failed: $it"); it.printStackTrace(System.out); return }.getOrThrow()
    println("content: ${body.length} chars\n${body.take(300)}")
}
