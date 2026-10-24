package app.openread

import android.app.Application
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash so the next launch can show it. Without this a crash on someone else's phone
 * is just "the app closed" and there is nothing to go on.
 */
object CrashReporter {
    private const val FILE = "last-crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(app.filesDir, FILE).writeText(
                    buildString {
                        append("开卷 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n")
                        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
                        append(" · 线程 ${thread.name}\n\n")
                        append(e.stackTraceToString().take(6000))
                    },
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The recorded crash, once: it is deleted as it is read. */
    fun take(app: Application): String? {
        val f = File(app.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
