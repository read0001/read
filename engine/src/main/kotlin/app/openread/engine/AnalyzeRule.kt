package app.openread.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Evaluates Legado rules against a page. A rule is a chain of segments: plain rules (jsoup, `@css:`, XPath, JSONPath,
 * `:regex`), `@js:` / `<js>` blocks, `{{js}}` / `@get:{key}` / `@put:{k:rule}` templates, and a `##regex##replacement`
 * tail. Each segment runs on the previous one's output.
 */
class AnalyzeRule(
    private val data: RuleData?,
    private val source: BookSource?,
    private val http: Http,
    private val book: Book? = null,
    private val chapter: Chapter? = null,
) {
    private var content: Any? = null
    private var baseUrl: String? = null
    private var redirectUrl: String? = null
    private var isJson = false
    private var isRegex = false

    private enum class Mode { XPath, Json, Default, Js, Regex }

    private class SourceRule(
        val mode: Mode,
        var rule: String,
        val put: Map<String, String>,
        val params: List<Pair<Int, String>>,
        val replaceRegex: String,
        val replacement: String,
        val replaceFirst: Boolean,
    )

    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRule {
        requireNotNull(content) { "content is null" }
        this.content = content
        isJson = if (isRegex) false else when (content) {
            is Map<*, *>, is List<*> -> true
            is Element -> false
            else -> content.toString().trim().let { it.startsWith("{") || it.startsWith("[") }
        }
        if (baseUrl != null) this.baseUrl = baseUrl
        return this
    }

    /** An analyzer for one list item that inherits this one's base URL and `:regex` mode. */
    fun child(item: Any, itemData: RuleData?): AnalyzeRule {
        val c = AnalyzeRule(itemData, source, http, book, chapter)
        c.isRegex = isRegex
        c.baseUrl = baseUrl
        c.redirectUrl = redirectUrl
        return c.setContent(item)
    }

    fun setBaseUrl(url: String?): AnalyzeRule { baseUrl = url; return this }
    fun setRedirectUrl(url: String?): AnalyzeRule { redirectUrl = url; return this }

    // ---- public evaluation ---------------------------------------------------------------------------------------

    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String> {
        if (rule.isNullOrEmpty()) return emptyList()
        val rules = splitSourceRule(rule)
        return stringList(rules, mContent, isUrl)
    }

    private fun stringList(rules: List<SourceRule>, mContent: Any?, isUrl: Boolean): List<String> {
        var result: Any? = mContent ?: content
        if (result == null || rules.isEmpty()) return emptyList()
        for (sr in rules) {
            putRule(sr.put)
            makeUp(sr, result)
            val cur = result ?: break
            if (sr.rule.isNotBlank() || sr.replaceRegex.isEmpty()) {
                result = when (sr.mode) {
                    Mode.Js -> evalJs(sr.rule, cur)
                    Mode.Json -> AnalyzeByJsonPath(cur).getStringList(sr.rule)
                    Mode.XPath -> AnalyzeByXPath(cur).getStringList(sr.rule)
                    Mode.Default -> if (isUrl && sr.rule.isBlank()) baseUrl else AnalyzeByJSoup(cur).getStringList(sr.rule)
                    Mode.Regex -> sr.rule
                }
            }
            if (result != null && sr.replaceRegex.isNotEmpty()) {
                result = if (result is List<*>) result.map { replace(Values.text(it), sr) } else replace(Values.text(result), sr)
            }
        }
        val list: List<String> = when (val r = result) {
            null -> emptyList()
            is String -> r.split("\n")
            is List<*> -> r.map { Values.text(it) }
            else -> listOf(Values.text(r))
        }
        if (!isUrl) return list
        val base = redirectUrl ?: baseUrl
        return list.filter { it.isNotBlank() }.map { UrlUtil.absolute(base, it) }.distinct()
    }

    fun getString(rule: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        if (rule.isNullOrEmpty()) return ""
        return string(splitSourceRule(rule), mContent, isUrl)
    }

    private fun string(rules: List<SourceRule>, mContent: Any?, isUrl: Boolean): String {
        var result: Any? = mContent ?: content
        if (result != null && rules.isNotEmpty()) {
            for (sr in rules) {
                putRule(sr.put)
                makeUp(sr, result)
                val cur = result ?: break
                if (sr.rule.isNotBlank() || sr.replaceRegex.isEmpty()) {
                    result = when (sr.mode) {
                        Mode.Js -> evalJs(sr.rule, cur)
                        Mode.Json -> AnalyzeByJsonPath(cur).getString(sr.rule)
                        Mode.XPath -> AnalyzeByXPath(cur).getString(sr.rule)
                        Mode.Default -> if (isUrl && sr.rule.isBlank()) baseUrl else AnalyzeByJSoup(cur).getString(sr.rule)
                        Mode.Regex -> sr.rule
                    }
                }
                if (result != null && sr.replaceRegex.isNotEmpty()) result = replace(Values.text(result), sr)
            }
        }
        val str = Parser.unescapeEntities(Values.text(result), false)
        if (!isUrl) return str
        return if (str.isBlank()) baseUrl.orEmpty() else UrlUtil.absolute(redirectUrl ?: baseUrl, str)
    }

    /** All matches of a list rule: jsoup/XPath [Element]s, JSON objects, or regex group lists. */
    fun getElements(ruleStr: String): List<Any> {
        val rules = splitSourceRule(ruleStr, allInOne = true)
        var result: Any? = content
        if (result == null || rules.isEmpty()) return emptyList()
        for (sr in rules) {
            val cur = result ?: break
            putRule(sr.put)
            makeUp(sr, cur)
            result = when (sr.mode) {
                Mode.Regex -> AnalyzeByRegex.getElements(cur.toString(), sr.rule.split("&&"))
                Mode.Js -> evalJs(sr.rule, cur)
                Mode.Json -> AnalyzeByJsonPath(cur).getList(sr.rule)
                Mode.XPath -> AnalyzeByXPath(cur).getElements(sr.rule)
                Mode.Default -> AnalyzeByJSoup(cur).getElements(sr.rule)
            }
            if (result != null && sr.replaceRegex.isNotEmpty()) result = replace(Values.text(result), sr)
        }
        return when (val r = result) {
            null -> emptyList()
            is List<*> -> r.filterNotNull()
            else -> listOf(r)
        }
    }

    fun getElement(rule: String): Any? = getElements(rule).firstOrNull()

    // ---- rule parsing --------------------------------------------------------------------------------------------

    private fun splitSourceRule(ruleStr: String, allInOne: Boolean = false): List<SourceRule> {
        val list = ArrayList<SourceRule>()
        if (ruleStr.isEmpty()) return list
        var mode = Mode.Default
        var start = 0
        if (allInOne && ruleStr.startsWith(":")) { mode = Mode.Regex; isRegex = true; start = 1 }
        else if (isRegex) mode = Mode.Regex
        for (m in AnalyzeUrl.JS_PATTERN.findAll(ruleStr)) {
            if (m.range.first > start) {
                val tmp = ruleStr.substring(start, m.range.first).trim()
                if (tmp.isNotEmpty()) list.add(buildRule(tmp, mode))
            }
            list.add(buildRule(m.groups[2]?.value ?: m.groups[1]?.value.orEmpty(), Mode.Js))
            start = m.range.last + 1
        }
        if (ruleStr.length > start) {
            val tmp = ruleStr.substring(start).trim()
            if (tmp.isNotEmpty()) list.add(buildRule(tmp, mode))
        }
        return list
    }

    private fun buildRule(ruleStr: String, main: Mode): SourceRule {
        var rule = ruleStr
        val mode = when {
            main == Mode.Js || main == Mode.Regex -> main
            ruleStr.startsWith("@CSS:", true) -> Mode.Default
            ruleStr.startsWith("@@") -> { rule = ruleStr.substring(2); Mode.Default }
            ruleStr.startsWith("@XPath:", true) -> { rule = ruleStr.substring(7); Mode.XPath }
            ruleStr.startsWith("@Json:", true) -> { rule = ruleStr.substring(6); Mode.Json }
            isJson || ruleStr.startsWith("$.") || ruleStr.startsWith("$[") -> Mode.Json
            ruleStr.startsWith("/") -> Mode.XPath
            else -> Mode.Default
        }
        if (mode == Mode.Js) return SourceRule(mode, rule, emptyMap(), emptyList(), "", "", false)

        val put = LinkedHashMap<String, String>()
        rule = PUT_PATTERN.replace(rule) { m ->
            (JsonCodec.parse(m.value.substring(5)) as? JsonObject)?.forEach { (k, v) -> put[k] = (v as? JsonPrimitive)?.content ?: v.toString() }
            ""
        }

        // The `##regex##replacement###` tail starts at the first `##` outside any `{{…}}` template.
        val cut = replaceStart(rule)
        var replaceRegex = ""
        var replacement = ""
        var replaceFirst = false
        if (cut >= 0) {
            val parts = rule.substring(cut).split("##")
            rule = rule.substring(0, cut)
            replaceRegex = parts.getOrElse(1) { "" }
            replacement = parts.getOrElse(2) { "" }
            replaceFirst = parts.size > 3
        }

        val params = ArrayList<Pair<Int, String>>()
        var pos = 0
        for (m in EXP_PATTERN.findAll(rule)) {
            if (m.range.first > pos) params.add(LITERAL to rule.substring(pos, m.range.first))
            val t = m.value
            if (t.startsWith("@get:", true)) params.add(GET to t.substring(6, t.length - 1))
            else params.add(JS to t.substring(2, t.length - 2))
            pos = m.range.last + 1
        }
        if (params.isNotEmpty() && pos < rule.length) params.add(LITERAL to rule.substring(pos))
        // Like Legado: a rule with `{{…}}` / `@get:` is a string template, so after substitution it is the result itself.
        val finalMode = if (params.isNotEmpty() && mode != Mode.Js && mode != Mode.Regex) Mode.Regex else mode
        return SourceRule(finalMode, rule, put, if (params.isEmpty()) emptyList() else params, replaceRegex, replacement, replaceFirst)
    }

    private fun replaceStart(rule: String): Int {
        var depth = 0
        var i = 0
        while (i < rule.length) {
            if (rule.startsWith("{{", i)) { depth++; i += 2; continue }
            if (rule.startsWith("}}", i) && depth > 0) { depth--; i += 2; continue }
            if (depth == 0 && rule.startsWith("##", i)) return i
            i++
        }
        return -1
    }

    /** Expands `{{…}}`, `@get:{…}` and (in regex mode) `$1` references into the rule text. */
    private fun makeUp(sr: SourceRule, result: Any?) {
        if (sr.params.isEmpty()) {
            if (isRegex && result is List<*> && sr.rule.contains("$")) sr.rule = expandGroups(sr.rule, result)
            return
        }
        val sb = StringBuilder()
        for ((type, text) in sr.params) {
            when (type) {
                LITERAL -> sb.append(if (isRegex && result is List<*>) expandGroups(text, result) else text)
                GET -> sb.append(data?.get(text).orEmpty())
                JS -> sb.append(
                    if (isRuleLike(text)) getString(text, result)
                    else Values.text(evalJs(text, result)),
                )
            }
        }
        sr.rule = sb.toString()
    }

    private fun expandGroups(text: String, groups: List<*>): String =
        GROUP_PATTERN.replace(text) { m -> groups.getOrNull(m.groupValues[1].toInt())?.toString().orEmpty() }

    private fun isRuleLike(s: String) = s.startsWith("@") || s.startsWith("$.") || s.startsWith("$[") || s.startsWith("//")

    private fun replace(text: String, sr: SourceRule): String {
        if (sr.replaceRegex.isEmpty()) return text
        return try {
            val re = Regex(sr.replaceRegex)
            if (sr.replaceFirst) re.find(text)?.let { m -> m.value.replaceFirst(re, sr.replacement) } ?: ""
            else text.replace(re, sr.replacement)
        } catch (_: Exception) {
            text.replace(sr.replaceRegex, sr.replacement)
        }
    }

    // ---- variables and JS ----------------------------------------------------------------------------------------

    private fun putRule(map: Map<String, String>) {
        for ((k, v) in map) data?.put(k, getString(v))
    }

    fun put(key: String, value: String): String { data?.put(key, value); return value }
    fun get(key: String): String = data?.get(key).orEmpty()

    fun evalJs(js: String, result: Any?): Any? {
        val b = LinkedHashMap<String, Any?>()
        b["java"] = JsExtensions(http, source, data, this)
        b["cookie"] = CookieBridge(http.cookieJar)
        b["cache"] = http.cache
        if (source != null) b["source"] = SourceBridge(source, http.varsOf(source.key))
        book?.let { b["book"] = BookBridge(it) }
        chapter?.let { b["chapter"] = ChapterBridge(it) }
        b["result"] = result
        b["baseUrl"] = baseUrl
        b["src"] = content?.toString()
        b["title"] = chapter?.title.orEmpty()
        return JsRuntime.eval(js, b, source?.jsLib)
    }

    private companion object {
        const val LITERAL = 0
        const val JS = 1
        const val GET = -2
        val PUT_PATTERN = Regex("@put:\\{[^}]+?\\}", RegexOption.IGNORE_CASE)
        val EXP_PATTERN = Regex("@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", RegexOption.IGNORE_CASE)
        val GROUP_PATTERN = Regex("\\$(\\d{1,2})")
    }
}
