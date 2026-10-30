package app.openread.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.openread.data.SearchScope
import app.openread.data.SourceBrief

/** 书源的分组名（一个源可以属于几个分组，用逗号或分号隔开）。 */
private fun SourceBrief.groups(): List<String> = group.orEmpty().split(',', ';', '，', '；').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 搜索页的“搜索源”选择：全部启用的书源、只搜固定的、或自选（可按分组、按名称筛选）。
 * 每次修改都立即交给 [onScope] / [onEdit] 保存。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScopeSheet(
    scope: SearchScope, selected: Set<String>, sources: List<SourceBrief>,
    onScope: (SearchScope) -> Unit, onEdit: (keys: Collection<String>, add: Boolean) -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text("搜索源", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val pinnedCount = sources.count { it.pinned }
            val chosen = sources.count { it.key in selected }
            ScopeRow(scope == SearchScope.All, "全部书源", "所有启用的书源，共 ${sources.size} 个") { onScope(SearchScope.All) }
            ScopeRow(scope == SearchScope.Pinned, "固定的书源", if (pinnedCount > 0) "只搜固定的 $pinnedCount 个" else "还没有固定的书源，到书源页点图钉固定") { onScope(SearchScope.Pinned) }
            ScopeRow(scope == SearchScope.Custom, "自选书源", "已选 $chosen 个") { onScope(SearchScope.Custom) }
            if (scope == SearchScope.Custom) CustomPicker(sources, selected, onEdit)
        }
    }
}

@Composable
private fun ScopeRow(on: Boolean, title: String, hint: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(on, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomPicker(sources: List<SourceBrief>, selected: Set<String>, onEdit: (keys: Collection<String>, add: Boolean) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = sources.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    val allGroups = remember(sources) { sources.flatMap { r -> r.groups().map { it to r.key } }.groupBy({ it.first }, { it.second }).toSortedMap().entries.toList() }
    val groups = allGroups.take(16)

    if (groups.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (name, keys) ->
            val all = keys.all { it in selected }
            // 点分组：这个分组的书源要么全选，要么全部取消
            FilterChip(all, { onEdit(keys, !all) }, label = { Text("$name（${keys.size}）") })
        }
    }
    if (allGroups.size > groups.size) Text(
        "还有 ${allGroups.size - groups.size} 个分组没有列出，可以用下面的名称筛选",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    OutlinedTextField(
        query, { query = it }, singleLine = true, label = { Text("按书源名称筛选") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton({ onEdit(shown.map { it.key }, true) }) { Text("全选（${shown.size}）") }
        TextButton({ onEdit(shown.map { it.key }, false) }) { Text("取消选择") }
    }
    LazyColumn(Modifier.heightIn(max = 320.dp)) {
        items(shown, key = { it.key }) { r ->
            val on = r.key in selected
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(listOf(r.key), !on) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(on, { onEdit(listOf(r.key), !on) })
                Text(r.name + if (r.pinned) "  📌" else "", maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
