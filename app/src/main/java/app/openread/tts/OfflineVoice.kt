package app.openread.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.SherpaLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Speaks with the speech model [OfflineModel] downloaded, running on the device through sherpa-onnx, so it needs neither a
 * system speech engine nor a network. The model is loaded when reading starts; the next piece is synthesized while the
 * current one plays.
 */
class OfflineVoice(private var rate: Float) : Voice {
    override val maxChunk = 120

    private var tts: OfflineTts? = null

    /** Native calls into the model run one at a time, and the model is not freed while one is running. */
    private val native = Any()
    @Volatile private var track: AudioTrack? = null

    override suspend fun speak(pieces: List<Piece>, onStart: (Piece) -> Unit): Spoken = coroutineScope {
        if (pieces.isEmpty()) return@coroutineScope Spoken.Done
        try {
            withContext(Dispatchers.Default) { engine() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) { // an UnsatisfiedLinkError is not an Exception
            return@coroutineScope Spoken.Failed("离线语音加载失败：${e.message ?: e.javaClass.simpleName}")
        }

        var next: Deferred<Result<GeneratedAudio>>? = async(Dispatchers.Default) { synthesize(pieces[0].text) }
        try {
            var played = 0
            var misses = 0
            for ((i, piece) in pieces.withIndex()) {
                val got = next!!.await()
                next = pieces.getOrNull(i + 1)?.let { n -> async(Dispatchers.Default) { synthesize(n.text) } }
                val audio = got.getOrNull()
                if (audio == null || audio.samples.isEmpty()) {
                    // A piece the model can't voice is skipped; a model that can't voice anything is a failure.
                    misses++
                    if ((played == 0 && misses >= 2) || misses >= 3) return@coroutineScope Spoken.Failed("离线语音合成失败：${got.exceptionOrNull()?.message ?: "没有声音"}")
                    continue
                }
                misses = 0
                onStart(piece)
                val error = withContext(Dispatchers.IO) { play(audio) }
                if (error != null) return@coroutineScope Spoken.Failed(error)
                played++
            }
            Spoken.Done
        } finally {
            next?.cancel()
        }
    }

    override fun stop() {
        // Releasing also unblocks a write that is waiting for room in a track that will never be drained again.
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
    }

    override fun setRate(rate: Float) {
        this.rate = rate // takes effect from the next piece; audio already synthesized keeps its speed
    }

    /** Frees the model's memory. Waits for a synthesis in progress, so it runs off the caller's thread. */
    override fun release() {
        stop()
        thread {
            synchronized(native) {
                tts?.free()
                tts = null
            }
        }
    }

    private fun engine(): OfflineTts = synchronized(native) {
        tts?.let { return it }
        SherpaLibrary.path = OfflineModel.library.path
        val dir = OfflineModel.modelDir
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/${OfflineModel.MODEL_FILE}",
                    lexicon = "$dir/lexicon.txt",
                    tokens = "$dir/tokens.txt",
                ),
                numThreads = 2,
            ),
            ruleFsts = OfflineModel.RULE_FSTS.joinToString(",") { "$dir/$it" },
        )
        OfflineTts(config = config).also { tts = it }
    }

    private fun synthesize(text: String): Result<GeneratedAudio> = runCatching {
        synchronized(native) { (tts ?: error("离线语音已释放")).generate(text, sid = 0, speed = rate) }
    }

    /** Plays [audio] to the end. Null when it played, otherwise what went wrong. */
    private suspend fun play(audio: GeneratedAudio): String? {
        val hz = audio.sampleRate
        val samples = audio.samples
        val buffer = maxOf(AudioTrack.getMinBufferSize(hz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), hz * 4 / 2)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(hz).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return "音频播放失败：${e.message ?: e.javaClass.simpleName}"
        }
        track = t
        try {
            t.play()
            var at = 0
            while (at < samples.size) {
                currentCoroutineContext().ensureActive()
                val n = t.write(samples, at, minOf(4096, samples.size - at), AudioTrack.WRITE_BLOCKING)
                if (n < 0) return "音频播放失败（$n）"
                at += n
            }
            // The buffer is only handed over, not yet heard: wait until the head has passed the end (or the clip's length is up).
            val limit = System.currentTimeMillis() + samples.size * 1000L / hz + 2_000
            while (t.playbackHeadPosition < samples.size && System.currentTimeMillis() < limit) {
                currentCoroutineContext().ensureActive()
                delay(30)
            }
            return null
        } finally {
            if (track === t) track = null
            runCatching { t.release() }
        }
    }
}
