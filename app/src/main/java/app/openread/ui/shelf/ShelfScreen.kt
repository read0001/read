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
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Check
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.openread.data.ShelfSort
import app.openread.data.isLocal
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.MoreVert
import kotlinx.coroutines.launch

class ShelfViewModel(private val c: AppContainer) : ViewModel() {
    val sort: StateFlow<ShelfSort> = c.prefs.flow.map { it.shelfSort }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, ShelfSort.Recent)
    private val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    val books: StateFlow<List<BookRow>?> = combine(c.library.shelf, sort) { list, s ->
        when (s) {
            ShelfSort.Recent -> list.sortedByDescending { it.readAt }
            ShelfSort.Updated -> list.sortedWith(compareByDescending<BookRow> { it.hasUpdate }.thenByDescending { it.checkedAt })
            ShelfSort.Added -> list.sortedByDescending { it.addedAt }
            ShelfSort.Name -> list.sortedWith { a, b -> collator.compare(a.name, b.name) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 列表还是网格；null = 设置还没读出来（先不画，免得选了列表的人每次先闪一下网格）。 */
    val listMode: StateFlow<Boolean?> = c.prefs.flow.map<_, Boolean?> { it.shelfList }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleLayout() { viewModelScope.launch { c.prefs.update { it.copy(shelfList = !it.shelfList) } } }

    fun setSort(s: ShelfSort) { viewModelScope.launch { c.prefs.update { it.copy(shelfSort = s) } } }

    fun setGroup(b: BookRow, group: String) { viewModelScope.launch { c.library.setGroup(b.bookUrl, group) } }

    /** 导入本地 TXT / EPUB。 */
    fun importLocal(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            _message.value = "正在导入…"
            _message.value = runCatching { c.library.addLocal(app.openread.data.LocalBooks.import(context, uri)) }
                .fold({ "已导入《${it.name}》，共 ${it.totalChapters} 章" }, { "导入失败：${it.message}" })
        }
    }
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
    val sort by vm.sort.collectAsState()
    val listModeOrNull by vm.listMode.collectAsState()
    val listMode = listModeOrNull == true
    var removing by remember { mutableStateOf<BookRow?>(null) }
    var group by rememberSaveable { mutableStateOf<String?>(null) } // null = 全部，"" = 未分组
    var more by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf<BookRow?>(null) }
    val importBook = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLocal(ctx, uri)
    }
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
            androidx.compose.material3.TopAppBar(
                title = { Text("书架") },
                actions = {
                    IconButton(onClick = vm::toggleLayout) {
                        androidx.compose.animation.Crossfade(listMode, label = "layout") { l ->
                            if (l) Icon(Icons.Outlined.GridView, "封面视图") else Icon(Icons.AutoMirrored.Outlined.ViewList, "列表视图")
                        }
                    }
                    IconButton(onClick = { refresh() }) { Icon(Icons.Outlined.Refresh, "检查更新") }
                    IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "搜索") }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text("导入本地书（TXT / EPUB）") }, onClick = { more = false; importBook.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) })
                            androidx.compose.material3.HorizontalDivider()
                            ShelfSort.entries.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text("按${o.label}排序") }, onClick = { more = false; vm.setSort(o) },
                                    trailingIcon = { if (sort == o) Icon(Icons.Outlined.Check, null) },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        removing?.let { b ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text("移出书架？") },
                text = { Text("《${b.name}》的阅读进度、书签和缓存会一起删除" + if (b.isLocal) "，导入的文件也会删掉。" else "。") },
                confirmButton = { TextButton(onClick = { vm.remove(b); removing = null }) { Text("移出", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
            )
        }
        grouping?.let { b ->
            GroupDialog(b, books.orEmpty().map { it.shelfGroup }.filter { it.isNotBlank() }.distinct().sorted(), onDismiss = { grouping = null }) { g -> vm.setGroup(b, g); grouping = null }
        }
        val list = books
        when {
            list == null || listModeOrNull == null -> Box(Modifier.fillMaxSize().padding(pad))
            list.isEmpty() -> EmptyState(
                "书架还是空的", "搜索想看的书，或者导入手机里的 TXT / EPUB",
                Modifier.padding(pad),
            ) {
                TextButton(onClick = onSearch) { Text("搜索") }
                TextButton(onClick = { importBook.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) }) { Text("导入本地书") }
            }
            else -> Column(Modifier.padding(pad).fillMaxSize()) {
              checking?.let { (done, total) ->
                Text(
                    "正在后台核对书源和最新章（${done}/${total} 本）…", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
              }
              // 分组：有书设了分组才显示
              val groups = list.map { it.shelfGroup }.filter { it.isNotBlank() }.distinct().sorted()
              // 选中的分组已经没有书了（改了分组名、书移走了）：当作“全部”
              val active = group.takeIf { it == null || it == "" || it in groups }
              if (groups.isNotEmpty()) {
                  androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                      item { androidx.compose.material3.FilterChip(active == null, { group = null }, label = { Text("全部 ${list.size}") }) }
                      items(groups.size) { i ->
                          val g = groups[i]
                          androidx.compose.material3.FilterChip(active == g, { group = g }, label = { Text("$g ${list.count { it.shelfGroup == g }}") })
                      }
                      val none = list.count { it.shelfGroup.isBlank() }
                      if (none > 0) item { androidx.compose.material3.FilterChip(active == "", { group = "" }, label = { Text("未分组 $none") }) }
                  }
              }
              val shown = when (active) { null -> list; "" -> list.filter { it.shelfGroup.isBlank() }; else -> list.filter { it.shelfGroup == group } }
              PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.animation.AnimatedContent(listMode, label = "shelf", transitionSpec = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                }) { asList ->
                  if (asList) androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                      listItems(shown, key = { it.bookUrl }) { b ->
                          ShelfItem(b, list = true, onOpen = { onOpen(b) }, onDetail = { onDetail(b) }, onPin = { vm.togglePin(b) }, onGroup = { grouping = b }, onRemove = { removing = b }, modifier = Modifier.animateItem())
                      }
                  } else LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                  ) {
                    items(shown, key = { it.bookUrl }) { b -> ShelfItem(b, list = false, onOpen = { onOpen(b) }, onDetail = { onDetail(b) }, onPin = { vm.togglePin(b) }, onGroup = { grouping = b }, onRemove = { removing = b }, modifier = Modifier.animateItem()) }
                  }
                }
              }
            }
        }
    }
}

/** 给一本书设分组：输入新的，或点已有的分组；留空 = 不分组。 */
@Composable
private fun GroupDialog(book: BookRow, groups: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(book.shelfGroup) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("《${book.name}》的分组") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text("分组名（留空 = 不分组）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (groups.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    groups.forEach { g -> androidx.compose.material3.AssistChip(onClick = { text = g }, label = { Text(g) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 阅读进度的一句话：未读、读到百分之几、读完了。 */
private fun progressOf(b: BookRow): String = when {
    b.totalChapters <= 0 || (b.readAt <= b.addedAt && b.chapterIndex == 0 && b.chapterOffset == 0) -> "未读"
    b.chapterIndex >= b.totalChapters - 1 -> "读到最后一章"
    else -> "已读 ${((b.chapterIndex + 1) * 100 / b.totalChapters).coerceIn(1, 99)}%"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfItem(b: BookRow, list: Boolean, onOpen: () -> Unit, onDetail: () -> Unit, onPin: () -> Unit, onGroup: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    // 按下时封面微微缩一下，松手弹回
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.95f else 1f, androidx.compose.animation.core.spring(stiffness = 600f), label = "press")
    val click = Modifier.combinedClickable(interactionSource = press, indication = if (list) androidx.compose.foundation.LocalIndication.current else null, onClick = onOpen, onLongClick = { menu = true })
    val cover: @Composable (Modifier) -> Unit = { m ->
        Box(m.graphicsLayer { scaleX = scale; scaleY = scale }) {
            BookCover(b.coverUrl, b.name, Modifier.fillMaxSize().shadow(3.dp, RoundedCornerShape(8.dp)))
            if (b.sourcePinned) Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp).background(androidx.compose.ui.graphics.Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PushPin, "已固定书源", Modifier.size(12.dp), tint = androidx.compose.ui.graphics.Color.White) }
            if (b.hasUpdate) Text(
                "更新", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).background(Unread, RoundedCornerShape(topEnd = 8.dp, bottomStart = 8.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
            // 封面底部一条细进度
            if (b.totalChapters > 0 && progressOf(b) != "未读") androidx.compose.material3.LinearProgressIndicator(
                progress = { ((b.chapterIndex + 1f) / b.totalChapters).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)),
                trackColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f), drawStopIndicator = {}, gapSize = 0.dp,
            )
        }
    }
    val menuItems: @Composable () -> Unit = {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // 本地书没有书源：没有详情页，也不用固定书源
            if (!b.isLocal) DropdownMenuItem(text = { Text("书籍详情") }, onClick = { menu = false; onDetail() })
            if (!b.isLocal) DropdownMenuItem(text = { Text(if (b.sourcePinned) "取消固定书源" else "固定书源") }, onClick = { menu = false; onPin() })
            DropdownMenuItem(text = { Text(if (b.shelfGroup.isBlank()) "设置分组" else "分组：${b.shelfGroup}") }, onClick = { menu = false; onGroup() })
            DropdownMenuItem(text = { Text("移出书架", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onRemove() })
        }
    }
    if (list) {
        androidx.compose.foundation.layout.Row(modifier.fillMaxWidth().then(click).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                cover(Modifier.width(60.dp).height(80.dp))
                menuItems()
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(b.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(b.author.ifBlank { "佚名" }, if (b.isLocal) "本地" else b.originName).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    progressOf(b) + (b.chapterTitle?.takeIf { progressOf(b) != "未读" }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
                b.latestChapter?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "最新：$it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (b.hasUpdate) Unread else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else Column(modifier.fillMaxWidth().then(click)) {
        Box {
            cover(Modifier.fillMaxWidth().aspectRatio(0.75f))
            menuItems()
        }
        Text(b.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(progressOf(b), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
