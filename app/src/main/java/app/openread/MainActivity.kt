package app.openread

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import app.openread.data.Settings
import app.openread.ui.nav.AppNav
import app.openread.ui.components.CrashDialog
import app.openread.ui.update.LocalUpdates
import app.openread.ui.update.UpdateHost
import androidx.compose.runtime.CompositionLocalProvider
import app.openread.ui.theme.OpenReadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** A `openread://search?q=…` link waiting for the UI to open the search page. */
    private val pendingSearch = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val lastCrash = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val updates get() = container.updates

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lastCrash.value = CrashReporter.take(application)
        setContent {
            val settings by container.prefs.flow.collectAsState(initial = Settings())
            OpenReadTheme(settings.themeMode, settings.dynamicColor) {
                CompositionLocalProvider(LocalUpdates provides updates) {
                    AppNav(pendingSearch.value) { pendingSearch.value = null }
                    UpdateHost(updates)
                    CrashDialog(lastCrash.value) { lastCrash.value = null }
                }
            }
        }
        handleImport(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImport(intent)
    }

    /** `legado://import/bookSource?src=<url>` (the link format Legado's source sites use) imports that file. */
    private fun handleImport(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "openread" && data.host == "search") { pendingSearch.value = data.getQueryParameter("q"); return }
        if (data.host != "import") return
        val src = data.getQueryParameter("src") ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { container.sources.import(container.http.execute(src, retry = 1).body) }
            }
            Toast.makeText(
                this@MainActivity,
                result.fold({ if (it > 0) "已导入 $it 个书源" else "没有识别到书源" }, { "导入失败：${it.message}" }),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
