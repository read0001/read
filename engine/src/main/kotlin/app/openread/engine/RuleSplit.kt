package app.openread.engine

/** Splits a rule on top-level delimiters, ignoring any inside `()`, `[]`, `{}` or quotes. */
internal object RuleSplit {
    class Result(val parts: List<String>, val delimiter: String?)

    /** Splits on whichever of [delims] shows up first, as Legado does: a rule never mixes `&&`, `||` and `%%`. */
    fun split(rule: String, vararg delims: String): Result {
        var found: String? = null
        val parts = ArrayList<String>()
        var depth = 0
        var quote = 0.toChar()
        var start = 0
        var i = 0
        while (i < rule.length) {
            val c = rule[i]
            if (quote != 0.toChar()) {
                if (c == '\\') i++ else if (c == quote) quote = 0.toChar()
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '(' || c == '[' || c == '{') {
                depth++
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth > 0) depth--
            } else if (depth == 0) {
                val d = if (found != null) delims.firstOrNull { it == found && rule.startsWith(it, i) }
                else delims.firstOrNull { rule.startsWith(it, i) }
                if (d != null) {
                    found = d
                    parts.add(rule.substring(start, i))
                    i += d.length
                    start = i
                    continue
                }
            }
            i++
        }
        parts.add(rule.substring(start))
        return Result(parts, found)
    }

    /** Interleaves lists for the `%%` operator: first of each, second of each, … */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) if (i < l.size) out.add(l[i])
        return out
    }
}
