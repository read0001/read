package app.openread.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads through a web speech service: each piece is fetched as an audio file from [template] (a URL with `{{text}}` where the
 * text goes) and played with the framework's [MediaPlayer], which needs no speech engine on the device. The next piece is
 * fetched while the current one plays. The built-in address is a public, unofficial one that can change or stop at any time,
 * which is why the reader can point at another service.
 */
class HttpVoice(context: Context, private val client: OkHttpClient, template: String, private var rate: Float) : Voice {
    override val maxChunk = 150

    private val template = template.trim().takeIf { PLACEHOLDER in it } ?: DEFAULT_URL
    private val dir = File(context.cacheDir, "tts")
    @Volatile private var player: MediaPlayer? = null

    override suspend fun speak(pieces: List<Piece>, onStart: (Piece) -> Unit): Spoken = coroutineScope {
        if (pieces.isEmpty()) return@coroutineScope Spoken.Done
        dir.deleteRecursively()
        dir.mkdirs()
        var next: Deferred<Result<File>>? = async(Dispatchers.IO) { fetch(pieces[0].text, 0) }
        try {
            var played = 0
            var misses = 0
            for ((i, piece) in pieces.withIndex()) {
                val got = next!!.await()
                next = pieces.getOrNull(i + 1)?.let { n -> async(Dispatchers.IO) { fetch(n.text, i + 1) } }
                val file = got.getOrNull()
                if (file == null) {
                    // One piece the service won't voice is skipped; a service that won't voice anything is a failure.
                    misses++
                    if ((played == 0 && misses >= 2) || misses >= 3) return@coroutineScope Spoken.Failed(got.exceptionOrNull()?.message ?: "在线语音请求失败")
                    continue
                }
                misses = 0
                onStart(piece)
                val error = play(file)
                file.delete()
                if (error != null) return@coroutineScope Spoken.Failed(error)
                played++
            }
            Spoken.Done
        } finally {
            next?.cancel()
        }
    }

    override fun stop() {
        player?.let { runCatching { it.release() } }
        player = null
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        player?.let { applyRate(it) }
    }

    override fun release() = stop()

    private fun applyRate(mp: MediaPlayer) {
        // Changing the speed of a prepared player that is not playing would start it, so only a playing one is touched.
        runCatching { if (mp.isPlaying) mp.playbackParams = PlaybackParams().setSpeed(rate) }
    }

    /** Plays [file] to the end. Null when it played, otherwise what went wrong. */
    private suspend fun play(file: File): String? = suspendCancellableCoroutine { cont ->
        val mp = MediaPlayer()
        player = mp
        fun end(error: String?) {
            if (player === mp) player = null
            runCatching { mp.release() }
            if (cont.isActive) cont.resume(error)
        }
        cont.invokeOnCancellation {
            if (player === mp) player = null
            runCatching { mp.release() }
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { end(null) }
            mp.setOnErrorListener { _, what, extra -> end("音频播放失败（$what/$extra）"); true }
            mp.setOnPreparedListener { it.start(); applyRate(it) }
            mp.prepareAsync()
        } catch (e: Exception) {
            end("音频播放失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Fetches the audio for [text] into a file, trying twice; a failure comes back as a result, never as an exception. */
    private suspend fun fetch(text: String, n: Int): Result<File> {
        val url = template.replace(PLACEHOLDER, URLEncoder.encode(text, "UTF-8"))
        var last: Throwable = IOException("在线语音请求失败")
        repeat(2) { attempt ->
            if (attempt > 0) delay(400)
            try {
                val bytes = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).bytes()
                return Result.success(File(dir, "$n.mp3").also { it.writeBytes(bytes) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        return Result.failure(last)
    }

    /** The body of a successful answer that looks like audio; an error page or an empty answer is an [IOException]. */
    private suspend fun Call.bytes(): ByteArray = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val result = runCatching {
                        if (!r.isSuccessful) throw IOException("在线语音服务返回 ${r.code}")
                        val type = r.header("Content-Type").orEmpty()
                        val body = r.body.bytes()
                        if (body.size < MIN_AUDIO || "json" in type || "html" in type || "text" in type) throw IOException("在线语音服务没有返回声音")
                        body
                    }
                    if (cont.isActive) cont.resumeWith(result)
                }
            }
        })
    }

    companion object {
        const val PLACEHOLDER = "{{text}}"
        const val DEFAULT_URL = "https://fanyi.baidu.com/gettts?lan=zh&text=$PLACEHOLDER&spd=5&source=web"
        private const val MIN_AUDIO = 512
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
