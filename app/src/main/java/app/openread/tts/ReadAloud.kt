package app.openread.tts

import android.content.Context
import app.openread.AppContainer
import app.openread.data.ChapterRow
import app.openread.data.VoiceEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reads the book aloud. One session for the whole process: the reader starts and pauses it, [ReadAloudService] keeps the
 * process alive and shows the notification, so listening goes on with the screen off. When a chapter ends the next one is
 * loaded and read, and the reader follows the chapter through [state].
 *
 * Who speaks is a [Voice]: the device's speech engine, a downloaded offline model, or an online service, whichever works (or
 * whichever the reader picked).
 * Each chapter is handed over paragraph by paragraph (title first), so the paragraph being read is known and a pause can
 * resume from it. Speech can't be paused in place, so a pause stops it and a resume starts again from that paragraph.
 */
object ReadAloud {
    data class State(
        /** A session exists: reading, or paused and waiting to be resumed. */
        val active: Boolean = false,
        val playing: Boolean = false,
        val bookUrl: String? = null,
        val book: String = "",
        val chapterIndex: Int = -1,
        val chapter: String = "",
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val SENTENCE_END = charArrayOf('。', '！', '？', '…', '”', '；')

    private var context: Context? = null
    private var container: AppContainer? = null
    private var chapters: List<ChapterRow> = emptyList()
    private var bookUrl = ""
    private var rate = 1f
    private var job: Job? = null

    private var system: SystemVoice? = null
    private var online: HttpVoice? = null
    private var offline: OfflineVoice? = null
    private var onlineUrl = ""
    private var voice: Voice? = null

    /** Voices that failed in this process; "auto" skips them from then on. */
    private val broken = mutableSetOf<Kind>()

    /** The paragraph whose speech started last; a resume starts there. */
    @Volatile private var paragraph = 0

    /** Starts reading [list] from [offset] in chapter [index]; replaces whatever was playing. */
    fun play(ctx: Context, app: AppContainer, book: String, list: List<ChapterRow>, index: Int, offset: Int, speed: Float) {
        context = ctx.applicationContext
        container = app
        chapters = list
        bookUrl = book
        rate = speed
        OfflineModel.attach(ctx)
        restart(index, offset, fromParagraph = null)
        ReadAloudService.start(ctx.applicationContext)
    }

    fun pause() {
        if (!_state.value.playing) return
        job?.cancel()
        voice?.stop()
        _state.update { it.copy(playing = false) }
    }

    fun resume() {
        val s = _state.value
        if (!s.active || s.playing) return
        restart(s.chapterIndex, 0, fromParagraph = paragraph)
    }

    fun toggle() = if (_state.value.playing) pause() else resume()

    fun stop() {
        job?.cancel()
        job = null
        voice?.stop()
        dropOffline()
        _state.value = State()
    }

    fun setRate(speed: Float) {
        rate = speed
        system?.setRate(speed)
        online?.setRate(speed)
        offline?.setRate(speed)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun restart(index: Int, offset: Int, fromParagraph: Int?) {
        job?.cancel()
        voice?.stop()
        _state.update { it.copy(active = true, playing = true, bookUrl = bookUrl, message = null) }
        val app = container ?: return
        job = app.appScope.launch { readChapters(app, index, offset, fromParagraph) }
    }

    private suspend fun readChapters(app: AppContainer, first: Int, firstOffset: Int, firstParagraph: Int?) {
        var index = first
        var offset = firstOffset
        var from = firstParagraph
        while (true) {
            val ch = chapters.getOrNull(index) ?: return finish()
            val row = app.library.book(bookUrl) ?: return fail("这本书不在书架上")
            if (ch.isVolume) { index++; offset = 0; from = null; continue }
            _state.update { it.copy(chapterIndex = index, book = row.name, chapter = ch.title, playing = true, active = true) }

            val raw = runCatching { app.library.text(row, ch) }.getOrNull() ?: return fail("「${ch.title}」读不出来，换个书源试试")
            val paragraphs = listOf(ch.title) + raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            paragraph = (from ?: if (offset <= 0) 0 else paragraphAt(raw, offset) + 1).coerceIn(0, paragraphs.lastIndex)

            val settings = app.prefs.current()
            val order = candidates(settings.voiceEngine, settings.offlineVoice)
            var at = 0
            while (true) {
                val kind = order[at]
                val result = if (kind == Kind.Offline && !(settings.offlineVoice && OfflineModel.ready)) {
                    Spoken.Failed(if (settings.offlineVoice) "离线语音还没有下载完，请到阅读设置里查看" else "离线语音没有开启，请到阅读设置里开启")
                } else {
                    val v = voiceOf(kind, app, settings.onlineVoiceUrl) ?: return fail("语音不可用")
                    voice = v
                    v.speak(pieces(paragraphs, paragraph, v.maxChunk)) { piece -> paragraph = piece.paragraph }
                }
                if (result is Spoken.Done) break
                val why = (result as Spoken.Failed).reason
                // In "auto" a voice that failed is not tried again in this process (the online one is always worth another go).
                if (settings.voiceEngine == VoiceEngine.Auto && kind != Kind.Online) broken += kind
                if (at + 1 >= order.size) return fail(if (order.size > 1) "语音都不可用：$why" else why)
                // Carry on from the same paragraph with the next voice.
                at++
                _state.update { it.copy(message = "$why，已改用${order[at].label}语音") }
            }
            index++
            offset = 0
            from = null
        }
    }

    private enum class Kind(val label: String) { System("系统"), Offline("离线"), Online("在线") }

    /** In "auto" the device's own voice goes first, then the offline one if the reader turned it on and it is downloaded, then the online one. */
    private fun candidates(engine: VoiceEngine, offlineOn: Boolean): List<Kind> = when (engine) {
        VoiceEngine.System -> listOf(Kind.System)
        VoiceEngine.Offline -> listOf(Kind.Offline)
        VoiceEngine.Online -> listOf(Kind.Online)
        VoiceEngine.Auto -> listOfNotNull(
            Kind.System.takeIf { it !in broken },
            Kind.Offline.takeIf { offlineOn && OfflineModel.ready && it !in broken },
            Kind.Online,
        )
    }

    private fun voiceOf(kind: Kind, app: AppContainer, url: String): Voice? {
        val ctx = context ?: return null
        return when (kind) {
            Kind.System -> system ?: SystemVoice(ctx, rate).also { system = it }
            Kind.Offline -> offline ?: OfflineVoice(rate).also { offline = it }
            Kind.Online -> {
                if (online == null || onlineUrl != url) {
                    online?.release()
                    online = HttpVoice(ctx, app.http.client, url, rate)
                    onlineUrl = url
                }
                online
            }
        }
    }

    /** The pieces of [paragraphs] from [from] on, cut to what [max] characters can carry, leaving out text with nothing to say. */
    private fun pieces(paragraphs: List<String>, from: Int, max: Int): List<Piece> =
        (from..paragraphs.lastIndex).flatMap { pi ->
            chunks(paragraphs[pi], max).filter { c -> c.any(Char::isLetterOrDigit) }.map { Piece(it, pi) }
        }

    /** Index into the chapter's paragraphs (without the title) of the character [offset]. */
    private fun paragraphAt(raw: String, offset: Int): Int {
        var pos = 0
        var n = 0
        for (line in raw.lines()) {
            if (line.isBlank()) { pos += line.length + 1; continue }
            if (pos >= offset) return n
            n++
            pos += line.length + 1
        }
        return n
    }

    private fun chunks(p: String, max: Int): List<String> {
        if (p.length <= max) return listOf(p)
        val out = ArrayList<String>()
        var rest = p
        while (rest.length > max) {
            val at = rest.lastIndexOfAny(SENTENCE_END, max - 1)
            val cut = if (at < max / 2) max else at + 1
            out += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** The service notices the session ending from [state] and stops itself. */
    private fun finish() {
        dropOffline()
        _state.value = State()
    }

    private fun fail(message: String) {
        dropOffline()
        _state.value = State(message = message)
    }

    /** The offline model holds a few hundred MB while loaded, so it is let go when the session ends (a pause keeps it). */
    private fun dropOffline() {
        offline?.release()
        offline = null
    }
}
