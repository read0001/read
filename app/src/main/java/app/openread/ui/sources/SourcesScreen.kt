package app.openread.ui.sources

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.openread.AppContainer
import app.openread.container
import app.openread.data.SourceRow
import app.openread.data.splitGroups
import app.openread.engine.UrlUtil
import app.openread.ui.components.EmptyState
import app.openread.ui.components.factory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** 书源页的状态筛选（参照「阅读」书源管理的筛选菜单）。 */
enum class SourceFilter(val label: String, val test: (SourceRow) -> Boolean) {
    All("全部", { true }),
    Pinned("固定", { it.pinned }),
    Enabled("启用", { it.enabled }),
    Disabled("停用", { !it.enabled }),
    Failed("失效", { it.checkOk == false }),
    Manual("手动添加", { !it.subscribed }),
}

/** 关键字匹配书源名、分组或网址；分组为空串表示“未分组”。 */
fun SourceRow.matches(query: String, filter: SourceFilter, group: String?): Boolean {
    if (!filter.test(this)) return false
    if (group != null && (if (group.isEmpty()) splitGroups(this.group).isNotEmpty() else group !in splitGroups(this.group))) return false
    val q = query.trim()
    return q.isEmpty() || name.contains(q, true) || this.group.orEmpty().contains(q, true) || key.contains(q, true)
}

data class SourcesUi(val testing: Set<String> = emptySet(), val testTotal: Int = 0, val testDone: Int = 0, val updating: Boolean = false, val message: String? = null)

class SourcesViewModel(private val c: AppContainer) : ViewModel() {
    val rows: StateFlow<List<SourceRow>?> = c.sources.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val lastUpdate: StateFlow<Long> = c.prefs.flow.let { f -> kotlinx.coroutines.flow.flow { f.collect { emit(it.lastSubscriptionUpdate) } } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    private val _ui = MutableStateFlow(SourcesUi())
    val ui: StateFlow<SourcesUi> = _ui

    fun toggle(r: SourceRow, on: Boolean) { viewModelScope.launch { c.sources.setEnabled(r.key, on) } }
    /**
     * 点图钉：以库里的当前状态取反（连点两下能来回切换）。订阅里已经没有的源，固定是它唯一留下的理由，
     * 取消固定会把它删掉，所以那种情况由界面先确认（[confirmedOrphan]）。
     */
    fun pin(r: SourceRow, confirmedOrphan: Boolean = false, onNeedConfirm: (SourceRow) -> Unit = {}) {
        viewModelScope.launch {
            val row = c.sources.get(r.key) ?: return@launch
            val on = !row.pinned
            if (!on && row.orphaned) {
                if (!confirmedOrphan) { onNeedConfirm(row); return@launch }
                c.sources.delete(row.key)
                c.prefs.editSearchSources(pick = false) { it - row.key }
            } else {
                c.sources.setPinned(row.key, on)
            }
        }
    }
    fun delete(r: SourceRow) {
        viewModelScope.launch {
            c.sources.delete(r.key)
            c.prefs.editSearchSources(pick = false) { it - r.key } // 自选搜索范围里也去掉它
        }
    }
    fun consume() = _ui.update { it.copy(message = null) }

    // ---- 多选后的批量操作 ----
    fun enableMany(keys: Set<String>, on: Boolean) {
        viewModelScope.launch {
            c.sources.setEnabled(keys, on)
            _ui.update { it.copy(message = "已${if (on) "启用" else "停用"} ${keys.size} 个书源") }
        }
    }

    fun pinMany(keys: Set<String>) {
        viewModelScope.launch {
            c.sources.setPinned(keys, true)
            _ui.update { it.copy(message = "已固定 ${keys.size} 个书源") }
        }
    }

    /**
     * 取消固定。“订阅里已移除”的源取消固定就会被删，所以只删用户确认过的 [confirmedOrphans]；
     * 确认之后才变成已移除的（比如中间跑完了一次订阅更新），这次先不动它，保持固定。
     */
    fun unpinMany(keys: Set<String>, confirmedOrphans: Set<String> = emptySet()) {
        viewModelScope.launch {
            val orphans = keys.mapNotNull { c.sources.get(it) }.filter { it.orphaned }.map { it.key }.toSet()
            val delete = orphans intersect confirmedOrphans
            val kept = orphans - delete
            c.sources.delete(delete)
            if (delete.isNotEmpty()) c.prefs.editSearchSources(pick = false) { it - delete }
            c.sources.setPinned(keys - orphans, false)
            _ui.update {
                it.copy(message = "已取消固定 ${keys.size - kept.size} 个书源" +
                    (if (delete.isNotEmpty()) "，删除了 ${delete.size} 个订阅里已移除的" else "") +
                    (if (kept.isNotEmpty()) "；${kept.size} 个刚变成订阅里已移除，先保留固定" else ""))
            }
        }
    }

    fun deleteMany(keys: Set<String>) {
        viewModelScope.launch {
            c.sources.delete(keys)
            c.prefs.editSearchSources(pick = false) { it - keys }
            _ui.update { it.copy(message = "已删除 ${keys.size} 个书源") }
        }
    }

    fun updateSubscription() {
        if (_ui.value.updating) return
        viewModelScope.launch {
            _ui.update { it.copy(updating = true) }
            val r = c.subscription.update()
            _ui.update { it.copy(updating = false, message = r.message) }
        }
    }

    /** Accepts a URL (one source, an array, or a subscription file) or pasted JSON. */
    fun import(input: String) {
        viewModelScope.launch {
            val text = input.trim()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = if (UrlUtil.isAbsolute(text)) c.http.execute(text, retry = 1).body else text
                    c.sources.import(json)
                }
            }
            _ui.update { it.copy(message = result.fold({ n -> if (n > 0) "已导入 $n 个书源" else "没有识别到书源" }, { e -> "导入失败：${e.message}" })) }
        }
    }

    fun test(r: SourceRow) {
        viewModelScope.launch {
            _ui.update { it.copy(testing = it.testing + r.key) }
            c.engine.probe(r)
            _ui.update { it.copy(testing = it.testing - r.key) }
        }
    }

    fun testAll(rows: List<SourceRow>) {
        if (_ui.value.testTotal > _ui.value.testDone) return
        viewModelScope.launch {
            _ui.update { it.copy(testTotal = rows.size, testDone = 0, testing = rows.map { r -> r.key }.toSet()) }
            val gate = Semaphore(6)
            rows.map { r ->
                async {
                    gate.withPermit { c.engine.probe(r) }
                    _ui.update { it.copy(testDone = it.testDone + 1, testing = it.testing - r.key) }
                }
            }.awaitAll()
            // 只数这次检测的书源（批量检测时只是选中的那几个）
            val tested = rows.map { it.key }.toSet()
            val ok = c.db.sources().snapshot().count { it.key in tested && it.checkOk == true }
            _ui.update { it.copy(testTotal = 0, testDone = 0, message = "检测完成：$ok / ${rows.size} 个可用") }
        }
    }

    fun disableFailed() {
        viewModelScope.launch {
            val failed = c.db.sources().snapshot().filter { it.enabled && it.checkOk == false }
            failed.forEach { c.sources.setEnabled(it.key, false) }
            _ui.update { it.copy(message = "已停用 ${failed.size} 个检测失败的书源") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen() {
    val ctx = LocalContext.current
    val vm: SourcesViewModel = viewModel(factory = factory { SourcesViewModel(ctx.container) })
    val rows by vm.rows.collectAsState()
    val ui by vm.ui.collectAsState()
    val last by vm.lastUpdate.collectAsState()
    val snack = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SourceRow?>(null) }
    var unpinning by remember { mutableStateOf<SourceRow?>(null) }
    // 搜索、筛选
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SourceFilter.All) }
    var group by remember { mutableStateOf<String?>(null) }
    // 多选：长按进入；批量操作只作用于当前筛选出来、并且勾选了的书源
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // 要取消固定的书源，以及其中经用户确认可以删除的“订阅里已移除”的源
    var batchUnpin by remember { mutableStateOf<Pair<Set<String>, Set<String>>?>(null) }
    var batchDelete by remember { mutableStateOf<Set<String>?>(null) }
    val visible = rows.orEmpty().filter { it.matches(query, filter, group) }
    val picked = visible.filter { it.key in selected }
    // 勾选只对当前看得见的书源有效：筛选变了、或者批量操作后书源离开了当前筛选，就把它的勾选去掉，
    // 免得之后换回别的筛选，又被下一次批量操作（比如删除）带上
    val visibleKeys = visible.map { it.key }.toSet()
    LaunchedEffect(visibleKeys) { if (!visibleKeys.containsAll(selected)) selected = selected intersect visibleKeys }
    fun exitSelect() { selecting = false; selected = emptySet() }
    BackHandler(enabled = selecting) { exitSelect() }

    LaunchedEffect(ui.message) { ui.message?.let { snack.showSnackbar(it); vm.consume() } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selecting) CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = ::exitSelect) { Icon(Icons.Outlined.Close, "退出多选") } },
                title = { Text("已选 ${picked.size} 个") },
                actions = {
                    val all = visible.isNotEmpty() && picked.size == visible.size
                    IconButton(onClick = { selected = if (all) emptySet() else visible.map { it.key }.toSet() }) { Icon(Icons.Outlined.SelectAll, if (all) "全不选" else "全选") }
                },
            ) else CenterAlignedTopAppBar(
                title = { Text("书源") },
                actions = {
                    IconButton(onClick = vm::updateSubscription, enabled = !ui.updating) { Icon(Icons.Outlined.Sync, "更新订阅") }
                    IconButton(onClick = { rows?.let(vm::testAll) }) { Icon(Icons.Outlined.NetworkCheck, "检测全部") }
                    IconButton(onClick = { importing = true }) { Icon(Icons.Outlined.Add, "添加书源") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (selecting) Surface(tonalElevation = 3.dp) {
                val keys = picked.map { it.key }.toSet()
                val on = keys.isNotEmpty()
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TextButton({ vm.enableMany(keys, true) }, enabled = on) { Text("启用") }
                    TextButton({ vm.enableMany(keys, false) }, enabled = on) { Text("停用") }
                    TextButton({ vm.pinMany(keys) }, enabled = on) { Text("固定") }
                    TextButton({
                        val orphans = picked.filter { it.orphaned && it.pinned }.map { it.key }.toSet()
                        if (orphans.isNotEmpty()) batchUnpin = keys to orphans else vm.unpinMany(keys)
                    }, enabled = on) { Text("取消固定") }
                    TextButton({ vm.testAll(picked) }, enabled = on && ui.testTotal == 0) { Text("检测") }
                    TextButton({ batchDelete = keys }, enabled = on) { Text("删除", color = if (on) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) }
                }
            }
        },
    ) { pad ->
        val list = rows
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (ui.updating || ui.testTotal > 0) LinearProgressIndicator(
                progress = { if (ui.testTotal > 0) ui.testDone.toFloat() / ui.testTotal else 0f }.takeIf { ui.testTotal > 0 } ?: { 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            if (list == null) return@Column
            if (list.isEmpty()) { EmptyState("还没有书源", "点右上角添加书源，或更新订阅"); return@Column }
            val on = list.count { it.enabled }
            Text(
                buildString {
                    append("共 ${list.size} 个，启用 $on 个，固定 ${list.count { it.pinned }} 个")
                    if (last > 0) append(" · 订阅更新于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}")
                    if (ui.testTotal > 0) append(" · 检测 ${ui.testDone}/${ui.testTotal}")
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (!selecting && list.any { it.enabled && it.checkOk == false }) TextButton(onClick = vm::disableFailed, modifier = Modifier.padding(horizontal = 8.dp)) { Text("停用检测失败的书源") }
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("搜索书源名称、分组或网址") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton({ query = "" }) { Icon(Icons.Outlined.Close, "清空") } }) else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // 状态标签上的数字，按当前分组和关键字算
            FilterBar(list, list.filter { it.matches(query, SourceFilter.All, group) }, filter, { filter = it }, group, { group = it })
            if (visible.size != list.size) Text(
                "符合条件的 ${visible.size} 个" + if (!selecting && visible.isNotEmpty()) " · 长按可多选" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (visible.isEmpty()) { EmptyState("没有符合条件的书源", "换个关键字，或者把筛选改回“全部”"); return@Column }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.key }) { r ->
                    SourceItem(
                        r, testing = r.key in ui.testing, selecting = selecting, selected = r.key in selected,
                        onToggle = { vm.toggle(r, it) }, onPin = { vm.pin(r, onNeedConfirm = { unpinning = it }) }, onTest = { vm.test(r) }, onDelete = { deleting = r },
                        onSelect = { selected = if (r.key in selected) selected - r.key else selected + r.key },
                        onLongPress = { selecting = true; selected = selected + r.key },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (importing) ImportDialog(
        clipboard = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty() },
        onDismiss = { importing = false },
        onImport = { importing = false; vm.import(it) },
    )
    unpinning?.let { r ->
        AlertDialog(
            onDismissRequest = { unpinning = null }, title = { Text("取消固定「${r.name}」？") },
            text = { Text("订阅里已经没有这个书源了，取消固定会把它删除。") },
            confirmButton = { TextButton(onClick = { vm.pin(r, confirmedOrphan = true); unpinning = null }) { Text("取消固定并删除") } },
            dismissButton = { TextButton(onClick = { unpinning = null }) { Text("保留") } },
        )
    }
    batchUnpin?.let { (keys, orphans) ->
        AlertDialog(
            onDismissRequest = { batchUnpin = null }, title = { Text("取消固定 ${keys.size} 个书源？") },
            text = { Text("其中 ${orphans.size} 个订阅里已经没有了，取消固定会把它们删除。") },
            confirmButton = { TextButton(onClick = { vm.unpinMany(keys, confirmedOrphans = orphans); batchUnpin = null }) { Text("取消固定") } },
            dismissButton = { TextButton(onClick = { batchUnpin = null }) { Text("算了") } },
        )
    }
    batchDelete?.let { keys ->
        AlertDialog(
            onDismissRequest = { batchDelete = null }, title = { Text("删除 ${keys.size} 个书源？") },
            text = { Text("订阅里的书源下次更新时可能会回来；手动添加的删了就没了。") },
            confirmButton = { TextButton(onClick = { vm.deleteMany(keys); batchDelete = null; exitSelect() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { batchDelete = null }) { Text("取消") } },
        )
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("删除「${r.name}」？") },
            text = { if (r.subscribed) Text("订阅里的书源下次更新时可能会回来。") },
            confirmButton = { TextButton(onClick = { vm.delete(r); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

/** 状态筛选和分组筛选，横向滚动的一排。 */
@Composable
private fun FilterBar(list: List<SourceRow>, scoped: List<SourceRow>, filter: SourceFilter, onFilter: (SourceFilter) -> Unit, group: String?, onGroup: (String?) -> Unit) {
    // 订阅里的分组很杂（大量只有一两个源的），按书源数从多到少排，常用的大分组在前面
    val groups = remember(list) { list.flatMap { splitGroups(it.group) }.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }) }
    val ungrouped = remember(list) { list.count { splitGroups(it.group).isEmpty() } }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            FilterChip(group != null, { menu = true }, label = { Text("分组：" + when (group) { null -> "全部"; "" -> "未分组"; else -> group } + " ▾") })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("全部分组") }, onClick = { menu = false; onGroup(null) })
                if (ungrouped > 0) DropdownMenuItem(text = { Text("未分组（$ungrouped）") }, onClick = { menu = false; onGroup("") })
                groups.forEach { (g, n) -> DropdownMenuItem(text = { Text("$g（$n）") }, onClick = { menu = false; onGroup(g) }) }
            }
        }
        SourceFilter.entries.forEach { f ->
            val n = scoped.count(f.test)
            if (f == SourceFilter.All || n > 0 || f == filter) FilterChip(filter == f, { onFilter(f) }, label = { Text("${f.label} $n") })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceItem(
    r: SourceRow, testing: Boolean, selecting: Boolean, selected: Boolean,
    onToggle: (Boolean) -> Unit, onPin: () -> Unit, onTest: () -> Unit, onDelete: () -> Unit, onSelect: () -> Unit, onLongPress: () -> Unit,
) {
    val status = when {
        testing -> "检测中…"
        r.checkOk == true -> "✓ 可用 · ${"%.1f".format(r.checkMs / 1000.0)}s · ${r.checkMsg.orEmpty()}"
        r.checkOk == false -> "✗ ${r.checkMsg.orEmpty()}"
        else -> null
    }
    ListItem(
        headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(r.group?.takeIf { it.isNotBlank() }, r.key.removePrefix("https://").removePrefix("http://").substringBefore("#"), if (r.subscribed) null else "手动添加", if (r.pinned) "已固定" else null, if (r.orphaned) "订阅里已移除" else null).joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (status != null) Text(
                    status, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (r.checkOk == false && !testing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        leadingContent = if (selecting) ({ Checkbox(selected, { onSelect() }) }) else null,
        trailingContent = if (selecting) null else ({
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = onPin) {
                    Icon(if (r.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (r.pinned) "取消固定" else "固定", tint = if (r.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "删除") }
                Switch(checked = r.enabled, onCheckedChange = onToggle)
            }
        }),
        // 平时点一下检测这个书源、长按进入多选；多选时点一下勾选
        modifier = Modifier.combinedClickable(
            enabled = selecting || !testing,
            onClick = if (selecting) onSelect else onTest,
            onLongClick = if (selecting) null else onLongPress,
        ),
    )
}

@Composable
private fun ImportDialog(clipboard: () -> String, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加书源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("粘贴书源 JSON，或填写 JSON 文件的网址。手动添加的书源默认固定：排在最前、搜索时优先，订阅更新不会删除或覆盖它。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8, placeholder = { Text("https://… 或 [{…}]") })
                TextButton(onClick = { text = clipboard() }) { Text("粘贴剪贴板") }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
