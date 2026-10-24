package app.openread.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import app.openread.BuildConfig
import app.openread.engine.BookSource
import app.openread.engine.Http
import app.openread.engine.JsonCodec
import app.openread.engine.Mirrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Checks `app-version.json` next to the book source subscription and installs a newer build. The file is asked from
 * several GitHub mirrors at once, like the subscription. A mirror cannot push a different app: Android only installs an
 * update that is signed with the same key as the installed one.
 */
class AppUpdate(private val context: Context, private val prefs: Prefs, private val http: Http) {
    class Info(
        val code: Int, val name: String, val notes: String, val url: String, val size: Long, val sha256: String?,
        /** A forced update cannot be dismissed or ignored. */
        val force: Boolean,
    )

    sealed interface Check {
        data class Available(val info: Info) : Check
        data object UpToDate : Check
        data class Failed(val reason: String) : Check
    }

    suspend fun isDue(): Boolean = System.currentTimeMillis() - prefs.current().lastUpdateCheck > CHECK_EVERY_MS

    /** [manual] ignores a version the user chose to skip, because they asked. */
    suspend fun check(manual: Boolean): Check = withContext(Dispatchers.IO) {
        val s = prefs.current()
        val base = s.subscriptionBase.trim().let { if (it.endsWith("/")) it else "$it/" }
        val resp = runCatching { Mirrors.fetch(http, base + "app-version.json") { parse(it) != null } }
            .getOrElse { return@withContext Check.Failed(it.message ?: it.javaClass.simpleName) }
        prefs.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        val info = parse(resp.body) ?: return@withContext Check.Failed("版本文件格式不对")
        when {
            info.code <= BuildConfig.VERSION_CODE -> Check.UpToDate
            !manual && !info.force && info.code == s.ignoredVersion -> Check.UpToDate
            else -> Check.Available(info)
        }
    }

    suspend fun ignore(info: Info) = prefs.update { it.copy(ignoredVersion = info.code) }

    private fun parse(text: String): Info? {
        val o = JsonCodec.parse(text) as? JsonObject ?: return null
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val code = str("versionCode")?.toIntOrNull() ?: return null
        val url = str("url")?.takeIf { it.startsWith("http") } ?: return null
        return Info(
            code, str("versionName") ?: code.toString(), str("notes").orEmpty(), url, str("size")?.toLongOrNull() ?: 0,
            str("sha256")?.lowercase()?.takeIf { it.length == 64 }, str("force") == "true",
        )
    }

    /** Downloads the APK, trying each mirror in turn. [onProgress] gets (bytes so far, total or 0). */
    suspend fun download(info: Info, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "openread-${info.code}.apk")
        // The shared client has a short whole-call limit; an APK on a slow link needs longer, as long as bytes keep coming.
        val client = http.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        var last: Throwable? = null
        for (u in Mirrors.of(info.url)) {
            try {
                client.newCall(Request.Builder().url(u).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val total = resp.body.contentLength().takeIf { it > 0 } ?: info.size
                    var done = 0L
                    resp.body.byteStream().use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                }
                if (info.sha256 != null && sha256(out) != info.sha256) error("校验失败")
                if (out.length() < 100_000) error("文件不完整")
                return@withContext out
            } catch (e: CancellationException) {
                out.delete()
                throw e
            } catch (e: Throwable) {
                last = e
                out.delete()
            }
        }
        throw last ?: IllegalStateException("下载失败")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val CHECK_EVERY_MS = 12 * 3600_000L

        fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

        fun install(context: Context, apk: File) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** The system page where the user lets this app install packages; false when the device has none. */
        fun openInstallPermissionSettings(context: Context): Boolean = runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }
}
