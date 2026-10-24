package app.openread.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.Charset
import java.util.Base64

/**
 * Turns a Legado URL rule into a request and runs it. Handles `{{js}}`, `<page1,page2>`, `@js:` / `<js>` blocks,
 * and the `url,{"method":"POST","body":"…","charset":"gbk","headers":{…}}` option suffix.
 */
class AnalyzeUrl(
    mUrl: String,
    private val key: String? = null,
    private val page: Int? = null,
    baseUrl: String? = null,
    private val source: BookSource? = null,
    private val http: Http,
    private val data: RuleData? = null,
    private val extraHeaders: Map<String, String>? = null,
    private val forceMethod: String? = null,
    private val forceBody: String? = null,
    private val book: Book? = null,
    private val chapter: Chapter? = null,
) {
    val baseUrl: String = (baseUrl ?: source?.baseUrl.orEmpty()).substringBefore("#").substringBefore(",{")
    var url: String = ""
        private set
    private var method = "GET"
    private var body: String? = null
    private var charset: Charset? = null
    private val headers = LinkedHashMap<String, String>()
    private var retry = 0
    /** Set when the source asked for a WebView; the plain request is made instead. */
    var webViewRequested = false
        private set

    private var ruleUrl = mUrl

    init {
        analyzeJs()
        replaceKeyPageJs()
        analyzeUrl()
    }

    private fun jsBindings(result: Any?): Map<String, Any?> {
        val rule = AnalyzeRule(data, source, http)
        val m = LinkedHashMap<String, Any?>()
        m["java"] = JsExtensions(http, source, data, rule)
        m["cookie"] = CookieBridge(http.cookieJar)
        m["cache"] = http.cache
        if (source != null) m["source"] = SourceBridge(source, http.varsOf(source.key))
        book?.let { m["book"] = BookBridge(it) }
        chapter?.let { m["chapter"] = ChapterBridge(it) }
        m["baseUrl"] = baseUrl
        m["result"] = result
        m["page"] = page ?: 1
        m["key"] = key.orEmpty()
        m["title"] = chapter?.title.orEmpty()
        return m
    }

    private fun evalJs(js: String, result: Any?): Any? = JsRuntime.eval(js, jsBindings(result), source?.jsLib)

    private fun analyzeJs() {
        var start = 0
        var result: Any? = ruleUrl
        var found = false
        for (m in JS_PATTERN.findAll(ruleUrl)) {
            found = true
            if (m.range.first > start) result = ruleUrl.substring(start, m.range.first).replace("@result", result.toString())
            result = evalJs(m.groups[2]?.value ?: m.groups[1]?.value.orEmpty(), result)
            start = m.range.last + 1
        }
        if (!found) return
        if (ruleUrl.length > start) result = ruleUrl.substring(start).replace("@result", result.toString())
        ruleUrl = Values.text(result)
    }

    private fun replaceKeyPageJs() {
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            ruleUrl = Templates.replaceInner(ruleUrl, "{{", "}}") { Values.text(evalJs(it, null)) }
        }
        val p = page
        if (p != null) {
            ruleUrl = PAGE_PATTERN.replace(ruleUrl) { m ->
                val pages = m.groupValues[1].split(",")
                (if (p <= pages.size) pages[p - 1] else pages.last()).trim()
            }
        }
    }

    private fun analyzeUrl() {
        val optionMatch = OPTION_SPLIT.find(ruleUrl)
        var urlPart = if (optionMatch != null) ruleUrl.substring(0, optionMatch.range.first) else ruleUrl
        val option = optionMatch?.let { JsonCodec.parse(ruleUrl.substring(it.range.last + 1)) as? JsonObject }
        charset = Http.charsetOrNull(option?.str("charset"))

        method = (forceMethod ?: option?.str("method") ?: "GET").uppercase()
        retry = option?.int("retry") ?: 0
        webViewRequested = option?.get("webView")?.let { (it as? JsonPrimitive)?.content?.let { c -> c == "true" || c == "1" } } ?: false
        body = forceBody ?: option?.get("body")?.let { if (it is JsonPrimitive) it.content else it.toString() }

        initHeaders(option?.get("headers"))
        extraHeaders?.let { headers.putAll(it) }

        if (urlPart.startsWith("data:")) { url = urlPart; return }
        // Resolve the path against the source address, but encode the query on its own: URI resolution would mangle `+` and `#`.
        val q = urlPart.indexOf('?')
        val cs = charset ?: Charsets.UTF_8
        url = if (q < 0) UrlUtil.absolute(baseUrl, urlPart).let { UrlUtil.escapeUnsafe(it) }
        else UrlUtil.absolute(baseUrl, urlPart.substring(0, q)).substringBefore("?").let { UrlUtil.escapeUnsafe(it) } +
            "?" + UrlUtil.encodeQuery(urlPart.substring(q + 1), cs)

        option?.str("js")?.takeIf { it.isNotBlank() }?.let { js ->
            evalJs(js, url)?.toString()?.takeIf { it.isNotBlank() }?.let { url = it }
        }
    }

    private fun initHeaders(optionHeaders: JsonElement?) {
        var text = source?.header?.trim().orEmpty()
        if (text.startsWith("@js:", true) || text.startsWith("<js>", true)) {
            text = Values.text(evalJs(text.removePrefix("@js:").removePrefix("<js>").removeSuffix("</js>"), null))
        }
        (JsonCodec.parse(text) as? JsonObject)?.forEach { (k, v) -> headers[k] = (v as? JsonPrimitive)?.content ?: v.toString() }
        when (optionHeaders) {
            is JsonObject -> optionHeaders.forEach { (k, v) -> headers[k] = (v as? JsonPrimitive)?.content ?: v.toString() }
            is JsonPrimitive -> (JsonCodec.parse(optionHeaders.content) as? JsonObject)?.forEach { (k, v) -> headers[k] = (v as? JsonPrimitive)?.content ?: v.toString() }
            else -> {}
        }
        if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = DEFAULT_UA
    }

    /** Form bodies are encoded per field in the request's charset; JSON bodies go out as they are. */
    private fun requestBody(): Pair<ByteArray?, String?> {
        val b = body ?: return null to null
        val cs = charset ?: Charsets.UTF_8
        val explicit = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
        if (b.trimStart().startsWith("{") || b.trimStart().startsWith("[")) {
            return b.toByteArray(cs) to (explicit ?: "application/json; charset=${cs.name()}")
        }
        val encoded = b.split("&").joinToString("&") { pair ->
            val i = pair.indexOf('=')
            if (i < 0) pair else {
                val v = pair.substring(i + 1)
                pair.substring(0, i + 1) + if (UrlUtil.isEncoded(v)) v else UrlUtil.encode(v, cs)
            }
        }
        return encoded.toByteArray(cs) to (explicit ?: "application/x-www-form-urlencoded; charset=${cs.name()}")
    }

    /** For logs: the request as it will be sent. */
    fun describe(): String = "$method $url" + (body?.let { " body=$it" } ?: "") + (charset?.let { " charset=${it.name()}" } ?: "")

    fun getStrResponse(): StrResponse {
        if (url.startsWith("data:")) return dataUrl(url)
        val (bytes, type) = requestBody()
        val h = headers.filterKeys { !it.equals("Content-Type", true) || bytes == null }
        return http.execute(url, method, h, bytes, type, charset, retry)
    }

    private fun dataUrl(u: String): StrResponse {
        val comma = u.indexOf(',')
        val meta = u.substring(5, maxOf(comma, 5))
        val payload = if (comma >= 0) u.substring(comma + 1) else ""
        val text = if (meta.contains("base64")) String(Base64.getMimeDecoder().decode(payload), charset ?: Charsets.UTF_8) else UrlUtil.decode(payload)
        return StrResponse(u, 200, "OK", text, emptyMap())
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val JS_PATTERN = Regex("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", RegexOption.IGNORE_CASE)
        private val PAGE_PATTERN = Regex("<(.*?)>")
        private val OPTION_SPLIT = Regex("\\s*,\\s*(?=\\{)")
    }
}

/** `{{…}}` scanning that survives braces inside the expression, e.g. `{{ {a:1}.a }}`. */
internal object Templates {
    fun replaceInner(text: String, open: String, close: String, transform: (String) -> String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val s = text.indexOf(open, i)
            if (s < 0) { sb.append(text, i, text.length); break }
            val e = findEnd(text, s + open.length, close)
            if (e < 0) { sb.append(text, i, text.length); break }
            sb.append(text, i, s).append(transform(text.substring(s + open.length, e)))
            i = e + close.length
        }
        return sb.toString()
    }

    private fun findEnd(text: String, from: Int, close: String): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '{' -> depth++
                text.startsWith(close, i) && depth == 0 -> return i
                c == '}' -> if (depth > 0) depth--
            }
            i++
        }
        return -1
    }
}

/** Coercions shared by the engine: a rule result of any kind becomes a string the way Legado shows it. */
internal object Values {
    fun text(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> if (v % 1.0 == 0.0 && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> if (v % 1f == 0f) v.toLong().toString() else v.toString()
        is Iterable<*> -> v.joinToString("\n") { text(it) }
        is Map<*, *> -> JsonCodec.lenient.encodeToString(JsonElement.serializer(), toJson(v))
        else -> v.toString()
    }

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
        is Iterable<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }
}
