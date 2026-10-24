package app.openread.engine

/**
 * Is the newest chapter of this source really there? On a serial with paid chapters most reprint sites list the newest chapters
 * but carry only a teaser (see [PreviewGuard]); the table of contents looks perfect and says nothing.
 *
 * The newest chapter is read and compared with the book's free chapters (length, and [PreviewGuard]). If it is a teaser, the last
 * chapter that is complete is found by bisection, assuming the paywall is one boundary: everything before it is free.
 */
object LatestCheck {
    /**
     * [completeTitle] is the newest chapter that is complete (the newest chapter itself when [complete]) and [completeCount] how many
     * chapters of the table of contents lead up to it. Titles are no good for comparing sources: serials restart the numbering
     * with every volume.
     */
    class Result(val total: Int, val complete: Boolean, val completeTitle: String, val completeCount: Int)

    private const val MIN_CHAPTERS = 30  // a short book has no paywall to speak of
    private const val CUT = 0.3          // a chapter shorter than this share of a free one is cut off, whatever it says

    private fun letters(s: String) = s.count { it.code in 0x4E00..0x9FFF }

    /** Null when the source's chapters cannot be read well enough to tell. */
    fun run(web: WebBook, book: Book, toc: List<Chapter>): Result? {
        val real = toc.filter { !it.isVolume }
        if (real.isEmpty()) return null
        val last = real.size - 1
        fun title(i: Int) = real[i].title
        if (real.size < MIN_CHAPTERS) return Result(real.size, true, title(last), real.size)

        val others = real.asSequence().map { it.url }.toSet()
        fun read(i: Int): String? = runCatching { web.content(book, real[i], real.getOrNull(i + 1)?.url, otherChapterUrls = others - real[i].url) }.getOrNull()
        val early = listOf(10, 14).mapNotNull(::read)
        if (early.isEmpty()) return null
        val ref = PreviewGuard.reference(early) ?: return null
        val average = early.sumOf(::letters) / early.size
        // Only clear signs count: a source that splits chapters in pieces has short ones everywhere, and that is not padding.
        fun complete(i: Int): Boolean? {
            val t = read(i) ?: return null
            return letters(t) >= CUT * average && !PreviewGuard.trim(t, ref).trimmed
        }

        when (complete(last)) {
            true -> return Result(real.size, true, title(last), real.size)
            null -> return null
            false -> {}
        }
        // One short chapter at the very end is a leave notice or an author's note, not a paywall: the one before it decides.
        if (last - 1 > 14 && complete(last - 1) == true) return Result(real.size, true, title(last - 1), real.size - 1)
        var lo = 14      // read above as free
        var hi = last    // read just now as a teaser
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            // A chapter that cannot be read says nothing: ask a neighbour instead of calling it padding.
            val verdict = complete(mid) ?: (if (mid + 1 < hi) complete(mid + 1) else null) ?: (if (mid - 1 > lo) complete(mid - 1) else null) ?: return null
            if (verdict) lo = mid else hi = mid
        }
        return Result(real.size, false, title(lo), lo + 1)
    }
}
