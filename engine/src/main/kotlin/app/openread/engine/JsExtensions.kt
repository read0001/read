package app.openread.engine

import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Where `java.log()` and friends go; the checker prints them, the app drops them. */
object EngineLog {
    @Volatile var sink: ((String) -> Unit)? = null
    fun log(msg: String) { sink?.invoke(msg) }
}

/** `cache` in source JS. In memory only. */
class CacheBridge {
    private val map = ConcurrentHashMap<String, String>()
    fun get(key: String): String? = map[key]
    fun put(key: String, value: Any?): String { map[key] = value?.toString().orEmpty(); return map[key]!! }
    fun put(key: String, value: Any?, @Suppress("UNUSED_PARAMETER") saveTimeSeconds: Int): String = put(key, value)
    fun delete(key: String) { map.remove(key) }
}

/** `cookie` in source JS. */
class CookieBridge(private val jar: MemoryCookieJar) {
    fun getCookie(url: String): String = jar.cookieString(url)
    fun getKey(url: String, key: String): String =
        jar.cookieString(url).split(";").map { it.trim() }.firstOrNull { it.startsWith("$key=") }?.substringAfter("=").orEmpty()
    fun setCookie(url: String, cookie: String?) { if (!cookie.isNullOrBlank()) jar.setCookies(url, cookie) }
    fun replaceCookie(url: String, cookie: String?) { jar.remove(url); setCookie(url, cookie) }
    fun removeCookie(url: String) { jar.remove(url) }
}

/** `source` in source JS. */
class SourceBridge(private val src: BookSource, private val data: RuleData?) {
    val bookSourceUrl: String get() = src.bookSourceUrl
    val bookSourceName: String get() = src.bookSourceName
    fun getKey(): String = src.bookSourceUrl
    fun getVariable(): String = data?.get("__source") ?: ""
    fun setVariable(v: String?) { data?.put("__source", v.orEmpty()) }
    fun put(k: String, v: String): String { data?.put(k, v); return v }
    fun get(k: String): String = data?.get(k) ?: ""
    fun getLoginInfo(): String? = null
    fun getLoginInfoMap(): Map<String, String> = emptyMap()
}

/** `book` in source JS. */
class BookBridge(private val b: Book) {
    val name: String get() = b.name
    val author: String get() = b.author
    val bookUrl: String get() = b.bookUrl
    val tocUrl: String get() = b.tocUrl
    val kind: String? get() = b.kind
    val intro: String? get() = b.intro
    val coverUrl: String? get() = b.coverUrl
    val origin: String get() = b.origin
    val originName: String get() = b.originName
    val latestChapterTitle: String? get() = b.latestChapterTitle
    val wordCount: String? get() = b.wordCount
    fun getVariable(): String = b.data.get("__book") ?: ""
    fun getVariable(key: String): String = b.data.get(key) ?: ""
    fun putVariable(key: String, value: String?) { b.data.put(key, value.orEmpty()) }
    fun putVariable(value: String?) { b.data.put("__book", value.orEmpty()) }
    fun put(key: String, value: String): String { b.data.put(key, value); return value }
    fun get(key: String): String = b.data.get(key) ?: ""
}

/** `chapter` in source JS. */
class ChapterBridge(private val c: Chapter) {
    val title: String get() = c.title
    val url: String get() = c.url
    val index: Int get() = c.index
    fun getVariable(key: String): String = c.data.get(key) ?: ""
    fun putVariable(key: String, value: String?) { c.data.put(key, value.orEmpty()) }
}

/** AES / DES with the calls sources use: `createSymmetricCrypto(...)`, `.decryptStr(...)`, `.encryptBase64(...)`. */
class SymmetricCrypto(private val transformation: String, key: ByteArray, private val iv: ByteArray?) {
    private val algorithm = transformation.substringBefore('/')
    private val keySpec = SecretKeySpec(key, algorithm)

    private fun run(mode: Int, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(transformation)
        if (iv != null && iv.isNotEmpty() && !transformation.contains("ECB", true)) cipher.init(mode, keySpec, IvParameterSpec(iv))
        else cipher.init(mode, keySpec)
        return cipher.doFinal(data)
    }

    fun decrypt(data: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, data)
    fun decrypt(data: String): ByteArray = run(Cipher.DECRYPT_MODE, decodeAuto(data))
    fun decryptStr(data: String): String = String(decrypt(data), Charsets.UTF_8)
    fun encrypt(data: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, data)
    fun encrypt(data: String): ByteArray = encrypt(data.toByteArray(Charsets.UTF_8))
    fun encryptBase64(data: String): String = Base64.getEncoder().encodeToString(encrypt(data))
    fun encryptHex(data: String): String = encrypt(data).joinToString("") { "%02x".format(it) }

    private fun decodeAuto(s: String): ByteArray {
        val t = s.trim()
        return if (t.length % 2 == 0 && t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) hex(t)
        else Base64.getMimeDecoder().decode(t)
    }

    companion object {
        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * The `java` object in source JS. Mirrors the subset of Legado's `JsExtensions` that real sources use; anything
 * that needs Android UI (WebView, browser, file access) is deliberately absent or throws [SourceException].
 */
open class JsExtensions(
    private val http: Http,
    private val source: BookSource?,
    private val data: RuleData?,
    private val rule: AnalyzeRule?,
) {
    // ---- network -------------------------------------------------------------------------------------------------

    private fun fetch(url: String, headers: Map<String, String>? = null): StrResponse =
        AnalyzeUrl(url, source = source, http = http, data = data, extraHeaders = headers).getStrResponse()

    fun ajax(url: Any?): String = runCatching { fetch(url.toString()).body }.getOrElse { it.message ?: "" }

    fun ajaxAll(urls: Any?): List<StrResponse> =
        (urls as? Iterable<*>)?.map { runCatching { fetch(it.toString()) }.getOrElse { e -> StrResponse(it.toString(), 0, e.message ?: "", "", emptyMap()) } }
            ?: emptyList()

    fun connect(url: String): StrResponse = fetch(url)

    fun connect(url: String, header: String?): StrResponse {
        val h = header?.let { JsonCodec.parse(it) as? kotlinx.serialization.json.JsonObject }
            ?.mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
        return fetch(url, h)
    }

    fun get(url: String, headers: Map<String, Any?>?): StrResponse = fetch(url, headers?.mapValues { it.value.toString() })

    fun post(url: String, body: String, headers: Map<String, Any?>?): StrResponse {
        val h = headers?.mapValues { it.value.toString() }
        return AnalyzeUrl(url, source = source, http = http, data = data, extraHeaders = h, forceMethod = "POST", forceBody = body).getStrResponse()
    }

    fun head(url: String, headers: Map<String, Any?>?): StrResponse =
        AnalyzeUrl(url, source = source, http = http, data = data, extraHeaders = headers?.mapValues { it.value.toString() }, forceMethod = "HEAD").getStrResponse()

    fun getWebViewUA(): String = AnalyzeUrl.DEFAULT_UA

    fun webView(html: String?, url: String?, js: String?): String? =
        throw SourceException("这个书源需要 WebView（java.webView），当前环境不支持")

    fun startBrowser(url: String?, title: String?) { throw SourceException("这个书源需要打开浏览器登录/验证") }
    fun startBrowserAwait(url: String?, title: String?): StrResponse = throw SourceException("这个书源需要打开浏览器登录/验证")
    fun getVerificationCode(imageUrl: String?): String = throw SourceException("这个书源需要输入验证码")

    // ---- rules ---------------------------------------------------------------------------------------------------

    private fun analyzer(): AnalyzeRule = rule ?: throw SourceException("当前环境没有可用的规则解析器")

    fun setContent(content: Any?): AnalyzeRule = analyzer().setContent(content)
    fun setContent(content: Any?, baseUrl: String?): AnalyzeRule = analyzer().setContent(content, baseUrl)
    fun getString(rule: String?): String = analyzer().getString(rule)
    fun getString(rule: String?, content: Any?): String = analyzer().getString(rule, content)
    fun getString(rule: String?, content: Any?, isUrl: Boolean): String = analyzer().getString(rule, content, isUrl)
    fun getStringList(rule: String?): List<String> = analyzer().getStringList(rule)
    fun getStringList(rule: String?, isUrl: Boolean): List<String> = analyzer().getStringList(rule, isUrl = isUrl)
    fun getElement(rule: String?): Any? = analyzer().getElements(rule.orEmpty()).firstOrNull()
    fun getElements(rule: String?): List<Any> = analyzer().getElements(rule.orEmpty())

    // ---- variables -----------------------------------------------------------------------------------------------

    fun put(key: String, value: String): String { data?.put(key, value); return value }
    fun get(key: String): String = data?.get(key).orEmpty()

    // ---- encoding ------------------------------------------------------------------------------------------------

    fun base64Decode(str: String?): String = base64DecodeToString(str, "UTF-8")
    fun base64Decode(str: String?, charset: String): String = base64DecodeToString(str, charset)
    fun base64DecodeToByteArray(str: String?): ByteArray? = str?.let { runCatching { Base64.getMimeDecoder().decode(it.trim()) }.getOrNull() }
    fun base64Encode(str: String?): String? = str?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    fun base64Encode(str: String?, @Suppress("UNUSED_PARAMETER") flags: Int): String? = base64Encode(str)

    private fun base64DecodeToString(str: String?, charset: String): String =
        base64DecodeToByteArray(str)?.let { String(it, Http.charsetOrNull(charset) ?: Charsets.UTF_8) }.orEmpty()

    fun hexDecodeToString(hex: String?): String? = hex?.let { runCatching { String(SymmetricCrypto.hex(it.trim()), Charsets.UTF_8) }.getOrNull() }
    fun hexEncodeToString(str: String?): String? = str?.toByteArray(Charsets.UTF_8)?.joinToString("") { "%02x".format(it) }
    fun strToBytes(str: String?): ByteArray? = str?.toByteArray(Charsets.UTF_8)
    fun strToBytes(str: String?, charset: String): ByteArray? = str?.toByteArray(Http.charsetOrNull(charset) ?: Charsets.UTF_8)
    fun bytesToStr(bytes: ByteArray?): String? = bytes?.let { String(it, Charsets.UTF_8) }
    fun bytesToStr(bytes: ByteArray?, charset: String): String? = bytes?.let { String(it, Http.charsetOrNull(charset) ?: Charsets.UTF_8) }

    fun encodeURI(str: String): String = runCatching { URLEncoder.encode(str, "UTF-8") }.getOrDefault(str)
    fun encodeURI(str: String, enc: String): String = runCatching { URLEncoder.encode(str, enc) }.getOrDefault(str)
    fun decodeURI(str: String): String = UrlUtil.decode(str)
    fun utf8ToGbk(str: String): String = String(str.toByteArray(Charsets.UTF_8), Charsets.UTF_8).let { String(it.toByteArray(Charset.forName("GBK")), Charset.forName("GBK")) }
    fun htmlFormat(str: String?): String = HtmlFormatter.format(str)
    fun t2s(text: String): String = text
    fun s2t(text: String): String = text

    // ---- hashing and crypto --------------------------------------------------------------------------------------

    private fun digest(algorithm: String, s: String): ByteArray = MessageDigest.getInstance(algorithm).digest(s.toByteArray(Charsets.UTF_8))
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    fun md5Encode(str: String): String = digest("MD5", str).hex()
    fun md5Encode16(str: String): String = md5Encode(str).substring(8, 24)
    fun digestHex(data: String, algorithm: String): String = digest(algorithm.replace("SHA1", "SHA-1").replace("SHA256", "SHA-256"), data).hex()
    fun digestBase64Str(data: String, algorithm: String): String =
        Base64.getEncoder().encodeToString(digest(algorithm.replace("SHA1", "SHA-1").replace("SHA256", "SHA-256"), data))

    fun createSymmetricCrypto(transformation: String, key: String): SymmetricCrypto = SymmetricCrypto(transformation, key.toByteArray(Charsets.UTF_8), null)
    fun createSymmetricCrypto(transformation: String, key: String, iv: String?): SymmetricCrypto =
        SymmetricCrypto(transformation, key.toByteArray(Charsets.UTF_8), iv?.toByteArray(Charsets.UTF_8))
    fun createSymmetricCrypto(transformation: String, key: ByteArray, iv: ByteArray?): SymmetricCrypto = SymmetricCrypto(transformation, key, iv)

    fun aesDecodeToString(str: String, key: String, transformation: String, iv: String?): String? =
        runCatching { createSymmetricCrypto(transformation, key, iv).decryptStr(str) }.getOrNull()
    fun aesBase64DecodeToString(str: String, key: String, transformation: String, iv: String?): String? = aesDecodeToString(str, key, transformation, iv)
    fun aesEncodeToBase64String(data: String, key: String, transformation: String, iv: String?): String? =
        runCatching { createSymmetricCrypto(transformation, key, iv).encryptBase64(data) }.getOrNull()
    fun aesEncodeToString(data: String, key: String, transformation: String, iv: String?): String? =
        runCatching { createSymmetricCrypto(transformation, key, iv).encryptHex(data) }.getOrNull()

    // ---- misc ----------------------------------------------------------------------------------------------------

    fun log(msg: Any?): Any? { EngineLog.log(msg.toString()); return msg }
    fun logType(any: Any?) { EngineLog.log(any?.javaClass?.name ?: "null") }
    fun toast(msg: Any?) { EngineLog.log("toast: $msg") }
    fun longToast(msg: Any?) { EngineLog.log("toast: $msg") }
    fun randomUUID(): String = UUID.randomUUID().toString()
    fun timeFormat(time: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(time))
    fun timeFormatUTC(time: Long, format: String, sh: Int): String? = runCatching {
        SimpleDateFormat(format, Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("GMT+${sh}") }.format(Date(time))
    }.getOrNull()

    fun importScript(path: String): String =
        if (UrlUtil.isAbsolute(path)) fetch(path).body else throw SourceException("不支持读取本地脚本：$path")

    fun readFile(path: String): ByteArray? = throw SourceException("不支持读取文件")
    fun readTxtFile(path: String): String = throw SourceException("不支持读取文件")
}
