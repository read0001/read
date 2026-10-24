package app.openread.engine

import org.jsoup.Jsoup

/** Repairs for table-of-contents quirks that show up on many sites, so the newest chapters end up last and in order. */
object TocFix {
    /**
     * - Adjacent entries with the same chapter number and the same title are one chapter posted twice; the first is kept.
     * - A list that is in descending order as a whole (a source that forgot the `-` prefix) is reversed.
     * - A "latest chapters" block pasted in front of the real list (newest first, then a notice or chapter 1, 2, 3…) is moved
     *   behind it in ascending order. That is the usual `最新章节` section of mobile novel sites.
     * Lists whose titles carry no chapter numbers are left alone.
     */
    fun apply(input: List<Chapter>): List<Chapter> {
        val list = dropRepeats(input)
        if (list.size < 8) return list
        val nums = list.map { ChapterNumbers.of(it.title) }
        if (nums.count { it != null } < list.size * 0.7) return list

        var up = 0
        var down = 0
        for (i in 1 until nums.size) {
            val a = nums[i - 1]
            val b = nums[i]
            if (a == null || b == null) continue
            if (b > a) up++ else if (b < a) down++
        }
        if (down > 0 && up == 0 || down > (up + down) * 0.9) return list.asReversed()

        // The numbered, strictly descending run at the very start: the "latest chapters" block, if there is one.
        val first = nums[0] ?: return list
        if (first < 20) return list // a list that starts at chapter 1, 2, 3… has nothing pasted in front
        var end = 1
        while (end < nums.size) {
            val a = nums[end - 1]
            val b = nums[end]
            if (a == null || b == null || b >= a) break
            if (b <= 10 && a > 50) break // dropping back to the first chapters: that is where the real list starts
            end++
        }
        // What follows must be the real list: it starts low, possibly after an unnumbered notice such as "欢迎收藏".
        val firstOfMain = (end until minOf(nums.size, end + 6)).firstNotNullOfOrNull { nums[it] } ?: return list
        if (firstOfMain > 10 || firstOfMain >= nums[end - 1]!!) return list
        return list.drop(end) + list.take(end).asReversed()
    }

    private fun dropRepeats(list: List<Chapter>): List<Chapter> {
        val out = ArrayList<Chapter>(list.size)
        for (c in list) {
            val prev = out.lastOrNull()
            val n = ChapterNumbers.of(c.title)
            if (prev != null && n != null && n == ChapterNumbers.of(prev.title) && prev.title.trim() == c.title.trim()) continue
            out.add(c)
        }
        return out
    }
}

/** Finds an ordinary "next page" link on a table-of-contents page. */
object TocPaging {
    private val nextText = Regex("^\\s*(下一页|下页|下一頁|下頁|下一页\\s*[>»›]?|Next|next|[>»›]+)\\s*$")

    fun nextLink(html: String, pageUrl: String): String? {
        val doc = Jsoup.parse(html, pageUrl)
        val href = doc.selectFirst("link[rel=next]")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: doc.select("a[href]").firstOrNull { a -> nextText.matches(a.text()) && a.attr("href").let { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("javascript", true) } }?.attr("href")
            ?: return null
        val abs = UrlUtil.absolute(pageUrl, href)
        // Stay on the same site and on a different page.
        if (UrlUtil.host(abs) != UrlUtil.host(pageUrl) || abs.substringBefore("#") == pageUrl.substringBefore("#")) return null
        return abs
    }
}
