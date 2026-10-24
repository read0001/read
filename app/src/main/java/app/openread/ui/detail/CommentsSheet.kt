package app.openread.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.openread.AppContainer
import app.openread.container
import app.openread.engine.BookComment
import app.openread.engine.Douban
import app.openread.engine.DoubanSubject
import app.openread.ui.components.factory
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import androidx.compose.foundation.background
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SubjectState {
    data object Loading : SubjectState
    data object None : SubjectState
    data class Failed(val message: String) : SubjectState
    data class Found(val subject: DoubanSubject) : SubjectState
}

data class CommentTab(val items: List<BookComment> = emptyList(), val total: Int = 0, val loading: Boolean = false, val ended: Boolean = false, val error: String? = null)

data class CommentsState(val subject: SubjectState = SubjectState.Loading, val short: CommentTab = CommentTab(), val long: CommentTab = CommentTab())

/** Comments for one book from Douban Books. Nothing is requested until the sheet is opened. */
class BookCommentsViewModel(private val c: AppContainer, private val name: String, private val author: String) : ViewModel() {
    private val _state = MutableStateFlow(CommentsState())
    val state: StateFlow<CommentsState> = _state

    init { find() }

    fun find() {
        _state.update { CommentsState() }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Douban.findSubject(c.http, name, author) } }
            val s = r.fold(
                { it?.let(SubjectState::Found) ?: SubjectState.None },
                { SubjectState.Failed(it.message ?: "加载失败") },
            )
            _state.update { it.copy(subject = s) }
            if (s is SubjectState.Found) more(long = false)
        }
    }

    fun more(long: Boolean) {
        val subject = (_state.value.subject as? SubjectState.Found)?.subject ?: return
        val tab = if (long) _state.value.long else _state.value.short
        if (tab.loading || tab.ended) return
        update(long) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { if (long) Douban.reviews(c.http, subject.id, tab.items.size) else Douban.shortComments(c.http, subject.id, tab.items.size) }
            }
            update(long) { cur ->
                r.fold(
                    { page ->
                        val all = cur.items + page.items
                        // Anonymous access stops after the hottest few dozen: an empty page is the end, not a failure.
                        cur.copy(items = all, total = page.total, loading = false, ended = page.items.isEmpty() || all.size >= page.total)
                    },
                    { cur.copy(loading = false, error = it.message ?: "加载失败") },
                )
            }
        }
    }

    private fun update(long: Boolean, f: (CommentTab) -> CommentTab) =
        _state.update { if (long) it.copy(long = f(it.long)) else it.copy(short = f(it.short)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(name: String, author: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val vm: BookCommentsViewModel = viewModel(key = "comments:$name:$author", factory = factory { BookCommentsViewModel(ctx.container, name, author) })
    val s by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val uri = LocalUriHandler.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxSize()) {
            Text("书评", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            when (val sub = s.subject) {
                SubjectState.Loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                SubjectState.None -> Note("豆瓣读书里没有找到《$name》，所以没有书评可看。", "有些网络小说没有纸质版，豆瓣上就没有条目。")
                is SubjectState.Failed -> Column(Modifier.padding(20.dp)) {
                    Text(sub.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::find) { Text("重试") }
                }
                is SubjectState.Found -> {
                    val d = sub.subject
                    Text(
                        buildString {
                            append("来自豆瓣读书 · ${d.title}")
                            d.rating?.let { append(" · $it 分") }
                            if (d.ratingCount > 0) append("（${d.ratingCount} 人评价）")
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("短评" + if (s.short.total > 0) " ${s.short.total}" else "") })
                        Tab(selected = tab == 1, onClick = { tab = 1; if (s.long.items.isEmpty()) vm.more(long = true) }, text = { Text("长评" + if (s.long.total > 0) " ${s.long.total}" else "") })
                    }
                    val t = if (tab == 0) s.short else s.long
                    val list = rememberLazyListState()
                    val nearEnd by remember { derivedStateOf { val l = list.layoutInfo; l.totalItemsCount > 0 && (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 3 } }
                    LaunchedEffect(nearEnd, t.items.size, tab) { if (nearEnd && t.items.isNotEmpty()) vm.more(long = tab == 1) }
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        itemsIndexed(t.items) { _, c -> CommentRow(c) { it?.let(uri::openUri) } }
                        item {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                when {
                                    t.loading -> CircularProgressIndicator(Modifier.size(24.dp))
                                    t.error != null -> { Text(t.error, color = MaterialTheme.colorScheme.error); TextButton(onClick = { vm.more(tab == 1) }) { Text("重试") } }
                                    t.ended && t.items.isEmpty() -> Text("还没有${if (tab == 0) "短评" else "长评"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    t.ended -> Text(
                                        if (t.items.size < t.total) "不登录豆瓣时只能看到最热门的 ${t.items.size} 条" else "没有更多了",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { uri.openUri(d.url) }) { Text("在豆瓣查看全部") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(title: String, hint: String) {
    Column(Modifier.padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Douban's image host refuses requests without a Referer; when the picture does not load, the name's first letter stands in. */
@Composable
private fun Avatar(name: String, url: String?) {
    val ctx = LocalContext.current
    val initial: @Composable () -> Unit = {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (url.isNullOrBlank()) { initial(); return }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(ctx).data(url).httpHeaders(NetworkHeaders.Builder().add("Referer", "https://m.douban.com/").build()).build(),
        contentDescription = null, modifier = Modifier.size(36.dp).clip(CircleShape),
        loading = { initial() }, error = { initial() },
    )
}

@Composable
private fun CommentRow(c: BookComment, open: (String?) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(c.user, c.avatar)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.user, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    c.stars?.let { Text("  " + "★".repeat(it) + "☆".repeat(5 - it), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium) }
                }
                Text(
                    listOfNotNull(c.time.takeIf { it.isNotBlank() }, c.votes.takeIf { it > 0 }?.let { "$it 赞" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                c.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
                Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                if (c.url != null) TextButton(onClick = { open(c.url) }) { Text("阅读全文") }
            }
        }
        HorizontalDivider(Modifier.padding(start = 68.dp))
    }
}
