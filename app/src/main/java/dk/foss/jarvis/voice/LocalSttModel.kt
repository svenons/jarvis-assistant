package dk.foss.jarvis.voice

import android.content.Context
import java.io.File

/**
 * An on-device speech-recognition model. Files are fetched from a *pinned* Hugging Face
 * revision and each one is checked against a SHA-256 before it is used.
 */
class LocalSttModel(
    val id: String,
    val label: String,
    /** One line for the picker. Deliberately no speed numbers — the app measures those. */
    val blurb: String,
    val family: Family,
    private val baseUrl: String,
    val files: List<Part>,
    /** Whisper only: fixed language code for a multilingual model. Empty = the model's own default. */
    val language: String = "",
) {
    /** Decides which sherpa-onnx model config [LocalSttEngine] builds. */
    enum class Family { NemoTransducer, Whisper, Moonshine }

    enum class Role { Encoder, Decoder, Joiner, Tokens, Preprocessor, UncachedDecoder, CachedDecoder }

    class Part(val name: String, val role: Role, val bytes: Long, val sha256: String)

    val totalBytes: Long = files.sumOf { it.bytes }

    fun url(part: Part) = "$baseUrl/${part.name}"

    fun part(role: Role): Part = files.first { it.role == role }

    companion object {
        /** Persisted in settings; also the folder name, so it must stay stable across versions. */
        const val DEFAULT_ID = "parakeet-tdt-0.6b-v2-int8"

        private fun hf(repo: String, revision: String) =
            "https://huggingface.co/csukuangfj/$repo/resolve/$revision"

        /** Ordered roughly small → large, so the picker reads as a speed ladder. */
        val all: List<LocalSttModel> = listOf(
            LocalSttModel(
                id = "moonshine-tiny-en-int8",
                label = "Moonshine tiny (English)",
                blurb = "Smallest model. Try this first if you want the lowest latency.",
                family = Family.Moonshine,
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
                id = "moonshine-base-en-int8",
                label = "Moonshine base (English)",
                blurb = "Small and built for on-device use; more accurate than tiny.",
                family = Family.Moonshine,
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
                id = "whisper-base.en-int8",
                label = "Whisper base.en",
                blurb = "Small OpenAI Whisper model, English only.",
                family = Family.Whisper,
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
                baseUrl = hf("sherpa-onnx-whisper-small.en", "d9533f69affd85061aee349af7fea5cb2996dbbe"),
                files = listOf(
                    Part("small.en-encoder.int8.onnx", Role.Encoder, 112_442_483, "8bdac288f369aa94ee2194059238c465ed82ea9d47ee8fa4a8c0a891873e462f"),
                    Part("small.en-decoder.int8.onnx", Role.Decoder, 262_223_042, "710ccf890e10f3faa15f51ec346081a2723c9f3adb6e4da81c6573a5a6f877fb"),
                    Part("small.en-tokens.txt", Role.Tokens, 835_554, "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"),
                ),
            ),
            LocalSttModel(
                id = DEFAULT_ID,
                label = "Parakeet TDT 0.6B v2 (English)",
                blurb = "NVIDIA Parakeet, English. Aimed at top accuracy; the heaviest on memory.",
                family = Family.NemoTransducer,
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
                baseUrl = hf("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "2bda32ec70b097a55adaa07d9a7173915b43cc78"),
                files = listOf(
                    Part("encoder.int8.onnx", Role.Encoder, 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                    Part("decoder.int8.onnx", Role.Decoder, 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                    Part("joiner.int8.onnx", Role.Joiner, 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                    Part("tokens.txt", Role.Tokens, 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
                ),
            ),
            LocalSttModel(
                id = "whisper-turbo-int8",
                label = "Whisper turbo (multilingual, set to English)",
                blurb = "Largest Whisper here (~1 GB, holds the most memory). Check the measured speed before relying on it.",
                family = Family.Whisper,
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
        var before = 0L
        for (part in model.files) {
            fetch(model, model.url(part), file(model, part.name), part.bytes, part.sha256) { progress(before + it) }
            before += part.bytes
        }
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
