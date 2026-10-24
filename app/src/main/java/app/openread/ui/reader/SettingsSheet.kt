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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.openread.data.PageMode
import app.openread.data.ReaderPalette
import app.openread.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: Settings, update: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("保持亮屏", Modifier.weight(1f))
                Switch(settings.keepScreenOn, { v -> update { it.copy(keepScreenOn = v) } })
            }
        }
    }
}
