package dk.foss.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * An on-device voice, shipped as one `.tar.bz2` on the sherpa-onnx `tts-models` release. The
 * archive's SHA-256 is pinned (GitHub's own asset digest), and it's unpacked after it verifies.
 * Every family except Supertonic needs the `espeak-ng-data` folder that comes inside the archive.
 */
class LocalTtsModel(
    val id: String,
    val label: String,
    /** One line for the picker. Deliberately no speed numbers — the app measures those. */
    val blurb: String,
    val family: Family,
    archiveName: String,
    val archiveBytes: Long,
    val archiveSha256: String,
    /** Main model file inside the archive (also what marks an install as complete). */
    val model: String,
    /** Speaker-embeddings file (Kokoro, Kitten); null for single-voice Piper models. */
    val voices: String? = null,
) {
    /** Decides which sherpa-onnx model config [LocalTtsEngine] builds. */
    enum class Family { Vits, Kokoro, Kitten, Supertonic }

    val archiveUrl: String = "$RELEASE_URL/$archiveName"

    companion object {
        /** Persisted in settings; also the folder name, so it must stay stable across versions. */
        const val DEFAULT_ID = "piper-en_US-lessac-medium-int8"

        private const val RELEASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

        /**
         * Ordered fastest → slowest by the real-time factor measured with the desktop sherpa-onnx 1.13.8 on
         * one sentence (4 threads): Supertonic 0.05, Piper low 0.14, Kitten 0.27, Piper medium 0.33,
         * Kokoro 1.3. A phone is slower in absolute terms; Settings shows the real numbers per voice.
         */
        val all: List<LocalTtsModel> = listOf(
            LocalTtsModel(
                id = "supertonic-en-int8",
                label = "Supertonic (English)",
                blurb = "Built for speed: the quickest of these in testing.",
                family = Family.Supertonic,
                archiveName = "sherpa-onnx-supertonic-tts-int8-2026-03-06.tar.bz2",
                archiveBytes = 84_692_981,
                archiveSha256 = "8c74359f63edd5045d47747f65331f0f6dbcbc91d7e898dd756d631295fe3259",
                model = "vector_estimator.int8.onnx",
            ),
            LocalTtsModel(
                id = "piper-en_US-lessac-low-int8",
                label = "Piper Lessac low (US English)",
                blurb = "Small and quick; lower audio quality (16 kHz).",
                family = Family.Vits,
                archiveName = "vits-piper-en_US-lessac-low-int8.tar.bz2",
                archiveBytes = 21_070_568,
                archiveSha256 = "af63fbe60d8bdcfccdee61ba057304a11dfc077145da383d4d351ec3c594d5e2",
                model = "en_US-lessac-low.onnx",
            ),
            LocalTtsModel(
                id = "kitten-nano-en-v0_8-int8",
                label = "Kitten nano v0.8 (English)",
                blurb = "Tiny model.",
                family = Family.Kitten,
                archiveName = "kitten-nano-en-v0_8-int8.tar.bz2",
                archiveBytes = 31_220_690,
                archiveSha256 = "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd",
                model = "model.int8.onnx",
                voices = "voices.bin",
            ),
            LocalTtsModel(
                id = DEFAULT_ID,
                label = "Piper Lessac medium (US English)",
                blurb = "Clear and light.",
                family = Family.Vits,
                archiveName = "vits-piper-en_US-lessac-medium-int8.tar.bz2",
                archiveBytes = 20_969_179,
                archiveSha256 = "f1c6d0295cf16087b05f80fdca5b44daca5cd78e2c425d419a42ba34929805f9",
                model = "en_US-lessac-medium.onnx",
            ),
            LocalTtsModel(
                id = "kokoro-int8-en-v0_19",
                label = "Kokoro v0.19 (English)",
                blurb = "Highest quality of these, but heavy: may not keep up in real time on a phone.",
                family = Family.Kokoro,
                archiveName = "kokoro-int8-en-v0_19.tar.bz2",
                archiveBytes = 103_248_205,
                archiveSha256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
                model = "model.int8.onnx",
                voices = "voices.bin",
            ),
        )

        fun byId(id: String): LocalTtsModel = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
    }
}

/** Owns the downloaded voices under `filesDir/tts/<id>/content/`. */
class LocalTtsStore private constructor(context: Context) : ModelStore<LocalTtsModel>() {

    val models: List<LocalTtsModel> = LocalTtsModel.all

    private val root = File(context.filesDir, "tts")

    init {
        // Voices that left the catalog (bigger, slower builds of the same models) would otherwise
        // sit on disk forever with no way to delete them from Settings.
        val known = models.map { it.id }.toSet()
        root.listFiles()?.forEach { if (it.isDirectory && it.name !in known) it.deleteRecursively() }
        initStates(models)
    }

    fun model(id: String): LocalTtsModel = LocalTtsModel.byId(id)

    /** Where the unpacked voice lives once installed. */
    fun content(model: LocalTtsModel) = File(dir(model), "content")

    override fun idOf(model: LocalTtsModel) = model.id
    override fun totalBytes(model: LocalTtsModel) = model.archiveBytes
    override fun dir(model: LocalTtsModel) = File(root, model.id)

    // The marker is written last, so a half-unpacked voice is never considered ready.
    override fun isReady(model: LocalTtsModel): Boolean =
        File(dir(model), COMPLETE).isFile && File(content(model), model.model).isFile

    override suspend fun install(model: LocalTtsModel, progress: (Long) -> Unit) {
        val archive = File(dir(model), "archive.tar.bz2")
        fetch(model, model.archiveUrl, archive, model.archiveBytes, model.archiveSha256, progress)
        setInstalling(model, 0, model.archiveBytes)
        val staging = File(dir(model), "staging")
        staging.deleteRecursively()
        try {
            unpack(archive, staging) { setInstalling(model, it, model.archiveBytes) }
            content(model).deleteRecursively()
            if (!staging.renameTo(content(model))) throw IOException("Could not install ${model.label}")
            File(dir(model), COMPLETE).writeText("ok")
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        archive.delete()
    }

    override fun releaseFromMemory(model: LocalTtsModel) = LocalTtsEngine.releaseIfLoaded(model.id)

    /**
     * Unpack a `.tar.bz2` into [dest], dropping the archive's single top-level folder. bzip2 is
     * decoded in pure Java and is CPU-bound (~5 s per 67 MB on a fast desktop, several times that on
     * a phone), so [onProgress] reports how much of the archive has been consumed.
     */
    private suspend fun unpack(archive: File, dest: File, onProgress: (Long) -> Unit) {
        dest.mkdirs()
        val base = dest.canonicalPath + File.separator
        val source = BufferedInputStream(CountingInput(archive.inputStream(), onProgress))
        TarArchiveInputStream(BZip2CompressorInputStream(source)).use { tar ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val entry = tar.nextEntry ?: break
                currentCoroutineContext().ensureActive()
                val rel = entry.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(dest, rel).canonicalFile
                if (!out.path.startsWith(base)) throw IOException("Unsafe path in archive: ${entry.name}")
                if (entry.isDirectory) { out.mkdirs(); continue }
                if (!entry.isFile) continue // no symlinks or devices
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { o ->
                    while (true) {
                        val n = tar.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /** Counts bytes read from the archive file, reporting every ~512 KB. */
    private class CountingInput(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            count += n
            if (count - lastReport >= 512 * 1024) { lastReport = count; onBytes(count) }
        }
    }

    companion object {
        private const val COMPLETE = ".complete"
        @Volatile private var instance: LocalTtsStore? = null
        fun get(context: Context): LocalTtsStore =
            instance ?: synchronized(this) {
                instance ?: LocalTtsStore(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Process-wide holder for the loaded voice; stays loaded across sentences and is freed after
 * [IDLE_RELEASE_MS]. One voice is loaded at a time. Native access goes through [lock].
 */
internal object LocalTtsEngine {
    /** Last measured timings for a voice, for the speed comparison in Settings. */
    data class Timing(val loadMs: Long = 0, val firstAudioMs: Long = 0, val genMs: Long = 0, val audioMs: Long = 0)

    private const val IDLE_RELEASE_MS = 5 * 60_000L

    private val lock = Any()
    private var tts: OfflineTts? = null
    private var loadedId: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val idleRelease = Runnable { Thread { release() }.start() }

    private val _timings = MutableStateFlow<Map<String, Timing>>(emptyMap())
    val timings: StateFlow<Map<String, Timing>> = _timings

    /**
     * Synthesize [text], passing audio to [onChunk] as it is produced ([onChunk] returns false to
     * stop early). Timings are recorded only for a run that completes.
     */
    fun generate(
        store: LocalTtsStore,
        model: LocalTtsModel,
        text: String,
        onChunk: (samples: FloatArray, sampleRate: Int) -> Boolean,
    ) {
        main.removeCallbacks(idleRelease)
        synchronized(lock) {
            val engine = load(store, model)
            val rate = engine.sampleRate()
            val start = SystemClock.elapsedRealtime()
            var firstAudioMs = 0L
            var samples = 0L
            var stopped = false
            val audio = engine.generateWithCallback(text, 0, 1.0f) { chunk ->
                if (samples == 0L) firstAudioMs = SystemClock.elapsedRealtime() - start
                samples += chunk.size
                if (onChunk(chunk, rate)) 1 else { stopped = true; 0 }
            }
            // A model that never streams still returns its audio: play that rather than stay silent.
            if (samples == 0L && !stopped && audio.samples.isNotEmpty()) {
                firstAudioMs = SystemClock.elapsedRealtime() - start
                samples = audio.samples.size.toLong()
                if (!onChunk(audio.samples, rate)) stopped = true
            }
            if (!stopped) {
                val genMs = SystemClock.elapsedRealtime() - start
                record(model.id) { it.copy(firstAudioMs = firstAudioMs, genMs = genMs, audioMs = samples * 1000L / rate) }
            }
        }
    }

    fun prewarm(store: LocalTtsStore, model: LocalTtsModel) {
        main.removeCallbacks(idleRelease)
        synchronized(lock) { load(store, model) }
    }

    fun scheduleRelease() {
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    fun release() = synchronized(lock) {
        tts?.release()
        tts = null
        loadedId = null
    }

    fun releaseIfLoaded(id: String) = synchronized(lock) { if (loadedId == id) release() }

    private fun load(store: LocalTtsStore, model: LocalTtsModel): OfflineTts {
        tts?.let { if (loadedId == model.id) return it }
        release() // switching voices: free the old one before allocating the new one
        val start = SystemClock.elapsedRealtime()
        // null AssetManager → load from the file paths in the config, not from app assets.
        val engine = OfflineTts(null, configFor(store, model))
        tts = engine
        loadedId = model.id
        record(model.id) { it.copy(loadMs = SystemClock.elapsedRealtime() - start) }
        return engine
    }

    private fun record(id: String, change: (Timing) -> Timing) =
        _timings.update { it + (id to change(it[id] ?: Timing())) }

    private fun configFor(store: LocalTtsStore, model: LocalTtsModel): OfflineTtsConfig {
        val dir = store.content(model)
        fun path(name: String) = File(dir, name).path
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val espeak = path("espeak-ng-data") // not used by Supertonic
        val modelConfig = when (model.family) {
            LocalTtsModel.Family.Vits -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = path(model.model), tokens = path("tokens.txt"), dataDir = espeak),
                numThreads = threads,
            )
            LocalTtsModel.Family.Kokoro -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = path(model.model),
                    voices = path(requireNotNull(model.voices)),
                    tokens = path("tokens.txt"),
                    dataDir = espeak,
                ),
                numThreads = threads,
            )
            LocalTtsModel.Family.Kitten -> OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = path(model.model),
                    voices = path(requireNotNull(model.voices)),
                    tokens = path("tokens.txt"),
                    dataDir = espeak,
                ),
                numThreads = threads,
            )
            LocalTtsModel.Family.Supertonic -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = path("duration_predictor.int8.onnx"),
                    textEncoder = path("text_encoder.int8.onnx"),
                    vectorEstimator = path(model.model),
                    vocoder = path("vocoder.int8.onnx"),
                    ttsJson = path("tts.json"),
                    unicodeIndexer = path("unicode_indexer.bin"),
                    voiceStyle = path("voice.bin"),
                ),
                numThreads = threads,
            )
        }
        return OfflineTtsConfig(model = modelConfig)
    }
}

/**
 * Speaks through a downloaded on-device voice (sherpa-onnx) — no network and no system TTS
 * engine, so it works on GrapheneOS. Audio is streamed to an [AudioTrack] as it is generated.
 * Single-flight like the other engines: a new [speak] or a [stop] invalidates the previous one.
 */
class LocalTts(context: Context, modelId: String) : TtsEngine {

    private val store = LocalTtsStore.get(context)
    private val model = store.model(modelId)
    private val main = Handler(Looper.getMainLooper())

    /** Bumped by every speak()/stop() so a stale synthesis or playback bails out. */
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val gen = ++generation
        stopTrack()
        if (!store.isReady(model)) {
            main.post { onError("Voice “${model.label}” not downloaded") }
            return
        }
        Thread {
            try {
                if (Playback(gen).run(text)) main.post { if (gen == generation) onDone() }
            } catch (e: Throwable) {
                // Stopped mid-write, or the model failed to load — only report the latter.
                if (gen == generation) main.post { onError(e.message ?: "On-device voice failed") }
            }
        }.start()
    }

    override fun stop() {
        generation++
        stopTrack()
    }

    override fun shutdown() {
        stop()
        LocalTtsEngine.scheduleRelease() // keep the voice warm briefly for the next conversation
    }

    /** Drop queued audio and unblock a writer stuck on a full buffer. */
    private fun stopTrack() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }

    /**
     * Plays one utterance. Starts the track only once ~[PREBUFFER_S] of audio is queued (or
     * generation has finished), so a voice that synthesizes slower than real time doesn't stutter.
     */
    private inner class Playback(private val gen: Int) {
        private var out: AudioTrack? = null
        private val pending = ArrayList<FloatArray>()
        private var queued = 0L
        private var written = 0L
        private var rate = 0

        /** Returns true if it played to the end, false if it was stopped. */
        fun run(text: String): Boolean {
            try {
                LocalTtsEngine.generate(store, model, text) { chunk, sampleRate ->
                    rate = sampleRate
                    add(chunk)
                }
                if (gen != generation) return false
                if (out == null && !start()) return false
                return drain()
            } finally {
                out?.let { t -> runCatching { t.release() }; if (track === t) track = null }
            }
        }

        private fun add(chunk: FloatArray): Boolean {
            if (gen != generation) return false
            if (out == null) {
                pending += chunk
                queued += chunk.size
                return if (queued >= rate * PREBUFFER_S) start() else true
            }
            return write(chunk)
        }

        private fun start(): Boolean {
            if (gen != generation) return false
            val t = newTrack(rate)
            out = t
            track = t
            t.play() // must be playing before writing: a blocking write to a stopped track never returns
            for (c in pending) if (!write(c)) return false
            pending.clear()
            return true
        }

        private fun write(chunk: FloatArray): Boolean {
            val t = out ?: return false
            var off = 0
            while (off < chunk.size) {
                if (gen != generation) return false
                val n = t.write(chunk, off, chunk.size - off, AudioTrack.WRITE_BLOCKING)
                if (n < 0) return false
                off += n
            }
            written += chunk.size
            return true
        }

        /** Wait for the queued audio to finish playing. */
        private fun drain(): Boolean {
            val t = out ?: return true // nothing was synthesized
            val deadline = SystemClock.elapsedRealtime() + written * 1000L / rate + 2_000
            while (gen == generation && t.playbackHeadPosition < written) {
                if (SystemClock.elapsedRealtime() > deadline) break
                Thread.sleep(20)
            }
            return gen == generation
        }

        private fun newTrack(sampleRate: Int): AudioTrack {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 4 * 2)) // ~2 s of float samples
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }

    private companion object {
        const val PREBUFFER_S = 0.6
    }
}
