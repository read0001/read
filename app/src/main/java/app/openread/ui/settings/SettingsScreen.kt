package app.openread.ui.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.openread.BuildConfig
import app.openread.container
import app.openread.data.Settings
import app.openread.data.ThemeMode
import app.openread.ui.update.LocalUpdates
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = ctx.container.prefs
    val s by prefs.flow.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    var base by remember(s.subscriptionBase) { mutableStateOf(s.subscriptionBase) }
    var status by remember { mutableStateOf<String?>(null) }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { CenterAlignedTopAppBar(title = { Text("设置") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("外观")
            Column(Modifier.selectableGroup()) {
                listOf(ThemeMode.System to "跟随系统", ThemeMode.Light to "浅色", ThemeMode.Dark to "深色").forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = s.themeMode == mode, role = Role.RadioButton, onClick = { scope.launch { prefs.update { it.copy(themeMode = mode) } } }).padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { RadioButton(selected = s.themeMode == mode, onClick = null); Text(label, Modifier.padding(start = 16.dp)) }
                }
            }
            ListItem(
                headlineContent = { Text("动态取色") }, supportingContent = { Text("使用系统壁纸配色（Android 12+）") },
                trailingContent = { Switch(s.dynamicColor, { v -> scope.launch { prefs.update { it.copy(dynamicColor = v) } } }) },
            )
            HorizontalDivider()
            Section("阅读")
            ListItem(
                headlineContent = { Text("自动换源") }, supportingContent = { Text("章节读不出来时，自动去别的书源找同一本书") },
                trailingContent = { Switch(s.autoSwitchSource, { v -> scope.launch { prefs.update { it.copy(autoSwitchSource = v) } } }) },
            )
            ListItem(
                headlineContent = { Text("阅读时保持亮屏") },
                trailingContent = { Switch(s.keepScreenOn, { v -> scope.launch { prefs.update { it.copy(keepScreenOn = v) } } }) },
            )
            HorizontalDivider()
            Section("书源订阅")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(
                    "应用每 12 小时自动换上最新验证过的书源。默认地址由仓库每 6 小时检测一次并更新；手动导入的书源不受影响。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(base, { base = it }, label = { Text("订阅目录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { scope.launch { prefs.update { it.copy(subscriptionBase = base.trim()) }; status = "更新中…"; status = ctx.container.subscription.update().message } }) { Text("保存并立即更新") }
                    TextButton(onClick = { base = Settings.DEFAULT_BASE; scope.launch { prefs.update { it.copy(subscriptionBase = Settings.DEFAULT_BASE) } } }) { Text("恢复默认") }
                }
                status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Section("关于")
            val updates = LocalUpdates.current
            ListItem(
                headlineContent = { Text("检查更新") },
                supportingContent = { Text("当前版本 ${BuildConfig.VERSION_NAME}") },
                modifier = Modifier.clickable { updates.check(manual = true) },
            )
            ListItem(headlineContent = { Text("开卷") }, supportingContent = { Text("兼容 Legado（阅读）书源格式 · 内容均来自第三方书源") })
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}
