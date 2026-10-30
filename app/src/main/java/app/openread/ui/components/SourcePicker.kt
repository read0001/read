package app.openread.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.openread.engine.SearchBook

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
