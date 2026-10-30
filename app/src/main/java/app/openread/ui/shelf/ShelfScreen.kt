package app.openread.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.openread.AppContainer
import app.openread.container
import app.openread.data.BookRow
import app.openread.data.ShelfOffer
import app.openread.data.SwitchOffer
import app.openread.ui.components.BookCover
import app.openread.ui.components.DataAsk
import app.openread.ui.components.DataAskDialog
import app.openread.ui.components.EmptyState
import app.openread.ui.components.SwitchOfferDialog
import app.openread.ui.components.Unread
import app.openread.ui.components.factory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ShelfViewModel(private val c: AppContainer) : ViewModel() {
    val books: StateFlow<List<BookRow>?> = c.library.shelf.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** While the sources are being checked in the background: how many books are done, of how many; otherwise null. */
    private val _checking = MutableStateFlow<Pair<Int, Int>?>(null)
    val checking: StateFlow<Pair<Int, Int>?> = _checking

    /** Books for which a clearly better source was found; the reader is asked about them one at a time. */
    private val _offers = MutableStateFlow<List<ShelfOffer>>(emptyList())
    val offers: StateFlow<List<ShelfOffer>> = _offers

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var verifyJob: Job? = null
    private var verifyRun = 0

    /** The background check searches every source: on mobile data it waits for the reader's yes. */
    val dataAsk = DataAsk { c.network.unmetered() }

    /**
     * A refresh has two parts. First every book's current source is read (new chapters; [done] hears how many books have some
     * and how many current sources could not be read). Then, quietly in the background, the newest chapter of the current
     * source is checked and all sources are searched and checked for a better one (see [Library.verifyShelf]).
     */
    fun refresh(done: (news: Int, unreachable: Int) -> Unit) {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val r = runCatching { c.library.checkUpdates() }.getOrNull()
            _refreshing.value = false
            done(r?.news ?: 0, r?.unreachable?.size ?: 0)
            if (r != null) verify(r.unreachable)
        }
    }

    private fun verify(unreachable: Set<String>) {
        verifyJob?.cancel()
        val run = ++verifyRun
        verifyJob = viewModelScope.launch {
            _offers.value = emptyList()
            val books = c.library.shelfSnapshot()
            if (books.isEmpty()) return@launch
            if (!dataAsk.allowed()) { _message.value = "没有核对书源。连上 Wi-Fi 后再刷新，就会在后台核对"; return@launch }
            try {
                c.library.verifyShelf(books, unreachable, onProgress = { d, t -> if (run == verifyRun) _checking.value = d to t }, onOffer = { o -> _offers.update { it + o } })
            } finally {
                // A newer run may already be showing its own progress; only the latest one clears it.
                if (run == verifyRun) _checking.value = null
            }
        }
    }

    /** Moves the book, if it is still on the shelf and still on the source the offer was made for. */
    fun acceptOffer(o: ShelfOffer) {
        _offers.update { it - o }
        viewModelScope.launch {
            val row = c.library.book(o.book.bookUrl)
            _message.value = when {
                row == null -> "《${o.book.name}》已经不在书架上了"
                row.sourcePinned -> "《${o.book.name}》已固定书源，没有切换"
                row.origin != o.book.origin -> "《${o.book.name}》已经换过书源了，请重新刷新核对"
                else -> try {
                    c.library.switchTo(row, o.offer.target)
                    "《${o.book.name}》已换到 ${o.offer.target.originName}"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "换源失败：${e.message}"
                }
            }
        }
    }

    /** 固定（或取消固定）一本书的书源：固定后刷新核对、自动换源、换源推荐都不再动它。 */
    fun togglePin(b: BookRow) {
        viewModelScope.launch {
            val row = c.library.book(b.bookUrl) ?: return@launch // 以库里的当前状态为准，不用列表项上可能已过期的值
            val pin = !row.sourcePinned
            c.library.setSourcePinned(b.bookUrl, pin)
            // 刚固定的书，已经排队的换源推荐作废
            if (pin) _offers.update { list -> list.filter { it.book.bookUrl != b.bookUrl } }
            _message.value = if (pin) "《${b.name}》已固定书源，不会再被自动换源或推荐换源" else "《${b.name}》已取消固定书源"
        }
    }

    /** Turned down: the same offer is not made again for a day. */
    fun dismissOffer(o: ShelfOffer) {
        c.library.declineOffer(o)
        _offers.update { it - o }
    }

    fun consumeMessage() { _message.value = null }

    fun remove(book: BookRow) {
        viewModelScope.launch { c.library.remove(book.bookUrl) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(onOpen: (BookRow) -> Unit, onDetail: (BookRow) -> Unit, onSearch: () -> Unit, onExplore: () -> Unit) {
    val ctx = LocalContext.current
    val vm: ShelfViewModel = viewModel(factory = factory { ShelfViewModel(ctx.container) })
    val books by vm.books.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val checking by vm.checking.collectAsState()
    val offers by vm.offers.collectAsState()
    val message by vm.message.collectAsState()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val refresh = {
        vm.refresh { n, down ->
            val text = (if (n > 0) "$n 本书有更新" else "没有新章节") + if (down > 0) "；$down 本书的书源打不开" else ""
            scope.launch { snack.showSnackbar(text) }
        }
    }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    DataAskDialog(vm.dataAsk)
    // One question at a time, naming the book.
    offers.firstOrNull()?.let { o ->
        SwitchOfferDialog(SwitchOffer(o.offer.target, "《${o.book.name}》\n\n${o.offer.message}"), onSwitch = { vm.acceptOffer(o) }, onDismiss = { vm.dismissOffer(o) })
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("书架") },
                actions = {
                    IconButton(onClick = { refresh() }) { Icon(Icons.Outlined.Refresh, "检查更新") }
                    IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "搜索") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val list = books
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad))
            list.isEmpty() -> EmptyState(
                "书架还是空的", "搜索想看的书，或者去发现页逛逛",
                Modifier.padding(pad),
            ) { TextButton(onClick = onSearch) { Text("搜索") }; }
            else -> Column(Modifier.padding(pad).fillMaxSize()) {
              checking?.let { (done, total) ->
                Text(
                    "正在后台核对书源和最新章（${done}/${total} 本）…", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
              }
              PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.bookUrl }) { b -> ShelfItem(b, onOpen = { onOpen(b) }, onDetail = { onDetail(b) }, onPin = { vm.togglePin(b) }, onRemove = { vm.remove(b) }) }
                }
              }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfItem(b: BookRow, onOpen: () -> Unit, onDetail: () -> Unit, onPin: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Box {
            BookCover(b.coverUrl, b.name, Modifier.fillMaxWidth().aspectRatio(0.75f))
            if (b.sourcePinned) Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp).background(androidx.compose.ui.graphics.Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PushPin, "已固定书源", Modifier.size(12.dp), tint = androidx.compose.ui.graphics.Color.White) }
            if (b.hasUpdate) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(10.dp).background(Unread, CircleShape),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("书籍详情") }, onClick = { menu = false; onDetail() })
                DropdownMenuItem(text = { Text(if (b.sourcePinned) "取消固定书源" else "固定书源") }, onClick = { menu = false; onPin() })
                DropdownMenuItem(text = { Text("移出书架") }, onClick = { menu = false; onRemove() })
            }
        }
        Text(b.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val progress = if (b.totalChapters > 0) "${b.chapterIndex + 1}/${b.totalChapters}" else "未读"
        Text(progress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
