package app.openread.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import app.openread.container
import app.openread.data.ReplaceRule
import kotlinx.coroutines.launch

/**
 * 屏蔽词 / 替换规则：正文里出现的文字换成别的，或者删掉（广告句、“请收藏本站”之类）。改了立即生效，正在看的章节会重新排版。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val rules by remember { c.db.replaceRules().observe() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ReplaceRule?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("屏蔽词 / 替换规则") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = ReplaceRule(pattern = "", replacement = "", isRegex = false, enabled = true, scope = "", orderNo = rules?.size ?: 0) }) {
                Icon(Icons.Outlined.Add, "添加规则")
            }
        },
    ) { pad ->
        val list = rules
        if (list != null && list.isEmpty()) {
            Text(
                "正文里出现的文字可以替换成别的，或者直接删掉（替换内容留空），比如书源夹带的“请收藏本站”“最新章节请到…”。\n\n" +
                    "也可以只对某本书生效，或者用正则表达式匹配。阅读和听书都按替换后的文字。\n\n点右下角的 + 添加。",
                Modifier.padding(pad).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(list.orEmpty(), key = { it.id }) { r ->
                ListItem(
                    headlineContent = { Text(r.pattern, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val parts = listOfNotNull(
                            if (r.replacement.isEmpty()) "删除" else "替换为「${r.replacement}」",
                            "正则".takeIf { r.isRegex },
                            r.scope.takeIf { it.isNotBlank() }?.let { "只对《$it》" },
                        )
                        Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = { Switch(r.enabled, { v -> scope.launch { c.db.replaceRules().upsert(r.copy(enabled = v)) } }) },
                    modifier = Modifier.clickable { editing = r },
                )
            }
        }
    }
    editing?.let { r ->
        RuleDialog(
            r, onDismiss = { editing = null },
            onSave = { n -> scope.launch { c.db.replaceRules().upsert(n) }; editing = null },
            onDelete = if (r.id != 0L) ({ scope.launch { c.db.replaceRules().delete(r.id) }; editing = null }) else null,
        )
    }
}

@Composable
private fun RuleDialog(rule: ReplaceRule, onDismiss: () -> Unit, onSave: (ReplaceRule) -> Unit, onDelete: (() -> Unit)?) {
    var pattern by remember { mutableStateOf(rule.pattern) }
    var replacement by remember { mutableStateOf(rule.replacement) }
    var regex by remember { mutableStateOf(rule.isRegex) }
    var scopeText by remember { mutableStateOf(rule.scope) }
    val regexError = if (regex && pattern.isNotEmpty()) runCatching { Regex(pattern); null }.getOrElse { "正则写得不对：${it.message?.lineSequence()?.firstOrNull().orEmpty()}" } else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id == 0L) "添加规则" else "编辑规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pattern, { pattern = it }, label = { Text("要替换的文字") }, isError = regexError != null, supportingText = regexError?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(replacement, { replacement = it }, label = { Text("替换为（留空 = 删除）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(scopeText, { scopeText = it }, label = { Text("只对书名包含这些字的书（留空 = 所有书）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().clickable { regex = !regex }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(regex, null)
                    Text("正则表达式（替换内容里可以用 \$1 引用分组）")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(rule.copy(pattern = pattern, replacement = replacement, isRegex = regex, scope = scopeText.trim())) }, enabled = pattern.isNotEmpty() && regexError == null) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
