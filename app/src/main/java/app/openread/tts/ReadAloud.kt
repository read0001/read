package app.openread.tts

import android.content.Context
import app.openread.AppContainer
import app.openread.data.ChapterRow
import app.openread.data.VoiceEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *
 * A chapter is [paragraphs] (the title first), handed to the voice sentence by sentence, so what is being read is always
 * known: [State.span] says which characters of which paragraph. The reader highlights that and keeps it on screen, and tells
 * [seek] when the reader moves to another page, so reading goes on from there. Speech can't be paused in place, so a pause
 * stops it and a resume starts again from the sentence that was being read.
 */
object ReadAloud {
    /** The speeds the reader can pick, as multiples of the voice's normal speed, and the size of one step. */
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 3f
    const val RATE_STEP = 0.25f

    /** Characters [from] until [to] of paragraph [paragraph] of the chapter being read. */
    data class Span(val paragraph: Int, val from: Int, val to: Int)

    data class State(
        /** A session exists: reading, or paused and waiting to be resumed. */
        val active: Boolean = false,
        val playing: Boolean = false,
        val bookUrl: String? = null,
        val book: String = "",
        val chapterIndex: Int = -1,
        val chapter: String = "",
        /** What is being read (or, while paused or loading, where reading will go on from); a zero-width span has nothing to highlight yet. */
        val span: Span? = null,
        val message: String? = null,
    )

    /** A place in the book: a chapter, a paragraph in it and a character in that paragraph. */
    private class Point(val chapter: Int, val paragraph: Int, val char: Int)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val SENTENCE_END = charArrayOf('。', '！', '？', '…', '；', '!', '?', ';')
    private val CLOSERS = charArrayOf('”', '’', '」', '』', '）', ')', '"', '\'')
    private val COMMAS = charArrayOf('，', '、', '：', ',')
    private const val MIN_PIECE = 12

    private var context: Context? = null
    private var container: AppContainer? = null
    private var chapters: List<ChapterRow> = emptyList()
    private var bookUrl = ""
    private var rate = 1f
    private var job: Job? = null
    private var rateJob: Job? = null

    private var system: SystemVoice? = null
    private var online: HttpVoice? = null
    private var offline: OfflineVoice? = null
    private var onlineUrl = ""
    private var voice: Voice? = null

    /** Voices that failed in this process; "auto" skips them from then on. */
    private val broken = mutableSetOf<Kind>()

    /** Bumped whenever reading restarts or stops; a voice still reporting for an older one is ignored. */
    @Volatile private var session = 0

    /** Where the sentence whose speech started last begins; a resume, a seek while paused and a speed change start there. */
    @Volatile private var resumeAt = Point(0, 0, 0)

    /** What a chapter says, paragraph by paragraph: the title, then every non-blank line of the text, trimmed. */
    fun paragraphs(title: String, body: String): List<String> =
        listOf(title) + body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** Starts reading [list] from character [char] of paragraph [paragraph] in chapter [index]; replaces whatever was playing. */
    fun play(ctx: Context, app: AppContainer, book: String, list: List<ChapterRow>, index: Int, paragraph: Int, char: Int, speed: Float) {
        context = ctx.applicationContext
        container = app
        chapters = list
        bookUrl = book
        rate = speed
        OfflineModel.attach(ctx)
        restart(Point(index, paragraph, char))
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
        restart(resumeAt)
    }

    fun toggle() = if (_state.value.playing) pause() else resume()

    /** The reader shows another place: reading goes on from there (a paused session will resume there). */
    fun seek(index: Int, paragraph: Int, char: Int) {
        val s = _state.value
        if (!s.active) return
        val at = Point(index, paragraph, char)
        if (s.playing) restart(at) else moveTo(at)
    }

    fun stop() {
        session++
        job?.cancel()
        job = null
        rateJob?.cancel()
        voice?.stop()
        dropOffline()
        _state.value = State()
    }

    /**
     * A new speed applies at once. The online voice changes the playing audio itself; the others have the text queued at the
     * old speed, so they start the current sentence again (after a short wait, as the speed slider fires many changes).
     */
    fun setRate(speed: Float) {
        if (speed == rate) return
        rate = speed
        system?.setRate(speed)
        online?.setRate(speed)
        offline?.setRate(speed)
        val app = container ?: return
        if (_state.value.playing && voice !is HttpVoice) {
            rateJob?.cancel()
            rateJob = app.appScope.launch {
                delay(500)
                if (_state.value.playing) restart(resumeAt)
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Marks [at] as the place reading will (re)start from, without speaking. */
    private fun moveTo(at: Point) {
        resumeAt = at
        _state.update { it.copy(chapterIndex = at.chapter, chapter = chapters.getOrNull(at.chapter)?.title ?: it.chapter, span = Span(at.paragraph, at.char, at.char)) }
    }

    private fun restart(at: Point) {
        job?.cancel()
        rateJob?.cancel()
        voice?.stop()
        moveTo(at)
        _state.update { it.copy(active = true, playing = true, bookUrl = bookUrl, message = null) }
        val app = container ?: return
        val mine = ++session
        job = app.appScope.launch { readChapters(app, at, mine) }
    }

    private suspend fun readChapters(app: AppContainer, start: Point, mine: Int) {
        var index = start.chapter
        var from = start.paragraph
        var fromChar = start.char
        while (true) {
            val ch = chapters.getOrNull(index) ?: return finish()
            val row = app.library.book(bookUrl) ?: return fail("这本书不在书架上")
            if (ch.isVolume) { index++; from = 0; fromChar = 0; continue }
            _state.update { it.copy(chapterIndex = index, book = row.name, chapter = ch.title, playing = true, active = true) }

            val raw = runCatching { app.library.text(row, ch) }.getOrNull() ?: return fail("「${ch.title}」读不出来，换个书源试试")
            val paragraphs = paragraphs(ch.title, raw)
            val first = from.coerceIn(0, paragraphs.lastIndex)
            moveTo(Point(index, first, if (first == from) fromChar else 0))

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
                    val here = resumeAt
                    v.speak(pieces(paragraphs, here.paragraph, here.char, v.maxChunk)) { piece ->
                        if (session == mine) {
                            resumeAt = Point(index, piece.paragraph, piece.from)
                            _state.update { it.copy(span = Span(piece.paragraph, piece.from, piece.to)) }
                        }
                    }
                }
                if (result is Spoken.Done) break
                val why = (result as Spoken.Failed).reason
                // In "auto" a voice that failed is not tried again in this process (the online one is always worth another go).
                if (settings.voiceEngine == VoiceEngine.Auto && kind != Kind.Online) broken += kind
                if (at + 1 >= order.size) return fail(if (order.size > 1) "语音都不可用：$why" else why)
                // Carry on from the same sentence with the next voice.
                at++
                _state.update { it.copy(message = "$why，已改用${order[at].label}语音") }
            }
            index++
            from = 0
            fromChar = 0
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

    /**
     * The pieces of [paragraphs] from character [fromChar] of paragraph [from] on: one per sentence (see [chunks]), leaving out
     * text with nothing to say.
     */
    private fun pieces(paragraphs: List<String>, from: Int, fromChar: Int, max: Int): List<Piece> {
        val out = ArrayList<Piece>()
        for (pi in from..paragraphs.lastIndex) {
            val text = paragraphs[pi]
            for (r in chunks(text, if (pi == from) fromChar.coerceIn(0, text.length) else 0, max)) {
                val piece = text.substring(r.first, r.last + 1)
                if (piece.any(Char::isLetterOrDigit)) out += Piece(piece, pi, r.first, r.last + 1)
            }
        }
        return out
    }

    /**
     * [text] from [from] on as ranges that each end at a sentence end, so that what is highlighted and what a page turn splits
     * is one sentence. The end marks and closing quotes that follow a sentence end belong to that sentence; fragments shorter
     * than [MIN_PIECE] are joined to the next one, and nothing is longer than [max] (cut at a comma when there is one).
     */
    private fun chunks(text: String, from: Int, max: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = from
        var i = from
        while (i < text.length) {
            val end = text[i] in SENTENCE_END
            i++
            if (end) while (i < text.length && (text[i] in SENTENCE_END || text[i] in CLOSERS)) i++
            if (i - start >= max) {
                // Too long without ending: cut at the last comma if there is one in the later half (a sentence end is cut there as is).
                val comma = if (end) -1 else text.lastIndexOfAny(COMMAS, i - 1)
                if (comma > start + max / 2) i = comma + 1
                out += start until i
                start = i
            } else if (end && i - start >= MIN_PIECE) {
                out += start until i
                start = i
            }
        }
        if (start < text.length) out += start until text.length
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
