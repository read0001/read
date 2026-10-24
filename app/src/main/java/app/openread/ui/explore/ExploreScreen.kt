package app.openread.ui.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.openread.AppContainer
import app.openread.container
import app.openread.data.SourceRow
import app.openread.engine.ExploreKind
import app.openread.engine.SearchBook
import app.openread.ui.components.BookRowItem
import app.openread.ui.components.EmptyState
import app.openread.ui.components.factory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExploreState(
    val sources: List<SourceRow> = emptyList(),
    val source: SourceRow? = null,
    val kinds: List<ExploreKind> = emptyList(),
    val kind: ExploreKind? = null,
    val items: List<SearchBook> = emptyList(),
    val loading: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val ready: Boolean = false,
)

class ExploreViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ExploreState())
    val state: StateFlow<ExploreState> = _state
    private var job: Job? = null
    private var page = 1

    init {
        viewModelScope.launch {
            c.subscription.seedIfEmpty()
            val rows = withContext(Dispatchers.IO) {
                c.sources.enabled().filter { r -> c.sources.parse(r)?.let { it.enabledExplore && !it.exploreUrl.isNullOrBlank() } == true }
            }
            _state.update { it.copy(sources = rows, ready = true) }
            rows.firstOrNull()?.let { select(it) }
        }
    }

    fun select(row: SourceRow) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(source = row, kinds = emptyList(), kind = null, items = emptyList(), loading = true, ended = false, error = null) }
            val kinds = withContext(Dispatchers.IO) { runCatching { c.engine.web(row)?.exploreKinds().orEmpty() } }
            val usable = kinds.getOrDefault(emptyList())
            if (usable.none { it.url != null }) {
                _state.update { it.copy(loading = false, error = kinds.exceptionOrNull()?.message ?: "这个书源没有可用的发现分类") }
                return@launch
            }
            _state.update { it.copy(kinds = usable) }
            pickKind(usable.first { it.url != null })
        }
    }

    fun pickKind(kind: ExploreKind) {
        job?.cancel()
        page = 1
        _state.update { it.copy(kind = kind, items = emptyList(), ended = false, error = null) }
        job = viewModelScope.launch { load() }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.ended || s.kind == null) return
        job = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val s = _state.value
        val row = s.source ?: return
        val url = s.kind?.url ?: return
        _state.update { it.copy(loading = true) }
        val result = withContext(Dispatchers.IO) { runCatching { c.engine.web(row)?.explore(url, page).orEmpty() } }
        val hits = result.getOrDefault(emptyList())
        _state.update { cur ->
            val known = cur.items.map { it.bookUrl }.toSet()
            val fresh = hits.filter { it.bookUrl !in known }
            cur.copy(
                items = cur.items + fresh, loading = false, ended = fresh.isEmpty(),
                error = if (cur.items.isEmpty() && fresh.isEmpty()) result.exceptionOrNull()?.message ?: "没有内容" else null,
            )
        }
        page++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(onOpen: (SearchBook) -> Unit) {
    val ctx = LocalContext.current
    val vm: ExploreViewModel = viewModel(factory = factory { ExploreViewModel(ctx.container) })
    val s by vm.state.collectAsState()
    var picking by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(title = {
                Row(Modifier.clickable(enabled = s.sources.isNotEmpty()) { picking = true }, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.source?.name ?: "发现")
                    if (s.sources.isNotEmpty()) Icon(Icons.Outlined.ArrowDropDown, "换书源")
                }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (s.ready && s.sources.isEmpty()) {
                EmptyState("没有支持发现的书源", "在书源页启用带发现功能的书源，或更新订阅")
                return@Column
            }
            if (s.kinds.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.kinds.filter { it.url != null }) { k ->
                    FilterChip(selected = k == s.kind, onClick = { vm.pickKind(k) }, label = { Text(k.title) })
                }
            }
            val listState = rememberLazyListState()
            val nearEnd by remember { androidx.compose.runtime.derivedStateOf { val l = listState.layoutInfo; l.totalItemsCount > 0 && (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 4 } }
            LaunchedEffect(nearEnd, s.items.size) { if (nearEnd && s.items.isNotEmpty()) vm.loadMore() }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(s.items, key = { it.bookUrl }) { b -> BookRowItem(b) { onOpen(b) } }
                }
                if (s.loading) CircularProgressIndicator(Modifier.align(if (s.items.isEmpty()) Alignment.Center else Alignment.BottomCenter).padding(16.dp))
                if (s.error != null && s.items.isEmpty() && !s.loading) EmptyState(s.error ?: "", "换一个分类或书源试试")
            }
        }
    }

    if (picking) ModalBottomSheet(onDismissRequest = { picking = false }) {
        LazyColumn {
            items(s.sources, key = { it.key }) { r ->
                ListItem(
                    headlineContent = { Text(r.name) }, supportingContent = r.group?.let { { Text(it) } },
                    modifier = Modifier.clickable { picking = false; vm.select(r) },
                )
            }
        }
    }
}
