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
    val subscriptionBase: String = DEFAULT_BASE,
    val lastSubscriptionUpdate: Long = 0,
    val ignoredVersion: Int = 0,
    val lastUpdateCheck: Long = 0,
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
        val base = stringPreferencesKey("sub_base")
        val updated = longPreferencesKey("sub_updated")
        val ignored = intPreferencesKey("ignored_version")
        val checked = longPreferencesKey("update_checked")
    }

    val flow: Flow<Settings> = context.store.data.map { p ->
        val d = Settings()
        Settings(
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
            subscriptionBase = p[K.base] ?: d.subscriptionBase,
            lastSubscriptionUpdate = p[K.updated] ?: 0,
            ignoredVersion = p[K.ignored] ?: 0,
            lastUpdateCheck = p[K.checked] ?: 0,
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun update(block: (Settings) -> Settings) {
        val next = block(current())
        context.store.edit { p: androidx.datastore.preferences.core.MutablePreferences ->
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
            p[K.base] = next.subscriptionBase
            p[K.updated] = next.lastSubscriptionUpdate
            p[K.ignored] = next.ignoredVersion
            p[K.checked] = next.lastUpdateCheck
        }
    }
}
