package app.openread.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import app.openread.data.PageMode
import app.openread.data.PageAnim
import app.openread.data.ChineseConvert
import app.openread.data.ContentFilter
import kotlinx.coroutines.launch
import app.openread.container
import app.openread.data.ReaderPalette
import app.openread.data.Settings
import app.openread.data.VoiceEngine
import app.openread.tts.HttpVoice
import app.openread.tts.OfflineModel
import app.openread.tts.ReadAloud

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: Settings, update: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit) {
    // 面板半透明的背后能看到正文，调字号、背景时直接看效果；常用的（亮度、字号、背景）放最上面
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), scrimColor = Color.Transparent) {
        // 只占屏幕下面六成，上面露出正文；更多设置在面板里往下滑
        Column(Modifier.fillMaxHeight(0.6f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BrightnessLow, "亮度", Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = if (settings.brightness < 0) 0.5f else settings.brightness,
                    onValueChange = { v -> update { it.copy(brightness = v.coerceIn(0.02f, 1f)) } },
                    enabled = settings.brightness >= 0, modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.BrightnessHigh, null, Modifier.padding(start = 8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(
                    selected = settings.brightness < 0, onClick = { update { it.copy(brightness = if (it.brightness < 0) 0.5f else -1f) } },
                    label = { Text("跟随系统") }, modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("字号", Modifier.padding(end = 16.dp))
                FilledTonalIconButton(onClick = { update { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(12)) } }, enabled = settings.fontSize > 12) { Text("A-", style = MaterialTheme.typography.labelMedium) }
                Slider(
                    value = settings.fontSize.toFloat(), onValueChange = { v -> update { it.copy(fontSize = v.roundToInt()) } },
                    valueRange = 12f..36f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                FilledTonalIconButton(onClick = { update { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(36)) } }, enabled = settings.fontSize < 36) { Text("A+", style = MaterialTheme.typography.titleSmall) }
                Text("${settings.fontSize}", Modifier.padding(start = 8.dp).widthIn(min = 24.dp), style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReaderPalette.entries.forEach { p ->
                    val picked = settings.palette == p
                    val ring by androidx.compose.animation.core.animateDpAsState(if (picked) 3.dp else 1.dp, label = "ring")
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Color(p.bg))
                                .border(BorderStroke(ring, if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), CircleShape)
                                .clickable { update { it.copy(palette = p, dayPalette = if (p.dark) it.dayPalette else p) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (picked) Icon(Icons.Outlined.Check, null, tint = Color(p.fg), modifier = Modifier.size(20.dp))
                            else Text("文", color = Color(p.fg), style = MaterialTheme.typography.labelLarge)
                        }
                        Text(p.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            Section("排版")
            ChipRow("行距", listOf(1.4f to "紧", 1.7f to "适中", 2.0f to "松", 2.3f to "很松"), settings.lineSpacing) { v -> update { it.copy(lineSpacing = v) } }
            ChipRow("段距", listOf(0f to "无", 0.3f to "小", 0.6f to "中", 1f to "大"), settings.paragraphGap) { v -> update { it.copy(paragraphGap = v) } }
            ChipRow("边距", listOf(12 to "窄", 22 to "中", 32 to "宽", 44 to "很宽"), settings.sideMargin) { v -> update { it.copy(sideMargin = v) } }
            FontRow(settings, update)
            SwitchRow("段首缩进两字", null, settings.indent) { v -> update { it.copy(indent = v) } }

            Section("翻页")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PageMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = settings.pageMode == m, onClick = { update { it.copy(pageMode = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, PageMode.entries.size),
                    ) { Text(m.label) }
                }
            }
            if (settings.pageMode == PageMode.Slide) {
                ChipRow("动画", PageAnim.entries.map { it to it.label }, settings.pageAnim) { v -> update { it.copy(pageAnim = v) } }
            }
            SwitchRow("单手模式", if (settings.pageMode == PageMode.Slide) "点屏幕左侧也翻到下一页" else "点屏幕上方也往下翻", settings.tapAllNext) { v -> update { it.copy(tapAllNext = v) } }
            SwitchRow("音量键翻页", "听书时音量键照常调音量", settings.volumeKeys) { v -> update { it.copy(volumeKeys = v) } }
            SwitchRow("页眉页脚", "显示章节名、时间、电量和阅读进度", settings.readerInfo) { v -> update { it.copy(readerInfo = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动翻页", Modifier.padding(end = 16.dp))
                Slider(
                    value = settings.autoPageSeconds.toFloat(), onValueChange = { v -> update { it.copy(autoPageSeconds = v.roundToInt()) } },
                    valueRange = 3f..60f, modifier = Modifier.weight(1f),
                )
                Text(if (settings.pageMode == PageMode.Slide) "${settings.autoPageSeconds} 秒/页" else "${settings.autoPageSeconds} 秒/屏", Modifier.padding(start = 8.dp))
            }

            Section("听书")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("语速", Modifier.padding(end = 16.dp))
                Slider(
                    value = settings.readAloudRate,
                    onValueChange = { v -> update { it.copy(readAloudRate = (v / ReadAloud.RATE_STEP).roundToInt() * ReadAloud.RATE_STEP) } },
                    valueRange = ReadAloud.MIN_RATE..ReadAloud.MAX_RATE, steps = ((ReadAloud.MAX_RATE - ReadAloud.MIN_RATE) / ReadAloud.RATE_STEP).roundToInt() - 1, modifier = Modifier.weight(1f),
                )
                Text("%.2f×".format(settings.readAloudRate), Modifier.padding(start = 8.dp))
            }
            Text("引擎")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val engines = VoiceEngine.entries.filter { it != VoiceEngine.Offline || settings.offlineVoice }
                engines.forEachIndexed { i, e ->
                    SegmentedButton(
                        selected = settings.voiceEngine == e, onClick = { update { it.copy(voiceEngine = e) } },
                        shape = SegmentedButtonDefaults.itemShape(i, engines.size),
                    ) { Text(e.label) }
                }
            }
            if (settings.voiceEngine == VoiceEngine.Auto || settings.voiceEngine == VoiceEngine.Online) {
                OutlinedTextField(
                    value = settings.onlineVoiceUrl, onValueChange = { v -> update { it.copy(onlineVoiceUrl = v.trim()) } },
                    label = { Text("在线语音接口（留空用内置）") },
                    placeholder = { Text(HttpVoice.PLACEHOLDER.let { "https://…?text=$it" }) },
                    supportingText = { Text("地址里用 ${HttpVoice.PLACEHOLDER} 代表要朗读的文字，返回 mp3 等音频。系统语音不可用时（如鸿蒙卓易通）自动改用在线。") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            OfflineVoiceRow(settings, update)

            Section("其他")
            Column {
                ChipRow("繁简", ChineseConvert.entries.map { it to it.label }, settings.chineseConvert, enabled = ContentFilter.convertSupported) { v -> update { it.copy(chineseConvert = v) } }
                if (!ContentFilter.convertSupported) Text("繁简转换需要 Android 10 及以上", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SwitchRow("保持亮屏", null, settings.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
            TextButton(onClick = { update { it.copy(tapGuideShown = false) }; onDismiss() }) { Text("再看一次点击区域说明") }
        }
    }
}

/** 设置分组的小标题。 */
@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

/** 一行开关：名称、可选的说明、右边的开关。 */
@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

/** 一行标签选项：左边是名称，右边一排可选的值。 */
@Composable
private fun <T> ChipRow(label: String, options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onPick: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 16.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, text) -> FilterChip(selected = selected == v, onClick = { onPick(v) }, label = { Text(text) }, enabled = enabled) }
        }
    }
}

/**
 * 字体：系统默认、衬线（宋体类）、等宽，以及导入的字体文件（TTF / OTF，存到 filesDir/fonts/）。选中导入的字体时可以删掉它。
 */
@Composable
private fun FontRow(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    val ctx = LocalContext.current
    val dir = remember { java.io.File(ctx.filesDir, "fonts") }
    var files by remember { mutableStateOf(dir.list()?.sorted().orEmpty()) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { importFont(ctx, uri, dir) }
            if (name == null) android.widget.Toast.makeText(ctx, "不是可用的字体文件（支持 TTF / OTF）", android.widget.Toast.LENGTH_LONG).show()
            else { files = dir.list()?.sorted().orEmpty(); update { it.copy(readerFont = name) } }
        }
    }
    Row(verticalAlignment = Alignment.Top) {
        Text("字体", Modifier.padding(end = 16.dp, top = 12.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("" to "默认", "serif" to "衬线", "mono" to "等宽") + files.map { it to it.substringBeforeLast('.') }).forEach { (v, text) ->
                FilterChip(selected = settings.readerFont == v, onClick = { update { it.copy(readerFont = v) } }, label = { Text(text, maxLines = 1) })
            }
            androidx.compose.material3.AssistChip(onClick = { pick.launch(arrayOf("*/*")) }, label = { Text("导入字体") })
            if (settings.readerFont in files) androidx.compose.material3.AssistChip(onClick = { deleting = settings.readerFont }, label = { Text("删除这个字体") })
        }
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("删除字体？") }, text = { Text(name) },
            confirmButton = {
                TextButton({
                    java.io.File(dir, name).delete()
                    files = dir.list()?.sorted().orEmpty()
                    if (settings.readerFont == name) update { it.copy(readerFont = "") }
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
}

/** 把选中的字体文件复制进 [dir]；读不出字形的文件不要，返回存下的文件名或 null。 */
private fun importFont(ctx: android.content.Context, uri: android.net.Uri, dir: java.io.File): String? {
    val raw = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "font.ttf"
    val name = raw.replace(Regex("[/\\\\:*?\"<>|]"), "_").let { if (it.contains('.')) it else "$it.ttf" }
    dir.mkdirs()
    val tmp = java.io.File(dir, ".$name.tmp")
    runCatching { ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } }.getOrNull() ?: return null
    val ok = runCatching { android.graphics.Typeface.Builder(tmp).build() }.getOrNull()?.let { it != android.graphics.Typeface.DEFAULT } == true
    if (!ok) { tmp.delete(); return null }
    val out = java.io.File(dir, name)
    out.delete()
    return if (tmp.renameTo(out)) name else { tmp.delete(); null }
}

/** What the reader is told before the offline voice is turned on: it costs data, storage and memory. */
private const val OFFLINE_NOTICE = """离线语音在手机上自己合成声音，不用联网，也不依赖系统语音。开启前请知道：

• 需要下载${OfflineModel.APPROX_SIZE}（建议用 Wi-Fi），下载后占用约 45MB 存储。
• 朗读时占用较多内存（约 300MB），内存小的手机可能变卡，或被系统关掉后台。
• 比系统语音和在线语音更费电，声音也更机械。
• 开启后，系统语音不可用时优先使用它；也可以在“听书引擎”里单独选它。

随时可以关闭，并删除已下载的文件。"""

/**
 * The offline voice is off until the reader turns it on, and turning it on first explains what it costs. Only then does the
 * download (which is not in the APK) start; this row also shows its progress and lets the reader cancel, retry or delete it.
 */
@Composable
private fun OfflineVoiceRow(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { OfflineModel.attach(ctx) }
    val status by OfflineModel.status.collectAsState()
    var asking by remember { mutableStateOf(false) }
    val on = settings.offlineVoice

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("开启离线语音？") },
            text = { Text(OFFLINE_NOTICE) },
            confirmButton = {
                TextButton({
                    asking = false
                    update { it.copy(offlineVoice = true) }
                    if (status !is OfflineModel.Status.Ready) OfflineModel.download(ctx.container)
                }) { Text(if (status is OfflineModel.Status.Ready) "开启" else "开启并下载") }
            },
            dismissButton = { TextButton({ asking = false }) { Text("取消") } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("离线语音")
                Text(
                    when {
                        !on && status is OfflineModel.Status.Ready -> "已关闭，下载的文件仍占用约 45MB"
                        !on -> "默认关闭。开启后可在没有系统语音、没有网络时朗读，需下载${OfflineModel.APPROX_SIZE}"
                        else -> when (val s = status) {
                            OfflineModel.Status.Missing -> "已开启，但还没有下载"
                            is OfflineModel.Status.Downloading -> "下载中 ${(s.progress * 100).roundToInt()}%"
                            OfflineModel.Status.Ready -> "已开启，不联网也能听"
                            is OfflineModel.Status.Failed -> "下载失败：${s.reason}"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(on, { want ->
                if (want) {
                    asking = true
                } else {
                    // Off means off: a download in progress is dropped, and the engine choice falls back to "auto".
                    if (status is OfflineModel.Status.Downloading) OfflineModel.delete()
                    update { it.copy(offlineVoice = false, voiceEngine = if (it.voiceEngine == VoiceEngine.Offline) VoiceEngine.Auto else it.voiceEngine) }
                }
            })
        }
        (status as? OfflineModel.Status.Downloading)?.let { LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth()) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            when {
                on && (status is OfflineModel.Status.Failed || status is OfflineModel.Status.Missing) ->
                    TextButton({ OfflineModel.download(ctx.container) }) { Text(if (status is OfflineModel.Status.Failed) "重试" else "下载") }
                !on && status is OfflineModel.Status.Ready -> TextButton({ OfflineModel.delete() }) { Text("删除文件") }
                on && status is OfflineModel.Status.Ready -> TextButton({ OfflineModel.delete() }) { Text("删除文件") }
            }
        }
    }
}
