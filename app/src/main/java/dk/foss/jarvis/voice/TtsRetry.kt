package dk.foss.jarvis.voice

/**
 * How a voice recovers when synthesizing a sentence fails, so the sentence still comes out complete instead of being
 * dropped: try again as-is, then with the model reloaded, then in two halves, then with symbols stripped. Pure, so the
 * ladder and the text tweaks can be checked without a phone.
 */
internal object TtsRetry {

    /**
     * Run [attempts] in order until one produces audio. An attempt returns its audio chunks, `null` if it was stopped
     * (give up quietly), or nothing/throws if it failed. [onFail] hears each failure (attempt index, cause).
     * Returns the audio, or null if stopped. Throws only when every attempt failed, with the last cause.
     */
    fun firstSuccess(
        attempts: List<() -> List<FloatArray>?>,
        onFail: (Int, Throwable) -> Unit = { _, _ -> },
    ): List<FloatArray>? {
        var last: Throwable? = null
        attempts.forEachIndexed { i, attempt ->
            try {
                val audio = attempt() ?: return null // stopped
                if (audio.isNotEmpty() && audio.sumOf { it.size } > 0) return audio
                last = IllegalStateException("The voice produced no audio for this sentence")
            } catch (e: Throwable) {
                last = e
            }
            onFail(i, last!!)
        }
        throw last ?: IllegalStateException("No attempts to make")
    }

    /** Two pieces that read as the sentence when spoken one after the other: split at the space nearest the middle. */
    fun splitInHalf(text: String): List<String> {
        val t = text.trim()
        if (t.length < 24) return listOf(t)
        val mid = t.length / 2
        // Prefer a natural break (comma, semicolon, colon, dash) near the middle, else any space.
        val breaks = t.indices.filter { t[it] in ",;:" && it > 8 && it < t.length - 8 }
        val at = breaks.minByOrNull { kotlin.math.abs(it - mid) }?.plus(1)
            ?: t.indices.filter { t[it] == ' ' }.minByOrNull { kotlin.math.abs(it - mid) }
            ?: return listOf(t)
        val (a, b) = t.substring(0, at).trim() to t.substring(at).trim()
        return listOf(a, b).filter { it.isNotEmpty() }
    }

    /** Keep letters, digits and ordinary punctuation; turn emoji, markdown and other symbols into spaces. */
    fun simplify(text: String): String =
        text.map { if (it.isLetterOrDigit() || it in " .,!?;:'’-") it else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
}
