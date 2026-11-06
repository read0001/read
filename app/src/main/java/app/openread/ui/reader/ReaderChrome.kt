package app.openread.ui.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 页眉页脚要显示的时间和电量；整个阅读页共用一份，每分钟、电量变化时更新。 */
class ReaderClock(val time: String, val battery: Int, val charging: Boolean)

val LocalReaderClock = compositionLocalOf { ReaderClock("", -1, false) }

@Composable
fun rememberReaderClock(): ReaderClock {
    val ctx = LocalContext.current
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA) }
    var time by remember { mutableStateOf(fmt.format(java.util.Date())) }
    var battery by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            time = fmt.format(java.util.Date())
            // 对齐到下一分钟的开头
            kotlinx.coroutines.delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    DisposableEffect(ctx) {
        fun read(i: Intent?) {
            if (i == null) return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            battery = if (level < 0) -1 else level * 100 / scale
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = read(i)
        }
        // 电量广播是粘性的：注册时就拿到当前电量
        read(ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return ReaderClock(time, battery, charging)
}

/** 一节小电池：外框 + 按电量填充，充电时填充用强调色。 */
@Composable
fun BatteryIcon(level: Int, charging: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 20.dp, height = 10.dp)) {
        val cap = 2.dp.toPx()
        val stroke = 1.dp.toPx()
        val bodyW = size.width - cap
        drawRoundRect(color, topLeft = Offset(stroke / 2, stroke / 2), size = Size(bodyW - stroke, size.height - stroke), cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(stroke))
        drawRect(color, topLeft = Offset(bodyW, size.height * 0.3f), size = Size(cap, size.height * 0.4f))
        val inset = stroke + 1.dp.toPx()
        val fill = (bodyW - inset * 2) * (level.coerceIn(0, 100) / 100f)
        if (fill > 0f) drawRoundRect(
            if (charging) Color(0xFF3FB950) else color, topLeft = Offset(inset, inset),
            size = Size(fill, size.height - inset * 2), cornerRadius = CornerRadius(1.dp.toPx()),
        )
    }
}

/** 页眉：左边章节名（或书名），右边可以放别的。 */
@Composable
fun ReaderHeader(text: String, fg: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = fg.copy(alpha = 0.45f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** 页脚：左边时间和电量，右边页码和全书进度。 */
@Composable
fun ReaderFooter(page: String, percent: Float, fg: Color, modifier: Modifier = Modifier) {
    val clock = LocalReaderClock.current
    val c = fg.copy(alpha = 0.45f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(clock.time, color = c, fontSize = 11.sp)
        if (clock.battery >= 0) {
            BatteryIcon(clock.battery, clock.charging, c, Modifier.padding(start = 8.dp))
            Text("${clock.battery}", color = c, fontSize = 10.sp, modifier = Modifier.padding(start = 3.dp))
        }
        Box(Modifier.weight(1f))
        if (page.isNotEmpty()) Text(page, color = c, fontSize = 11.sp, modifier = Modifier.padding(end = 10.dp))
        Text("%.1f%%".format(percent.coerceIn(0f, 1f) * 100), color = c, fontSize = 11.sp)
    }
}

/**
 * 第一次打开阅读页时的点击区域提示：盖住整页，标出“上一页 / 菜单 / 下一页”（滚动模式是上中下三块）。点一下就关。
 */
@Composable
fun TapGuide(visible: Boolean, scroll: Boolean, allNext: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        val zone = Color.White.copy(alpha = 0.10f)
        val line = Color.White.copy(alpha = 0.35f)
        val prev = if (allNext) "下一页" else "上一页"
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.84f)).pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            @Composable
            fun Zone(label: String, hint: String, mod: Modifier) = Box(mod.padding(6.dp).background(zone, RoundedCornerShape(12.dp)).border(1.dp, line, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(hint, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                }
            }
            if (scroll) Column(Modifier.fillMaxSize().padding(12.dp)) {
                Zone(if (allNext) "向下翻一屏" else "向上翻一屏", "点上方", Modifier.fillMaxWidth().weight(0.3f))
                Zone("菜单", "点中间 · 长按复制文字", Modifier.fillMaxWidth().weight(0.4f))
                Zone("向下翻一屏", "点下方 · 也可以直接滑动", Modifier.fillMaxWidth().weight(0.3f))
            } else Row(Modifier.fillMaxSize().padding(12.dp)) {
                Zone(prev, "点左侧", Modifier.fillMaxHeight().weight(0.3f))
                Zone("菜单", "点中间\n长按复制文字", Modifier.fillMaxHeight().weight(0.4f))
                Zone("下一页", "点右侧\n也可以左右滑动", Modifier.fillMaxHeight().weight(0.3f))
            }
            Text(
                "点任意处开始阅读", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
            )
        }
    }
}

/** 菜单、面板用的配色：跟阅读背景走，深色背景时用深色。 */
@Composable
fun ReaderMenuTheme(dark: Boolean, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val isDark = base.background.luminance() < 0.5f
    // 始终走同一个 MaterialTheme 调用、只换配色：深浅切换时菜单和面板里记住的状态（面板展开、滚动位置）不会丢
    val scheme = when {
        isDark == dark -> base
        dark -> DarkMenuColors
        else -> LightMenuColors
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

private val DarkMenuColors = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFF96D4C1), onPrimary = Color(0xFF00382D), primaryContainer = Color(0xFF14503F), onPrimaryContainer = Color(0xFFB2F0DD),
    secondaryContainer = Color(0xFF334B44), onSecondaryContainer = Color(0xFFCDE8DE),
)

private val LightMenuColors = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF2F5D50), onPrimary = Color.White, primaryContainer = Color(0xFFB2F0DD), onPrimaryContainer = Color(0xFF002019),
)
