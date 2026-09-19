package dk.foss.jarvis.ui

/**
 * Removes a fixed [marker] from streamed text, even when it arrives split across chunks, and remembers that it
 * was seen. Text that could still turn into the marker is held back until the next chunk decides it.
 */
class MarkerFilter(private val marker: String) {
    private val held = StringBuilder()

    var found = false
        private set

    /** Feed one chunk; returns the part that is safe to show and speak. */
    fun feed(chunk: String): String {
        held.append(chunk)
        val out = StringBuilder()
        while (true) {
            val at = held.indexOf(marker)
            if (at < 0) break
            out.append(held, 0, at)
            held.delete(0, at + marker.length)
            found = true
        }
        // Keep back the longest tail that is still a prefix of the marker.
        var keep = minOf(held.length, marker.length - 1)
        while (keep > 0 && !marker.startsWith(held.substring(held.length - keep))) keep--
        out.append(held, 0, held.length - keep)
        held.delete(0, held.length - keep)
        return out.toString()
    }

    /** End of the stream: whatever is still held back turned out not to be the marker. */
    fun finish(): String = held.toString().also { held.setLength(0) }
}
