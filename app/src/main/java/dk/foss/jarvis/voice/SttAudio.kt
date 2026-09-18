package dk.foss.jarvis.voice

/** Audio and text helpers for the on-device recognizers. Pure, so they don't need a phone to check. */
internal object SttAudio {

    /**
     * Cut [samples] into pieces of at most [maxSeconds] (0 = no limit, one piece). Each cut lands on the quietest
     * ~20 ms in the last 40% of the piece, so it falls between words rather than in one. No samples are dropped.
     * Some models fail on long audio and return nothing, silently, so they are given short pieces.
     */
    fun split(samples: FloatArray, maxSeconds: Int, sampleRate: Int): List<FloatArray> {
        val max = maxSeconds * sampleRate
        if (max <= 0 || samples.size <= max) return listOf(samples)
        val pieces = ArrayList<FloatArray>()
        var start = 0
        while (samples.size - start > max) {
            val cut = quietestPoint(samples, start + max * 6 / 10, start + max, sampleRate)
            pieces += samples.copyOfRange(start, cut)
            start = cut
        }
        pieces += samples.copyOfRange(start, samples.size)
        return pieces
    }

    /** Sample index of the lowest-energy 20 ms window that starts in [from, to]. */
    private fun quietestPoint(samples: FloatArray, from: Int, to: Int, sampleRate: Int): Int {
        val window = sampleRate / 50
        var bestAt = to
        var bestEnergy = Float.MAX_VALUE
        var i = from
        while (i + window <= minOf(to + window, samples.size)) {
            var e = 0f
            for (k in i until i + window) e += samples[k] * samples[k]
            if (e < bestEnergy) { bestEnergy = e; bestAt = i + window / 2 }
            i += window / 2
        }
        return bestAt.coerceIn(from, to)
    }

    /** "TURN OFF THE LIGHTS I THINK" becomes "Turn off the lights I think". */
    fun sentenceCase(text: String): String =
        text.lowercase()
            .replace(Regex("\\bi\\b"), "I")
            .replaceFirstChar { it.uppercase() }
}
