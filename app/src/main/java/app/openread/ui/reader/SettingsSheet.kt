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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
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
import app.openread.container
import app.openread.data.ReaderPalette
import app.openread.data.Settings
import app.openread.data.VoiceEngine
import app.openread.tts.HttpVoice
import app.openread.tts.OfflineModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: Settings, update: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("字号", Modifier.padding(end = 16.dp))
                FilledTonalIconButton(onClick = { update { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(12)) } }) { Text("A-") }
                Text("${settings.fontSize}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilledTonalIconButton(onClick = { update { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(36)) } }) { Text("A+") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("行距", Modifier.padding(end = 16.dp))
                listOf(1.4f, 1.7f, 2.0f, 2.3f).forEach { v ->
                    FilterChip(selected = settings.lineSpacing == v, onClick = { update { it.copy(lineSpacing = v) } }, label = { Text("%.1f".format(v)) }, modifier = Modifier.padding(end = 8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("背景", Modifier.padding(end = 16.dp))
                ReaderPalette.entries.forEach { p ->
                    val picked = settings.palette == p
                    Box(
                        Modifier.padding(end = 12.dp).size(38.dp).clip(CircleShape).background(Color(p.bg))
                            .border(BorderStroke(if (picked) 3.dp else 1.dp, if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), CircleShape)
                            .clickable { update { it.copy(palette = p) } },
                        contentAlignment = Alignment.Center,
                    ) { Text("文", color = Color(p.fg), style = MaterialTheme.typography.labelLarge) }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PageMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = settings.pageMode == m, onClick = { update { it.copy(pageMode = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, PageMode.entries.size),
                    ) { Text(m.label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("亮度", Modifier.padding(end = 16.dp))
                Slider(
                    value = if (settings.brightness < 0) 0.5f else settings.brightness,
                    onValueChange = { v -> update { it.copy(brightness = v.coerceIn(0.02f, 1f)) } },
                    enabled = settings.brightness >= 0, modifier = Modifier.weight(1f),
                )
                Checkbox(checked = settings.brightness < 0, onCheckedChange = { sys -> update { it.copy(brightness = if (sys) -1f else 0.5f) } })
                Text("系统")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("听书语速", Modifier.padding(end = 16.dp))
                Slider(
                    value = settings.readAloudRate,
                    onValueChange = { v -> update { it.copy(readAloudRate = (v * 4).roundToInt() / 4f) } },
                    valueRange = 0.5f..2f, steps = 5, modifier = Modifier.weight(1f),
                )
                Text("%.2f×".format(settings.readAloudRate))
            }
            Text("听书引擎")
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("保持亮屏", Modifier.weight(1f))
                Switch(settings.keepScreenOn, { v -> update { it.copy(keepScreenOn = v) } })
            }
        }
    }
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
