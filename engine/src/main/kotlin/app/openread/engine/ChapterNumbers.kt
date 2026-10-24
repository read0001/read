package app.openread.engine

/** Reads the number out of a title such as "第一千五百九十三章 古帝洞府所在" or "第832章 莫崖". */
object ChapterNumbers {
    private val title = Regex("第\\s*([零〇一二两三四五六七八九十百千万\\d]+)\\s*[章节回话]")
    private val digits = "零一二三四五六七八九"

    fun of(chapterTitle: String): Int? = title.find(chapterTitle)?.groupValues?.get(1)?.let(::parse)

    internal fun parse(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        if (s.any { it.isDigit() }) return null
        var total = 0
        var section = 0
        var digit = 0
        for (c in s) {
            val d = digits.indexOf(c).takeIf { it >= 0 } ?: if (c == '〇') 0 else if (c == '两') 2 else -1
            when {
                d >= 0 -> digit = d
                c == '十' -> { section += (if (digit == 0) 1 else digit) * 10; digit = 0 }
                c == '百' -> { section += digit * 100; digit = 0 }
                c == '千' -> { section += digit * 1000; digit = 0 }
                c == '万' -> { total += (section + digit) * 10000; section = 0; digit = 0 }
                else -> return null
            }
        }
        return total + section + digit
    }
}

/** How far a source has got with a serial: serials differ a lot, and a source stuck at chapter 70 when others are at 94 is the wrong one to open. */
object Freshness {
    /** The chapter number of the source's newest chapter, or -1 when it does not say or the title has no number. */
    fun of(hit: SearchBook): Int = hit.latestChapterTitle?.let(ChapterNumbers::of) ?: -1

    /** Most up to date first; sources that do not say keep their order after the ones that do. */
    fun newestFirst(hits: List<SearchBook>): List<SearchBook> = hits.sortedByDescending(::of)

    /** A source in [others] that is at least [by] chapters ahead of [mine], the furthest one; null when mine is fine or unknown. */
    fun fresherThan(mine: SearchBook, others: List<SearchBook>, by: Int = 3): SearchBook? {
        val m = of(mine).takeIf { it >= 0 } ?: return null
        return others.maxByOrNull(::of)?.takeIf { of(it) >= m + by }
    }
}
