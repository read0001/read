package app.openread.tts

/** A stretch of text a [Voice] reads in one go, tagged with the paragraph of the chapter it comes from. */
class Piece(val text: String, val paragraph: Int)

sealed interface Spoken {
    data object Done : Spoken

    /** Nothing could be spoken, or speech broke off; [reason] is worded for the reader to see. */
    data class Failed(val reason: String) : Spoken
}

/** Something that can read text aloud. [ReadAloud] drives one chapter at a time and falls back from one voice to another. */
interface Voice {
    /** The longest piece worth handing over at once; [ReadAloud] splits paragraphs at sentence ends below this. */
    val maxChunk: Int

    /** Reads [pieces] in order, calling [onStart] as each one begins. Returns when they are all read, or when it can't go on. */
    suspend fun speak(pieces: List<Piece>, onStart: (Piece) -> Unit): Spoken

    /** Silences whatever is playing; the suspended [speak] is cancelled by its caller. */
    fun stop()

    fun setRate(rate: Float)

    /** Frees the engine; the voice is not used again. */
    fun release()
}
