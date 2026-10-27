package app.openread.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether heavy background work (checking every source for a book) may go ahead: at once on a connection that does not count
 * data, and only after the reader says yes on mobile data. A screen shows [DataAskDialog] while [pending] is true.
 */
class DataAsk(private val unmetered: () -> Boolean) {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending
    private var reply: CompletableDeferred<Boolean>? = null

    /** True to go ahead. Suspends while the question is on screen; leaving the screen cancels it. */
    suspend fun allowed(): Boolean {
        if (unmetered()) return true
        val d = CompletableDeferred<Boolean>()
        reply?.complete(false) // an older question that was never answered
        reply = d
        _pending.value = true
        try {
            return d.await()
        } finally {
            // Only the newest question may take the dialog down; an older one finishing must not hide a newer one.
            if (reply === d) {
                reply = null
                _pending.value = false
            }
        }
    }

    fun answer(yes: Boolean) {
        reply?.complete(yes)
    }
}

@Composable
fun DataAskDialog(ask: DataAsk) {
    val pending by ask.pending.collectAsState()
    if (!pending) return
    AlertDialog(
        onDismissRequest = { ask.answer(false) },
        title = { Text("当前不是 Wi-Fi") },
        text = { Text("核对书源要搜索所有书源，并读取各家的最新章，会用不少流量，建议在 Wi-Fi 下进行。\n\n现在用移动网络核对吗？") },
        confirmButton = { TextButton({ ask.answer(true) }) { Text("现在核对") } },
        dismissButton = { TextButton({ ask.answer(false) }) { Text("先不核对") } },
    )
}
