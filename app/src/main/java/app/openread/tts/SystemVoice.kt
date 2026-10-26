package app.openread.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * The device's own text-to-speech engine. Environments that run Android apps without a full Android underneath (HarmonyOS's
 * Zhuoyitong, for one) may have no engine at all, or one that accepts text and never makes a sound, so every way of failing is
 * reported as [Spoken.Failed] for [ReadAloud] to fall back on.
 */
class SystemVoice(private val context: Context, private var rate: Float) : Voice {
    override val maxChunk = 300 // engines reject long input

    private var tts: TextToSpeech? = null

    @Volatile private var queue: List<Piece> = emptyList()
    @Volatile private var onStart: (Piece) -> Unit = {}
    @Volatile private var finished: CompletableDeferred<Unit>? = null
    @Volatile private var started = false
    @Volatile private var errorCode: Int? = null

    override suspend fun speak(pieces: List<Piece>, onStart: (Piece) -> Unit): Spoken {
        if (pieces.isEmpty()) return Spoken.Done
        val t = engine() ?: return Spoken.Failed(problem)
        val done = CompletableDeferred<Unit>()
        finished = done
        queue = pieces
        this.onStart = onStart
        started = false
        errorCode = null
        for (i in pieces.indices) {
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (t.speak(pieces[i].text, mode, null, i.toString()) == TextToSpeech.ERROR && i == 0) {
                return Spoken.Failed("语音引擎拒绝了朗读，请在系统设置的语音输出里换一个引擎试试")
            }
        }
        // Some engines never report that the last utterance ended: once one has been silent for a while the chapter counts as
        // read, rather than stalling the whole session. One that never starts at all is broken.
        var quiet = 0
        var waited = 0
        while (withTimeoutOrNull(3_000) { done.await() } == null) {
            if (!started) {
                if (++waited >= 4) { t.stop(); return Spoken.Failed("语音引擎没有发出声音") }
                continue
            }
            quiet = if (t.isSpeaking) 0 else quiet + 1
            if (quiet >= 3) break
        }
        if (!started) return Spoken.Failed("语音引擎出错了（${errorCode ?: "未知"}）")
        return Spoken.Done
    }

    override fun stop() {
        tts?.stop()
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        tts?.setSpeechRate(rate)
    }

    override fun release() {
        tts?.shutdown()
        tts = null
    }

    private var problem = "这台设备没有可用的语音引擎"

    /** Creates the engine on first use. Configured on this side of the callback, so no half-set-up instance is ever seen. */
    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        val init = CompletableDeferred<Int>()
        val t = TextToSpeech(context) { status -> init.complete(status) }
        // A missing engine normally reports an error at once; the timeout is for one that never answers.
        val status = withTimeoutOrNull(8_000) { init.await() }
        if (status != TextToSpeech.SUCCESS) {
            t.shutdown()
            problem = "这台设备没有可用的语音引擎"
            return null
        }
        if (t.setLanguage(Locale.SIMPLIFIED_CHINESE) < 0) {
            t.shutdown()
            problem = "没有中文语音，请到系统设置的语音输出里安装中文语音包"
            return null
        }
        t.setSpeechRate(rate)
        t.setOnUtteranceProgressListener(listener)
        tts = t
        return t
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val piece = utteranceId?.toIntOrNull()?.let { queue.getOrNull(it) } ?: return
            started = true
            onStart(piece)
        }

        override fun onDone(utteranceId: String?) {
            started = true // an engine that skips onStart has still spoken if it reports being done
            ended(utteranceId, null)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = ended(utteranceId, null)

        override fun onError(utteranceId: String?, errorCode: Int) = ended(utteranceId, errorCode)
    }

    private fun ended(utteranceId: String?, error: Int?) {
        if (error != null) errorCode = error
        val last = utteranceId?.toIntOrNull() == queue.lastIndex
        // An error before anything was heard means the engine can't speak at all; no point waiting for the rest.
        if (last || (error != null && !started)) finished?.complete(Unit)
    }
}
