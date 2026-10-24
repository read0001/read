package app.openread.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.openread.AppContainer
import app.openread.container
import app.openread.data.HistoryRow
import app.openread.engine.SearchBook
import app.openread.ui.components.BookCover
import app.openread.ui.components.EmptyState
import app.openread.ui.components.factory
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One book as found in one or more sources; [hits] are in the order the sources answered. */
class BookGroup(val name: String, val author: String, val hits: MutableList<SearchBook>) {
    val first: SearchBook get() = hits.first()
    val cover: String? get() = hits.firstNotNullOfOrNull { it.coverUrl }
}

data class SearchState(val keyword: String = "", val groups: List<BookGroup> = emptyList(), val searching: Boolean = false, val total: Int = 0, val answered: Int = 0, val started: Boolean = false)

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state
    val history: StateFlow<List<HistoryRow>> = c.db.history().recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var job: Job? = null

    fun search(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            c.db.history().add(HistoryRow(k, System.currentTimeMillis()))
            c.subscription.seedIfEmpty() // first launch: the bundled list may still be going in
            val rows = c.sources.enabled()
            _state.value = SearchState(k, searching = rows.isNotEmpty(), total = rows.size, started = true)
            val index = LinkedHashMap<String, BookGroup>()
            var answered = 0
            c.engine.search(k, rows).collect { hits ->
                answered++
                for (h in hits) index.getOrPut(h.name + "\u0000" + h.author) { BookGroup(h.name, h.author, mutableListOf()) }.hits.add(h)
                // exact title matches first, then by how many sources have the book
                val sorted = index.values.sortedWith(compareByDescending<BookGroup> { it.name == k }.thenByDescending { it.name.contains(k) }.thenByDescending { it.hits.size })
                _state.update { it.copy(groups = sorted.map { g -> BookGroup(g.name, g.author, g.hits.toMutableList()) }, answered = answered) }
            }
            _state.update { it.copy(searching = false) }
        }
    }

    fun clearHistory() { viewModelScope.launch { c.db.history().clear() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(initial: String?, onBack: () -> Unit, onOpen: (SearchBook, List<SearchBook>) -> Unit) {
    val ctx = LocalContext.current
    val vm: SearchViewModel = viewModel(factory = factory { SearchViewModel(ctx.container) })
    val state by vm.state.collectAsState()
    val history by vm.history.collectAsState()
    var text by remember { mutableStateOf(initial.orEmpty()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (!initial.isNullOrBlank()) vm.search(initial)
        else {
            // The text field joins the tree a frame after this effect starts; asking too early throws.
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                title = {
                    TextField(
                        value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("书名或作者") },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.search(text) }),
                        trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Outlined.Close, "清空") } },
                    )
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (state.searching) LinearProgressIndicator(
                progress = { if (state.total == 0) 0f else state.answered.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                !state.started -> HistoryList(history.map { it.keyword }, onPick = { text = it; vm.search(it) }, onClear = vm::clearHistory)
                state.groups.isEmpty() && !state.searching -> EmptyState("没有找到「${state.keyword}」", "换个关键字，或者在书源页启用更多书源")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (state.searching) item {
                        Text(
                            "已搜 ${state.answered}/${state.total} 个书源，找到 ${state.groups.size} 本",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(state.groups, key = { it.name + "\u0000" + it.author }) { g -> ResultRow(g) { onOpen(g.first, g.hits.drop(1)) } }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryList(items: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (items.isEmpty()) { EmptyState("搜索书籍", "同时在所有已启用的书源里找"); return }
    Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("搜索历史", style = MaterialTheme.typography.titleSmall)
            Text("清空", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onClear))
        }
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { k -> SuggestionChip(onClick = { onPick(k) }, label = { Text(k) }) }
        }
    }
}

@Composable
private fun ResultRow(g: BookGroup, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        BookCover(g.cover, g.name, Modifier.width(64.dp).height(86.dp))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(g.author.takeIf { it.isNotBlank() }, g.first.kind).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            g.first.latestChapterTitle?.let {
                Text("最新：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.padding(top = 6.dp)) { AssistChip(onClick = onClick, label = { Text("${g.hits.size} 个书源") }) }
        }
    }
    HorizontalDivider(Modifier.padding(start = 94.dp))
}
