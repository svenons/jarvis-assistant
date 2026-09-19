package dk.foss.jarvis.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Records mic audio to a 16 kHz mono WAV with energy-based voice-activity
 * detection: it waits for speech, then stops after a short trailing silence.
 * "Speech" is anything clearly above the room's own background level (learned while
 * waiting), so normal talking at arm's length works; quiet speech is then boosted
 * before it is handed on. Callbacks are delivered on the main thread.
 */
class AudioCapture(private val context: Context) {

    @Volatile private var active = false
    @Volatile private var generation = 0
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO is granted
    fun start(
        onSpeechStart: () -> Unit,
        onResult: (File?) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (active) return
        active = true
        val gen = ++generation
        thread = Thread {
            var record: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                val bufSize = maxOf(minBuf, SAMPLE_RATE) // ~1s internal headroom
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL, ENCODING, bufSize,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    post { onError("Microphone unavailable") }
                    return@Thread
                }

                val pcm = ByteArrayOutputStream()
                val frame = ShortArray(FRAME_SAMPLES) // ~50ms frames for snappy onset
                // Pre-roll ring buffer: keep the most recent frames so that when speech
                // is detected we can PREPEND them — otherwise the start of the first
                // word (the frames before detection) is lost.
                val preRoll = ArrayDeque<ByteArray>()
                var preRollMs = 0
                record.startRecording()

                // Ambient level = the quietest recent frame before speech begins (minimum statistics).
                val ambient = ArrayDeque<Double>()
                var noise = 0.0
                var frameIndex = 0
                val speechLevels = ArrayList<Double>() // RMS of each frame counted as speech
                var speechStarted = false
                var speechFrames = 0
                var silenceMs = 0
                var elapsedMs = 0
                var notifiedStart = false

                while (active && gen == generation) {
                    val n = record.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    val frameMs = n * 1000 / SAMPLE_RATE
                    elapsedMs += frameMs

                    val rms = rms(frame, n)
                    frameIndex++
                    // The first frames are mic warm-up (often digital silence or a click): ignore them.
                    val warm = frameIndex > WARMUP_FRAMES
                    if (warm && !speechStarted) {
                        ambient.addLast(rms)
                        if (ambient.size > AMBIENT_FRAMES) ambient.removeFirst()
                        noise = ambient.min()
                    }
                    // Frozen once speech starts, so a quiet talker can't raise their own bar.
                    val threshold = (noise * NOISE_FACTOR).coerceIn(MIN_SPEECH_RMS, MAX_SPEECH_RMS)
                    val loud = warm && rms > threshold
                    if (loud) speechLevels.add(rms)

                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        bytes[i * 2] = (frame[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (frame[i].toInt() shr 8 and 0xFF).toByte()
                    }

                    if (loud) {
                        speechFrames++
                        if (speechFrames >= 2) speechStarted = true
                        silenceMs = 0
                    } else if (speechStarted) {
                        silenceMs += frameMs
                    }

                    if (speechStarted) {
                        if (!notifiedStart) {
                            notifiedStart = true
                            // Flush the pre-roll first so the word onset is included.
                            while (preRoll.isNotEmpty()) pcm.write(preRoll.removeFirst())
                            post { onSpeechStart() }
                        }
                        pcm.write(bytes)
                    } else {
                        // Not speaking yet — keep this frame in the pre-roll (capped).
                        preRoll.addLast(bytes)
                        preRollMs += frameMs
                        while (preRollMs > PREROLL_MS && preRoll.isNotEmpty()) {
                            preRoll.removeFirst()
                            preRollMs -= frameMs
                        }
                    }

                    val ended = speechStarted && silenceMs >= END_SILENCE_MS
                    val tooLong = elapsedMs >= MAX_MS
                    val noSpeech = !speechStarted && elapsedMs >= NO_SPEECH_MS
                    if (ended || tooLong || noSpeech) break
                }

                record.stop()

                // Require enough *real* loud speech (not just bytes — pre-roll padding
                // would inflate the byte count) before transcribing, else treat as
                // no-speech. Stops ambient noise/silence blips from being sent to
                // Scribe (which hallucinates phantom phrases on near-silence).
                val enough = speechStarted && speechFrames >= MIN_SPEECH_FRAMES
                val superseded = gen != generation
                if (superseded) {
                    // A newer capture took over — don't deliver a stale result.
                } else if (!enough) {
                    post { onResult(null) }
                } else {
                    val samples = pcm.toByteArray()
                    // Typical loudness of the speech; quiet talkers are lifted to a healthy level.
                    val level = speechLevels.sorted()[(speechLevels.size * 0.9).toInt().coerceAtMost(speechLevels.size - 1)]
                    val gain = (TARGET_RMS / level).coerceIn(1.0, MAX_GAIN)
                    if (gain > 1.05) amplify(samples, gain)
                    Log.d(TAG, "ambient=${noise.roundToInt()} speech=${level.roundToInt()} gain=${"%.1f".format(gain)}")
                    val file = writeWav(samples)
                    post { onResult(file) }
                }
            } catch (e: Exception) {
                if (gen == generation) post { onError(e.message ?: "Recording failed") }
            } finally {
                // Only clear the shared flag if we're still the current generation,
                // so a stale thread can't disarm a newer capture.
                if (gen == generation) active = false
                runCatching { record?.release() }
            }
        }.also { it.start() }
    }

    /** Stop recording; whatever was captured so far is returned via onResult. */
    fun stop() { active = false }

    /** Abort with no result. */
    fun cancel() {
        active = false
        thread = null
    }

    /** Scale 16-bit little-endian PCM in place by [gain], clipping at full scale. */
    private fun amplify(pcm: ByteArray, gain: Double) {
        var i = 0
        while (i + 1 < pcm.size) {
            val v = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8) // sign-extends via the high byte
            val out = (v * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i] = (out and 0xFF).toByte()
            pcm[i + 1] = (out shr 8 and 0xFF).toByte()
            i += 2
        }
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }

    private fun writeWav(pcm: ByteArray): File {
        val file = File(context.cacheDir, "stt_${pcm.size}.wav")
        RandomAccessFile(file, "rw").use { out ->
            val dataLen = pcm.size
            val totalLen = dataLen + 36
            val byteRate = SAMPLE_RATE * 2
            val header = ByteArray(44)
            fun putInt(off: Int, v: Int) {
                header[off] = (v and 0xFF).toByte()
                header[off + 1] = (v shr 8 and 0xFF).toByte()
                header[off + 2] = (v shr 16 and 0xFF).toByte()
                header[off + 3] = (v shr 24 and 0xFF).toByte()
            }
            fun putShort(off: Int, v: Int) {
                header[off] = (v and 0xFF).toByte()
                header[off + 1] = (v shr 8 and 0xFF).toByte()
            }
            "RIFF".toByteArray().copyInto(header, 0)
            putInt(4, totalLen)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            putInt(16, 16)          // PCM fmt chunk size
            putShort(20, 1)         // audio format = PCM
            putShort(22, 1)         // channels = mono
            putInt(24, SAMPLE_RATE)
            putInt(28, byteRate)
            putShort(32, 2)         // block align
            putShort(34, 16)        // bits per sample
            "data".toByteArray().copyInto(header, 36)
            putInt(40, dataLen)
            out.write(header)
            out.write(pcm)
        }
        return file
    }

    private fun post(block: () -> Unit) = main.post(block)

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SAMPLES = SAMPLE_RATE / 20 // 50ms frames
        const val PREROLL_MS = 600        // audio kept before detection so word onsets aren't clipped
        const val TAG = "AudioCapture"
        // Speech = RMS above NOISE_FACTOR x the room's ambient level, clamped to this range. The floor
        // stops mic hiss counting as speech; the ceiling stops a wrong ambient estimate (e.g. someone
        // already talking when recording began) from demanding a shout. Was a fixed 1000, which needed
        // raised-voice, close-to-the-phone speaking.
        const val MIN_SPEECH_RMS = 250.0
        const val MAX_SPEECH_RMS = 700.0
        const val NOISE_FACTOR = 3.0
        const val WARMUP_FRAMES = 3       // 150 ms of mic start-up ignored for detection
        const val AMBIENT_FRAMES = 20     // ~1 s window for the ambient estimate
        const val TARGET_RMS = 3000.0     // level quiet speech is lifted to (about -20 dBFS)
        const val MAX_GAIN = 12.0         // never amplify more than this, so noise isn't blown up
        const val END_SILENCE_MS = 1800   // trailing silence that ends a turn (allows mid-sentence pauses)
        const val NO_SPEECH_MS = 8000     // give up if nobody speaks
        const val MAX_MS = 30000          // hard cap on a single utterance
        const val MIN_SPEECH_FRAMES = 6   // ~300ms of actual loud speech required (50ms frames)
    }
}
