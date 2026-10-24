package app.openread.ui.sources

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
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

data class SourcesUi(val testing: Set<String> = emptySet(), val testTotal: Int = 0, val testDone: Int = 0, val updating: Boolean = false, val message: String? = null)

class SourcesViewModel(private val c: AppContainer) : ViewModel() {
    val rows: StateFlow<List<SourceRow>?> = c.sources.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val lastUpdate: StateFlow<Long> = c.prefs.flow.let { f -> kotlinx.coroutines.flow.flow { f.collect { emit(it.lastSubscriptionUpdate) } } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    private val _ui = MutableStateFlow(SourcesUi())
    val ui: StateFlow<SourcesUi> = _ui

    fun toggle(r: SourceRow, on: Boolean) { viewModelScope.launch { c.sources.setEnabled(r.key, on) } }
    fun delete(r: SourceRow) { viewModelScope.launch { c.sources.delete(r.key) } }
    fun consume() = _ui.update { it.copy(message = null) }

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
            val ok = c.sources.let { c.db.sources().snapshot() }.count { it.checkOk == true }
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

    LaunchedEffect(ui.message) { ui.message?.let { snack.showSnackbar(it); vm.consume() } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("书源") },
                actions = {
                    IconButton(onClick = vm::updateSubscription, enabled = !ui.updating) { Icon(Icons.Outlined.Sync, "更新订阅") }
                    IconButton(onClick = { rows?.let(vm::testAll) }) { Icon(Icons.Outlined.NetworkCheck, "检测全部") }
                    IconButton(onClick = { importing = true }) { Icon(Icons.Outlined.Add, "导入") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val list = rows
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (ui.updating || ui.testTotal > 0) LinearProgressIndicator(
                progress = { if (ui.testTotal > 0) ui.testDone.toFloat() / ui.testTotal else 0f }.takeIf { ui.testTotal > 0 } ?: { 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            if (list == null) return@Column
            if (list.isEmpty()) { EmptyState("还没有书源", "点右上角导入，或更新订阅"); return@Column }
            val on = list.count { it.enabled }
            Text(
                buildString {
                    append("共 ${list.size} 个，启用 $on 个")
                    if (last > 0) append(" · 订阅更新于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}")
                    if (ui.testTotal > 0) append(" · 检测 ${ui.testDone}/${ui.testTotal}")
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (list.any { it.enabled && it.checkOk == false }) TextButton(onClick = vm::disableFailed, modifier = Modifier.padding(horizontal = 8.dp)) { Text("停用检测失败的书源") }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.key }) { r ->
                    SourceItem(r, testing = r.key in ui.testing, onToggle = { vm.toggle(r, it) }, onTest = { vm.test(r) }, onDelete = { deleting = r })
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
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("删除「${r.name}」？") },
            text = { if (r.subscribed) Text("订阅里的书源下次更新时可能会回来。") },
            confirmButton = { TextButton(onClick = { vm.delete(r); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SourceItem(r: SourceRow, testing: Boolean, onToggle: (Boolean) -> Unit, onTest: () -> Unit, onDelete: () -> Unit) {
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
                    listOfNotNull(r.key.removePrefix("https://").removePrefix("http://").substringBefore("#"), if (r.subscribed) null else "手动导入").joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (status != null) Text(
                    status, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (r.checkOk == false && !testing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "删除") }
                Switch(checked = r.enabled, onCheckedChange = onToggle)
            }
        },
        modifier = Modifier.clickable(enabled = !testing, onClick = onTest),
    )
}

@Composable
private fun ImportDialog(clipboard: () -> String, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入书源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("粘贴书源 JSON，或填写 JSON 文件的网址。手动导入的书源不会被订阅更新覆盖。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8, placeholder = { Text("https://… 或 [{…}]") })
                TextButton(onClick = { text = clipboard() }) { Text("粘贴剪贴板") }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
