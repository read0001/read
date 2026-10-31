package app.openread.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openread.container
import app.openread.data.today

/** 读了多久：今天、最近 7 天、累计，以及读得最久的书。只算阅读页开着并且在翻页的时间，听书不算。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val log = LocalContext.current.container.db.readLog()
    val day = remember { today() }
    val weekStart = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() - 6 * 24 * 3600_000L))
    }
    val todayMs by remember { log.dayTotal(day) }.collectAsState(0L)
    val weekMs by remember { log.since(weekStart) }.collectAsState(0L)
    val totalMs by remember { log.total() }.collectAsState(0L)
    val books by remember { log.perBook() }.collectAsState(emptyList())
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("阅读统计") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                ListItem(headlineContent = { Text("今天") }, trailingContent = { Text(duration(todayMs), style = MaterialTheme.typography.titleMedium) })
                ListItem(headlineContent = { Text("最近 7 天") }, trailingContent = { Text(duration(weekMs), style = MaterialTheme.typography.titleMedium) })
                ListItem(headlineContent = { Text("累计") }, trailingContent = { Text(duration(totalMs), style = MaterialTheme.typography.titleMedium) })
                HorizontalDivider()
                Text(
                    if (books.isEmpty()) "还没有阅读记录。阅读页开着、并且在翻页的时间才算，听书不算。" else "读得最久的书",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
            }
            items(books, key = { it.bookName }) { b ->
                ListItem(headlineContent = { Text(b.bookName, maxLines = 1, overflow = TextOverflow.Ellipsis) }, trailingContent = { Text(duration(b.ms)) })
            }
        }
    }
}

/** 时长的说法：1 小时 5 分、12 分钟、不到 1 分钟。 */
fun duration(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> if (ms > 0) "不到 1 分钟" else "0 分钟"
        min < 60 -> "$min 分钟"
        else -> "${min / 60} 小时 ${min % 60} 分"
    }
}
