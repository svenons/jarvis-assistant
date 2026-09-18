package dk.foss.jarvis.voice

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * An on-device speech-recognition model. Either individual files fetched from a *pinned* Hugging Face
 * revision, each checked against a SHA-256 ([files] with hashes), or one `.tar.bz2` from the sherpa-onnx
 * `asr-models` release ([archive], pinned to GitHub's own asset digest) that is unpacked after it verifies.
 */
class LocalSttModel(
    val id: String,
    val label: String,
    /** One line for the picker. Deliberately no speed numbers: the app measures those. */
    val blurb: String,
    val family: Family,
    private val baseUrl: String = "",
    /** The files the engine loads. For an [archive] model these are the unpacked files (sizes are checked; no per-file hash). */
    val files: List<Part>,
    /** Whisper only: fixed language code for a multilingual model. Empty = the model's own default. */
    val language: String = "",
    val archive: Archive? = null,
    /** The model prints ALL CAPS with no punctuation; tidy it into a normal sentence. */
    val uppercaseOutput: Boolean = false,
    /** The model breaks on audio longer than this many seconds (0 = no limit), so longer speech is split at pauses. */
    val maxSegmentSeconds: Int = 0,
) {
    /** Decides which sherpa-onnx model config [LocalSttEngine] builds. */
    enum class Family { NemoTransducer, Transducer, Whisper, Moonshine, MoonshineV2 }

    enum class Role { Encoder, Decoder, Joiner, Tokens, Preprocessor, UncachedDecoder, CachedDecoder }

    class Part(val name: String, val role: Role, val bytes: Long, val sha256: String = "")

    /** A model shipped as one archive on the sherpa-onnx `asr-models` release. */
    class Archive(val name: String, val bytes: Long, val sha256: String)

    /** What one download costs: the archive if there is one, else the sum of the files. */
    val totalBytes: Long = archive?.bytes ?: files.sumOf { it.bytes }

    fun url(part: Part) = "$baseUrl/${part.name}"

    val archiveUrl: String? = archive?.let { "$RELEASE_URL/${it.name}" }

    fun part(role: Role): Part = files.first { it.role == role }

    companion object {
        /** Persisted in settings; also the folder name, so it must stay stable across versions. */
        const val DEFAULT_ID = "parakeet-tdt-110m-en-int8"

        private const val RELEASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

        private fun hf(repo: String, revision: String) =
            "https://huggingface.co/csukuangfj/$repo/resolve/$revision"

        /**
         * Tiny models only (a download of about 140 MB or less), ordered fastest to slowest by the real-time
         * factor measured with desktop sherpa-onnx 1.13.8, 4 threads, on a 5 s clip: Zipformer small 0.014,
         * Parakeet 110M 0.021, Moonshine tiny 0.027 (word error rate 0.13 / 0.00 / 0.00). A phone is slower in
         * absolute terms; Settings shows the real numbers per model. Left out on purpose: Whisper (tiny.en is
         * 4 to 6 times slower than these), Moonshine base, and everything from Parakeet 0.6B (660 MB) up.
         */
        val all: List<LocalSttModel> = listOf(
            LocalSttModel(
                id = "zipformer-small-en-int8",
                label = "Zipformer small (English)",
                blurb = "Smallest and fastest. Less accurate: it can add or miss a word.",
                family = Family.Transducer,
                baseUrl = hf("sherpa-onnx-zipformer-small-en-2023-06-26", "e8add5377a6cb7b629b5a0e08d6afe7a73442814"),
                files = listOf(
                    Part("encoder-epoch-99-avg-1.int8.onnx", Role.Encoder, 26_015_366, "3a6ac78a31cc2c60ca8c1e2e2f43c878fbbcaf051ada4900e0a42ef8ba53d375"),
                    Part("decoder-epoch-99-avg-1.int8.onnx", Role.Decoder, 1_307_236, "f462ab9189ba6f9b2658774e6bf3d651913de54a3833655dc5e093e2f5e4c2b6"),
                    Part("joiner-epoch-99-avg-1.int8.onnx", Role.Joiner, 259_335, "6b183b6ec656e4d3ca6b86d0aeca992dac14df80aae6b416d7c068d0ff2bd4d7"),
                    Part("tokens.txt", Role.Tokens, 5_048, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
                ),
                uppercaseOutput = true,
            ),
            LocalSttModel(
                id = DEFAULT_ID,
                label = "Parakeet TDT 110M (English)",
                blurb = "The most accurate of the tiny models, and still quick.",
                family = Family.NemoTransducer,
                files = listOf(
                    Part("encoder.int8.onnx", Role.Encoder, 131_113_202),
                    Part("decoder.int8.onnx", Role.Decoder, 3_955_863),
                    Part("joiner.int8.onnx", Role.Joiner, 1_411_403),
                    Part("tokens.txt", Role.Tokens, 9_953),
                ),
                archive = Archive(
                    name = "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2",
                    bytes = 108_035_095,
                    sha256 = "f628312e9fdf8686374cb01a69425c41732529d540860311f16f37cbc32cfe9b",
                ),
            ),
            LocalSttModel(
                id = "moonshine-tiny-en-2026",
                label = "Moonshine tiny 2026 (English)",
                blurb = "Tiny download. Long speech is split into short pieces for it.",
                family = Family.MoonshineV2,
                files = listOf(
                    Part("encoder_model.ort", Role.Encoder, 13_281_600),
                    Part("decoder_model_merged.ort", Role.Decoder, 30_412_256),
                    Part("tokens.txt", Role.Tokens, 549_350),
                ),
                archive = Archive(
                    name = "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2",
                    bytes = 29_858_559,
                    sha256 = "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d",
                ),
                // Measured: its quantized decoder fails inside ONNX Runtime (and returns nothing, silently) on audio
                // of 10 s or more, and works at 8 s. Stay well under.
                maxSegmentSeconds = 7,
            ),
        )

        fun byId(id: String): LocalSttModel = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
    }
}

/** Owns the downloaded STT model files under `filesDir/models/<id>/`. */
class LocalSttStore private constructor(context: Context) : ModelStore<LocalSttModel>() {

    val models: List<LocalSttModel> = LocalSttModel.all

    private val root = File(context.filesDir, "models")

    init {
        // Models that left the catalog (the big Whisper and Parakeet 0.6B builds) would otherwise sit on disk with
        // no way to delete them from Settings, and they are hundreds of MB each.
        val known = models.map { it.id }.toSet()
        root.listFiles()?.forEach { if (it.isDirectory && it.name !in known) it.deleteRecursively() }
        initStates(models)
    }

    fun model(id: String): LocalSttModel = LocalSttModel.byId(id)

    fun file(model: LocalSttModel, name: String): File = File(dir(model), name)

    override fun idOf(model: LocalSttModel) = model.id
    override fun totalBytes(model: LocalSttModel) = model.totalBytes
    override fun dir(model: LocalSttModel) = File(root, model.id)

    // A file only gets its final name once verified, so "exists at the right size" means "verified".
    override fun isReady(model: LocalSttModel): Boolean =
        model.files.all { file(model, it.name).let { f -> f.isFile && f.length() == it.bytes } }

    override suspend fun install(model: LocalSttModel, progress: (Long) -> Unit) {
        val archive = model.archive
        if (archive != null) {
            installArchive(model, archive, progress)
            return
        }
        var before = 0L
        for (part in model.files) {
            fetch(model, model.url(part), file(model, part.name), part.bytes, part.sha256) { progress(before + it) }
            before += part.bytes
        }
    }

    /** Fetch the release archive (checked against its digest), then unpack it into the model folder. */
    private suspend fun installArchive(model: LocalSttModel, archive: LocalSttModel.Archive, progress: (Long) -> Unit) {
        val file = File(dir(model), "archive.tar.bz2")
        fetch(model, requireNotNull(model.archiveUrl), file, archive.bytes, archive.sha256, progress)
        setInstalling(model, 0, archive.bytes)
        val staging = File(dir(model), "staging")
        staging.deleteRecursively()
        try {
            unpack(file, staging) { setInstalling(model, it, archive.bytes) }
            // Move the files into place one at a time; isReady() only passes once all have their expected size.
            for (part in model.files) {
                val src = File(staging, part.name)
                if (!src.isFile || src.length() != part.bytes) throw IOException("${part.name} is missing or the wrong size in the archive")
                val dest = file(model, part.name)
                dest.delete()
                if (!src.renameTo(dest)) throw IOException("Could not install ${part.name}")
            }
        } finally {
            staging.deleteRecursively()
        }
        file.delete()
    }

    // Don't delete files a loaded model still has open.
    override fun releaseFromMemory(model: LocalSttModel) = LocalSttEngine.releaseIfLoaded(model.id)

    companion object {
        @Volatile private var instance: LocalSttStore? = null
        fun get(context: Context): LocalSttStore =
            instance ?: synchronized(this) {
                instance ?: LocalSttStore(context.applicationContext).also { instance = it }
            }
    }
}
