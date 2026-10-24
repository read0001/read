package app.openread.engine

/**
 * The paid chapters of a serial (on 剑烛大荒, everything after chapter ~50) are mostly missing from reprint sites: they carry the
 * first few paragraphs and pad the rest with sentences from other novels. Every copy has the same padding, so comparing sources
 * does not help, and the sources themselves do nothing wrong.
 *
 * What does show: the real opening talks about this book's people (names that also occur in the free chapters), and what follows
 * is a long run of long paragraphs that never mention them. This trims the chapter after the opening.
 *
 * Deleting real text is worse than leaving the padding in, so it is strict. A chapter is only touched when all of these hold:
 *  - it is much shorter than the book's free chapters (padded chapters are about a third of their length);
 *  - the real opening is at most [MAX_HEAD] paragraphs, ends on a paragraph that mentions the book's names, and the paragraph
 *    right after it does not;
 *  - at least [MIN_TAIL] long paragraphs follow and at most [MAX_RELATED] of them mention those names.
 * Checked on 20 chapters of one book against three different reference chapters: 13 of 15 padded chapters trimmed, none of the
 * 5 free chapters touched.
 */
object PreviewGuard {
    /** What the free chapters of a book look like: its recurring names and terms, and how long a chapter normally is. */
    class Reference(val vocabulary: Set<String>, val averageChars: Int)

    class Result(val text: String, val trimmed: Boolean, val keptParagraphs: Int = 0)

    const val NOTICE = "（本章只有开头一小段。这个书源后面的文字是别的小说混进来的，已经隐藏；完整内容请到原站阅读，或试试换源。）"

    private const val MIN_PARAGRAPH = 25      // letters; shorter lines say nothing about whose book it is
    private const val MAX_HEAD = 10
    private const val MIN_TAIL = 10
    private const val MAX_RELATED = 0.15
    private const val SHORT = 0.8
    private const val CANDIDATE_CHARS = 2600  // above this, nobody looks for padding

    private val function = "的了是在有和不我他她你这那也就都而及与着一个们会还又说道到被把给让对于以为所自己什么没".toSet()

    private fun letters(s: String) = s.filter { it.code in 0x4E00..0x9FFF }

    /** Three-letter runs without function words: in prose these are mostly names and terms. */
    private fun grams(paragraph: String): Set<String> {
        val s = letters(paragraph)
        val out = HashSet<String>()
        for (i in 0..s.length - 3) {
            val g = s.substring(i, i + 3)
            if (g.none { it in function }) out.add(g)
        }
        return out
    }

    private fun paragraphs(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Cheap check, no network: only short chapters with many long paragraphs are worth building a reference for. */
    fun isCandidate(text: String): Boolean {
        if (text.length >= CANDIDATE_CHARS) return false
        return paragraphs(text).count { letters(it).length >= MIN_PARAGRAPH } >= MIN_TAIL + 2
    }

    /** From the text of a few early chapters; null when there is not enough to tell this book's names apart. */
    fun reference(chapters: List<String>): Reference? {
        val texts = chapters.filter { it.isNotBlank() }
        if (texts.isEmpty()) return null
        val count = HashMap<String, Int>()
        for (t in texts) for (p in paragraphs(t)) grams(p).forEach { count.merge(it, 1, Int::plus) }
        val vocabulary = count.filter { it.value >= 3 }.keys
        if (vocabulary.isEmpty()) return null
        return Reference(vocabulary, texts.sumOf { letters(it).length } / texts.size)
    }

    fun trim(text: String, ref: Reference): Result {
        val paras = paragraphs(text)
        if (paras.size < MIN_TAIL + 2) return Result(text, false)
        if (paras.sumOf { letters(it).length } >= SHORT * ref.averageChars) return Result(text, false)
        val g = paras.map(::grams)
        // Names repeat inside a real opening even when the reference never saw them (a new character).
        val opening = HashMap<String, Int>()
        for (s in g.take(8)) s.forEach { opening.merge(it, 1, Int::plus) }
        val shared = opening.filter { it.value >= 2 }.keys
        val related = g.map { s -> s.any { it in ref.vocabulary || it in shared } }
        val long = paras.map { letters(it).length >= MIN_PARAGRAPH }

        var best = 0
        for (c in 1..minOf(MAX_HEAD, paras.size - 1)) {
            if (!related[c - 1] || related[c]) continue // the opening ends on a related paragraph; what follows must not be one
            val tail = (c until paras.size).filter { long[it] }
            if (tail.size >= MIN_TAIL && tail.count { related[it] } <= MAX_RELATED * tail.size) best = c // keep the largest
        }
        if (best == 0) return Result(text, false)
        return Result(paras.take(best).joinToString("\n"), true, best)
    }
}
