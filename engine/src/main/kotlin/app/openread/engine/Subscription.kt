package app.openread.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Subscription files live on GitHub, which is slow or blocked in places. [Mirrors.of] lists the same file through the
 * usual mirrors and [Mirrors.fetch] asks all of them at once, like the TV app does.
 */
object Mirrors {
    private const val RAW = "https://raw.githubusercontent.com/"
    private const val GH_PROXY = "https://gh-proxy.com/"
    private const val GRACE_MS = 3_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every address that serves the GitHub raw file [url]; other URLs are returned as they are. */
    fun of(url: String): List<String> {
        val path = when {
            url.startsWith(RAW) -> url.removePrefix(RAW)
            url.startsWith(GH_PROXY + RAW) -> url.removePrefix(GH_PROXY + RAW)
            else -> return listOf(url)
        }
        val parts = path.split("/", limit = 4)
        val jsdelivr = if (parts.size == 4) listOf("https://cdn.jsdelivr.net/gh/${parts[0]}/${parts[1]}@${parts[2]}/${parts[3]}") else emptyList()
        return listOf(GH_PROXY + RAW + path, "https://ghfast.top/" + RAW + path) + jsdelivr +
            listOf(RAW + path, "https://ghproxy.net/" + RAW + path)
    }

    /**
     * Asks every mirror at once. The first answer that passes [valid] wins, but GitHub's own copy is preferred when it
     * arrives within a short grace period, since a mirror can serve an old copy for hours.
     */
    suspend fun fetch(http: Http, url: String, valid: (String) -> Boolean = { it.isNotBlank() }): StrResponse {
        val urls = of(url)
        if (urls.size == 1) return http.execute(url, retry = 1)
        val raw = urls.indexOfFirst { it.startsWith(RAW) }
        val answers = Channel<Pair<Int, Result<StrResponse>>>(urls.size)
        val jobs = urls.mapIndexed { i, u -> scope.launch { answers.send(i to runCatching { http.execute(u, retry = 1) }) } }
        var chosen: StrResponse? = null
        var failure: Throwable? = null
        var answered = 0
        while (answered < urls.size) {
            val next = if (chosen == null) answers.receive() else (withTimeoutOrNull(GRACE_MS) { answers.receive() } ?: break)
            answered++
            val (i, r) = next
            val resp = r.getOrNull()
            if (resp == null || resp.code != 200 || !valid(resp.body)) {
                failure = failure ?: r.exceptionOrNull() ?: HttpException("HTTP ${resp?.code}")
                continue
            }
            if (chosen == null || i == raw) chosen = resp
            if (i == raw) break
        }
        jobs.forEach { it.cancel() }
        return chosen ?: throw (failure ?: HttpException("没有可用的镜像"))
    }

    /** What came back from asking several addresses for the same file. */
    class Answers(val bodies: List<String>, val error: String?)

    /**
     * Asks every mirror at once and keeps every usable answer: waits for the first one, then at most a short grace period
     * for the rest. Mirrors differ in how long they keep an old copy, so the caller picks the newest (a version manifest
     * from a stale mirror must not hide a release). A 404 means "that mirror has no such file" and is not an error.
     */
    suspend fun fetchAll(http: Http, url: String, valid: (String) -> Boolean = { it.isNotBlank() }): Answers = fetchAllFrom(http, of(url), valid)

    suspend fun fetchAllFrom(http: Http, urls: List<String>, valid: (String) -> Boolean = { it.isNotBlank() }): Answers {
        val answers = Channel<Pair<String?, String?>>(urls.size)
        val jobs = urls.map { u ->
            scope.launch {
                val r = runCatching { http.execute(u, retry = 0) }
                val resp = r.getOrNull()
                answers.send(
                    when {
                        resp == null -> null to (r.exceptionOrNull()?.message ?: "连接失败")
                        resp.code == 404 -> null to null
                        resp.code != 200 -> null to "HTTP ${resp.code}"
                        !valid(resp.body) -> null to "内容格式不对"
                        else -> resp.body to null
                    },
                )
            }
        }
        val good = ArrayList<String>()
        var error: String? = null
        var answered = 0
        while (answered < urls.size) {
            val (body, e) = if (good.isEmpty()) answers.receive() else (withTimeoutOrNull(GRACE_MS) { answers.receive() } ?: break)
            answered++
            if (body != null) good.add(body) else error = error ?: e
        }
        jobs.forEach { it.cancel() }
        return Answers(good, error)
    }

    /**
     * For a download: every mirror is asked at once and the first to answer with a file wins; the other requests are
     * cancelled, so the bytes come from the fastest line. The caller reads and closes the response.
     */
    suspend fun openFirst(client: okhttp3.OkHttpClient, urls: List<String>, headers: Map<String, String> = emptyMap()): okhttp3.Response {
        val calls = urls.map { client.newCall(okhttp3.Request.Builder().url(it).apply { headers.forEach { (k, v) -> header(k, v) } }.build()) }
        val answers = Channel<Pair<okhttp3.Call, okhttp3.Response?>>(calls.size)
        val jobs = calls.map { call -> scope.launch { answers.send(call to runCatching { call.execute() }.getOrNull()) } }
        var winner: okhttp3.Response? = null
        var lastCode = 0
        try {
            repeat(calls.size) {
                val (call, r) = answers.receive()
                if (r == null) return@repeat
                if (winner == null && r.isSuccessful) {
                    winner = r
                    calls.forEach { other -> if (other !== call) other.cancel() }
                } else {
                    lastCode = r.code
                    r.close()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The download was abandoned: stop every request, including a winner nobody will read.
            calls.forEach { it.cancel() }
            winner?.close()
            throw e
        }
        jobs.forEach { it.cancel() }
        return winner ?: throw HttpException("下载失败（" + (if (lastCode > 0) "HTTP $lastCode" else "所有线路都连不上") + "）", code = lastCode)
    }
}
