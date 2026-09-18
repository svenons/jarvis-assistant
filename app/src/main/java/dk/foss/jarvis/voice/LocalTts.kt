package dk.foss.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
         * Tiny voices only (each a download of about 100 MB or less), ordered fastest to slowest by the real-time
         * factor measured with desktop sherpa-onnx 1.13.8, 4 threads, on one sentence: Supertonic 0.05,
         * Inflect nano 0.085, Piper low 0.15, Kitten nano 0.29, Piper medium 0.33. A phone is slower in absolute
         * terms; Settings shows the real numbers per voice. Left out on purpose: Kokoro (103 MB, RTF 1.3, slower
         * than real time even on a desktop) and Supertonic 3 (129 MB, RTF 0.27).
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
                id = "inflect-nano-en-v2",
                label = "Inflect nano v2 (English)",
                blurb = "Newest tiny voice, and the smallest download that is also quick.",
                family = Family.Vits,
                archiveName = "vits-inflect-en-nano-v2.tar.bz2",
                archiveBytes = 22_429_639,
                archiveSha256 = "9a6b1188b5f3be8813e0552056328495b4e88ffd1ef18ff837272bde7b3bc136",
                model = "model.onnx",
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
class LocalTts(context: Context, modelId: String) : QueuedTts {

    private val store = LocalTtsStore.get(context)
    private val model = store.model(modelId)
    private val main = Handler(Looper.getMainLooper())

    /** One sentence in flight. Frames are counted along the shared output track, across sentences. */
    private class Item(
        val text: String,
        val gen: Int,
        val onStart: () -> Unit,
        val onDone: () -> Unit,
        val onError: (String) -> Unit,
    ) {
        @Volatile var startFrame = -1L
        @Volatile var endFrame = -1L // -1 until its synthesis has finished
        @Volatile var deadlineMs = 0L // give up waiting for playback to reach endFrame after this
        var started = false // main thread only
    }

    /** The output for one generation. stop() swaps in a fresh one, so a stale worker can't touch the next run's state. */
    private class Out {
        @Volatile var track: AudioTrack? = null
        @Volatile var rate = 0
        @Volatile var started = false // play() has been called
        var fed = 0L // frames handed in, whether written to the track yet or still buffered
        val pending = ArrayList<FloatArray>() // held back until PREBUFFER_S is queued, so a slow voice doesn't stutter
        var pendingFrames = 0L
    }

    @Volatile private var generation = 0
    @Volatile private var out = Out()

    private val lock = Any()
    private val waiting = java.util.ArrayDeque<Item>() // waiting for synthesis; guarded by lock
    private val playing = ArrayList<Item>() // synthesizing or waiting to finish playing; guarded by lock
    private var worker: Thread? = null // guarded by lock

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        stop()
        enqueue(text, {}, onDone, onError)
    }

    override fun enqueue(text: String, onStart: () -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        if (!store.isReady(model)) {
            main.post { onError("Voice “${model.label}” not downloaded") }
            return
        }
        if (text.none { it.isLetterOrDigit() }) { // punctuation only: nothing to say, and nothing to synthesize
            main.post { onStart(); onDone() }
            return
        }
        main.removeCallbacks(idleClose)
        val item = Item(text, generation, onStart, onDone, onError)
        synchronized(lock) {
            waiting.addLast(item)
            if (worker == null) {
                val gen = generation
                val o = out
                worker = Thread { workerLoop(gen, o) }.also { it.start() }
            }
        }
    }

    override fun stop() {
        generation++
        main.removeCallbacks(poll)
        main.removeCallbacks(idleClose)
        val old = out
        out = Out()
        synchronized(lock) { waiting.clear(); playing.clear(); worker = null }
        closeTrack(old) // also unblocks a writer stuck on a full buffer
    }

    override fun shutdown() {
        stop()
        LocalTtsEngine.scheduleRelease() // keep the voice warm briefly for the next conversation
    }

    // --- synthesis: one worker thread turns queued sentences into audio, ahead of playback ---

    private fun workerLoop(gen: Int, o: Out) {
        while (true) {
            val item = synchronized(lock) {
                if (gen != generation) return // stopped: a newer worker owns the queue now
                waiting.pollFirst() ?: run { worker = null; return }
            }
            synthesize(item, gen, o)
        }
    }

    private fun synthesize(item: Item, gen: Int, o: Out) {
        var attempt = 0
        while (gen == generation) {
            val fedBefore = o.fed
            try {
                val frames = speakInto(item, gen, o) ?: return // null: stopped
                // A voice that "succeeds" with no audio is a failure too, not a silent skip.
                if (frames == 0L) throw IllegalStateException("The voice produced no audio for this sentence")
                finish(item, frames, o)
                return
            } catch (e: Throwable) {
                if (gen != generation) return
                Log.w(TAG, "voice failed (attempt ${attempt + 1}) on \"${item.text.take(40)}\"", e)
                // Retry once with the model reloaded (a wedged native model is the likeliest cause), but only if
                // nothing of this sentence reached the speaker yet: replaying would repeat the start of it.
                if (attempt++ == 0 && o.fed == fedBefore) {
                    LocalTtsEngine.release()
                    continue
                }
                fail(item, e.message ?: e.javaClass.simpleName)
                return
            }
        }
    }

    /** Synthesize [item], streaming chunks into the shared output. Returns the frames produced, or null if stopped. */
    private fun speakInto(item: Item, gen: Int, o: Out): Long? {
        var produced = 0L
        var stopped = false
        LocalTtsEngine.generate(store, model, item.text) { chunk, sampleRate ->
            if (gen != generation) { stopped = true; return@generate false }
            if (produced == 0L) {
                item.startFrame = o.fed
                synchronized(lock) { playing += item }
                main.post(poll) // the sentence is now in the playing list: start watching playback
            }
            o.rate = sampleRate
            produced += chunk.size
            if (feed(chunk, gen, o)) true else { stopped = true; false }
        }
        if (stopped || gen != generation) return null
        // A short sentence never reached the prebuffer: start playing what there is.
        if (!o.started && !startTrack(gen, o)) return null
        return produced
    }

    private fun feed(chunk: FloatArray, gen: Int, o: Out): Boolean {
        if (gen != generation || o.rate <= 0) return false
        o.fed += chunk.size
        if (!o.started) {
            o.pending += chunk
            o.pendingFrames += chunk.size
            return if (o.pendingFrames >= o.rate * PREBUFFER_S) startTrack(gen, o) else true
        }
        return write(chunk, gen, o)
    }

    private fun startTrack(gen: Int, o: Out): Boolean {
        if (gen != generation || o.rate <= 0) return false
        val t = o.track ?: newTrack(o.rate).also { o.track = it }
        t.play() // must be playing before writing: a blocking write to a stopped track never returns
        o.started = true
        for (c in o.pending) if (!write(c, gen, o)) return false
        o.pending.clear()
        o.pendingFrames = 0
        return true
    }

    /** Blocking write into the track. It blocks once ~2 s is queued, which is what keeps synthesis from racing far ahead. */
    private fun write(chunk: FloatArray, gen: Int, o: Out): Boolean {
        val t = o.track ?: return false
        var off = 0
        while (off < chunk.size) {
            if (gen != generation) return false
            val n = t.write(chunk, off, chunk.size - off, AudioTrack.WRITE_BLOCKING)
            if (n < 0) return false
            off += n
        }
        return true
    }

    /** Synthesis finished: from here on the sentence is "done" once playback reaches its last frame. */
    private fun finish(item: Item, frames: Long, o: Out) {
        val head = o.track?.playbackHeadPosition?.toLong() ?: 0L
        val ahead = (item.startFrame + frames - head).coerceAtLeast(0)
        item.deadlineMs = SystemClock.elapsedRealtime() + ahead * 1000L / o.rate.coerceAtLeast(1) + 3_000
        item.endFrame = item.startFrame + frames
        main.post(poll)
    }

    /** The sentence could not be spoken: drop it from playback, tell the caller, and let the queue move on. */
    private fun fail(item: Item, message: String) {
        synchronized(lock) { playing.remove(item) }
        main.post { if (item.gen == generation) item.onError(message) }
    }

    // --- playback progress: the main thread watches the track's head and reports sentences starting and finishing ---

    private val poll = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            val o = out
            val head = if (o.started) o.track?.playbackHeadPosition?.toLong() ?: 0L else -1L
            val now = SystemClock.elapsedRealtime()
            val starts = ArrayList<Item>()
            val finished = ArrayList<Item>()
            synchronized(lock) {
                val it = playing.iterator()
                while (it.hasNext()) {
                    val item = it.next()
                    if (item.gen != generation) { it.remove(); continue }
                    if (!item.started && head >= item.startFrame && item.startFrame >= 0) { item.started = true; starts += item }
                    if (item.endFrame >= 0 && ((head >= 0 && head >= item.endFrame) || now > item.deadlineMs)) {
                        finished += item
                        it.remove()
                    }
                }
            }
            starts.forEach { it.onStart() }
            finished.forEach { if (!it.started) it.onStart(); it.onDone() }
            val busy = synchronized(lock) { playing.isNotEmpty() || waiting.isNotEmpty() || worker != null }
            if (busy) main.postDelayed(this, POLL_MS) else main.postDelayed(idleClose, IDLE_CLOSE_MS)
        }
    }

    /** Nothing queued for a while: release the audio track (the voice itself stays loaded). */
    private val idleClose = Runnable {
        val idle = synchronized(lock) { waiting.isEmpty() && playing.isEmpty() && worker == null }
        if (idle) {
            val old = out
            out = Out()
            closeTrack(old)
        }
    }

    private fun closeTrack(o: Out) {
        o.track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        o.track = null
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

    private companion object {
        const val TAG = "LocalTts"
        const val PREBUFFER_S = 0.6
        const val POLL_MS = 25L
        const val IDLE_CLOSE_MS = 4_000L
    }
}
