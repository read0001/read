package app.openread.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import app.openread.ui.components.BookCover
import app.openread.ui.components.EmptyState
import app.openread.ui.components.Unread
import app.openread.ui.components.factory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShelfViewModel(private val c: AppContainer) : ViewModel() {
    val books: StateFlow<List<BookRow>?> = c.library.shelf.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** Returns how many books have new chapters. */
    fun refresh(done: (Int) -> Unit) {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val n = runCatching { c.library.checkUpdates() }.getOrDefault(0)
            _refreshing.value = false
            done(n)
        }
    }

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
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val refresh = {
        vm.refresh { n -> scope.launch { snack.showSnackbar(if (n > 0) "$n 本书有更新" else "没有新章节") } }
    }

    Scaffold(
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
            else -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.padding(pad).fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.bookUrl }) { b -> ShelfItem(b, onOpen = { onOpen(b) }, onDetail = { onDetail(b) }, onRemove = { vm.remove(b) }) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfItem(b: BookRow, onOpen: () -> Unit, onDetail: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Box {
            BookCover(b.coverUrl, b.name, Modifier.fillMaxWidth().aspectRatio(0.75f))
            if (b.hasUpdate) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(10.dp).background(Unread, CircleShape),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("书籍详情") }, onClick = { menu = false; onDetail() })
                DropdownMenuItem(text = { Text("移出书架") }, onClick = { menu = false; onRemove() })
            }
        }
        Text(b.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val progress = if (b.totalChapters > 0) "${b.chapterIndex + 1}/${b.totalChapters}" else "未读"
        Text(progress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
