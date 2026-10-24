package app.openread.checker

import app.openread.engine.BookSource
import app.openread.engine.Http
import app.openread.engine.JsonCodec
import app.openread.engine.Mirrors
import app.openread.engine.Probe
import app.openread.engine.ProbeResult
import app.openread.engine.TocConsistency
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val USAGE = """usage: checker <candidates.tsv> <out-dir> [--concurrency N] [--keywords a,b,c] [--max-test N] [--per-host N] [--timeout SEC] [--only TEXT] [--no-proxy]

Fetches every collection listed in candidates.tsv (name<TAB>url per line), runs the real search -> info -> toc ->
content flow against each enabled text source, and writes out-dir/booksources.json with the ones that work.
Exits 1 without touching any file when nothing works, so a bad run never publishes an empty list."""

private class Options(
    val candidates: File,
    val out: File,
    val concurrency: Int,
    val keywords: List<String>,
    val maxTest: Int,
    val perHost: Int,
    val timeoutSec: Long,
    val only: String?,
    val useProxy: Boolean,
)

private fun parseArgs(args: Array<String>): Options? {
    val pos = ArrayList<String>()
    val flags = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            if (a == "--no-proxy") flags[a] = "1" else { flags[a] = args.getOrNull(i + 1) ?: return null; i++ }
        } else pos.add(a)
        i++
    }
    if (pos.size < 2) return null
    return Options(
        File(pos[0]), File(pos[1]),
        flags["--concurrency"]?.toIntOrNull() ?: 24,
        (flags["--keywords"] ?: "斗破苍穹,诡秘之主,斗罗大陆").split(",").map { it.trim() }.filter { it.isNotEmpty() },
        flags["--max-test"]?.toIntOrNull() ?: 2000,
        flags["--per-host"]?.toIntOrNull() ?: 1,
        flags["--timeout"]?.toLongOrNull() ?: 15,
        flags["--only"],
        "--no-proxy" !in flags,
    )
}

private fun proxyFromEnv(): Proxy? {
    val v = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy").firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
        ?: return null
    val u = runCatching { URI(v) }.getOrNull() ?: return null
    val host = u.host ?: return null
    return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, if (u.port > 0) u.port else 8080))
}

private fun host(src: BookSource): String =
    runCatching { URI(src.baseUrl).host }.getOrNull()?.removePrefix("www.")?.removePrefix("m.") ?: src.baseUrl

/** What the engine cannot do at all; testing these only burns time. */
private val unsupported = listOf("java.webView(", "java.startBrowser", "java.getVerificationCode", "java.openUrl(")

internal fun usable(src: BookSource, blocklist: List<String>): String? {
    val text = "${src.bookSourceName} ${src.bookSourceGroup} ${src.bookSourceComment} ${src.key}".lowercase()
    if (blocklist.any { text.contains(it) }) return "blocklisted"
    if (!src.enabled) return "disabled"
    if (!src.isText) return "not text"
    if (!src.canSearch) return "no search"
    if (src.ruleSearch?.name.isNullOrBlank() || src.ruleSearch?.bookUrl.isNullOrBlank()) return "no search rule"
    if (src.ruleToc?.chapterList.isNullOrBlank() || src.ruleContent?.content.isNullOrBlank()) return "no toc/content rule"
    val raw = src.toJson()
    if (unsupported.any { raw.contains(it) }) return "needs webview/browser"
    return null
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "latest" && args.size >= 3) {
        latestReport(args.drop(1), Http(proxy = proxyFromEnv(), timeoutSec = 15))
        exitProcess(0)
    }
    if (args.firstOrNull() == "dump" && args.size >= 6) {
        dumpChapters(args.drop(1), Http(proxy = proxyFromEnv(), timeoutSec = 20))
        exitProcess(0)
    }
    if (args.firstOrNull() == "compare" && args.size >= 4) {
        compareChapter(args.drop(1), Http(proxy = proxyFromEnv(), timeoutSec = 20))
        exitProcess(0)
    }
    if (args.firstOrNull() == "tail" && args.size >= 2) {
        tailReport(args.drop(1), Http(proxy = proxyFromEnv(), timeoutSec = 20))
        exitProcess(0)
    }
    if (args.firstOrNull() == "debug" && args.size >= 3) {
        debugSource(args.drop(1), Http(proxy = proxyFromEnv(), timeoutSec = 20))
        exitProcess(0)
    }
    val o = parseArgs(args) ?: run { System.err.println(USAGE); exitProcess(2) }
    val http = Http(proxy = if (o.useProxy) proxyFromEnv() else null, timeoutSec = o.timeoutSec)
    val blocklist = File(o.candidates.absoluteFile.parentFile, "blocklist.txt").takeIf { it.exists() }?.readLines()
        ?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() && !it.startsWith("#") } ?: emptyList()
    val publishedFile = File(o.out, "booksources.json")
    val stateFile = File(o.out, "state.json")

    // 1. collect candidate sources from every collection, plus whatever was published last time
    val pool = LinkedHashMap<String, Pair<BookSource, String>>()
    val lines = o.candidates.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    for (line in lines) {
        val cols = line.split("\t")
        if (cols.size < 2) { System.err.println("skip malformed line: $line"); continue }
        val (name, url) = cols[0] to cols[1]
        val sources = runCatching {
            runBlocking { Mirrors.fetch(http, url) { BookSource.parseList(it).isNotEmpty() } }.let { BookSource.parseList(it.body) }
        }.getOrElse { println("  ✗ $name: ${it.message}"); emptyList() }
        println("  ${if (sources.isEmpty()) "✗" else "✓"} $name: ${sources.size} sources")
        for (s in sources) pool.putIfAbsent(s.key, s to name)
    }
    val previous: List<BookSource> = if (publishedFile.exists()) BookSource.parseList(publishedFile.readText()) else emptyList()
    for (s in previous) pool.putIfAbsent(s.key, s to "上次发布")
    println("pool: ${pool.size} distinct sources")

    // 2. prefilter
    val skipped = HashMap<String, Int>()
    var candidates = pool.values.filter { (s, _) ->
        val why = usable(s, blocklist)
        if (why != null) skipped.merge(why, 1, Int::plus)
        why == null
    }
    println("skipped: $skipped -> ${candidates.size} to test")
    o.only?.let { t -> candidates = candidates.filter { (s, _) -> s.bookSourceName.contains(t) || s.key.contains(t) } }
    if (candidates.size > o.maxTest) {
        // Keep the previously published ones, fill the rest in file order.
        val prevKeys = previous.map { it.key }.toSet()
        candidates = candidates.sortedBy { if (it.first.key in prevKeys) 0 else 1 }.take(o.maxTest)
        println("limited to ${o.maxTest}")
    }

    // 3. probe
    val executor = Executors.newFixedThreadPool(o.concurrency) { r -> Thread(r).apply { isDaemon = true } }
    val results = ArrayList<ProbeResult>()
    val futures = candidates.map { (s, _) -> s to executor.submit(Callable { Probe.run(s, http, o.keywords) }) }
    var done = 0
    for ((s, f) in futures) {
        val r = try { f.get(90, TimeUnit.SECONDS) } catch (e: Exception) {
            f.cancel(true)
            ProbeResult(s.key, s.bookSourceName, false, "search", "超时", 0, 90_000, null, null, 0, 0)
        }
        results.add(r)
        done++
        println("[%4d/%d] %s %s %s".format(done, futures.size, if (r.ok) "✓" else "✗", r.name, if (r.ok) "${r.totalMs}ms ${r.book} ${r.chapters}章" else "${r.stage}: ${r.error}"))
    }
    executor.shutdownNow()

    // 3b. a source that returns only the first page of a table of contents passes every probe step: compare with the others
    val truncated = TocConsistency.incomplete(results)
    if (truncated.isNotEmpty()) println("incomplete table of contents (far fewer chapters than other sources): ${truncated.size}")
    for (i in results.indices) {
        val r = results[i]
        if (r.key in truncated) results[i] = ProbeResult(r.key, r.name, false, "toc", "目录不完整：只有 ${r.chapters} 章，其他书源多得多", r.searchMs, r.totalMs, r.keyword, r.book, r.chapters, 0)
    }

    // 4. choose what to publish
    val byKey = pool.mapValues { it.value.first }
    val state = readState(stateFile)
    val prevKeys = previous.map { it.key }.toSet()
    val fails = HashMap<String, Int>()
    for (r in results) {
        fails[r.key] = if (r.ok) 0 else (state[r.key] ?: 0) + 1
    }
    val passing = results.filter { it.ok }
    if (passing.isEmpty()) {
        System.err.println("no source passed; leaving ${o.out} untouched")
        exitProcess(1)
    }
    // A source that worked last time and failed once may just have had a bad moment: keep it until it fails twice running.
    // (A truncated table of contents is not a bad moment, so it is never kept.)
    val retained = results.filter { !it.ok && it.key in prevKeys && (fails[it.key] ?: 0) < 2 && it.error?.startsWith("目录不完整") != true }
    // Stable order (collection order) so a run that changes nothing writes the same file and CI makes no commit.
    val order = pool.keys.withIndex().associate { it.value to it.index }
    val chosen = (passing + retained).sortedBy { order[it.key] ?: Int.MAX_VALUE }
    val seenHosts = HashMap<String, Int>()
    val finalList = chosen.filter { r ->
        val h = host(byKey.getValue(r.key))
        val n = seenHosts.merge(h, 1, Int::plus)!!
        n <= o.perHost
    }

    o.out.mkdirs()
    val timing = results.associateBy { it.key }
    val array = JsonArray(
        finalList.map { r ->
            val src = byKey.getValue(r.key)
            JsonObject(src.raw + mapOf("enabled" to JsonPrimitive(true)))
        },
    )
    publishedFile.writeText(JsonCodec.pretty.encodeToString(JsonElement.serializer(), array) + "\n")
    stateFile.writeText(JsonCodec.pretty.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("sources", JsonObject(fails.filterKeys { k -> finalList.any { it.key == k } || fails[k]!! > 0 && k in prevKeys }.mapValues { JsonPrimitive(it.value) }))
    }) + "\n")
    File(o.out, "results.json").writeText(JsonCodec.pretty.encodeToString(JsonElement.serializer(), JsonArray(
        results.map { r ->
            buildJsonObject {
                put("key", r.key); put("name", r.name); put("ok", r.ok); put("stage", r.stage)
                put("error", r.error?.let { JsonPrimitive(it) } ?: JsonNull)
                put("searchMs", r.searchMs); put("totalMs", r.totalMs); put("keyword", r.keyword ?: ""); put("book", r.book ?: "")
                put("chapters", r.chapters); put("contentChars", r.contentChars)
            }
        },
    )) + "\n")

    val stages = results.groupingBy { if (it.ok) "ok" else it.stage }.eachCount()
    println("\n${passing.size}/${results.size} passed (${stages}); published ${finalList.size} (${retained.size} kept from last run)")
    println("by host: ${timing.size} tested")
    finalList.forEach { println("  ${it.name}  ${it.totalMs}ms  ${it.key}") }
}

private fun readState(f: File): Map<String, Int> {
    if (!f.exists()) return emptyMap()
    val obj = (JsonCodec.parse(f.readText()) as? JsonObject)?.get("sources") as? JsonObject ?: return emptyMap()
    return obj.mapValues { (it.value as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
}
