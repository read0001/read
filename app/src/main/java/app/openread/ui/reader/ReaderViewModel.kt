package app.openread.ui.reader

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openread.AppContainer
import app.openread.data.BookRow
import app.openread.data.ChapterRow
import app.openread.data.Settings
import app.openread.data.SwitchOffer
import app.openread.data.betterLine
import app.openread.ui.components.DataAsk
import app.openread.data.truncatedNote
import app.openread.engine.SearchBook
import app.openread.tts.ReadAloud
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ChapterText {
    data object Loading : ChapterText
    class Ready(val text: String) : ChapterText
    class Failed(val message: String) : ChapterText
}

/** Where the reader is: a chapter and a character offset in it. [Int.MAX_VALUE] means "the last page". */
data class Position(val index: Int, val offset: Int)

data class ReaderState(
    val book: BookRow? = null,
    val chapters: List<ChapterRow> = emptyList(),
    val position: Position = Position(0, 0),
    val loaded: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
    /** Set when checking the sources found a clearly better one: the reader is asked whether to move there. */
    val offer: SwitchOffer? = null,
)

class ReaderViewModel(private val c: AppContainer, bookUrl: String, private val startIndex: Int?) : ViewModel() {
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state
    val settings: StateFlow<Settings> = c.prefs.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Chapter text by index; a Compose state map so pages recompose when a chapter arrives. */
    val texts = mutableStateMapOf<Int, ChapterText>()

    /** The shelf key changes when the book moves to another source, so progress is always saved under this one. */
    private var currentUrl = bookUrl

    /** Bumped on a source switch so loads that were already running for the old source are ignored when they land. */
    private var generation = 0
    private var saveJob: Job? = null
    private var triedAutoSwitch = false

    init {
        viewModelScope.launch {
            val row = c.library.book(bookUrl)
            if (row == null) { _state.value = ReaderState(loaded = true, error = "这本书不在书架上"); return@launch }
            var chapters = c.library.chapters(bookUrl)
            if (chapters.isEmpty()) chapters = runCatching { c.library.reloadToc(row) }.getOrDefault(emptyList())
            if (chapters.isEmpty()) { _state.value = ReaderState(book = row, loaded = true, error = "目录为空，换个书源试试"); return@launch }
            val idx = (startIndex ?: row.chapterIndex).coerceIn(0, chapters.lastIndex)
            val offset = if (startIndex == null) row.chapterOffset else 0
            _state.value = ReaderState(row, chapters, Position(idx, offset), loaded = true)
            ensure(idx)
        }
    }

    fun ensure(idx: Int, force: Boolean = false) {
        val s = _state.value
        val row = s.book ?: return
        val ch = s.chapters.getOrNull(idx) ?: return
        if (!force && texts[idx] != null && texts[idx] !is ChapterText.Failed) return
        // A volume heading has no body of its own; show the title alone.
        if (ch.isVolume) { texts[idx] = ChapterText.Ready(""); return }
        texts[idx] = ChapterText.Loading
        val gen = generation
        viewModelScope.launch {
            val result = runCatching { c.library.text(row, ch, refresh = force) }
            if (gen != generation) return@launch
            result.onSuccess { texts[idx] = ChapterText.Ready(it) }
                .onFailure { e ->
                    texts[idx] = ChapterText.Failed(e.message ?: e.javaClass.simpleName)
                    if (idx == _state.value.position.index) autoSwitch()
                }
        }
    }

    /** Loads the chapters around [idx] so page turns across chapter edges never wait. */
    fun prefetch(idx: Int) {
        ensure(idx)
        ensure(idx + 1)
        ensure(idx - 1)
        if (idx + 2 <= _state.value.chapters.lastIndex) ensure(idx + 2)
    }

    /**
     * Jumps to a chapter. With [Int.MAX_VALUE] the position is left as "end of chapter" until the pager lands on the
     * last page and reports the real offset; anything else is remembered straight away.
     */
    fun go(index: Int, offset: Int = 0) {
        val i = index.coerceIn(0, _state.value.chapters.lastIndex.coerceAtLeast(0))
        _state.update { it.copy(position = Position(i, offset)) }
        prefetch(i)
        if (offset != Int.MAX_VALUE) record(i, offset)
    }

    /** Remembers where the reader is; writes to disk after a short pause so flipping pages does not hammer the database. */
    fun record(index: Int, offset: Int) {
        val s = _state.value
        if (!s.loaded || s.chapters.isEmpty()) return
        val title = s.chapters.getOrNull(index)?.title
        _state.update { it.copy(position = Position(index, offset)) }
        val url = currentUrl
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            c.library.saveProgress(url, index, offset, title)
        }
    }

    /** Called when the reader closes: the pending write must not be lost with the view model's scope. */
    fun flush() {
        val s = _state.value
        if (!s.loaded || s.chapters.isEmpty()) return
        val p = s.position
        val url = currentUrl
        val title = s.chapters.getOrNull(p.index)?.title
        saveJob?.cancel()
        c.appScope.launch { c.library.saveProgress(url, p.index, if (p.offset == Int.MAX_VALUE) 0 else p.offset, title) }
    }

    fun reload(idx: Int) = ensure(idx, force = true)

    /** The first text on screen: character [char] of paragraph [paragraph] of chapter [index]. */
    class Visible(val index: Int, val paragraph: Int, val char: Int)

    @Volatile private var visible: Visible? = null

    /** The page or scroll position changed: this is now the first text the reader sees. */
    fun noteVisible(index: Int, paragraph: Int, char: Int) {
        visible = Visible(index, paragraph, char)
    }

    /**
     * The listen button. While a session for this book exists (reading or paused) it pauses or resumes it, and the session
     * already follows the page the reader is on; otherwise reading starts from the first text on screen.
     */
    fun readAloud(context: Context) {
        val s = _state.value
        val book = s.book ?: return
        val ra = ReadAloud.state.value
        if (ra.active && ra.bookUrl == book.bookUrl) {
            ReadAloud.toggle()
            return
        }
        val at = visible?.takeIf { it.index == s.position.index } ?: Visible(s.position.index, 0, 0)
        ReadAloud.play(context, c, book.bookUrl, s.chapters, at.index, at.paragraph, at.char, settings.value.readAloudRate)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun updateSettings(block: (Settings) -> Settings) {
        viewModelScope.launch { c.prefs.update(block) }
    }

    /** Downloads the next [count] chapters in the background so the book can be read offline. */
    fun cacheAhead(count: Int = 50) {
        val s = _state.value
        val row = s.book ?: return
        val from = s.position.index
        viewModelScope.launch {
            _state.update { it.copy(message = "开始缓存后面 $count 章") }
            var ok = 0
            for (i in from..minOf(from + count, s.chapters.lastIndex)) {
                val ch = s.chapters[i]
                if (ch.isVolume || c.library.isCached(row.bookUrl, ch)) { ok++; continue }
                runCatching { c.library.text(row, ch) }.onSuccess { ok++ }
            }
            _state.update { it.copy(message = "已缓存 $ok 章") }
        }
    }

    // ---- changing source -----------------------------------------------------------------------------------------

    /** Other sources with this book, for the manual picker; the list grows as sources answer. */
    fun alternatives(): Flow<List<SearchBook>> = _state.value.book?.let { c.library.alternativesFlow(it) } ?: emptyFlow()

    /** [others] are the sources the picker found; once moved, the new source is checked against them (see [checkLatest]). */
    fun switchTo(target: SearchBook, auto: Boolean = false, others: List<SearchBook> = emptyList()) {
        val row = _state.value.book ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { c.library.switchTo(row, target) }
                .onSuccess { fresh ->
                    generation++
                    currentUrl = fresh.bookUrl
                    val chapters = c.library.chapters(fresh.bookUrl)
                    texts.clear()
                    val p = Position(fresh.chapterIndex, fresh.chapterOffset)
                    _state.update { it.copy(book = fresh, chapters = chapters, position = p, busy = false, message = (if (auto) "已自动换源到 " else "已换到 ") + target.originName) }
                    prefetch(p.index)
                    checkLatest(target, fresh.bookUrl, others.filter { it.origin != target.origin })
                }
                .onFailure { e -> _state.update { it.copy(busy = false, message = "换源失败：${e.message}") } }
        }
    }

    private var checkJob: Job? = null
    private var lastOthers: List<SearchBook> = emptyList()

    /** Checking every other source is heavy: on mobile data it waits for the reader's yes. */
    val dataAsk = DataAsk { c.network.unmetered() }

    /**
     * The source the book was just moved to may end in teasers padded with other novels (see LatestCheck), so its newest
     * chapters are checked now, in the background. If they are teasers, the other sources are checked too and, when one is
     * clearly better, the reader is asked whether to move there; nothing is switched without their yes.
     */
    private fun checkLatest(target: SearchBook, url: String, others: List<SearchBook>) {
        checkJob?.cancel()
        lastOthers = others
        checkJob = viewModelScope.launch {
            val mine = withTimeoutOrNull(90_000) { c.library.checkLatest(target) } ?: return@launch
            if (mine.complete || currentUrl != url) return@launch // the reader may have moved on to yet another source meanwhile
            if (others.isNotEmpty() && !dataAsk.allowed()) {
                // The question may have been open a long time: say it only if this is still the book's source.
                if (currentUrl == url) _state.update { it.copy(message = truncatedNote(target.originName, mine) + "当前不是 Wi-Fi，没有核对其他书源。") }
                return@launch
            }
            val found = if (others.isEmpty()) null else c.library.findCompleteSource(mine, others) { _, _ -> }
            if (currentUrl != url) return@launch
            val better = found?.better
            _state.update {
                when {
                    better != null -> it.copy(offer = SwitchOffer(better.source, truncatedNote(target.originName, mine) + betterLine(better) + "\n\n要切换过去吗？"))
                    found == null -> it.copy(message = truncatedNote(target.originName, mine) + "可以再换个书源。")
                    else -> it.copy(message = truncatedNote(target.originName, mine) + "已核对 ${found.checked} 个其他书源，没有更完整的。")
                }
            }
        }
    }

    fun acceptOffer() {
        val o = _state.value.offer ?: return
        _state.update { it.copy(offer = null) }
        switchTo(o.target, others = lastOthers)
    }

    fun dismissOffer() = _state.update { it.copy(offer = null) }

    /** A chapter would not load: once per reading session, quietly look for the same book elsewhere. */
    private fun autoSwitch() {
        if (triedAutoSwitch || !settings.value.autoSwitchSource) return
        triedAutoSwitch = true
        val row = _state.value.book ?: return
        viewModelScope.launch {
            _state.update { it.copy(message = "这个书源读不出来，正在找别的书源…", busy = true) }
            val candidates = runCatching { c.library.alternatives(row) }.getOrDefault(emptyList())
            if (candidates.isEmpty()) { _state.update { it.copy(busy = false, message = "没有找到可换的书源") }; return@launch }
            _state.update { it.copy(busy = false) }
            switchTo(candidates.first(), auto = true, others = candidates.drop(1))
        }
    }
}
