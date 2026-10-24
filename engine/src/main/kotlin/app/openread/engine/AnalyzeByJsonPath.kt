package app.openread.engine

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.ReadContext

/** `$.data.list[*].name` style rules over JSON, with `{$.field}` inline templates and `&&` / `||` / `%%`. */
internal class AnalyzeByJsonPath(json: Any) {
    private val ctx: ReadContext = when (json) {
        is ReadContext -> json
        is String -> JsonPath.using(CONF).parse(json)
        else -> JsonPath.using(CONF).parse(json)
    }

    private fun read(path: String): Any? = try { ctx.read<Any?>(path) } catch (_: Exception) { null }

    fun getString(rule: String): String? {
        if (rule.isEmpty()) return null
        val split = RuleSplit.split(rule, "&&", "||")
        if (split.parts.size == 1) {
            if (rule.contains("{\$.")) return Templates.replaceInner(rule, "{\$.", "}") { getString("\$.$it").orEmpty() }
            return read(rule)?.let(Values::text)
        }
        val out = ArrayList<String>()
        for (p in split.parts) {
            val t = getString(p)
            if (!t.isNullOrEmpty()) {
                out.add(t)
                if (split.delimiter == "||") break
            }
        }
        return out.joinToString("\n")
    }

    fun getStringList(rule: String): List<String> {
        if (rule.isEmpty()) return emptyList()
        val split = RuleSplit.split(rule, "&&", "||", "%%")
        if (split.parts.size == 1) {
            if (rule.contains("{\$.")) return listOf(Templates.replaceInner(rule, "{\$.", "}") { getString("\$.$it").orEmpty() })
            return when (val o = read(rule)) {
                null -> emptyList()
                is List<*> -> o.map { Values.text(it) }
                else -> listOf(Values.text(o))
            }
        }
        val results = ArrayList<List<String>>()
        for (p in split.parts) {
            val t = getStringList(p)
            if (t.isNotEmpty()) {
                results.add(t)
                if (split.delimiter == "||") break
            }
        }
        return if (split.delimiter == "%%") RuleSplit.interleave(results) else results.flatten()
    }

    fun getList(rule: String): List<Any> {
        if (rule.isEmpty()) return emptyList()
        val split = RuleSplit.split(rule, "&&", "||", "%%")
        if (split.parts.size == 1) {
            return when (val o = read(rule)) {
                null -> emptyList()
                is List<*> -> o.filterNotNull()
                else -> listOf(o)
            }
        }
        val results = ArrayList<List<Any>>()
        for (p in split.parts) {
            val t = getList(p)
            if (t.isNotEmpty()) {
                results.add(t)
                if (split.delimiter == "||") break
            }
        }
        return if (split.delimiter == "%%") RuleSplit.interleave(results) else results.flatten()
    }

    private companion object {
        val CONF: Configuration = Configuration.builder().options(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL).build()
    }
}
