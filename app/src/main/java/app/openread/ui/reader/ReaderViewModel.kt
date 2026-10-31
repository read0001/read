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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.withPermit
import app.openread.data.BookmarkRow
import app.openread.data.isLocal
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
    /** 每次主动跳转（目录、书签、进度条、上下章按钮）加一：阅读页据此把画面移到 [position]，哪怕还在同一章。 */
    val jump: Int = 0,
    /** 正在缓存：已处理几章、共几章；没在缓存时为 null。 */
    val caching: Pair<Int, Int>? = null,
    /** 这本书已缓存的章数（打开缓存面板时统计，-1 = 还没统计）。 */
    val cached: Int = -1,
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

    /** 当前这本书的书签（换源后书的地址会变，跟着换）。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<BookmarkRow>> = _state.map { it.book?.bookUrl }.distinctUntilChanged()
        .flatMapLatest { url -> if (url == null) emptyFlow() else c.library.bookmarks(url) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 替换规则或繁简转换改了：已经加载的章节重新取一遍（缓存在磁盘上的是原文，不用重新下载）
        // 旧正文先留着，新的到了再换，页面不会先塌成转圈；所有已加载的章节都换（滚动模式里上面的章也是）
        viewModelScope.launch {
            c.library.filter.version.drop(1).collect {
                val s = _state.value
                val row = s.book ?: return@collect
                if (!s.loaded) return@collect
                val gen = generation
                for (k in texts.keys.toList()) {
                    val ch = s.chapters.getOrNull(k) ?: continue
                    if (texts[k] !is ChapterText.Ready || ch.isVolume) continue
                    viewModelScope.launch {
                        runCatching { c.library.text(row, ch) }.onSuccess { if (gen == generation && texts[k] is ChapterText.Ready) texts[k] = ChapterText.Ready(it) }
                    }
                }
                // 正在听这本书：从正在读的句子重新开始，听到的也换成新的文字
                val ra = ReadAloud.state.value
                if (ra.active && ra.playing && ra.bookUrl == row.bookUrl) ra.span?.let { ReadAloud.seek(ra.chapterIndex, it.paragraph, it.from) }
            }
        }
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
    fun go(index: Int, offset: Int = 0, jump: Boolean = false) {
        val i = index.coerceIn(0, _state.value.chapters.lastIndex.coerceAtLeast(0))
        _state.update { it.copy(position = Position(i, offset), jump = if (jump) it.jump + 1 else it.jump) }
        prefetch(i)
        if (offset != Int.MAX_VALUE) record(i, offset)
    }

    /** Remembers where the reader is; writes to disk after a short pause so flipping pages does not hammer the database. */
    fun record(index: Int, offset: Int) {
        val s = _state.value
        if (!s.loaded || s.chapters.isEmpty()) return
        tick()
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

    /** 章节名的显示形式（繁简转换）。 */
    fun title(t: String) = c.library.title(t)

    // ---- 阅读时长：页面在前台、两次翻页间隔不到 5 分钟，这段时间才算在读；听书时不算 ----

    private var resumedAt = 0L
    private var lastTick = 0L

    fun onResume() { resumedAt = System.currentTimeMillis(); lastTick = resumedAt }

    fun onPause() { tick(); resumedAt = 0L }

    private fun tick() {
        if (resumedAt == 0L) return
        val now = System.currentTimeMillis()
        val gap = now - lastTick
        lastTick = now
        if (gap !in 1..5 * 60_000L) return // 放着没动超过 5 分钟：这段不算
        val ms = gap
        val name = _state.value.book?.name ?: return
        val ra = ReadAloud.state.value
        if (ra.active && ra.playing && ra.bookUrl == _state.value.book?.bookUrl) return // 听书时页面跟着翻，不算阅读
        if (ms >= 1000) c.appScope.launch { c.library.addReadTime(name, ms) }
    }

    // ---- 书签 ----

    /** 在当前看到的位置加书签；摘录取那一段开头的几十个字。 */
    fun addBookmark() {
        val s = _state.value
        val book = s.book ?: return
        val ch = s.chapters.getOrNull(s.position.index) ?: return
        val v = visible?.takeIf { it.index == s.position.index }
        val text = (texts[s.position.index] as? ChapterText.Ready)?.text
        val paragraphs = text?.let { ReadAloud.paragraphs(title(ch.title), it) }
        val excerpt = if (paragraphs != null && v != null) paragraphs.getOrNull(v.paragraph)?.drop(v.char)?.take(60).orEmpty() else ""
        val offset = if (paragraphs != null && v != null) Paginator.paragraphStarts(paragraphs).getOrElse(v.paragraph) { 0 } + v.char
        else s.position.offset.takeIf { it != Int.MAX_VALUE } ?: 0
        viewModelScope.launch {
            c.library.addBookmark(BookmarkRow(bookUrl = book.bookUrl, chapterIndex = s.position.index, chapterTitle = ch.title, offset = offset, excerpt = excerpt.ifBlank { ch.title }, createdAt = System.currentTimeMillis()))
            _state.update { it.copy(message = "已添加书签") }
        }
    }

    fun deleteBookmark(id: Long) { viewModelScope.launch { c.library.deleteBookmark(id) } }

    fun openBookmark(b: BookmarkRow) = go(b.chapterIndex, b.offset, jump = true)

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

    private var cacheJob: Job? = null

    /** 统计这本书已经缓存了几章（缓存面板打开时）。 */
    fun countCached() {
        val s = _state.value
        val row = s.book ?: return
        viewModelScope.launch { _state.update { it.copy(cached = c.library.cachedCount(row, s.chapters)) } }
    }

    /**
     * 在后台下载章节，好离线读：[from] 起的 [count] 章（null = 到最后一章）。已经缓存的跳过；一章失败不影响后面的。
     */
    fun cache(from: Int, count: Int?) {
        val s = _state.value
        val row = s.book ?: return
        if (cacheJob?.isActive == true) return
        val range = from.coerceAtLeast(0)..minOf(if (count == null) s.chapters.lastIndex else from + count - 1, s.chapters.lastIndex)
        if (range.isEmpty()) return
        cacheJob = viewModelScope.launch {
            var ok = 0
            var failed = 0
            val total = range.count()
            _state.update { it.copy(caching = 0 to total) }
            try {
                val gate = kotlinx.coroutines.sync.Semaphore(3)
                kotlinx.coroutines.coroutineScope {
                    for (i in range) {
                        val ch = s.chapters[i]
                        launch {
                            gate.withPermit {
                                if (ch.isVolume || c.library.isCached(row.bookUrl, ch)) ok++
                                else runCatching { c.library.download(row, ch) }.onSuccess { ok++ }.onFailure { failed++ }
                                _state.update { it.copy(caching = (ok + failed) to total) }
                            }
                        }
                    }
                }
                _state.update { it.copy(message = "已缓存 $ok 章" + if (failed > 0) "，$failed 章下载失败" else "") }
            } finally {
                _state.update { it.copy(caching = null) }
                countCached()
            }
        }
    }

    fun cancelCache() { cacheJob?.cancel() }

    /** 删掉这本书缓存的正文；正在看的几章重新下载。 */
    fun clearCache() {
        val row = _state.value.book ?: return
        cacheJob?.cancel()
        viewModelScope.launch {
            c.library.clearCache(row)
            _state.update { it.copy(message = "已删除本书缓存") }
            countCached()
        }
    }

    // ---- changing source -----------------------------------------------------------------------------------------

    /** Other sources with this book, for the manual picker; the list grows as sources answer. */
    /** 换源面板的搜索：关掉面板不会中断，再打开复用（见 Library.pickerSearch）。 */
    fun pickerSearch(refresh: Boolean = false) = _state.value.book?.let { c.library.pickerSearch(it, refresh) }

    /** [others] are the sources the picker found; once moved, the new source is checked against them (see [checkLatest]). */
    fun switchTo(target: SearchBook, auto: Boolean = false, others: List<SearchBook> = emptyList()) {
        val row = _state.value.book ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { c.library.switchTo(row, target) }
                .onSuccess { fresh ->
                    generation++
                    cacheJob?.cancel() // 缓存任务拿的是旧书的目录，换源后停掉
                    currentUrl = fresh.bookUrl
                    val chapters = c.library.chapters(fresh.bookUrl)
                    texts.clear()
                    val p = Position(fresh.chapterIndex, fresh.chapterOffset)
                    _state.update { it.copy(book = fresh, chapters = chapters, position = p, busy = false, message = (if (auto) "已自动换源到 " else "已换到 ") + target.originName) }
                    prefetch(p.index)
                    // 固定了书源的书，换源后不再核对最新章、不再推荐别的书源。
                    if (!fresh.sourcePinned) checkLatest(target, fresh.bookUrl, others.filter { it.origin != target.origin })
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

    /** 固定（或取消固定）这本书的书源。以点击那一刻的当前状态为准，连点两下也会正确地来回切换。 */
    fun togglePin() {
        viewModelScope.launch {
            val url = currentUrl
            val row = c.library.book(url) ?: return@launch // 书已经不在书架上（或已换到别的 key）
            val pin = !row.sourcePinned
            c.library.setSourcePinned(url, pin)
            if (currentUrl != url) return@launch // 写库期间换了书源：界面状态由换源那条路径更新
            // 刚固定：正在进行的核对停掉，已经出现的推荐撤掉
            if (pin) checkJob?.cancel()
            _state.update { it.copy(book = it.book?.copy(sourcePinned = pin), offer = if (pin) null else it.offer) }
        }
    }

    /** A chapter would not load: once per reading session, quietly look for the same book elsewhere. */
    private fun autoSwitch() {
        if (_state.value.book?.sourcePinned == true || _state.value.book?.isLocal == true) return // 书源被固定了（或是本地书）：读不出来也不自动换
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
