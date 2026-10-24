package app.openread.engine

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What a source's JS sees as the result of `java.get` / `java.connect`; also what [AnalyzeUrl] returns. */
class StrResponse(
    val url: String,
    val code: Int,
    val message: String,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    fun body() = body
    fun code() = code
    fun url() = url
    fun message() = message
    fun headers(): Map<String, List<String>> = headers
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    val isSuccessful: Boolean get() = code in 200..299
    override fun toString() = body
}

class HttpException(message: String, val code: Int = 0, cause: Throwable? = null) : Exception(message, cause)

/** In-memory cookies. The Android app swaps in a persistent jar; the checker just uses this one. */
open class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            for (c in cookies) {
                list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                if (c.expiresAt > System.currentTimeMillis()) list.add(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.flatMap { list ->
            synchronized(list) { list.filter { it.expiresAt > now && it.matches(url) } }
        }
    }

    /** `k=v; k2=v2`, the form `cookie.getCookie()` returns in a source's JS. */
    fun cookieString(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return ""
        return loadForRequest(u).joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun setCookies(url: String, header: String) {
        val u = url.toHttpUrlOrNull() ?: return
        val parsed = header.split(";").mapNotNull { part ->
            val kv = part.trim()
            if (kv.contains("=")) Cookie.parse(u, "$kv; Path=/") else null
        }
        saveFromResponse(u, parsed)
    }

    fun remove(url: String) {
        val u = url.toHttpUrlOrNull() ?: return
        store.remove(u.host)
    }
}

class Http(
    proxy: Proxy? = null,
    val cookieJar: MemoryCookieJar = MemoryCookieJar(),
    timeoutSec: Long = 15,
    parent: OkHttpClient? = null,
) {
    /** `cache` in source JS; lives as long as this client. */
    val cache = CacheBridge()

    /** `source.put/get` in source JS: variables are per source, shared by its search, info, toc and content calls. */
    val sourceVars = ConcurrentHashMap<String, RuleData>()
    fun varsOf(sourceKey: String): RuleData = sourceVars.getOrPut(sourceKey) { RuleData() }

    val client: OkHttpClient = (parent?.newBuilder() ?: OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .callTimeout(timeoutSec * 3, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true))
        .cookieJar(cookieJar)
        .build()

    /**
     * Many small sites serve an incomplete certificate chain, which browsers repair and Java/Android do not. A host
     * whose strict handshake fails on the certificate is retried, and from then on served, without chain validation.
     * Hosts with a valid chain are never affected.
     */
    private val lenientHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lenientClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        client.newBuilder().sslSocketFactory(ctx.socketFactory, trustAll).build()
    }

    /** A client with its own cookies and cache that shares this one's connection pool, proxy and timeouts. */
    fun fork(): Http = Http(parent = client)

    fun execute(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        charset: Charset? = null,
        retry: Int = 0,
    ): StrResponse {
        var last: Exception? = null
        repeat(retry + 1) {
            try {
                return once(url, method, headers, body, contentType, charset)
            } catch (e: HttpException) {
                last = e
            } catch (e: java.io.IOException) {
                last = HttpException(e.message ?: e.javaClass.simpleName, cause = e)
            }
        }
        throw last ?: HttpException("请求失败")
    }

    private fun once(url: String, method: String, headers: Map<String, String>, body: ByteArray?, contentType: String?, charset: Charset?): StrResponse {
        val httpUrl = url.toHttpUrlOrNull() ?: throw HttpException("地址无效：$url")
        val media: MediaType? = contentType?.toMediaTypeOrNull()
        val reqBody: RequestBody? = if (method.equals("GET", true) || method.equals("HEAD", true)) null
        else (body ?: ByteArray(0)).toRequestBody(media)
        // OkHttp replaces an explicit Cookie header with the jar's cookies, so a source's own cookies go into the jar instead.
        headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value?.let { cookieJar.setCookies(url, it) }
        val safeHeaders = headers.filterKeys { it.isNotBlank() && !it.equals("Cookie", true) }.mapValues { headerValue(it.value) }
        val req = try {
            Request.Builder().url(httpUrl).method(method.uppercase(), reqBody).headers(safeHeaders.toHeaders()).build()
        } catch (e: IllegalArgumentException) {
            throw HttpException("请求头无效：${e.message}", cause = e)
        }
        val host = httpUrl.host
        val response = try {
            (if (host in lenientHosts) lenientClient else client).newCall(req).execute()
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            if (host in lenientHosts || !isCertificateProblem(e)) throw e
            lenientHosts.add(host)
            lenientClient.newCall(req).execute()
        }
        response.use { resp ->
            val bytes = resp.body.bytes()
            val text = decode(bytes, resp.body.contentType(), charset)
            return StrResponse(resp.request.url.toString(), resp.code, resp.message, text, resp.headers.toMultimap())
        }
    }

    /** Header values must be printable ASCII; browsers percent-encode the rest (a Referer with Chinese in it, say). */
    private fun headerValue(v: String): String = UrlUtil.escapeUnsafe(v.filter { it >= ' ' && it != '\u007f' }).trim()

    /** Only "no path to a trusted root" (missing intermediate certificate). Expired or mismatched certificates are not retried. */
    private fun isCertificateProblem(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it.message?.contains("unable to find valid certification path") == true || it.message?.contains("Trust anchor for certification path not found") == true }

    companion object {
        private val metaCharset = Regex("""charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)

        fun charsetOrNull(name: String?): Charset? = name?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Charset.forName(if (it.equals("gb2312", true)) "GBK" else it) }.getOrNull() }

        /** Header charset > `<meta charset>` > strict UTF-8 > GBK, because Chinese novel sites are split between UTF-8 and GBK. */
        fun decode(bytes: ByteArray, type: MediaType?, forced: Charset?): String {
            if (forced != null) return String(bytes, forced)
            type?.charset()?.let { return String(bytes, it) }
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            charsetOrNull(metaCharset.find(head)?.groupValues?.get(1))?.let { return String(bytes, it) }
            return runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrElse { String(bytes, Charset.forName("GBK")) }
        }
    }
}
