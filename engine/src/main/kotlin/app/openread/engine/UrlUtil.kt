package app.openread.engine

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

object UrlUtil {
    private val schemeRe = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    fun isAbsolute(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    /** Resolves [relative] against [base] the way a browser does; `data:`/`javascript:` and similar are kept as they are. */
    fun absolute(base: String?, relative: String?, charset: Charset = Charsets.UTF_8): String {
        val rel = relative?.trim().orEmpty()
        if (rel.isEmpty()) return base.orEmpty()
        if (isAbsolute(rel)) return rel
        if (schemeRe.containsMatchIn(rel) && !rel.startsWith("//")) return rel
        if (base.isNullOrBlank()) return rel
        val b = base.substringBefore("#").let { if (isAbsolute(it)) it else return rel }
        // java.net.URI (RFC 2396) drops the last path segment for a query-only reference; browsers keep it.
        if (rel.startsWith("?")) return b.substringBefore("?") + escapeUnsafe(rel, charset)
        return runCatching {
            // URI.resolve chokes on spaces and non-ASCII; escape just those first.
            URI(escapeUnsafe(b)).resolve(escapeUnsafe(rel, charset)).toString()
        }.getOrElse { rel }
    }

    /** Percent-encodes only characters a URL cannot carry (non-ASCII, spaces, quotes…), leaving existing `%XX` and delimiters alone. */
    fun escapeUnsafe(s: String, charset: Charset = Charsets.UTF_8): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val cp = s.codePointAt(i)
            when {
                cp > 127 -> {
                    val str = String(Character.toChars(cp))
                    for (b in str.toByteArray(charset)) sb.append('%').append("%02X".format(b.toInt() and 0xFF))
                    i += Character.charCount(cp)
                    continue
                }
                c == ' ' -> sb.append("%20")
                c == '"' || c == '<' || c == '>' || c == '\\' || c == '^' || c == '`' || c == '{' || c == '|' || c == '}' ->
                    sb.append('%').append("%02X".format(c.code))
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * Encodes each `k=v` of a query string: values are percent-encoded in [charset] (so `+`, `#`, `/` and non-ASCII survive)
     * unless they already contain a `%XX` escape.
     */
    fun encodeQuery(query: String, charset: Charset): String = query.split("&").joinToString("&") { pair ->
        val i = pair.indexOf('=')
        if (i < 0) escapeUnsafe(pair, charset)
        else {
            val v = pair.substring(i + 1)
            escapeUnsafe(pair.substring(0, i), charset) + "=" + if (isEncoded(v)) escapeUnsafe(v, charset) else encode(v, charset)
        }
    }

    fun encode(s: String, charset: Charset = Charsets.UTF_8): String = URLEncoder.encode(s, charset)

    fun decode(s: String, charset: Charset = Charsets.UTF_8): String = runCatching { URLDecoder.decode(s, charset) }.getOrDefault(s)

    fun host(url: String): String? = runCatching { URI(escapeUnsafe(url)).host }.getOrNull()

    /** True when [s] has a `%XX` escape, i.e. it was already encoded. */
    fun isEncoded(s: String) = Regex("%[0-9A-Fa-f]{2}").containsMatchIn(s)
}
