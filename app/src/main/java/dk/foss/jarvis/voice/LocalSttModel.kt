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
    /** One line for the picker. The speed numbers live in [desktopRtf]; the app also measures the real ones. */
    val blurb: String,
    val family: Family,
    /** When this build was published (not when the original model was trained). */
    val year: Int,
    /** Published accuracy for the picker, e.g. "WER 6.3%"; blank when there is no source I could point to. */
    val score: String = "",
    /** Measured real-time factor with desktop sherpa-onnx 1.13.8, 4 threads, on a 15 s clip (lower is faster). */
    val desktopRtf: Double,
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

    /** The picker's fact line, e.g. "2026 · WER 5.9% · 0.07× real time on a desktop". */
    val meta: String = modelMeta(year, score, desktopRtf)

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
         * Ordered fastest to slowest by [desktopRtf] (a phone is slower in absolute terms; Settings shows the real
         * numbers per model once one has been used). Both tiny and larger models are here on purpose: the tiny ones
         * for phones, Parakeet 0.6B / Whisper for the most accurate transcripts at a big download.
         *
         * Scores are the published Open ASR Leaderboard average word error rate where the model appears there;
         * blank means I found no source, not that the model is bad. Moonshine 2026 builds fail inside ONNX Runtime
         * on 10 s or more of audio (silently returning nothing), hence [maxSegmentSeconds].
         */
        val all: List<LocalSttModel> = listOf(
            LocalSttModel(
                id = "zipformer-small-en-int8",
                label = "Zipformer small (English)",
                blurb = "Smallest and fastest. Less accurate: it can add or miss a word.",
                family = Family.Transducer,
                year = 2023,
                desktopRtf = 0.020,
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
                id = "moonshine-tiny-en-2026",
                label = "Moonshine tiny 2026 (English)",
                blurb = "Newest tiny model. Long speech is split into short pieces for it.",
                family = Family.MoonshineV2,
                year = 2026,
                desktopRtf = 0.029,
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
                maxSegmentSeconds = 7,
            ),
            LocalSttModel(
                id = DEFAULT_ID,
                label = "Parakeet TDT 110M (English)",
                blurb = "The most accurate of the tiny models, and still quick.",
                family = Family.NemoTransducer,
                year = 2026,
                desktopRtf = 0.034,
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
                id = "moonshine-tiny-en-int8",
                label = "Moonshine tiny (English)",
                blurb = "Tiny, built for low latency. The 2024 version.",
                family = Family.Moonshine,
                year = 2024,
                score = "WER 12.7%",
                desktopRtf = 0.037,
                baseUrl = hf("sherpa-onnx-moonshine-tiny-en-int8", "bf2b762c076d8ea61e2af0b3851c9564fb77552e"),
                files = listOf(
                    Part("preprocess.onnx", Role.Preprocessor, 6_800_738, "f33addce61a143460fe753b5ee5b7db255e5140b5b779c065b94f6c83ff0bf4e"),
                    Part("encode.int8.onnx", Role.Encoder, 18_249_187, "8774dfba578de027ec6595c2c654a0836434489bc963a0db124a7f181f571acb"),
                    Part("uncached_decode.int8.onnx", Role.UncachedDecoder, 53_216_096, "216737000dd5881a17aa043f6bbd286add33e4c3b0ae257153e2ec15438bdc41"),
                    Part("cached_decode.int8.onnx", Role.CachedDecoder, 45_264_830, "2aff28bba6a03d8dcf5c9feac45462629bae37317442299f28115ad09da773f6"),
                    Part("tokens.txt", Role.Tokens, 436_688, "1165c2aeb9f72f457a83be2d459a09054f27490acd9b41bd43794dfd25e296ea"),
                ),
            ),
            LocalSttModel(
                id = "moonshine-base-en-2026",
                label = "Moonshine base 2026 (English)",
                blurb = "Newest small model, more accurate than tiny. Long speech is split into short pieces for it.",
                family = Family.MoonshineV2,
                year = 2026,
                desktopRtf = 0.044,
                files = listOf(
                    Part("encoder_model.ort", Role.Encoder, 31_326_816),
                    Part("decoder_model_merged.ort", Role.Decoder, 109_424_400),
                    Part("tokens.txt", Role.Tokens, 549_350),
                ),
                archive = Archive(
                    name = "sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2",
                    bytes = 111_266_225,
                    sha256 = "43232c1d13013d37317163baec3135bd771a186a4356f28c889bab453bb0e891",
                ),
                maxSegmentSeconds = 7,
            ),
            LocalSttModel(
                id = "moonshine-base-en-int8",
                label = "Moonshine base (English)",
                blurb = "Small and built for on-device use; more accurate than tiny.",
                family = Family.Moonshine,
                year = 2024,
                desktopRtf = 0.051,
                baseUrl = hf("sherpa-onnx-moonshine-base-en-int8", "052b0798ad1bf046a140fdd4efcd9426530fa3f5"),
                files = listOf(
                    Part("preprocess.onnx", Role.Preprocessor, 14_077_290, "ffa630d395c5ccf76f5d4954be5b882df76aaf6491519ec01fd82ea7a3819fb2"),
                    Part("encode.int8.onnx", Role.Encoder, 50_311_494, "7e38770f776f2e5583a53b052936005df2ba5c833d7e09c2a5fd796b94bf73e2"),
                    Part("uncached_decode.int8.onnx", Role.UncachedDecoder, 122_120_451, "c01f4b35093bcac20d352d23a75a539e772964579f9d024a90e5e6f09cae9987"),
                    Part("cached_decode.int8.onnx", Role.CachedDecoder, 99_983_837, "2db74e51cedf64a8b1be3c8192e0bb5e4923af0e90bd9e87f8e8771873f8ea03"),
                    Part("tokens.txt", Role.Tokens, 436_688, "1165c2aeb9f72f457a83be2d459a09054f27490acd9b41bd43794dfd25e296ea"),
                ),
            ),
            LocalSttModel(
                id = "parakeet-tdt-0.6b-v2-int8",
                label = "Parakeet TDT 0.6B v2 (English)",
                blurb = "NVIDIA Parakeet, English. Aimed at top accuracy; the heaviest on memory.",
                family = Family.NemoTransducer,
                year = 2025,
                desktopRtf = 0.064,
                baseUrl = hf("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", "1ab9323565ddb038682214b292f588070a538ce2"),
                files = listOf(
                    Part("encoder.int8.onnx", Role.Encoder, 652_184_296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
                    Part("decoder.int8.onnx", Role.Decoder, 7_257_753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
                    Part("joiner.int8.onnx", Role.Joiner, 1_739_080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
                    Part("tokens.txt", Role.Tokens, 9_384, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
                ),
            ),
            LocalSttModel(
                id = "parakeet-tdt-0.6b-v3-int8",
                label = "Parakeet TDT 0.6B v3 (multilingual)",
                blurb = "Same size as v2 but covers 25 European languages. Pick v2 for English only.",
                family = Family.NemoTransducer,
                year = 2025,
                score = "WER 6.3%",
                desktopRtf = 0.066,
                baseUrl = hf("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "2bda32ec70b097a55adaa07d9a7173915b43cc78"),
                files = listOf(
                    Part("encoder.int8.onnx", Role.Encoder, 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                    Part("decoder.int8.onnx", Role.Decoder, 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                    Part("joiner.int8.onnx", Role.Joiner, 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                    Part("tokens.txt", Role.Tokens, 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
                ),
            ),
            LocalSttModel(
                id = "parakeet-unified-en-0.6b-int8",
                label = "Parakeet Unified 0.6B (English)",
                blurb = "Newest Parakeet and the most accurate here. Same size as v2: about 650 MB and heavy on memory.",
                family = Family.NemoTransducer,
                year = 2026,
                score = "WER 5.9%",
                desktopRtf = 0.069,
                files = listOf(
                    Part("encoder.int8.onnx", Role.Encoder, 654_040_552),
                    Part("decoder.int8.onnx", Role.Decoder, 7_257_753),
                    Part("joiner.int8.onnx", Role.Joiner, 1_735_860),
                    Part("tokens.txt", Role.Tokens, 8_952),
                ),
                archive = Archive(
                    name = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming.tar.bz2",
                    bytes = 501_350_460,
                    sha256 = "99f63605b3a85a54c250c0869670a687b7d6598a47bf2421515e1f839a76e150",
                ),
            ),
            LocalSttModel(
                id = "whisper-tiny.en-int8",
                label = "Whisper tiny.en",
                blurb = "OpenAI Whisper tiny, English only. A small download, but slower than the models above.",
                family = Family.Whisper,
                year = 2024,
                desktopRtf = 0.090,
                baseUrl = hf("sherpa-onnx-whisper-tiny.en", "d026532c022fa99fd789d6b32446a1df7b6bfc43"),
                files = listOf(
                    Part("tiny.en-encoder.int8.onnx", Role.Encoder, 12_937_772, "0ce578b827c94a961aacb8fa14b02f096504b337e5c94be37c36238cbe3e8bc6"),
                    Part("tiny.en-decoder.int8.onnx", Role.Decoder, 89_853_865, "06c0e6ff6348d427e51839219d1c886c18cfdf411e629e33f5e1679bff9c1527"),
                    Part("tiny.en-tokens.txt", Role.Tokens, 835_554, "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"),
                ),
            ),
            LocalSttModel(
                id = "whisper-base.en-int8",
                label = "Whisper base.en",
                blurb = "Small OpenAI Whisper model, English only.",
                family = Family.Whisper,
                year = 2024,
                desktopRtf = 0.178,
                baseUrl = hf("sherpa-onnx-whisper-base.en", "59eea950fc76df2453efb57e6c0fd334548e8ffe"),
                files = listOf(
                    Part("base.en-encoder.int8.onnx", Role.Encoder, 29_120_534, "ef6b936f4c9b1d90a3b68634b60c4ed8576b26172b33c2535ec0e933c9edb823"),
                    Part("base.en-decoder.int8.onnx", Role.Decoder, 130_669_978, "f7162ad6db2dbef16cfaeaa7f945b9d7dd9c1b8d472f6aca82f2273d185e4d41"),
                    Part("base.en-tokens.txt", Role.Tokens, 835_554, "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"),
                ),
            ),
            LocalSttModel(
                id = "whisper-small.en-int8",
                label = "Whisper small.en",
                blurb = "Larger Whisper, English only. Slower than base.en, more accurate.",
                family = Family.Whisper,
                year = 2024,
                desktopRtf = 0.496,
                baseUrl = hf("sherpa-onnx-whisper-small.en", "d9533f69affd85061aee349af7fea5cb2996dbbe"),
                files = listOf(
                    Part("small.en-encoder.int8.onnx", Role.Encoder, 112_442_483, "8bdac288f369aa94ee2194059238c465ed82ea9d47ee8fa4a8c0a891873e462f"),
                    Part("small.en-decoder.int8.onnx", Role.Decoder, 262_223_042, "710ccf890e10f3faa15f51ec346081a2723c9f3adb6e4da81c6573a5a6f877fb"),
                    Part("small.en-tokens.txt", Role.Tokens, 835_554, "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"),
                ),
            ),
            LocalSttModel(
                id = "whisper-turbo-int8",
                label = "Whisper turbo (multilingual, set to English)",
                blurb = "Largest Whisper here (~1 GB, holds the most memory). Check the measured speed before relying on it.",
                family = Family.Whisper,
                year = 2024,
                score = "WER 7.8%",
                desktopRtf = 0.642,
                baseUrl = hf("sherpa-onnx-whisper-turbo", "2ca6ff69fc878651b770880507669577ac41c2ff"),
                files = listOf(
                    Part("turbo-encoder.int8.onnx", Role.Encoder, 674_716_297, "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b"),
                    Part("turbo-decoder.int8.onnx", Role.Decoder, 361_080_764, "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547"),
                    Part("turbo-tokens.txt", Role.Tokens, 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
                ),
                language = "en", // skip per-utterance language detection: faster, and no wrong guesses on short clips
            ),
        )

        fun byId(id: String): LocalSttModel = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
    }
}

/** Owns the downloaded STT model files under `filesDir/models/<id>/`. */
class LocalSttStore private constructor(context: Context) : ModelStore<LocalSttModel>() {

    val models: List<LocalSttModel> = LocalSttModel.all

    private val root = File(context.filesDir, "models")

    init { initStates(models) }

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
