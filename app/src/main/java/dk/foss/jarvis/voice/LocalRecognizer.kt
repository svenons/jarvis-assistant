package dk.foss.jarvis.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fully on-device speech-to-text (sherpa-onnx). Records an utterance with [AudioCapture]
 * (same silence detection as [ScribeRecognizer]) and transcribes it locally with the chosen
 * [LocalSttModel] — no network, no Google speech service, so it works on GrapheneOS. Like
 * Scribe there are no live partials: the transcript arrives on onFinal.
 */
class LocalRecognizer(context: Context, modelId: String) : VoiceRecognizer {

    private val store = LocalSttStore.get(context)
    private val model = store.model(modelId)
    private val capture = AudioCapture(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Bumped by stop()/release()/start() so a stale capture or decode can't deliver. */
    @Volatile private var generation = 0

    override fun isAvailable(): Boolean = store.isReady(model)

    override fun prewarm() {
        if (!store.isReady(model)) return
        scope.launch(Dispatchers.Default) { runCatching { LocalSttEngine.prewarm(store, model) } }
    }

    override fun start(languageTag: String?, listener: VoiceRecognizer.Listener) {
        val gen = ++generation
        capture.cancel()
        if (!store.isReady(model)) {
            // Not a hiccup worth retrying — the user has to download the model first.
            listener.onError("Speech model “${model.label}” not downloaded — get it in Settings", transient = false)
            return
        }
        listener.onReady()
        capture.start(
            onSpeechStart = {},
            onResult = { file ->
                if (gen != generation) {
                    file?.delete()
                } else {
                    listener.onEnd()
                    if (file == null) listener.onError("No speech heard", transient = false)
                    else scope.launch { deliver(gen, file, listener) }
                }
            },
            onError = { msg -> if (gen == generation) listener.onError(msg, transient = true) },
        )
    }

    private suspend fun deliver(gen: Int, file: File, listener: VoiceRecognizer.Listener) {
        val result = withContext(Dispatchers.Default) {
            runCatching { LocalSttEngine.transcribe(store, model, readPcm(file)) }.also { file.delete() }
        }
        if (gen != generation) return
        result.fold(
            onSuccess = { text ->
                if (text.isBlank()) listener.onError("No speech heard", transient = false)
                else listener.onFinal(text)
            },
            // A load failure (missing/corrupt model, out of memory) won't fix itself on retry.
            onFailure = { listener.onError("On-device speech model failed: ${it.message}", transient = false) },
        )
    }

    override fun stop() {
        generation++
        capture.cancel()
    }

    override fun release() {
        generation++
        capture.cancel()
        runCatching { scope.cancel() }
        LocalSttEngine.scheduleRelease() // keep the model warm briefly for the next conversation
    }

    /** AudioCapture writes 16 kHz mono 16-bit PCM behind a 44-byte WAV header. */
    private fun readPcm(file: File): FloatArray {
        val bytes = file.readBytes()
        val n = (bytes.size - WAV_HEADER_BYTES) / 2
        val out = FloatArray(n)
        val pcm = ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = pcm.short / 32768f
        return out
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }
}

/**
 * Process-wide holder for the loaded model. Big models take seconds and hundreds of MB to load,
 * so it stays loaded across conversations and is freed after [IDLE_RELEASE_MS]. Only one model
 * is loaded at a time; asking for a different one frees the old one first. All native access
 * goes through [lock]: releasing during a decode would crash.
 */
internal object LocalSttEngine {
    /** Last measured timings for a model, for the speed comparison in Settings. */
    data class Timing(val loadMs: Long = 0, val audioMs: Long = 0, val decodeMs: Long = 0)

    private const val SAMPLE_RATE = 16_000
    private const val IDLE_RELEASE_MS = 5 * 60_000L

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var loadedId: String? = null
    private val main = Handler(Looper.getMainLooper())
    // Freed on a worker thread: release() waits for any in-flight decode.
    private val idleRelease = Runnable { Thread { release() }.start() }

    private val _timings = MutableStateFlow<Map<String, Timing>>(emptyMap())
    val timings: StateFlow<Map<String, Timing>> = _timings

    fun prewarm(store: LocalSttStore, model: LocalSttModel) {
        main.removeCallbacks(idleRelease)
        synchronized(lock) { load(store, model) }
    }

    fun transcribe(store: LocalSttStore, model: LocalSttModel, samples: FloatArray): String {
        main.removeCallbacks(idleRelease)
        return synchronized(lock) {
            val rec = load(store, model)
            val start = SystemClock.elapsedRealtime()
            val stream = rec.createStream()
            val text = try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
            val decodeMs = SystemClock.elapsedRealtime() - start
            record(model.id) { it.copy(audioMs = samples.size * 1000L / SAMPLE_RATE, decodeMs = decodeMs) }
            text
        }
    }

    fun scheduleRelease() {
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    fun release() = synchronized(lock) {
        recognizer?.release()
        recognizer = null
        loadedId = null
    }

    fun releaseIfLoaded(id: String) = synchronized(lock) { if (loadedId == id) release() }

    private fun load(store: LocalSttStore, model: LocalSttModel): OfflineRecognizer {
        recognizer?.let { if (loadedId == model.id) return it }
        release() // switching models: free the old one before allocating the new one
        val start = SystemClock.elapsedRealtime()
        // null AssetManager → load from the file paths in the config, not from app assets.
        val rec = OfflineRecognizer(null, configFor(store, model))
        recognizer = rec
        loadedId = model.id
        record(model.id) { it.copy(loadMs = SystemClock.elapsedRealtime() - start) }
        return rec
    }

    private fun record(id: String, change: (Timing) -> Timing) =
        _timings.update { it + (id to change(it[id] ?: Timing())) }

    private fun configFor(store: LocalSttStore, model: LocalSttModel): OfflineRecognizerConfig {
        fun path(role: LocalSttModel.Role) = store.file(model, model.part(role).name).path
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val modelConfig = when (model.family) {
            LocalSttModel.Family.NemoTransducer -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path(LocalSttModel.Role.Encoder),
                    decoder = path(LocalSttModel.Role.Decoder),
                    joiner = path(LocalSttModel.Role.Joiner),
                ),
                tokens = path(LocalSttModel.Role.Tokens),
                numThreads = threads,
                modelType = "nemo_transducer",
            )
            LocalSttModel.Family.Whisper -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = path(LocalSttModel.Role.Encoder),
                    decoder = path(LocalSttModel.Role.Decoder),
                    language = model.language,
                ),
                tokens = path(LocalSttModel.Role.Tokens),
                numThreads = threads,
                modelType = "whisper",
            )
            LocalSttModel.Family.Moonshine -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = path(LocalSttModel.Role.Preprocessor),
                    encoder = path(LocalSttModel.Role.Encoder),
                    uncachedDecoder = path(LocalSttModel.Role.UncachedDecoder),
                    cachedDecoder = path(LocalSttModel.Role.CachedDecoder),
                ),
                tokens = path(LocalSttModel.Role.Tokens),
                numThreads = threads,
            )
        }
        return OfflineRecognizerConfig(modelConfig = modelConfig)
    }
}
