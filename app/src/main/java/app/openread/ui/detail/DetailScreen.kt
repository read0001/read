package app.openread.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.openread.AppContainer
import app.openread.container
import app.openread.data.BookRow
import app.openread.data.toBook
import app.openread.data.toChapter
import app.openread.engine.Book
import app.openread.engine.Chapter
import app.openread.engine.SearchBook
import app.openread.ui.components.BookCover
import app.openread.ui.components.factory
import app.openread.ui.nav.Handoff
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    val inShelf: Boolean = false,
    val readIndex: Int = 0,
    val alternatives: List<SearchBook>? = null,
    val finding: Boolean = false,
    val switching: Boolean = false,
    val message: String? = null,
)

class DetailViewModel(private val c: AppContainer, private val bookUrl: String) : ViewModel() {
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state
    private var origin: SearchBook? = null

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = DetailState()
            val row = c.library.book(bookUrl)
            if (row != null) {
                val ch = c.library.chapters(bookUrl).map { it.toChapter() }
                _state.value = DetailState(loading = false, book = row.toBook(), chapters = ch, inShelf = true, readIndex = row.chapterIndex)
                return@launch
            }
            val hit = Handoff.search?.takeIf { it.bookUrl == bookUrl }
            if (hit == null) { _state.value = DetailState(loading = false, error = "找不到这本书的来源，请重新搜索"); return@launch }
            origin = hit
            openWithFallback(hit, Handoff.alternates)
        }
    }

    private suspend fun open(hit: SearchBook) {
        runCatching { c.library.open(hit) }
            .onSuccess { (b, ch) -> _state.update { it.copy(loading = false, error = null, book = b, chapters = ch, switching = false) } }
            .onFailure { e -> _state.update { it.copy(loading = false, switching = false, error = "${hit.originName}：${e.message ?: "打开失败"}") } }
    }

    /**
     * Opens [hit]; if that source is down, the same book from the next source in [alternates] is tried (up to five in all),
     * so one broken site among many does not leave the reader on an error page.
     */
    private suspend fun openWithFallback(hit: SearchBook, alternates: List<SearchBook>) {
        val candidates = listOf(hit) + alternates.take(4)
        var firstError: String? = null
        for ((i, cand) in candidates.withIndex()) {
            val r = runCatching { c.library.open(cand) }
            val ok = r.getOrNull()?.takeIf { it.second.isNotEmpty() }
            if (ok != null) {
                _state.update { it.copy(loading = false, error = null, book = ok.first, chapters = ok.second, switching = false, message = if (i > 0) "${hit.originName} 打不开，已换到 ${cand.originName}" else null) }
                return
            }
            firstError = firstError ?: "${cand.originName}：${r.exceptionOrNull()?.message ?: "目录为空"}"
        }
        _state.update { it.copy(loading = false, switching = false, error = firstError ?: "打开失败") }
    }

    /** Puts the book on the shelf (if it is not already) and returns its key. */
    suspend fun ensureShelf(): String? {
        val s = _state.value
        val b = s.book ?: return null
        if (!s.inShelf) {
            c.library.add(b, s.chapters)
            _state.update { it.copy(inShelf = true) }
        }
        return b.bookUrl
    }

    fun toggleShelf() {
        viewModelScope.launch {
            val s = _state.value
            val b = s.book ?: return@launch
            if (s.inShelf) { c.library.remove(b.bookUrl); _state.update { it.copy(inShelf = false, message = "已移出书架") } }
            else { ensureShelf(); _state.update { it.copy(message = "已加入书架") } }
        }
    }

    fun findAlternatives() {
        val b = _state.value.book ?: return
        viewModelScope.launch {
            _state.update { it.copy(finding = true, alternatives = emptyList()) }
            val row = c.library.book(b.bookUrl) ?: b.toRowForSearch()
            val alts = runCatching { c.library.alternatives(row) }.getOrDefault(emptyList())
            _state.update { it.copy(finding = false, alternatives = alts) }
        }
    }

    fun dismissAlternatives() = _state.update { it.copy(alternatives = null) }

    fun switchTo(target: SearchBook) {
        viewModelScope.launch {
            _state.update { it.copy(alternatives = null, switching = true) }
            val b = _state.value.book ?: return@launch
            val row = c.library.book(b.bookUrl)
            if (row != null) {
                runCatching { c.library.switchTo(row, target) }
                    .onSuccess { fresh ->
                        val ch = c.library.chapters(fresh.bookUrl).map { it.toChapter() }
                        _state.update { it.copy(book = fresh.toBook(), chapters = ch, switching = false, readIndex = fresh.chapterIndex, message = "已换到 ${target.originName}") }
                    }
                    .onFailure { e -> _state.update { it.copy(switching = false, message = "换源失败：${e.message}") } }
            } else {
                Handoff.search = target
                origin = target
                open(target)
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

private fun Book.toRowForSearch(): BookRow = BookRow(
    bookUrl, origin, originName, name, author, coverUrl, intro, kind, tocUrl, latestChapterTitle, wordCount, "{}", 0, 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(bookUrl: String, onBack: () -> Unit, onRead: (String, Int?) -> Unit) {
    val ctx = LocalContext.current
    val vm: DetailViewModel = viewModel(key = bookUrl, factory = factory { DetailViewModel(ctx.container, bookUrl) })
    val s by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    var showToc by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    if (s.book != null) IconButton(onClick = { vm.findAlternatives() }) { Icon(Icons.Outlined.SwapHoriz, "换源") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            val b = s.book
            if (b != null) androidx.compose.material3.BottomAppBar {
                OutlinedButton(onClick = vm::toggleShelf, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(if (s.inShelf) "移出书架" else "加入书架") }
                Button(
                    onClick = { scope.launch { vm.ensureShelf()?.let { onRead(it, null) } } },
                    enabled = s.chapters.isNotEmpty(), modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                ) { Text(if (s.inShelf && s.readIndex > 0) "继续阅读" else "开始阅读") }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val b = s.book
            when {
                s.loading || s.switching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                b == null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.error ?: "加载失败", style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.padding(top = 12.dp)) {
                        TextButton(onClick = vm::load) { Text("重试") }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(Modifier.padding(16.dp)) {
                            BookCover(b.coverUrl, b.name, Modifier.width(100.dp).height(134.dp))
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(b.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(b.author.ifBlank { "佚名" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                                b.kind?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 2) }
                                b.wordCount?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Box(Modifier.padding(top = 8.dp)) { AssistChip(onClick = { vm.findAlternatives() }, label = { Text(b.originName) }) }
                            }
                        }
                    }
                    item {
                        var expanded by remember { mutableStateOf(false) }
                        Text(
                            b.intro?.takeIf { it.isNotBlank() } ?: "暂无简介", style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 16.dp),
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("目录") },
                            supportingContent = { Text(if (s.chapters.isEmpty()) "目录为空" else "共 ${s.chapters.size} 章 · 最新 ${s.chapters.last().title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, null) },
                            modifier = Modifier.padding(top = 12.dp).clickable(enabled = s.chapters.isNotEmpty()) { showToc = true },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("书评") },
                            supportingContent = { Text("来自豆瓣读书的短评和长评") },
                            trailingContent = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                            modifier = Modifier.clickable { showComments = true },
                        )
                    }
                }
            }
        }
    }

    if (showComments) s.book?.let { CommentsSheet(it.name, it.author, onDismiss = { showComments = false }) }

    if (showToc) TocSheet(s.chapters, current = s.readIndex, onDismiss = { showToc = false }) { idx ->
        showToc = false
        scope.launch { vm.ensureShelf()?.let { onRead(it, idx) } }
    }

    s.alternatives?.let { alts ->
        ModalBottomSheet(onDismissRequest = vm::dismissAlternatives) {
            Text("换源", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (s.finding) Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            else if (alts.isEmpty()) Text("其他书源里没有找到这本书", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn {
                items(alts, key = { it.origin }) { a ->
                    ListItem(
                        headlineContent = { Text(a.originName) },
                        supportingContent = { Text(a.latestChapterTitle?.let { "最新：$it" } ?: a.bookUrl, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { vm.switchTo(a) },
                    )
                }
            }
        }
    }
}

/** The table of contents, scrolled to the current chapter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(chapters: List<Chapter>, current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var reversed by remember { mutableStateOf(false) }
    val list = if (reversed) chapters.asReversed() else chapters
    val start = list.indexOfFirst { it.index == current }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("目录 · ${chapters.size} 章", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { reversed = !reversed }) { Text(if (reversed) "正序" else "倒序") }
        }
        LazyColumn(state = state) {
            items(list, key = { it.index }) { ch ->
                val here = ch.index == current
                ListItem(
                    headlineContent = {
                        Text(
                            ch.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (here || ch.isVolume) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    modifier = Modifier.clickable { onPick(ch.index) },
                )
            }
        }
    }
}
