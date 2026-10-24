package app.openread.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements

/** Legado's default rule mode: `class.name@tag.a.0@text`, or `@css:` + a CSS selector with a trailing `@attr`. */
internal class AnalyzeByJSoup(doc: Any) {
    private val element: Element = when (doc) {
        is Element -> doc
        else -> Jsoup.parse(doc.toString())
    }

    private class Rule(val rule: String, val css: Boolean)

    private fun parse(ruleStr: String): Rule =
        if (ruleStr.startsWith("@CSS:", true)) Rule(ruleStr.substring(5).trim(), true) else Rule(ruleStr, false)

    fun getElements(ruleStr: String): List<Element> {
        if (ruleStr.isEmpty()) return emptyList()
        val r = parse(ruleStr)
        val split = RuleSplit.split(r.rule, "&&", "||", "%%")
        val results = ArrayList<List<Element>>()
        for (part in split.parts) {
            val els: List<Element> = if (r.css) element.select(part) else elementsOf(element, part)
            if (els.isNotEmpty()) {
                results.add(els)
                if (split.delimiter == "||") break
            }
        }
        return if (split.delimiter == "%%") RuleSplit.interleave(results) else results.flatten()
    }

    fun getString(ruleStr: String): String? {
        val list = getStringList(ruleStr)
        return if (list.isEmpty()) null else list.joinToString("\n")
    }

    fun getStringList(ruleStr: String): List<String> {
        if (ruleStr.isEmpty()) return emptyList()
        val r = parse(ruleStr)
        if (r.rule.isEmpty()) return listOf(element.data())
        val split = RuleSplit.split(r.rule, "&&", "||", "%%")
        val results = ArrayList<List<String>>()
        for (part in split.parts) {
            val temp: List<String>? = if (r.css) {
                val at = part.lastIndexOf('@')
                if (at < 0) lastOf(element.select(part), "text") else lastOf(element.select(part.substring(0, at)), part.substring(at + 1))
            } else stringsOf(part)
            if (!temp.isNullOrEmpty()) {
                results.add(temp)
                if (split.delimiter == "||") break
            }
        }
        return if (split.delimiter == "%%") RuleSplit.interleave(results) else results.flatten()
    }

    private fun elementsOf(root: Element, rule: String): List<Element> {
        var cur: List<Element> = listOf(root)
        for (seg in RuleSplit.split(rule, "@").parts.filter { it.isNotBlank() }) {
            val next = ArrayList<Element>()
            for (e in cur) next.addAll(single(e, seg))
            cur = next
        }
        return cur
    }

    private fun stringsOf(rule: String): List<String>? {
        val segs = RuleSplit.split(rule, "@").parts.filter { it.isNotBlank() }.ifEmpty { return null }
        var cur: List<Element> = listOf(element)
        for (i in 0 until segs.size - 1) {
            val next = ArrayList<Element>()
            for (e in cur) next.addAll(single(e, segs[i]))
            cur = next
        }
        return if (cur.isEmpty()) null else lastOf(cur, segs.last())
    }

    private fun lastOf(els: List<Element>, last: String): List<String> {
        val out = ArrayList<String>()
        when (last.trim()) {
            "text" -> els.forEach { e -> e.text().takeIf { it.isNotEmpty() }?.let(out::add) }
            "textNodes" -> els.forEach { e ->
                val t = e.textNodes().map(TextNode::text).map { it.trim() }.filter { it.isNotEmpty() }
                if (t.isNotEmpty()) out.add(t.joinToString("\n"))
            }
            "ownText" -> els.forEach { e -> e.ownText().takeIf { it.isNotEmpty() }?.let(out::add) }
            "html" -> {
                val copy = Elements(els.map { it.clone() })
                copy.select("script").remove()
                copy.select("style").remove()
                out.add(copy.outerHtml())
            }
            "all" -> out.add(Elements(els).outerHtml())
            else -> els.forEach { e ->
                val v = e.attr(last.trim())
                if (v.isNotBlank() && v !in out) out.add(v)
            }
        }
        return out
    }

    // ---- one `type.name.index` step ------------------------------------------------------------------------------

    private class Index(val ints: List<Int>, val ranges: List<Triple<Int?, Int?, Int?>>, val order: List<Any>, val exclude: Boolean)

    private val bracket = Regex("""^(.*?)(!?)\s*\[([^\]]*)]$""")
    private val dotted = Regex("""^(.*?)([.!])(-?\d+(?::-?\d+)*)$""")

    private fun single(root: Element, rule0: String): List<Element> {
        val rule = rule0.trim()
        var before = rule
        var index: Index? = null
        bracket.find(rule)?.let { m ->
            val items = m.groupValues[3].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val order = ArrayList<Any>()
            val ints = ArrayList<Int>()
            val ranges = ArrayList<Triple<Int?, Int?, Int?>>()
            var valid = items.isNotEmpty()
            for (it in items) {
                if (it.contains(":")) {
                    val p = it.split(":")
                    if (p.size > 3 || p.any { s -> s.isNotEmpty() && s.toIntOrNull() == null }) { valid = false; break }
                    val t = Triple(p[0].toIntOrNull(), p.getOrNull(1)?.toIntOrNull(), p.getOrNull(2)?.toIntOrNull())
                    ranges.add(t); order.add(t)
                } else {
                    val n = it.toIntOrNull() ?: run { valid = false; null } ?: break
                    ints.add(n); order.add(n)
                }
            }
            if (valid) {
                before = m.groupValues[1]
                index = Index(ints, ranges, order, m.groupValues[2] == "!")
            }
        }
        if (index == null) dotted.find(rule)?.let { m ->
            val nums = m.groupValues[3].split(":").map { it.toInt() }
            before = m.groupValues[1]
            index = Index(nums, emptyList(), nums, m.groupValues[2] == "!")
        }
        var els: List<Element> = if (before.isEmpty()) root.children() else {
            val p = before.split(".")
            when (p[0]) {
                "children" -> root.children()
                "class" -> root.getElementsByClass(p.getOrElse(1) { "" })
                "tag" -> root.getElementsByTag(p.getOrElse(1) { "" })
                "id" -> root.getElementById(p.getOrElse(1) { "" })?.let { listOf(it) } ?: emptyList()
                "text" -> root.getElementsContainingOwnText(p.getOrElse(1) { "" })
                else -> runCatching { root.select(before) }.getOrDefault(Elements())
            }
        }
        val idx = index ?: return els
        val len = els.size
        if (len == 0) return els
        val picked = LinkedHashSet<Int>()
        for (o in idx.order) {
            if (o is Int) {
                if (o in 0 until len) picked.add(o) else if (o < 0 && len >= -o) picked.add(o + len)
            } else {
                @Suppress("UNCHECKED_CAST") val (sx, ex, stx) = o as Triple<Int?, Int?, Int?>
                var start = sx ?: 0
                if (start < 0) start += len
                var end = ex ?: (len - 1)
                if (end < 0) end += len
                if ((start < 0 && end < 0) || (start >= len && end >= len)) continue
                start = start.coerceIn(0, len - 1)
                end = end.coerceIn(0, len - 1)
                val step = if (stx == null) 1 else if (stx > 0) stx else if (-stx < len) stx + len else 1
                if (end >= start) { var i = start; while (i <= end) { picked.add(i); i += step } }
                else { var i = start; while (i >= end) { picked.add(i); i -= step } }
            }
        }
        els = if (idx.exclude) els.filterIndexed { i, _ -> i !in picked } else picked.map { els[it] }
        return els
    }
}
