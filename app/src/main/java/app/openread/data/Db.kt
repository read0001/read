package app.openread.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sources")
data class SourceRow(
    @PrimaryKey val key: String,
    val name: String,
    val group: String?,
    /** The source exactly as published, so nothing is lost on export. */
    val json: String,
    val enabled: Boolean,
    /** True when it came from the subscription; false for sources imported by hand, which updates never touch. */
    val subscribed: Boolean,
    val orderNo: Int,
    val checkedAt: Long = 0,
    val checkOk: Boolean? = null,
    val checkMs: Long = 0,
    val checkMsg: String? = null,
    /** 固定的书源：排在最前、搜索时优先，订阅更新不会删除或覆盖它（手动添加的书源默认固定）。 */
    @androidx.room.ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** 固定的订阅源，在最近一次订阅更新里已经不在订阅了：还留着（因为固定），界面上标出来，取消固定后下次更新就会清掉。 */
    @androidx.room.ColumnInfo(defaultValue = "0") val orphaned: Boolean = false,
)

/** 书源的简要信息（不含规则 JSON）。 */
data class SourceBrief(val key: String, val name: String, val group: String?, val pinned: Boolean)

@Entity(tableName = "books")
data class BookRow(
    @PrimaryKey val bookUrl: String,
    val origin: String,
    val originName: String,
    val name: String,
    val author: String,
    val coverUrl: String?,
    val intro: String?,
    val kind: String?,
    val tocUrl: String,
    val latestChapter: String?,
    val wordCount: String?,
    /** Variables the source stored with `@put`, as JSON. */
    val vars: String,
    val addedAt: Long,
    val readAt: Long,
    val chapterIndex: Int = 0,
    /** Character offset into the current chapter, so the position survives a change of font size. */
    val chapterOffset: Int = 0,
    val chapterTitle: String? = null,
    val totalChapters: Int = 0,
    val hasUpdate: Boolean = false,
    val checkedAt: Long = 0,
    /** 固定这本书的书源：刷新核对、自动换源、换源推荐都不再动它，直到取消固定。 */
    @androidx.room.ColumnInfo(defaultValue = "0") val sourcePinned: Boolean = false,
)

@Entity(tableName = "chapters", primaryKeys = ["bookUrl", "idx"])
data class ChapterRow(
    val bookUrl: String,
    val idx: Int,
    val title: String,
    val url: String,
    val isVolume: Boolean,
    val vars: String,
)

/** How a source behaved in past searches, so a search can ask the ones that work first and rest the ones that keep failing. */
@Entity(tableName = "source_health")
data class SourceHealth(
    @PrimaryKey val key: String,
    /** Searches in a row that ended in an error or a timeout. */
    val failStreak: Int,
    val lastOkAt: Long,
    val lastFailAt: Long,
    /** Smoothed time of the searches that found something. */
    val avgMs: Long,
)

@Entity(tableName = "history")
data class HistoryRow(@PrimaryKey val keyword: String, val at: Long)

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY pinned DESC, orderNo, name")
    fun all(): Flow<List<SourceRow>>

    @Query("SELECT * FROM sources ORDER BY pinned DESC, orderNo, name")
    suspend fun snapshot(): List<SourceRow>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY pinned DESC, orderNo, name")
    suspend fun enabled(): List<SourceRow>

    /** 搜索源选择面板只要这几列，不读每个书源的规则 JSON。 */
    @Query("SELECT `key`, name, `group`, pinned FROM sources WHERE enabled = 1 ORDER BY pinned DESC, orderNo, name")
    fun enabledBrief(): Flow<List<SourceBrief>>

    @Query("SELECT * FROM sources WHERE `key` = :key")
    suspend fun get(key: String): SourceRow?

    /** 手动添加的书源（订阅更新不会动的那些）。 */
    @Query("SELECT * FROM sources WHERE subscribed = 0")
    suspend fun manual(): List<SourceRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<SourceRow>)

    @Query("DELETE FROM sources WHERE `key` = :key")
    suspend fun delete(key: String)

    /** 只删没固定的订阅源；固定的留着，哪怕订阅里已经没有它了。 */
    @Query("DELETE FROM sources WHERE subscribed = 1 AND pinned = 0")
    suspend fun deleteUnpinnedSubscribed()

    @Query("UPDATE sources SET pinned = :pinned WHERE `key` = :key")
    suspend fun setPinned(key: String, pinned: Boolean)

    @Query("UPDATE sources SET orphaned = :orphaned WHERE `key` = :key")
    suspend fun setOrphaned(key: String, orphaned: Boolean)

    @Query("UPDATE sources SET enabled = :enabled WHERE `key` = :key")
    suspend fun setEnabled(key: String, enabled: Boolean)

    @Query("UPDATE sources SET checkedAt = :at, checkOk = :ok, checkMs = :ms, checkMsg = :msg WHERE `key` = :key")
    suspend fun setCheck(key: String, at: Long, ok: Boolean, ms: Long, msg: String?)

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int

    @Transaction
    suspend fun replaceSubscribed(rows: List<SourceRow>) {
        // 手动添加的书源，订阅更新一概不动。固定的订阅源只是“不会因为订阅里没有了而被删”：订阅里还有它时，
        // 规则内容照常更新（站点改版后订阅发布了修复，固定的源也要拿到），固定标记和启用/停用选择、检测结果都保留。
        val all = snapshot()
        val manual = all.filter { !it.subscribed }.map { it.key }.toSet()
        val prior = all.filter { it.subscribed }.associateBy { it.key }
        deleteUnpinnedSubscribed()
        val incoming = rows.filter { it.key !in manual }
        upsert(
            incoming.map { r ->
                prior[r.key]?.let { r.copy(enabled = it.enabled, pinned = it.pinned, checkedAt = it.checkedAt, checkOk = it.checkOk, checkMs = it.checkMs, checkMsg = it.checkMsg) } ?: r
            },
        )
        // 固定着、订阅里却已经没有的源：留下，并标出来；订阅里又有了就取消标记
        val here = incoming.map { it.key }.toSet()
        prior.values.filter { it.pinned && it.key !in here }.forEach { setOrphaned(it.key, true) }
    }
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY readAt DESC")
    fun shelf(): Flow<List<BookRow>>

    @Query("SELECT * FROM books ORDER BY readAt DESC")
    suspend fun snapshot(): List<BookRow>

    @Query("SELECT * FROM books WHERE bookUrl = :url")
    suspend fun get(url: String): BookRow?

    @Query("SELECT * FROM books WHERE bookUrl = :url")
    fun observe(url: String): Flow<BookRow?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BookRow)

    @Query("DELETE FROM books WHERE bookUrl = :url")
    suspend fun delete(url: String)

    @Query("UPDATE books SET chapterIndex = :index, chapterOffset = :offset, chapterTitle = :title, readAt = :at, hasUpdate = 0 WHERE bookUrl = :url")
    suspend fun saveProgress(url: String, index: Int, offset: Int, title: String?, at: Long)

    @Query("UPDATE books SET totalChapters = :total, latestChapter = :latest, hasUpdate = :hasUpdate, checkedAt = :at WHERE bookUrl = :url")
    suspend fun saveUpdate(url: String, total: Int, latest: String?, hasUpdate: Boolean, at: Long)

    @Query("UPDATE books SET sourcePinned = :pinned WHERE bookUrl = :url")
    suspend fun setSourcePinned(url: String, pinned: Boolean)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl ORDER BY idx")
    suspend fun list(bookUrl: String): List<ChapterRow>

    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl ORDER BY idx")
    fun observe(bookUrl: String): Flow<List<ChapterRow>>

    @Query("SELECT COUNT(*) FROM chapters WHERE bookUrl = :bookUrl")
    suspend fun count(bookUrl: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<ChapterRow>)

    @Query("DELETE FROM chapters WHERE bookUrl = :bookUrl")
    suspend fun clear(bookUrl: String)

    @Transaction
    suspend fun replace(bookUrl: String, rows: List<ChapterRow>) {
        clear(bookUrl)
        rows.chunked(500).forEach { insert(it) }
    }
}

@Dao
interface HealthDao {
    @Query("SELECT * FROM source_health")
    suspend fun all(): List<SourceHealth>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<SourceHealth>)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY at DESC LIMIT 20")
    fun recent(): Flow<List<HistoryRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(row: HistoryRow)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(entities = [SourceRow::class, BookRow::class, ChapterRow::class, HistoryRow::class, SourceHealth::class], version = 3, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun books(): BookDao
    abstract fun chapters(): ChapterDao
    abstract fun history(): HistoryDao
    abstract fun health(): HealthDao
}

/** 1 → 2 only adds a table; the shelf, chapters and sources are kept. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `source_health` (`key` TEXT NOT NULL, `failStreak` INTEGER NOT NULL, `lastOkAt` INTEGER NOT NULL, `lastFailAt` INTEGER NOT NULL, `avgMs` INTEGER NOT NULL, PRIMARY KEY(`key`))")
    }
}

/** 2 → 3 给书源和书架各加一个“固定”标记，已有的书架、章节和书源原样保留。 */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sources` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `sources` ADD COLUMN `orphaned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `books` ADD COLUMN `sourcePinned` INTEGER NOT NULL DEFAULT 0")
        // 手动添加的书源本来就不会被订阅动，新版里默认固定：升级后保持一致。
        db.execSQL("UPDATE `sources` SET `pinned` = 1 WHERE `subscribed` = 0")
    }
}
