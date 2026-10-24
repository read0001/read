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
    suspend fun fetch(
        http: Http,
        url: String,
        /** Extra check on a mirror's answer, given its body and the address it came from; the signature check lives here. */
        verify: ((body: String, from: String) -> Boolean)? = null,
        valid: (String) -> Boolean = { it.isNotBlank() },
    ): StrResponse {
        val urls = of(url)
        if (urls.size == 1) {
            val r = http.execute(url, retry = 1)
            if (verify != null && !verify(r.body, url)) throw HttpException("签名校验失败")
            return r
        }
        val raw = urls.indexOfFirst { it.startsWith(RAW) }
        val answers = Channel<Pair<Int, Result<StrResponse>>>(urls.size)
        val jobs = urls.mapIndexed { i, u ->
            scope.launch {
                val r = runCatching {
                    http.execute(u, retry = 1).also { if (verify != null && it.code == 200 && !verify(it.body, u)) throw HttpException("签名校验失败") }
                }
                answers.send(i to r)
            }
        }
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
}
