package app.openread.data

import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 正文出库前的最后一道：用户的替换规则（净化广告句、屏蔽词）和繁简转换。阅读和听书都从 [Library.text] 拿正文，
 * 所以两边看到、听到的是同一份文字。缓存在磁盘上的是书源的原文，规则改了不用重新下载。
 */
class ContentFilter(db: AppDb, prefs: Prefs, scope: CoroutineScope) {
    private class Compiled(val rule: ReplaceRule, val regex: Regex?)

    @Volatile private var rules: List<Compiled> = emptyList()
    @Volatile private var convert = ChineseConvert.None
    private val loaded = CompletableDeferred<Unit>()

    /** 规则或转换方式变了就加一：阅读页据此重新排版已经加载的章节。 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    init {
        scope.launch {
            combine(db.replaceRules().observe(), prefs.flow.map { it.chineseConvert }.distinctUntilChanged()) { r, c -> r to c }
                .collect { (r, c) ->
                    rules = r.filter { it.enabled && it.pattern.isNotEmpty() }.map { Compiled(it, if (it.isRegex) runCatching { Regex(it.pattern) }.getOrNull() else null) }
                    convert = c
                    loaded.complete(Unit)
                    _version.update { it + 1 }
                }
        }
    }

    /** 等规则第一次读出来，免得刚启动时读到的章节没过滤。 */
    suspend fun ready() = loaded.await()

    /** 正文：先按规则替换，再做繁简转换。 */
    fun body(text: String, bookName: String): String {
        var t = text
        for (c in rules) {
            val r = c.rule
            if (r.scope.isNotBlank() && !bookName.contains(r.scope.trim())) continue
            t = when {
                !r.isRegex -> t.replace(r.pattern, r.replacement)
                c.regex != null -> runCatching { c.regex.replace(t, r.replacement) }.getOrDefault(t)
                else -> t // 写错的正则：跳过，不影响阅读
            }
        }
        return convert(t)
    }

    /** 章节名等短文字只做繁简转换。 */
    fun title(text: String): String = convert(text)

    private fun convert(text: String): String {
        if (convert == ChineseConvert.None || text.isEmpty() || Build.VERSION.SDK_INT < 29) return text
        val t = transliterator(convert) ?: return text
        return synchronized(t) { t.transliterate(text) }
    }

    private val transliterators = HashMap<ChineseConvert, Any?>()

    private fun transliterator(c: ChineseConvert): android.icu.text.Transliterator? = synchronized(transliterators) {
        if (Build.VERSION.SDK_INT < 29) return null
        transliterators.getOrPut(c) {
            runCatching { android.icu.text.Transliterator.getInstance(if (c == ChineseConvert.ToTraditional) "Hans-Hant" else "Hant-Hans") }.getOrNull()
        } as android.icu.text.Transliterator?
    }

    companion object {
        /** 繁简转换要用系统自带的 ICU 转换器（Android 10 起才有）。 */
        val convertSupported get() = Build.VERSION.SDK_INT >= 29
    }
}
