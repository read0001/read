package app.openread.engine

/** The `:regex1&&regex2` all-in-one mode: each hit becomes a list of groups, addressed as `$1`, `$2` in the field rules. */
internal object AnalyzeByRegex {
    fun getElements(res: String, regs: List<String>, index: Int = 0): List<List<String>> {
        if (index >= regs.size) return emptyList()
        val m = Regex(regs[index], RegexOption.MULTILINE).toPattern().matcher(res)
        if (!m.find()) return emptyList()
        if (index + 1 == regs.size) {
            val out = ArrayList<List<String>>()
            do {
                out.add((0..m.groupCount()).map { m.group(it).orEmpty() })
            } while (m.find())
            return out
        }
        val sb = StringBuilder()
        do { sb.append(m.group()) } while (m.find())
        return getElements(sb.toString(), regs, index + 1)
    }
}
