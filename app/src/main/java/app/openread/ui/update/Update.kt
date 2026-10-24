package app.openread.ui.update

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.openread.AppContainer
import app.openread.data.AppUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: AppUpdate.Info) : UpdateState
    /** [background]: the dialog is hidden and the download carries on; a notification says when it is done. */
    data class Downloading(val info: AppUpdate.Info, val done: Long, val total: Long, val background: Boolean = false) : UpdateState
    data class Ready(val info: AppUpdate.Info, val file: File) : UpdateState
    data class NeedInstallPermission(val file: File) : UpdateState
    data class Notice(val text: String) : UpdateState
}

/** Lives as long as the process: the download runs in the app scope, not in a screen. */
class UpdateController(private val c: AppContainer, private val app: Application) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null
    private var pending: File? = null

    /** Quietly, at most every 12 hours; only speaks up when there is something new. */
    fun checkOnLaunch() {
        if (_state.value != UpdateState.Idle) return
        c.appScope.launch { if (c.appUpdate.isDue()) check(manual = false) }
    }

    fun check(manual: Boolean) {
        if (_state.value !is UpdateState.Idle && _state.value !is UpdateState.Notice) return
        job = c.appScope.launch {
            if (manual) _state.value = UpdateState.Checking
            _state.value = when (val r = c.appUpdate.check(manual)) {
                is AppUpdate.Check.Available -> UpdateState.Available(r.info)
                AppUpdate.Check.UpToDate -> if (manual) UpdateState.Notice("已是最新版本") else UpdateState.Idle
                is AppUpdate.Check.Failed -> if (manual) UpdateState.Notice("检查更新失败：${r.reason}") else UpdateState.Idle
            }
        }
    }

    fun download(info: AppUpdate.Info) {
        job?.cancel()
        job = c.appScope.launch {
            _state.value = UpdateState.Downloading(info, 0, 0)
            var lastUi = 0L
            runCatching {
                c.appUpdate.download(info) { done, total ->
                    val now = System.currentTimeMillis()
                    val cur = _state.value as? UpdateState.Downloading
                    if (cur != null && (now - lastUi > 150 || done == total)) { lastUi = now; _state.value = cur.copy(done = done, total = total) }
                }
            }.onSuccess { file -> finished(info, file) }
                .onFailure { e ->
                    _state.value = if (e is CancellationException) UpdateState.Idle
                    else UpdateState.Notice((e.message?.takeIf { it.startsWith("下载失败") } ?: "下载失败：${e.message ?: e.javaClass.simpleName}") + "。再点一次“立即更新”会从已下载的部分继续。")
                }
        }
    }

    private fun finished(info: AppUpdate.Info, file: File) {
        // The installer cannot be started from the background (Android blocks it), so then the reader is told instead.
        val background = (_state.value as? UpdateState.Downloading)?.background == true || !c.inForeground
        if (background) {
            _state.value = UpdateState.Ready(info, file)
            UpdateNotifier.downloaded(app, info.name)
        } else installOrAsk(file)
    }

    /** Hides the dialog; the download goes on. */
    fun toBackground() { (_state.value as? UpdateState.Downloading)?.let { _state.value = it.copy(background = true) } }

    fun install(file: File) = installOrAsk(file)

    private fun installOrAsk(file: File) {
        if (AppUpdate.canInstall(app)) { AppUpdate.install(app, file); _state.value = UpdateState.Idle }
        else { pending = file; _state.value = UpdateState.NeedInstallPermission(file) }
    }

    /** Back from the "allow installs" settings page: carry on with the download that was waiting. */
    fun onResume() {
        val f = pending ?: return
        if (AppUpdate.canInstall(app)) { pending = null; AppUpdate.install(app, f); _state.value = UpdateState.Idle }
    }

    fun openInstallSettings() {
        if (!AppUpdate.openInstallPermissionSettings(app)) _state.value = UpdateState.Notice("请到 系统设置 → 应用 → 特殊应用权限 → 安装未知应用，允许“开卷”，然后重新检查更新。")
    }

    fun ignore(info: AppUpdate.Info) { c.appScope.launch { c.appUpdate.ignore(info) }; _state.value = UpdateState.Idle }
    /** Cancelling keeps what was downloaded, so the next try carries on from there. */
    fun dismiss() { job?.cancel(); pending = null; _state.value = UpdateState.Idle }
}


val LocalUpdates = compositionLocalOf<UpdateController> { error("UpdateController not provided") }

@Composable
fun UpdateHost(vm: UpdateController) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.checkOnLaunch() }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.onResume() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    when (val s = state) {
        UpdateState.Idle -> {}
        UpdateState.Checking -> AlertDialog(
            onDismissRequest = vm::dismiss, title = { Text("检查更新") },
            text = { Column { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) } },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("取消") } },
        )
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { if (!s.info.force) vm.dismiss() },
            title = { Text("发现新版本 ${s.info.name}") },
            text = { Text(listOf(s.info.notes.ifBlank { "修复问题，改进体验" }, if (s.info.size > 0) "大小 ${mb(s.info.size)}" else "").filter { it.isNotEmpty() }.joinToString("\n\n")) },
            // One row: the quiet "ignore" on the left, the two real choices on the right, like a standard dialog.
            confirmButton = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!s.info.force) TextButton(onClick = { vm.ignore(s.info) }) { Text("忽略此版本") }
                    Spacer(Modifier.weight(1f))
                    if (!s.info.force) TextButton(onClick = vm::dismiss) { Text("稍后") }
                    TextButton(onClick = { vm.download(s.info) }) { Text("立即更新") }
                }
            },
        )
        is UpdateState.Downloading -> if (!s.background) AlertDialog(
            onDismissRequest = {}, title = { Text("正在下载 ${s.info.name}") },
            text = {
                Column {
                    if (s.total > 0) LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("${mb(s.done)} / ${mb(maxOf(s.total, s.done))}", Modifier.padding(top = 8.dp))
                    Text("取消后已下载的部分会保留，下次接着下。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::dismiss) { Text("取消") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        vm.toBackground()
                    }) { Text("后台下载") }
                }
            },
        )
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = vm::dismiss, title = { Text("${s.info.name} 已下载完成") },
            text = { Text("现在安装吗？") },
            confirmButton = { TextButton(onClick = { vm.install(s.file) }) { Text("立即安装") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("稍后") } },
        )
        is UpdateState.NeedInstallPermission -> AlertDialog(
            onDismissRequest = vm::dismiss, title = { Text("需要允许安装") },
            text = { Text("下载已完成。需要先允许“开卷”安装应用（只需要允许这一次），返回后会自动继续。") },
            confirmButton = { TextButton(onClick = { vm.openInstallSettings() }) { Text("去允许") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("取消") } },
        )
        is UpdateState.Notice -> AlertDialog(
            onDismissRequest = vm::dismiss, text = { Text(s.text) },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("知道了") } },
        )
    }
}

private fun mb(bytes: Long) = "%.1f MB".format(Locale.ROOT, bytes / 1048576.0)
