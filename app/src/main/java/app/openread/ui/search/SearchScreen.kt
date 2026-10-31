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
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import app.openread.data.SearchScope
import app.openread.data.Settings
import app.openread.data.SourceBrief
import app.openread.engine.Freshness
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

data class SearchState(val keyword: String = "", val groups: List<BookGroup> = emptyList(), val searching: Boolean = false, val total: Int = 0, val answered: Int = 0, val started: Boolean = false, val rested: Int = 0)

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state
    val history: StateFlow<List<HistoryRow>> = c.db.history().recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var job: Job? = null

    val settings: StateFlow<Settings> = c.prefs.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())

    /** 所有启用的书源：“搜索源”选择面板和计数用。 */
    val enabledSources: StateFlow<List<SourceBrief>> = c.sources.enabledBrief.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 自选书源的改动：在同一个存储事务里读-改-写（见 Prefs.editSearchSources），快速连点也不会互相覆盖。
     * [add] 为 true 时加入 [keys]，否则去掉；同时切到“自选”。
     */
    fun editCustom(keys: Collection<String>, add: Boolean) {
        viewModelScope.launch { c.prefs.editSearchSources(pick = true) { if (add) it + keys else it - keys.toSet() } }
    }

    /** 切换搜索范围。从别的范围切到“自选”、而且已选集合还是空的，就先拿固定的书源预填，免得一进来就是 0 个。 */
    fun setScope(scope: SearchScope) {
        viewModelScope.launch {
            val now = c.prefs.current()
            if (scope == SearchScope.Custom && now.searchScope != SearchScope.Custom && now.searchSources.isEmpty()) {
                val pinned = c.sources.enabled().filter { it.pinned }.map { it.key }
                c.prefs.editSearchSources(pick = true) { it + pinned }
            } else {
                c.prefs.update { it.copy(searchScope = scope) }
            }
        }
    }

    /**
     * 搜索源面板打开时的范围；关闭时和存储里的最新值比较，不一样就按新范围把上一次搜过的词重搜一遍
     * （新范围里一个书源都没有时也搜：页面会明确提示范围里没有可用的书源，而不是继续显示旧范围的结果）。
     */
    private var scopeBefore: Pair<SearchScope, Set<String>>? = null

    fun scopeOpened() {
        viewModelScope.launch { c.prefs.current().let { scopeBefore = it.searchScope to it.searchSources } }
    }

    fun scopeClosed() {
        viewModelScope.launch {
            val before = scopeBefore ?: return@launch
            scopeBefore = null
            val now = c.prefs.current()
            val keyword = _state.value.keyword
            if (_state.value.started && keyword.isNotBlank() && before != (now.searchScope to now.searchSources)) search(keyword)
        }
    }

    fun search(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            c.db.history().add(HistoryRow(k, System.currentTimeMillis()))
            c.subscription.seedIfEmpty() // first launch: the bundled list may still be going in
            // 搜哪些书源，由“搜索源”的设置决定（固定的书源在引擎里本来就优先问）
            val s = c.prefs.current()
            val enabled = c.sources.enabled()
            val rows = when (s.searchScope) {
                SearchScope.All -> enabled
                SearchScope.Pinned -> enabled.filter { it.pinned }
                SearchScope.Custom -> enabled.filter { it.key in s.searchSources }
            }
            _state.value = SearchState(k, searching = rows.isNotEmpty(), total = rows.size, started = true)
            // 同一本书在几个书源里都有时，固定的书源排前面（点开默认用它），其余按最新章节新旧
            val pinned = rows.filter { it.pinned }.map { it.key }.toSet()
            val index = LinkedHashMap<String, BookGroup>()
            var answered = 0
            c.engine.search(k, rows).collect { progress ->
                if (!progress.counted) { _state.update { it.copy(rested = progress.rested, total = rows.size - progress.rested) }; return@collect }
                val hits = progress.hits
                answered++
                if (hits.isEmpty()) { _state.update { it.copy(answered = answered) }; return@collect }
                for (h in hits) index.getOrPut(h.name + "\u0000" + h.author) { BookGroup(h.name, h.author, mutableListOf()) }.hits.add(h)
                // exact title matches first, then by how many sources have the book
                val sorted = index.values.sortedWith(compareByDescending<BookGroup> { it.name == k }.thenByDescending { it.name.contains(k) }.thenByDescending { it.hits.size })
                _state.update { it.copy(groups = sorted.map { g -> BookGroup(g.name, g.author, Freshness.newestFirst(g.hits).sortedByDescending { it.origin in pinned }.toMutableList()) }, answered = answered) }
            }
            _state.update { it.copy(searching = false) }
        }
    }

    fun setPrecise(on: Boolean) { viewModelScope.launch { c.prefs.update { it.copy(preciseSearch = on) } } }

    fun clearHistory() { viewModelScope.launch { c.db.history().clear() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(initial: String?, onBack: () -> Unit, onOpen: (SearchBook, List<SearchBook>) -> Unit) {
    val ctx = LocalContext.current
    val vm: SearchViewModel = viewModel(factory = factory { SearchViewModel(ctx.container) })
    val state by vm.state.collectAsState()
    val history by vm.history.collectAsState()
    val settings by vm.settings.collectAsState()
    val enabledSources by vm.enabledSources.collectAsState()
    var showScope by remember { mutableStateOf(false) }
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
            val scopeLabel = when (settings.searchScope) {
                SearchScope.All -> "全部书源（${enabledSources.size}）"
                SearchScope.Pinned -> "固定的书源（${enabledSources.count { it.pinned }}）"
                SearchScope.Custom -> "自选 ${enabledSources.count { it.key in settings.searchSources }} 个"
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { vm.scopeOpened(); showScope = true },
                    label = { Text("搜索源：$scopeLabel") },
                    leadingIcon = { Icon(Icons.Outlined.FilterList, null) },
                )
                FilterChip(settings.preciseSearch, { vm.setPrecise(!settings.preciseSearch) }, label = { Text("精准搜索") })
            }
            // 精准搜索：只显示书名或作者包含关键字的（不用重搜，切换立即生效）
            val shown = if (settings.preciseSearch) state.groups.filter { it.name.contains(state.keyword, true) || it.author.contains(state.keyword, true) } else state.groups
            val hiddenByPrecise = state.groups.size - shown.size
            if (state.searching) LinearProgressIndicator(
                progress = { if (state.total == 0) 0f else state.answered.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth(),
            )
            // 放在列表外面：插进带 key 的列表顶部的话，列表会停在原来那一项上，提示被挤到看不见的地方
            if (state.started && !state.searching && hiddenByPrecise > 0 && shown.isNotEmpty()) Text(
                "精准搜索隐藏了 $hiddenByPrecise 本书名和作者都不含「${state.keyword}」的结果",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                !state.started -> HistoryList(history.map { it.keyword }, onPick = { text = it; vm.search(it) }, onClear = vm::clearHistory)
                shown.isEmpty() && hiddenByPrecise > 0 && !state.searching -> EmptyState(
                    "没有书名或作者包含「${state.keyword}」的结果",
                    "精准搜索隐藏了 $hiddenByPrecise 个不相干的结果，点上面的“精准搜索”关掉就能看到",
                )
                state.groups.isEmpty() && !state.searching -> EmptyState(
                    "没有找到「${state.keyword}」",
                    when {
                        settings.searchScope != SearchScope.All && state.total == 0 -> "“搜索源”的范围里没有可用的书源，点上面的“搜索源”改一下"
                        settings.searchScope != SearchScope.All -> "只搜了“搜索源”范围内的 ${state.total} 个书源；可以点上面的“搜索源”改成全部书源再试"
                        else -> "换个关键字，或者在书源页启用更多书源"
                    },
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (state.searching) item {
                        Text(
                            "已搜 ${state.answered}/${state.total} 个书源，找到 ${shown.size} 本" +
                                (if (hiddenByPrecise > 0) "（精准搜索隐藏了 $hiddenByPrecise 本）" else "") +
                                (if (state.rested > 0) "（先跳过 ${state.rested} 个最近总出错的）" else ""),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(shown, key = { it.name + "\u0000" + it.author }) { g -> ResultRow(g) { onOpen(g.first, g.hits.drop(1)) } }
                }
            }
        }
    }

    if (showScope) SearchScopeSheet(
        scope = settings.searchScope, selected = settings.searchSources, sources = enabledSources,
        onScope = vm::setScope, onEdit = vm::editCustom,
        onDismiss = { showScope = false; vm.scopeClosed() },
    )
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
            Box(Modifier.padding(top = 6.dp)) { AssistChip(onClick = onClick, label = { Text(if (g.hits.size == 1) g.first.originName else "${g.first.originName} 等 ${g.hits.size} 个书源", maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
        }
    }
    HorizontalDivider(Modifier.padding(start = 94.dp))
}
