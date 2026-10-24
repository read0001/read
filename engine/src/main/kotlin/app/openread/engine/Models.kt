package app.openread.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Rule strings of one book source section; every field is a Legado rule (see [AnalyzeRule]). */
class SearchRule(
    val checkKeyWord: String?, val bookList: String?, val name: String?, val author: String?, val intro: String?,
    val kind: String?, val lastChapter: String?, val updateTime: String?, val bookUrl: String?, val coverUrl: String?,
    val wordCount: String?,
)

class BookInfoRule(
    val init: String?, val name: String?, val author: String?, val intro: String?, val kind: String?,
    val lastChapter: String?, val updateTime: String?, val coverUrl: String?, val tocUrl: String?,
    val wordCount: String?, val canReName: String?,
)

class TocRule(
    val preUpdateJs: String?, val chapterList: String?, val chapterName: String?, val chapterUrl: String?,
    val formatJs: String?, val isVolume: String?, val isVip: String?, val isPay: String?, val updateTime: String?,
    val nextTocUrl: String?,
)

class ContentRule(
    val content: String?, val title: String?, val nextContentUrl: String?, val webJs: String?,
    val sourceRegex: String?, val replaceRegex: String?, val imageStyle: String?,
)

/**
 * A Legado "书源". The rule fields keep their Legado names so any source exported by Legado loads as is.
 * [raw] is the original JSON object; it is what gets written back out so no field is ever lost.
 */
class BookSource(
    val bookSourceUrl: String,
    val bookSourceName: String,
    val bookSourceGroup: String?,
    val bookSourceType: Int,
    val bookSourceComment: String?,
    val enabled: Boolean,
    val enabledExplore: Boolean,
    val enabledCookieJar: Boolean,
    val header: String?,
    val loginUrl: String?,
    val loginCheckJs: String?,
    val jsLib: String?,
    val concurrentRate: String?,
    val bookUrlPattern: String?,
    val searchUrl: String?,
    val exploreUrl: String?,
    val ruleSearch: SearchRule?,
    val ruleExplore: SearchRule?,
    val ruleBookInfo: BookInfoRule?,
    val ruleToc: TocRule?,
    val ruleContent: ContentRule?,
    val customOrder: Int,
    val raw: JsonObject,
) {
    /** The key a source is identified by everywhere: Legado uses the URL, sometimes with a `#comment` suffix. */
    val key: String get() = bookSourceUrl

    /** Host-style base URL: [bookSourceUrl] without a `#…` comment suffix. */
    val baseUrl: String get() = bookSourceUrl.substringBefore("#").trimEnd('/')

    /** Text sources only: audio (1), image (2) and file (3) sources need other readers. */
    val isText: Boolean get() = bookSourceType == 0

    val canSearch: Boolean get() = !searchUrl.isNullOrBlank() && ruleSearch?.bookList?.isNotBlank() == true

    fun toJson(): String = JsonCodec.pretty.encodeToString(JsonObject.serializer(), raw)

    companion object {
        /** Parses one object or an array of objects; entries that are not valid sources are skipped. */
        fun parseList(text: String): List<BookSource> {
            val root = runCatching { JsonCodec.lenient.parseToJsonElement(text.trim().removePrefix("﻿")) }.getOrNull()
                ?: return emptyList()
            val items = when (root) {
                is JsonArray -> root.toList()
                is JsonObject -> listOf(root)
                else -> emptyList()
            }
            return items.mapNotNull { (it as? JsonObject)?.let(::fromJson) }
        }

        fun fromJson(o: JsonObject): BookSource? {
            val url = o.str("bookSourceUrl")?.trim().orEmpty()
            val name = o.str("bookSourceName")?.trim().orEmpty()
            if (url.isEmpty() || name.isEmpty()) return null
            return BookSource(
                bookSourceUrl = url,
                bookSourceName = name,
                bookSourceGroup = o.str("bookSourceGroup"),
                bookSourceType = o.int("bookSourceType") ?: 0,
                bookSourceComment = o.str("bookSourceComment"),
                enabled = o.bool("enabled") ?: true,
                enabledExplore = o.bool("enabledExplore") ?: true,
                enabledCookieJar = o.bool("enabledCookieJar") ?: true,
                header = o.str("header"),
                loginUrl = o.str("loginUrl"),
                loginCheckJs = o.str("loginCheckJs"),
                jsLib = o.str("jsLib"),
                concurrentRate = o.str("concurrentRate"),
                bookUrlPattern = o.str("bookUrlPattern"),
                searchUrl = o.str("searchUrl"),
                exploreUrl = o.str("exploreUrl"),
                ruleSearch = o.obj("ruleSearch")?.let(::searchRule),
                ruleExplore = o.obj("ruleExplore")?.let(::searchRule),
                ruleBookInfo = o.obj("ruleBookInfo")?.let { r ->
                    BookInfoRule(
                        r.str("init"), r.str("name"), r.str("author"), r.str("intro"), r.str("kind"),
                        r.str("lastChapter"), r.str("updateTime"), r.str("coverUrl"), r.str("tocUrl"),
                        r.str("wordCount"), r.str("canReName"),
                    )
                },
                ruleToc = o.obj("ruleToc")?.let { r ->
                    TocRule(
                        r.str("preUpdateJs"), r.str("chapterList"), r.str("chapterName"), r.str("chapterUrl"),
                        r.str("formatJs"), r.str("isVolume"), r.str("isVip"), r.str("isPay"), r.str("updateTime"),
                        r.str("nextTocUrl"),
                    )
                },
                ruleContent = o.obj("ruleContent")?.let { r ->
                    ContentRule(
                        r.str("content"), r.str("title"), r.str("nextContentUrl"), r.str("webJs"),
                        r.str("sourceRegex"), r.str("replaceRegex"), r.str("imageStyle"),
                    )
                },
                customOrder = o.int("customOrder") ?: 0,
                raw = o,
            )
        }

        private fun searchRule(r: JsonObject) = SearchRule(
            r.str("checkKeyWord"), r.str("bookList"), r.str("name"), r.str("author"), r.str("intro"),
            r.str("kind"), r.str("lastChapter"), r.str("updateTime"), r.str("bookUrl"), r.str("coverUrl"),
            r.str("wordCount"),
        )
    }
}

/** Sources are hand-written by many people, so every field type is read leniently (number-as-string, 0/1 booleans…). */
internal fun JsonObject.str(k: String): String? = when (val v = this[k]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.contentOrNull
    else -> v.toString()
}

internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.longOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }

internal fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let {
    it.booleanOrNull ?: it.contentOrNull?.let { s -> s == "1" || s.equals("true", true) }
}

internal fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

object JsonCodec {
    /** Standard JSON plus trailing commas and comments. Single quotes and bare keys go through [RelaxedJson] instead. */
    val lenient = Json { ignoreUnknownKeys = true; allowTrailingComma = true; allowComments = true }
    val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun parse(text: String): JsonElement? =
        runCatching { lenient.parseToJsonElement(text) }.getOrNull() ?: RelaxedJson.parse(text.trim())
}

/** Variables set with `@put` / `java.put`; shared between a search hit, its book and its chapters, as in Legado. */
class RuleData {
    val variables = LinkedHashMap<String, String>()
    fun put(key: String, value: String) { variables[key] = value }
    fun get(key: String): String? = variables[key]
}

data class SearchBook(
    val name: String,
    val author: String,
    val bookUrl: String,
    val origin: String,
    val originName: String,
    val kind: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val wordCount: String? = null,
    val latestChapterTitle: String? = null,
    val data: RuleData = RuleData(),
)

class Book(
    var bookUrl: String,
    var tocUrl: String,
    val origin: String,
    var originName: String,
    var name: String,
    var author: String,
    var kind: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    var wordCount: String? = null,
    var latestChapterTitle: String? = null,
    var updateTime: String? = null,
    val data: RuleData = RuleData(),
) {
    companion object {
        fun from(s: SearchBook) = Book(
            bookUrl = s.bookUrl, tocUrl = s.bookUrl, origin = s.origin, originName = s.originName, name = s.name,
            author = s.author, kind = s.kind, coverUrl = s.coverUrl, intro = s.intro, wordCount = s.wordCount,
            latestChapterTitle = s.latestChapterTitle, data = s.data,
        )
    }
}

data class Chapter(
    val index: Int,
    val title: String,
    val url: String,
    val isVolume: Boolean = false,
    val isVip: Boolean = false,
    val isPay: Boolean = false,
    val updateTime: String? = null,
    val data: RuleData = RuleData(),
)

/** One tab on a source's discovery page. A blank [url] is a section heading. */
data class ExploreKind(val title: String, val url: String?)

/** A source stopped working in a way the user could see, e.g. a rule that matched nothing. */
class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
