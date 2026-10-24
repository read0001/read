package app.openread.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import app.openread.BuildConfig
import app.openread.engine.Http
import app.openread.engine.JsonCodec
import app.openread.engine.Mirrors
import app.openread.engine.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
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
        val answers = Mirrors.fetchAll(http, base + "app-version.json") { parse(it) != null }
        prefs.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        // Several mirrors answered: the highest version wins, because a mirror may still serve an old copy.
        val info = answers.bodies.mapNotNull(::parse).maxByOrNull { it.code }
            ?: return@withContext Check.Failed(answers.error ?: "没有找到版本信息")
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

    /**
     * Downloads the APK from whichever mirror answers first. Bytes already on disk are kept: a dropped connection is retried
     * with a `Range` request, and so is a later attempt after the user cancelled or the process was killed. [onProgress]
     * gets (bytes so far, total or 0).
     */
    suspend fun download(info: Info, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "openread-${info.code}.apk")
        val part = File(dir, "openread-${info.code}.apk.part")
        dir.listFiles()?.forEach { if (it != part && it != out) it.delete() }
        if (out.exists()) { if (isComplete(out, info)) return@withContext out else out.delete() }
        // The shared client has a short whole-call limit; an APK on a slow link needs longer, as long as bytes keep coming.
        val client = http.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        var failure: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            coroutineContext.ensureActive()
            try {
                fetchInto(client, info, part, onProgress)
                if (!isComplete(part, info)) { part.delete(); error("校验失败：下载的文件和发布信息对不上，已丢弃") }
                check(part.renameTo(out)) { "无法保存下载的文件" }
                return@withContext out
            } catch (e: CancellationException) {
                throw e // the partial file stays for the next try
            } catch (e: Exception) {
                failure = e
                if (e is IllegalStateException) throw e
                kotlinx.coroutines.delay(1500L * attempt)
            }
        }
        throw failure ?: IllegalStateException("下载失败")
    }

    private suspend fun fetchInto(client: okhttp3.OkHttpClient, info: Info, part: File, onProgress: (Long, Long) -> Unit) {
        val have = part.length()
        val resp = try {
            Mirrors.openFirst(client, Mirrors.of(info.url), if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap())
        } catch (e: HttpException) {
            part.delete() // a mirror that refuses the range (or a stale partial file): start over next attempt
            throw e
        }
        resp.use { r ->
            val resumed = have > 0 && r.code == 206 && r.header("Content-Range")?.trim()?.startsWith("bytes $have-") == true
            if (!resumed) part.delete()
            val offset = if (resumed) have else 0L
            val total = (r.body.contentLength().takeIf { it > 0 }?.plus(offset)) ?: info.size
            var done = offset
            r.body.byteStream().use { input ->
                java.io.FileOutputStream(part, resumed).use { o ->
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
            if (total > 0 && done < total) throw java.io.IOException("下载中断")
        }
    }

    private fun isComplete(file: File, info: Info): Boolean {
        if (file.length() < 100_000) return false
        if (info.size > 0 && file.length() != info.size) return false
        val want = info.sha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == want
    }

    companion object {
        const val CHECK_EVERY_MS = 12 * 3600_000L
        private const val MAX_ATTEMPTS = 4

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
