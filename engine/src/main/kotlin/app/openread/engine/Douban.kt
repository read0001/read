package app.openread.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

class DoubanException(message: String) : Exception(message)

/** The print edition's page on Douban Books that matches a web novel. */
class DoubanSubject(val id: String, val title: String, val abstract: String, val rating: Double?, val ratingCount: Int, val url: String)

class BookComment(
    val user: String,
    val avatar: String?,
    /** 1–5, or null when the reader did not rate. */
    val stars: Int?,
    val time: String,
    val text: String,
    val votes: Int,
    /** Set for long reviews, where [text] is only the abstract. */
    val title: String? = null,
    val url: String? = null,
)

class CommentPage(val total: Int, val items: List<BookComment>)

/**
 * Reader comments for a whole book, taken from Douban Books. Book sources carry no comment rules in practice (none of the
 * 2,600 sources checked had one), so this is the one place real comments come from.
 *
 * It uses Douban's mobile site and the JSON its pages load, which works without logging in but is not a documented API:
 * it can change or start refusing requests. Anonymous access shows the hottest comments only. Everything here is read-only
 * and meant to be called when the user asks to see comments, not in bulk.
 */
object Douban {
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val SEARCH = "https://search.douban.com/book/subject_search?search_text="
    private const val API = "https://m.douban.com/rexxar/api/v2/book/"

    /** The Douban book for [name] by [author], or null when there is no confident match (better nothing than another book's comments). */
    fun findSubject(http: Http, name: String, author: String): DoubanSubject? {
        val html = get(http, SEARCH + URLEncoder.encode(name, "UTF-8"), "https://book.douban.com/")
        return pick(parseSearch(html), name, author)
    }

    fun shortComments(http: Http, id: String, start: Int = 0, count: Int = 20): CommentPage =
        parseInterests(get(http, "$API$id/interests?count=$count&start=$start&for_mobile=1&order_by=hot", "https://m.douban.com/book/subject/$id/comments"))

    fun reviews(http: Http, id: String, start: Int = 0, count: Int = 20): CommentPage =
        parseReviews(get(http, "$API$id/reviews?count=$count&start=$start&for_mobile=1", "https://m.douban.com/book/subject/$id/reviews"))

    private fun get(http: Http, url: String, referer: String): String {
        val r = http.execute(url, headers = mapOf("User-Agent" to UA, "Referer" to referer, "Accept-Language" to "zh-CN,zh;q=0.9"), retry = 1)
        if (r.code == 200) return r.body
        throw DoubanException(
            when (r.code) {
                403, 429, 400 -> "豆瓣暂时限制了访问，请稍后再试"
                else -> "豆瓣返回了 HTTP ${r.code}"
            },
        )
    }

    // ---- parsing (pure, tested against saved responses) ----------------------------------------------------------------

    internal fun parseSearch(html: String): List<DoubanSubject> {
        val at = html.indexOf("window.__DATA__ = ")
        if (at < 0) return emptyList()
        val json = balancedObject(html, html.indexOf('{', at)) ?: return emptyList()
        val items = (JsonCodec.parse(json) as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = o.str("url") ?: return@mapNotNull null
            val id = Regex("/subject/(\\d+)").find(url)?.groupValues?.get(1) ?: return@mapNotNull null // series and ads have no /subject/ page
            val rating = o.obj("rating")
            DoubanSubject(id, o.str("title").orEmpty(), o.str("abstract").orEmpty(), rating?.str("value")?.toDoubleOrNull(), rating?.int("count") ?: 0, url)
        }
    }

    /** `{…}` starting at [from], honouring strings and escapes. */
    private fun balancedObject(s: String, from: Int): String? {
        if (from < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in from until s.length) {
            val c = s[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return s.substring(from, i + 1)
            }
        }
        return null
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Title without an edition or volume suffix: "斗破苍穹（第一辑）" and "斗破苍穹 : 废柴当自强" are not the novel itself. */
    private fun baseTitle(t: String) = t.substringBefore(" : ").substringBefore("：").substringBefore("（").substringBefore("(")

    internal fun pick(list: List<DoubanSubject>, name: String, author: String): DoubanSubject? {
        val n = norm(name)
        if (n.isEmpty()) return null
        // Sources write authors as "天蚕土豆_qd22", "天蚕土豆 著", "天蚕土豆/某某": keep the first name-like piece.
        val a = norm(author.split("_", "/", "，", ",", "·", " ").firstOrNull { it.isNotBlank() }.orEmpty().removeSuffix("著"))
        return list.filter { norm(it.title) == n || norm(baseTitle(it.title)) == n && it.title == name }
            .filter { a.isEmpty() || norm(it.abstract).contains(a) }
            .maxByOrNull { it.ratingCount }
    }

    internal fun parseInterests(json: String): CommentPage {
        val o = parseObject(json)
        val items = (o["interests"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val i = e as? JsonObject ?: return@mapNotNull null
            val text = i.str("comment")?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val u = i.obj("user")
            BookComment(
                user = u?.str("name").orEmpty().ifEmpty { "豆瓣用户" }, avatar = u?.str("avatar"),
                stars = i.obj("rating")?.int("value")?.takeIf { it in 1..5 }, time = i.str("create_time").orEmpty().take(10),
                text = text, votes = i.int("vote_count") ?: 0,
            )
        }
        return CommentPage(o.int("total") ?: items.size, items)
    }

    internal fun parseReviews(json: String): CommentPage {
        val o = parseObject(json)
        val items = (o["reviews"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            val text = r.str("abstract")?.trim().orEmpty()
            val title = r.str("title")?.trim().orEmpty()
            if (text.isEmpty() && title.isEmpty()) return@mapNotNull null
            val u = r.obj("user")
            BookComment(
                user = u?.str("name").orEmpty().ifEmpty { "豆瓣用户" }, avatar = u?.str("avatar"),
                stars = r.obj("rating")?.int("value")?.takeIf { it in 1..5 }, time = r.str("create_time").orEmpty().take(10),
                text = text, votes = r.int("useful_count") ?: 0, title = title.ifEmpty { null }, url = r.str("url"),
            )
        }
        return CommentPage(o.int("total") ?: items.size, items)
    }

    private fun parseObject(json: String): JsonObject {
        val o = JsonCodec.parse(json) as? JsonObject ?: throw DoubanException("豆瓣返回的内容无法解析")
        if (o.str("msg") == "need_permission") throw DoubanException("豆瓣要求登录才能查看更多")
        return o
    }
}
