package dk.foss.jarvis.wake

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * One wake phrase the always-on listener can use. [strong] / [sustained] are the score bars (see
 * `WakeWordService.onScore`) at "Normal" sensitivity; models score differently, so each has its own.
 */
class WakeModel(val id: String, val phrase: String, val asset: String?, val strong: Float, val sustained: Float)

object WakeModels {
    /** The slot for a model the user imported (an openWakeWord classifier for any phrase). */
    const val CUSTOM_ID = "custom"
    const val DEFAULT_ID = "jarvis_v1"

    /** Bundled in `assets/`. Ids are persisted in settings, so they must stay stable. */
    val bundled: List<WakeModel> = listOf(
        WakeModel("jarvis_v1", "Hey Jarvis", "jarvis_v1.onnx", strong = 0.3f, sustained = 0.2f),
        WakeModel("jarvis_v2", "Hey Jarvis (fewer false triggers)", "jarvis_v2.onnx", strong = 0.3f, sustained = 0.2f),
        WakeModel("hey_jarvis_v0.1", "Hey Jarvis (original)", "hey_jarvis_v0.1.onnx", strong = 0.5f, sustained = 0.35f),
    )

    /** openWakeWord's own default operating point; a fair start for a model trained with its notebook. */
    private const val CUSTOM_STRONG = 0.5f
    private const val CUSTOM_SUSTAINED = 0.35f

    private const val MAX_MODEL_BYTES = 8L * 1024 * 1024 // real classifiers are a few hundred KB

    fun customFile(context: Context) = File(context.filesDir, "wake/custom.onnx")

    fun hasCustom(context: Context) = customFile(context).isFile

    /** The model to run for [id]; a missing custom model falls back to the default. */
    fun resolve(context: Context, id: String, customName: String): WakeModel {
        if (id == CUSTOM_ID && hasCustom(context)) {
            return WakeModel(CUSTOM_ID, customName.ifBlank { "Custom wake word" }, null, CUSTOM_STRONG, CUSTOM_SUSTAINED)
        }
        return bundled.firstOrNull { it.id == id } ?: bundled.first { it.id == DEFAULT_ID }
    }

    /**
     * Copy the model at [uri] into the custom slot after checking it looks like an openWakeWord
     * classifier — an input of [batch, 16, 96] embeddings — so a wrong file is refused up front
     * instead of silently never triggering.
     */
    fun importCustom(context: Context, uri: Uri): Result<Unit> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_MODEL_BYTES) { "That file is too big to be a wake word model." }
            }
            out.toByteArray()
        } ?: error("Couldn't read that file.")

        val env = OrtEnvironment.getEnvironment()
        val shape = try {
            env.createSession(bytes).use { s ->
                (s.inputInfo.values.first().info as TensorInfo).shape
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("That isn't a valid ONNX model.")
        }
        require(shape.size == 3 && shape[1] == 16L && shape[2] == 96L) {
            "That isn't an openWakeWord model (expected an input of 16 × 96, got ${shape.joinToString("×")})."
        }

        val dest = customFile(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(dest)) { "Couldn't save the model." }
    }

    fun removeCustom(context: Context) {
        customFile(context).delete()
    }
}
