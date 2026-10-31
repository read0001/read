package app.openread.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.openread.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

enum class ThemeMode { System, Light, Dark }

/** Reading surface palettes: paper, warm parchment, soft green, dim grey and true night. */
enum class ReaderPalette(val label: String, val bg: Long, val fg: Long) {
    Paper("纸张", 0xFFFAF7F0, 0xFF2B2B2B),
    Parchment("羊皮纸", 0xFFF1E4C8, 0xFF3B2F1E),
    Green("护眼", 0xFFD7E8D3, 0xFF22301F),
    Grey("灰", 0xFF3A3D40, 0xFFC9CCCF),
    Night("夜间", 0xFF0F1113, 0xFF9AA0A6),
}

enum class PageMode(val label: String) { Slide("左右翻页"), Scroll("上下滚动") }

/** 翻页动画：平移（默认）、覆盖（上一页盖在下一页上滑走）、无。 */
enum class PageAnim(val label: String) { Slide("平移"), Cover("覆盖"), None("无") }

/** 繁简转换：显示和朗读的正文都按这个转换（需要 Android 10 及以上）。 */
enum class ChineseConvert(val label: String) { None("不转换"), ToTraditional("简→繁"), ToSimplified("繁→简") }

/** 书架排序。 */
enum class ShelfSort(val label: String) { Recent("最近阅读"), Updated("最近更新"), Added("加入时间"), Name("书名") }

/** 搜索页搜哪些书源：全部启用的、只搜固定的、或自选的。 */
enum class SearchScope(val label: String) { All("全部书源"), Pinned("固定的书源"), Custom("自选书源") }

/** Who reads aloud: the device's speech engine, an online service, a downloaded offline model, or whichever works. */
enum class VoiceEngine(val label: String) { Auto("自动"), System("系统"), Online("在线"), Offline("离线") }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val fontSize: Int = 19,
    val lineSpacing: Float = 1.7f,
    val palette: ReaderPalette = ReaderPalette.Paper,
    val pageMode: PageMode = PageMode.Slide,
    val keepScreenOn: Boolean = true,
    val brightness: Float = -1f,
    val autoSwitchSource: Boolean = true,
    /** Speed of read-aloud, 1 is the engine's normal speed. */
    val readAloudRate: Float = 1f,
    val voiceEngine: VoiceEngine = VoiceEngine.Auto,
    /** Address of the online speech service, with {{text}} where the text goes; blank is the built-in one. */
    val onlineVoiceUrl: String = "",
    /** The offline voice is something the reader turns on (and downloads) knowingly; it is never used while this is off. */
    val offlineVoice: Boolean = false,
    /** 搜索范围，以及“自选书源”时选了哪些（书源的 key）。 */
    val searchScope: SearchScope = SearchScope.All,
    val searchSources: Set<String> = emptySet(),
    /** 精准搜索：只显示书名或作者包含关键字的结果（书源的模糊匹配常带回不相干的书）。 */
    val preciseSearch: Boolean = false,
    val subscriptionBase: String = DEFAULT_BASE,
    val lastSubscriptionUpdate: Long = 0,
    val ignoredVersion: Int = 0,
    val lastUpdateCheck: Long = 0,
    /** 音量键翻页（听书时不翻页，音量键照常调音量）。 */
    val volumeKeys: Boolean = true,
    /** 正文左右边距（dp）。 */
    val sideMargin: Int = 22,
    /** 段间距，以字号为单位（0 = 不额外留空）。 */
    val paragraphGap: Float = 0f,
    /** 段首缩进两个字。 */
    val indent: Boolean = true,
    /** 正文字体：空 = 系统默认，"serif" / "mono"，其余是导入的字体文件名（filesDir/fonts/ 下）。 */
    val readerFont: String = "",
    val chineseConvert: ChineseConvert = ChineseConvert.None,
    val pageAnim: PageAnim = PageAnim.Slide,
    /** 自动翻页：左右翻页时每页停留的秒数；上下滚动时滚过一屏的秒数。 */
    val autoPageSeconds: Int = 12,
    val shelfSort: ShelfSort = ShelfSort.Recent,
    /** 后台（Wi-Fi 下）检查书架更新，有新章节时发通知。 */
    val notifyUpdates: Boolean = true,
) {
    companion object {
        val DEFAULT_BASE = "https://raw.githubusercontent.com/${BuildConfig.SUBSCRIPTION_REPO}/${BuildConfig.SUBSCRIPTION_BRANCH}/booksource-check/out/"
    }
}

class Prefs(private val context: Context) {
    private object K {
        val theme = intPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val font = intPreferencesKey("font")
        val line = floatPreferencesKey("line")
        val palette = intPreferencesKey("palette")
        val mode = intPreferencesKey("page_mode")
        val awake = booleanPreferencesKey("awake")
        val brightness = floatPreferencesKey("brightness")
        val autoSwitch = booleanPreferencesKey("auto_switch")
        val ttsRate = floatPreferencesKey("tts_rate")
        val ttsEngine = intPreferencesKey("tts_engine")
        val ttsUrl = stringPreferencesKey("tts_url")
        val ttsOffline = booleanPreferencesKey("tts_offline")
        val searchScope = intPreferencesKey("search_scope")
        val searchSources = androidx.datastore.preferences.core.stringSetPreferencesKey("search_sources")
        val precise = booleanPreferencesKey("search_precise")
        val base = stringPreferencesKey("sub_base")
        val updated = longPreferencesKey("sub_updated")
        val ignored = intPreferencesKey("ignored_version")
        val checked = longPreferencesKey("update_checked")
        val volumeKeys = booleanPreferencesKey("volume_keys")
        val margin = intPreferencesKey("side_margin")
        val gap = floatPreferencesKey("paragraph_gap")
        val indent = booleanPreferencesKey("indent")
        val readerFont = stringPreferencesKey("reader_font")
        val convert = intPreferencesKey("chinese_convert")
        val anim = intPreferencesKey("page_anim")
        val autoPage = intPreferencesKey("auto_page_seconds")
        val shelfSort = intPreferencesKey("shelf_sort")
        val notifyUpdates = booleanPreferencesKey("notify_updates")
    }

    private fun decode(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            themeMode = ThemeMode.entries.getOrElse(p[K.theme] ?: 0) { d.themeMode },
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            fontSize = p[K.font] ?: d.fontSize,
            lineSpacing = p[K.line] ?: d.lineSpacing,
            palette = ReaderPalette.entries.getOrElse(p[K.palette] ?: 0) { d.palette },
            pageMode = PageMode.entries.getOrElse(p[K.mode] ?: 0) { d.pageMode },
            keepScreenOn = p[K.awake] ?: d.keepScreenOn,
            brightness = p[K.brightness] ?: d.brightness,
            autoSwitchSource = p[K.autoSwitch] ?: d.autoSwitchSource,
            readAloudRate = p[K.ttsRate] ?: d.readAloudRate,
            // The offline choice only exists while the offline voice is on.
            voiceEngine = VoiceEngine.entries.getOrElse(p[K.ttsEngine] ?: 0) { d.voiceEngine }
                .let { if (it == VoiceEngine.Offline && p[K.ttsOffline] != true) VoiceEngine.Auto else it },
            onlineVoiceUrl = p[K.ttsUrl] ?: d.onlineVoiceUrl,
            offlineVoice = p[K.ttsOffline] ?: d.offlineVoice,
            searchScope = SearchScope.entries.getOrElse(p[K.searchScope] ?: 0) { d.searchScope },
            searchSources = p[K.searchSources] ?: d.searchSources,
            preciseSearch = p[K.precise] ?: d.preciseSearch,
            subscriptionBase = p[K.base] ?: d.subscriptionBase,
            lastSubscriptionUpdate = p[K.updated] ?: 0,
            ignoredVersion = p[K.ignored] ?: 0,
            lastUpdateCheck = p[K.checked] ?: 0,
            volumeKeys = p[K.volumeKeys] ?: d.volumeKeys,
            sideMargin = p[K.margin] ?: d.sideMargin,
            paragraphGap = p[K.gap] ?: d.paragraphGap,
            indent = p[K.indent] ?: d.indent,
            readerFont = p[K.readerFont] ?: d.readerFont,
            chineseConvert = ChineseConvert.entries.getOrElse(p[K.convert] ?: 0) { d.chineseConvert },
            pageAnim = PageAnim.entries.getOrElse(p[K.anim] ?: 0) { d.pageAnim },
            autoPageSeconds = p[K.autoPage] ?: d.autoPageSeconds,
            shelfSort = ShelfSort.entries.getOrElse(p[K.shelfSort] ?: 0) { d.shelfSort },
            notifyUpdates = p[K.notifyUpdates] ?: d.notifyUpdates,
        )
    }

    val flow: Flow<Settings> = context.store.data.map(::decode)

    suspend fun current(): Settings = flow.first()

    /**
     * 自选搜索书源的读-改-写，整个过程在同一个存储事务里：快速连点几个书源，每一次都基于最新的集合，不会互相覆盖。
     * [pick] 为 true 时同时把搜索范围切到“自选”。
     */
    suspend fun editSearchSources(pick: Boolean, change: (Set<String>) -> Set<String>) {
        context.store.edit { p: androidx.datastore.preferences.core.MutablePreferences ->
            p[K.searchSources] = change(p[K.searchSources] ?: emptySet())
            if (pick) p[K.searchScope] = SearchScope.Custom.ordinal
        }
    }

    /** 备份用：所有设置的原始键值（带类型），不含订阅更新时间、检查更新这类本机状态。 */
    suspend fun exportRaw(): kotlinx.serialization.json.JsonObject {
        val skip = setOf(K.updated.name, K.checked.name, K.ignored.name)
        val map = context.store.data.first().asMap()
        return kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in map) {
                if (k.name in skip) continue
                val (t, value) = when (v) {
                    is Boolean -> "b" to kotlinx.serialization.json.JsonPrimitive(v)
                    is Int -> "i" to kotlinx.serialization.json.JsonPrimitive(v)
                    is Long -> "l" to kotlinx.serialization.json.JsonPrimitive(v)
                    is Float -> "f" to kotlinx.serialization.json.JsonPrimitive(v)
                    is String -> "s" to kotlinx.serialization.json.JsonPrimitive(v)
                    is Set<*> -> "ss" to kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) })
                    else -> continue
                }
                put(k.name, kotlinx.serialization.json.buildJsonObject { put("t", kotlinx.serialization.json.JsonPrimitive(t)); put("v", value) })
            }
        }
    }

    /** 恢复 [exportRaw] 导出的设置；认不出的键跳过。 */
    suspend fun importRaw(o: kotlinx.serialization.json.JsonObject) {
        context.store.edit { p: androidx.datastore.preferences.core.MutablePreferences ->
            for ((name, e) in o) {
                val obj = e as? kotlinx.serialization.json.JsonObject ?: continue
                val t = (obj["t"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                val v = obj["v"] ?: continue
                val prim = v as? kotlinx.serialization.json.JsonPrimitive
                runCatching {
                    when (t) {
                        "b" -> p[booleanPreferencesKey(name)] = prim!!.content.toBooleanStrict()
                        "i" -> p[intPreferencesKey(name)] = prim!!.content.toInt()
                        "l" -> p[longPreferencesKey(name)] = prim!!.content.toLong()
                        "f" -> p[floatPreferencesKey(name)] = prim!!.content.toFloat()
                        "s" -> p[stringPreferencesKey(name)] = prim!!.content
                        "ss" -> p[androidx.datastore.preferences.core.stringSetPreferencesKey(name)] =
                            (v as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }.toSet()
                    }
                }
            }
        }
    }

    suspend fun update(block: (Settings) -> Settings) {
        // 读、改、写都在同一个存储事务里：并发的另一处改动（比如自选书源、订阅更新时间）不会被这里读到的旧值盖掉。
        context.store.edit { p: androidx.datastore.preferences.core.MutablePreferences ->
            val next = block(decode(p))
            p[K.theme] = next.themeMode.ordinal
            p[K.dynamic] = next.dynamicColor
            p[K.font] = next.fontSize
            p[K.line] = next.lineSpacing
            p[K.palette] = next.palette.ordinal
            p[K.mode] = next.pageMode.ordinal
            p[K.awake] = next.keepScreenOn
            p[K.brightness] = next.brightness
            p[K.autoSwitch] = next.autoSwitchSource
            p[K.ttsRate] = next.readAloudRate
            p[K.ttsEngine] = next.voiceEngine.ordinal
            p[K.ttsUrl] = next.onlineVoiceUrl
            p[K.ttsOffline] = next.offlineVoice
            p[K.searchScope] = next.searchScope.ordinal
            p[K.searchSources] = next.searchSources
            p[K.precise] = next.preciseSearch
            p[K.base] = next.subscriptionBase
            p[K.updated] = next.lastSubscriptionUpdate
            p[K.ignored] = next.ignoredVersion
            p[K.checked] = next.lastUpdateCheck
            p[K.volumeKeys] = next.volumeKeys
            p[K.margin] = next.sideMargin
            p[K.gap] = next.paragraphGap
            p[K.indent] = next.indent
            p[K.readerFont] = next.readerFont
            p[K.convert] = next.chineseConvert.ordinal
            p[K.anim] = next.pageAnim.ordinal
            p[K.autoPage] = next.autoPageSeconds
            p[K.shelfSort] = next.shelfSort.ordinal
            p[K.notifyUpdates] = next.notifyUpdates
        }
    }
}
