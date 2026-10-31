package app.openread.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import app.openread.AppContainer
import app.openread.container
import app.openread.engine.SearchBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 换源面板里“按书源名称搜索”的输入框。 */
@Composable
fun SourceNameFilter(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        label = { Text("按书源名称搜索") },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton({ onChange("") }) { Icon(Icons.Outlined.Close, "清空") } }) else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** 换源面板里“固定当前书源”的开关行：固定后不再自动换源，也不再推荐换源。 */
@Composable
fun PinSourceRow(pinned: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("固定当前书源")
            Text("固定后不再自动换源，也不再推荐换源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(pinned, { onToggle() })
    }
}

/** 按书源名称过滤，忽略大小写；关键字为空时原样返回。 */
fun List<SearchBook>.filterByName(query: String): List<SearchBook> {
    val q = query.trim()
    return if (q.isEmpty()) this else filter { it.originName.contains(q, ignoreCase = true) }
}

/**
 * 换源面板里对书源本身的操作（参照「阅读」换源列表的长按菜单）：设为固定书源、停用书源。
 * 停用的书源从当前列表里拿掉；[pinned] 是启用且固定的书源，列表里排前面。
 */
class AltSourceActions(private val c: AppContainer, private val scope: CoroutineScope) {
    var hidden by mutableStateOf(emptySet<String>())
        private set

    fun pin(key: String) { scope.launch { c.sources.setPinned(key, true) } }
    fun disable(key: String) {
        hidden = hidden + key
        c.library.forgetSource(key)
        scope.launch { c.sources.setEnabled(key, false) }
    }
}

@Composable
fun rememberAltSourceActions(): AltSourceActions {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    return remember(c) { AltSourceActions(c, scope) }
}

@Composable
fun rememberPinnedSources(): Set<String> {
    val c = LocalContext.current.container
    val pinned by remember(c) { c.sources.pinnedKeys }.collectAsState(emptySet())
    return pinned
}

/** 换源列表要显示的：按名称过滤、去掉刚停用的、固定的书源排前面（其余保持原来的顺序）。 */
fun List<SearchBook>.arrange(query: String, pinned: Set<String>, hidden: Set<String>): List<SearchBook> =
    filterByName(query).filter { it.origin !in hidden }.sortedByDescending { it.origin in pinned }

/** 换源列表里的一个候选：点一下换过去；长按可以把这个书源设为固定书源，或者停用它。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AltSourceItem(a: SearchBook, pinned: Boolean, onClick: () -> Unit, actions: AltSourceActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(a.originName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(a.latestChapterTitle?.let { "最新：$it" } ?: a.bookUrl, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = if (pinned) ({ Icon(Icons.Filled.PushPin, "固定的书源", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }) else null,
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        )
        DropdownMenu(menu, { menu = false }) {
            if (!pinned) DropdownMenuItem(text = { Text("设为固定书源（排前面、优先搜索）") }, onClick = { menu = false; actions.pin(a.origin) })
            DropdownMenuItem(text = { Text("停用这个书源") }, onClick = { menu = false; actions.disable(a.origin) })
        }
    }
}
