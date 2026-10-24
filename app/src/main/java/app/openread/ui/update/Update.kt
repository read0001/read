package app.openread.ui.update

import android.content.Context
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    data class Downloading(val info: AppUpdate.Info, val done: Long, val total: Long) : UpdateState
    data class NeedInstallPermission(val file: File) : UpdateState
    data class Notice(val text: String) : UpdateState
}

/** Lives as long as the activity, so a download survives leaving the settings page that started it. */
class UpdateViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null
    private var pending: File? = null

    /** Quietly, at most every 12 hours; only speaks up when there is something new. */
    fun checkOnLaunch() {
        if (_state.value != UpdateState.Idle) return
        viewModelScope.launch { if (c.appUpdate.isDue()) check(manual = false) }
    }

    fun check(manual: Boolean) {
        if (_state.value !is UpdateState.Idle && _state.value !is UpdateState.Notice) return
        job = viewModelScope.launch {
            if (manual) _state.value = UpdateState.Checking
            _state.value = when (val r = c.appUpdate.check(manual)) {
                is AppUpdate.Check.Available -> UpdateState.Available(r.info)
                AppUpdate.Check.UpToDate -> if (manual) UpdateState.Notice("已是最新版本") else UpdateState.Idle
                is AppUpdate.Check.Failed -> if (manual) UpdateState.Notice("检查更新失败：${r.reason}") else UpdateState.Idle
            }
        }
    }

    fun download(info: AppUpdate.Info, context: Context) {
        job = viewModelScope.launch {
            _state.value = UpdateState.Downloading(info, 0, 0)
            var lastUi = 0L
            runCatching {
                c.appUpdate.download(info) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 150 || done == total) { lastUi = now; _state.value = UpdateState.Downloading(info, done, total) }
                }
            }.onSuccess { file -> installOrAsk(context, file) }
                .onFailure { e -> _state.value = if (e is CancellationException) UpdateState.Idle else UpdateState.Notice("下载失败：${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private fun installOrAsk(context: Context, file: File) {
        if (AppUpdate.canInstall(context)) { AppUpdate.install(context, file); _state.value = UpdateState.Idle }
        else { pending = file; _state.value = UpdateState.NeedInstallPermission(file) }
    }

    /** Back from the "allow installs" settings page: carry on with the download that was waiting. */
    fun onResume(context: Context) {
        val f = pending ?: return
        if (AppUpdate.canInstall(context)) { pending = null; AppUpdate.install(context, f); _state.value = UpdateState.Idle }
    }

    fun openInstallSettings(context: Context) {
        if (!AppUpdate.openInstallPermissionSettings(context)) _state.value = UpdateState.Notice("请到 系统设置 → 应用 → 特殊应用权限 → 安装未知应用，允许“开卷”，然后重新检查更新。")
    }

    fun ignore(info: AppUpdate.Info) { viewModelScope.launch { c.appUpdate.ignore(info) }; _state.value = UpdateState.Idle }
    fun dismiss() { job?.cancel(); pending = null; _state.value = UpdateState.Idle }
}

val LocalUpdates = compositionLocalOf<UpdateViewModel> { error("UpdateViewModel not provided") }

@Composable
fun UpdateHost(vm: UpdateViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.checkOnLaunch() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.onResume(context) }
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
            confirmButton = { TextButton(onClick = { vm.download(s.info, context) }) { Text("立即更新") } },
            dismissButton = if (s.info.force) null else ({
                Column { TextButton(onClick = vm::dismiss) { Text("稍后") }; TextButton(onClick = { vm.ignore(s.info) }) { Text("忽略此版本") } }
            }),
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {}, title = { Text("正在下载 ${s.info.name}") },
            text = {
                Column {
                    if (s.total > 0) LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("${mb(s.done)} / ${mb(maxOf(s.total, s.done))}", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("取消") } },
        )
        is UpdateState.NeedInstallPermission -> AlertDialog(
            onDismissRequest = vm::dismiss, title = { Text("需要允许安装") },
            text = { Text("下载已完成。需要先允许“开卷”安装应用（只需要允许这一次），返回后会自动继续。") },
            confirmButton = { TextButton(onClick = { vm.openInstallSettings(context) }) { Text("去允许") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("取消") } },
        )
        is UpdateState.Notice -> AlertDialog(
            onDismissRequest = vm::dismiss, text = { Text(s.text) },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("知道了") } },
        )
    }
}

private fun mb(bytes: Long) = "%.1f MB".format(Locale.ROOT, bytes / 1048576.0)
