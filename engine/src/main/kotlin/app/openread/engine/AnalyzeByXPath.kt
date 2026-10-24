package app.openread.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.seimicrawler.xpath.JXDocument
import org.seimicrawler.xpath.JXNode

/** `//div[@id='list']/a/@href` rules, via JsoupXpath. */
internal class AnalyzeByXPath(doc: Any) {
    private val jx: JXDocument = when (doc) {
        is Element -> JXDocument.create(Elements(doc))
        else -> JXDocument.create(Jsoup.parse(doc.toString()))
    }

    fun getElements(rule: String): List<Any> {
        if (rule.isEmpty()) return emptyList()
        val split = RuleSplit.split(rule, "&&", "||", "%%")
        val results = ArrayList<List<Any>>()
        for (p in split.parts) {
            val nodes: List<Any> = nodesOf(p).map { if (it.isElement) it.asElement() else it.asString() }
            if (nodes.isNotEmpty()) {
                results.add(nodes)
                if (split.delimiter == "||") break
            }
        }
        return if (split.delimiter == "%%") RuleSplit.interleave(results) else results.flatten()
    }

    fun getStringList(rule: String): List<String> {
        if (rule.isEmpty()) return emptyList()
        val split = RuleSplit.split(rule, "&&", "||", "%%")
        val results = ArrayList<List<String>>()
        for (p in split.parts) {
            val strings = nodesOf(p).map { it.asString() }
            if (strings.isNotEmpty()) {
                results.add(strings)
                if (split.delimiter == "||") break
            }
        }
        return if (split.delimiter == "%%") RuleSplit.interleave(results) else results.flatten()
    }

    fun getString(rule: String): String? = getStringList(rule).takeIf { it.isNotEmpty() }?.joinToString("\n")

    private fun nodesOf(xpath: String): List<JXNode> = runCatching { jx.selN(xpath.trim()) }.getOrDefault(emptyList())
}
