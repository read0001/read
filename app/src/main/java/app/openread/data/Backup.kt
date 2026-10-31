package app.openread.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 备份与恢复：书架（含阅读进度、分组、固定书源）、书签、替换规则、阅读时长、手动添加的书源、订阅书源的启用/固定选择、
 * 全部设置，存成一个 JSON 文件。不含章节正文和目录（恢复后打开书时重新从书源读），也不含本地导入的书（文件太大）。
 */
class Backup(private val context: Context, private val db: AppDb, private val prefs: Prefs, private val sources: SourceRepo) {
    class Summary(val books: Int, val bookmarks: Int, val rules: Int, val sources: Int)

    suspend fun export(uri: Uri): Summary = withContext(Dispatchers.IO) {
        val books = db.books().snapshot().filter { !it.isLocal }
        val urls = books.map { it.bookUrl }.toSet()
        val marks = db.bookmarks().all().filter { it.bookUrl in urls }
        val rules = db.replaceRules().all()
        val all = db.sources().snapshot()
        val manual = all.filter { !it.subscribed }
        val root = buildJsonObject {
            put("app", "openread")
            put("format", 1)
            put("exportedAt", System.currentTimeMillis())
            put("books", buildJsonArray { books.forEach { add(book(it)) } })
            put("bookmarks", buildJsonArray {
                marks.forEach { m ->
                    add(buildJsonObject {
                        put("bookUrl", m.bookUrl); put("chapterIndex", m.chapterIndex); put("chapterTitle", m.chapterTitle)
                        put("offset", m.offset); put("excerpt", m.excerpt); put("createdAt", m.createdAt)
                    })
                }
            })
            put("rules", buildJsonArray {
                rules.forEach { r ->
                    add(buildJsonObject {
                        put("pattern", r.pattern); put("replacement", r.replacement); put("isRegex", r.isRegex)
                        put("enabled", r.enabled); put("scope", r.scope); put("orderNo", r.orderNo)
                    })
                }
            })
            put("readLog", buildJsonArray { db.readLog().all().forEach { l -> add(buildJsonObject { put("day", l.day); put("bookName", l.bookName); put("ms", l.ms) }) } })
            // 手动添加的书源整条带上；订阅来的只记启用、固定的选择（规则本身订阅里有）
            put("manualSources", buildJsonArray { manual.forEach { s -> add(buildJsonObject { put("json", s.json); put("enabled", s.enabled); put("pinned", s.pinned) }) } })
            put("sourceFlags", buildJsonArray { all.filter { it.subscribed }.forEach { s -> add(buildJsonObject { put("key", s.key); put("enabled", s.enabled); put("pinned", s.pinned) }) } })
            put("settings", prefs.exportRaw())
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString().toByteArray()) } ?: error("无法写入这个文件")
        Summary(books.size, marks.size, rules.size, manual.size)
    }

    suspend fun restore(uri: Uri): Summary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("打不开这个文件")
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (root == null || root.str("app") != "openread") error("不是开卷的备份文件")

        val books = root.arr("books").mapNotNull { runCatching { bookOf(it.jsonObject) }.getOrNull() }
        val marks = root.arr("bookmarks").mapNotNull { e ->
            val o = e.jsonObject
            runCatching { BookmarkRow(0, o.str("bookUrl")!!, o.int("chapterIndex"), o.str("chapterTitle").orEmpty(), o.int("offset"), o.str("excerpt").orEmpty(), o.long("createdAt")) }.getOrNull()
        }
        val rules = root.arr("rules").mapNotNull { e ->
            val o = e.jsonObject
            val pattern = o.str("pattern") ?: return@mapNotNull null
            ReplaceRule(0, pattern, o.str("replacement").orEmpty(), o.bool("isRegex"), o.bool("enabled", true), o.str("scope").orEmpty(), o.int("orderNo"))
        }
        val logs = root.arr("readLog").mapNotNull { e -> val o = e.jsonObject; ReadLog(o.str("day") ?: return@mapNotNull null, o.str("bookName") ?: return@mapNotNull null, o.long("ms")) }

        // 手动书源：照常导入（导入会保留已有的固定选择），再按备份里的启用、固定设好
        val manual = root.arr("manualSources").map { it.jsonObject }
        var sourceCount = 0
        for (o in manual) {
            val json = o.str("json") ?: continue
            if (sources.import(json) > 0) sourceCount++
            app.openread.engine.BookSource.parseList(json).firstOrNull()?.key?.let { k ->
                sources.setEnabled(k, o.bool("enabled", true))
                sources.setPinned(k, o.bool("pinned", true))
            }
        }
        val existing = db.sources().snapshot().associateBy { it.key }
        for (e in root.arr("sourceFlags")) {
            val o = e.jsonObject
            val k = o.str("key") ?: continue
            if (k !in existing) continue
            sources.setEnabled(k, o.bool("enabled", true))
            sources.setPinned(k, o.bool("pinned"))
        }

        db.withTransaction {
            for (b in books) {
                // 书架上已经有的书：保留现有目录；进度以备份为准
                db.books().upsert(b)
            }
            val restoredUrls = marks.map { it.bookUrl }.toSet()
            restoredUrls.forEach { db.bookmarks().clear(it) }
            if (marks.isNotEmpty()) db.bookmarks().upsertAll(marks)
            // 规则：已有一模一样的就不重复加
            val have = db.replaceRules().all().map { Triple(it.pattern, it.replacement, it.scope) }.toSet()
            rules.filter { Triple(it.pattern, it.replacement, it.scope) !in have }.forEach { db.replaceRules().upsert(it) }
            // 阅读时长：同一天同一本书取较大的那个
            val old = db.readLog().all().associateBy { it.day to it.bookName }
            db.readLog().upsertAll(logs.map { l -> old[l.day to l.bookName]?.takeIf { it.ms > l.ms } ?: l })
        }
        (root["settings"] as? JsonObject)?.let { prefs.importRaw(it) }
        Summary(books.size, marks.size, rules.size, sourceCount)
    }

    private fun book(b: BookRow) = buildJsonObject {
        put("bookUrl", b.bookUrl); put("origin", b.origin); put("originName", b.originName); put("name", b.name); put("author", b.author)
        put("coverUrl", b.coverUrl); put("intro", b.intro); put("kind", b.kind); put("tocUrl", b.tocUrl); put("latestChapter", b.latestChapter)
        put("wordCount", b.wordCount); put("vars", b.vars); put("addedAt", b.addedAt); put("readAt", b.readAt); put("chapterIndex", b.chapterIndex)
        put("chapterOffset", b.chapterOffset); put("chapterTitle", b.chapterTitle); put("totalChapters", b.totalChapters)
        put("sourcePinned", b.sourcePinned); put("shelfGroup", b.shelfGroup)
    }

    private fun bookOf(o: JsonObject) = BookRow(
        bookUrl = o.str("bookUrl")!!, origin = o.str("origin")!!, originName = o.str("originName").orEmpty(), name = o.str("name")!!,
        author = o.str("author").orEmpty(), coverUrl = o.str("coverUrl"), intro = o.str("intro"), kind = o.str("kind"), tocUrl = o.str("tocUrl").orEmpty(),
        latestChapter = o.str("latestChapter"), wordCount = o.str("wordCount"), vars = o.str("vars") ?: "{}", addedAt = o.long("addedAt"), readAt = o.long("readAt"),
        chapterIndex = o.int("chapterIndex"), chapterOffset = o.int("chapterOffset"), chapterTitle = o.str("chapterTitle"), totalChapters = o.int("totalChapters"),
        sourcePinned = o.bool("sourcePinned"), shelfGroup = o.str("shelfGroup").orEmpty(),
    )

    private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(k: String): String? = prim(k)?.content
    private fun JsonObject.int(k: String): Int = prim(k)?.intOrNull ?: 0
    private fun JsonObject.long(k: String): Long = prim(k)?.longOrNull ?: 0L
    private fun JsonObject.bool(k: String, default: Boolean = false): Boolean = prim(k)?.booleanOrNull ?: default
    private fun JsonObject.arr(k: String): JsonArray = (this[k] as? JsonArray) ?: JsonArray(emptyList<JsonElement>())
}
