package app.openread.tts

import android.content.Context
import android.os.Build
import app.openread.AppContainer
import app.openread.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * The offline read-aloud voice lives outside the APK: a sherpa-onnx library for this device's ABI and a small Chinese speech
 * model, downloaded from [BuildConfig.OFFLINE_VOICE_BASE] when the reader asks for it and kept in the app's private folder.
 * `scripts/pack-offline-voice.py` writes what is served there; every file is checked against the hash in its manifest.
 */
object OfflineModel {
    sealed interface Status {
        data object Missing : Status
        data class Downloading(val progress: Float) : Status
        data object Ready : Status
        data class Failed(val reason: String) : Status
    }

    /** What the download costs, for the button that starts it. */
    const val APPROX_SIZE = "约 23MB"

    const val MODEL_FILE = "zh_CN-xiao_ya-medium.onnx"
    val RULE_FSTS = listOf("phone.fst", "date.fst", "number.fst")

    private const val READY_MARK = "ready"
    private const val LIB_NAME = "libsherpa-onnx-jni.so"
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

    private val _status = MutableStateFlow<Status>(Status.Missing)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var root: File? = null
    private var job: Job? = null

    val ready: Boolean get() = _status.value is Status.Ready
    val library: File get() = File(checkNotNull(root), LIB_NAME)
    val modelDir: File get() = File(checkNotNull(root), "model")

    /** Remembers where the files live and picks up a download that already finished; safe to call again. */
    @Synchronized
    fun attach(context: Context) {
        if (root != null) return
        val dir = File(context.applicationContext.filesDir, "offline-voice")
        root = dir
        if (File(dir, READY_MARK).isFile && File(dir, LIB_NAME).isFile) _status.value = Status.Ready
    }

    fun download(app: AppContainer) {
        val dir = root ?: return
        if (_status.value is Status.Downloading) return
        _status.value = Status.Downloading(0f)
        job = app.appScope.launch {
            _status.value = try {
                fetchAll(app.http.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(), dir)
                Status.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Status.Failed(e.message ?: "下载失败")
            }
        }
    }

    fun delete() {
        job?.cancel()
        job = null
        root?.deleteRecursively()
        _status.value = Status.Missing
    }

    private class Item(val file: String, val size: Long, val sha256: String, val dest: File)

    private suspend fun fetchAll(client: OkHttpClient, dir: File) {
        File(dir, READY_MARK).delete()
        val manifest = JSONObject(client.newCall(Request.Builder().url(BuildConfig.OFFLINE_VOICE_BASE + "manifest.json").build()).execute().use {
            if (!it.isSuccessful) throw IOException("下载失败（HTTP ${it.code}）")
            it.body.string()
        })
        val libs = manifest.getJSONObject("libs")
        val abi = Build.SUPPORTED_ABIS.firstOrNull { libs.has(it) } ?: throw IOException("这台设备的处理器（${Build.SUPPORTED_ABIS.firstOrNull()}）暂不支持离线语音")
        val model = manifest.getJSONObject("model").getJSONArray("files")
        val items = buildList {
            libs.getJSONObject(abi).let { add(item(it, File(dir, LIB_NAME))) }
            for (i in 0 until model.length()) {
                val f = model.getJSONObject(i)
                val name = f.getString("name")
                require(SAFE_NAME.matches(name)) { "清单里的文件名无效" }
                add(item(f, File(dir, "model/$name")))
            }
        }

        val total = items.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        var reported = 0L
        fun advance(bytes: Long) {
            done += bytes
            if (done - reported >= 128 * 1024 || done >= total) {
                reported = done
                _status.value = Status.Downloading((done.toFloat() / total).coerceAtMost(1f))
            }
        }
        for (item in items) {
            currentCoroutineContext().ensureActive()
            if (item.dest.isFile && sha256(item.dest) == item.sha256) { advance(item.size); continue }
            fetch(client, item, ::advance)
        }
        File(dir, LIB_NAME).setReadOnly() // code the app loads must not be writable
        File(dir, READY_MARK).writeText(manifest.getJSONObject("model").optString("id"))
    }

    private fun item(json: JSONObject, dest: File): Item {
        val file = json.getString("file")
        require(SAFE_NAME.matches(file)) { "清单里的文件名无效" }
        return Item(file, json.getLong("size"), json.getString("sha256"), dest)
    }

    /** Downloads one gzip, unpacks it as it arrives, and keeps the result only if its hash is the manifest's. */
    private suspend fun fetch(client: OkHttpClient, item: Item, advance: (Long) -> Unit) {
        item.dest.parentFile?.mkdirs()
        val part = File(item.dest.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(BuildConfig.OFFLINE_VOICE_BASE + item.file).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败（HTTP ${resp.code}）")
            val counted = object : FilterInputStream(resp.body.byteStream()) {
                override fun read(): Int = super.read().also { if (it >= 0) advance(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }
            }
            GZIPInputStream(counted).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        if (digest.digest().toHex() != item.sha256) {
            part.delete()
            throw IOException("文件校验失败：${item.dest.name}")
        }
        item.dest.delete()
        if (!part.renameTo(item.dest)) throw IOException("无法保存 ${item.dest.name}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input: InputStream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
