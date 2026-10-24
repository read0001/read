package app.openread.engine

import org.jsoup.Jsoup

/** Repairs for table-of-contents quirks that show up on many sites, so the newest chapters end up last and in order. */
object TocFix {
    /**
     * - A list that is in descending order as a whole (a source that forgot the `-` prefix) is reversed.
     * - A "latest chapters" block pasted in front of the real list (newest first, then chapter 1, 2, 3…) is moved behind it
     *   in ascending order. That is the usual `最新章节` section of mobile novel sites.
     * Lists whose titles carry no chapter numbers are left alone.
     */
    fun apply(list: List<Chapter>): List<Chapter> {
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

        // Longest descending run at the very start.
        var end = 1
        while (end < nums.size) {
            val a = nums[end - 1]
            val b = nums[end]
            if (a == null || b == null || b >= a) break
            end++
        }
        // The run ends at the smallest number, which is where the real list starts (chapter 1, 2, 3…).
        val mainStart = end - 1
        if (mainStart < 2 || mainStart > 60) return list
        val firstOfMain = nums[mainStart] ?: return list
        if (firstOfMain > 10 || nums.getOrNull(mainStart + 1)?.let { it > firstOfMain } != true) return list
        return list.drop(mainStart) + list.take(mainStart).asReversed()
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
