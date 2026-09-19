/*
 * Adapted from openwakeword-android-kt (https://github.com/Re-MENTIA/openwakeword-android-kt),
 * Copyright Re-MENTIA, licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * Changes: the library's engine can only load a classifier from the app's assets. This version takes
 * the classifier as bytes, so a model the user imported at runtime can be used; it also opens the
 * mel-spectrogram and embedding models once instead of on every 80 ms frame, and always processes
 * whole 1280-sample frames. The feature maths (mel -> embedding -> last 16 embeddings -> classifier)
 * is the library's.
 */
package dk.foss.jarvis.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * Wake word detection for an openWakeWord classifier supplied as bytes. Emits the raw per-frame
 * score (~every 80 ms), like the library engine's `scores`.
 */
internal class CustomWakeEngine(
    context: Context,
    classifierBytes: ByteArray,
    private val scope: CoroutineScope,
) {
    private val env = OrtEnvironment.getEnvironment()
    private val classifier: OrtSession = env.createSession(classifierBytes)
    private val features = FeatureExtractor(
        env,
        env.createSession(context.assets.open("melspectrogram.onnx").use { it.readBytes() }),
        env.createSession(context.assets.open("embedding_model.onnx").use { it.readBytes() }),
    )

    private val _scores = MutableSharedFlow<Float>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val scores: Flow<Float> = _scores

    private var job: Job? = null

    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before starting
    fun start() {
        stop()
        job = scope.launch(Dispatchers.IO) {
            var record: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 4),
                )
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Failed to initialize AudioRecord" }
                record.startRecording()
                val pcm = ShortArray(FRAME)
                while (isActive) {
                    // AudioRecord may return short reads; the feature code wants whole frames.
                    var have = 0
                    while (have < FRAME && isActive) {
                        val n = record.read(pcm, have, FRAME - have)
                        if (n < 0) throw IllegalStateException("AudioRecord.read failed: $n")
                        have += n
                    }
                    if (have < FRAME) break
                    _scores.tryEmit(predict(FloatArray(FRAME) { pcm[it] / 32768f }))
                }
            } catch (e: Exception) {
                Log.e(TAG, "custom wake engine stopped", e)
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun release() {
        stop()
        runCatching { classifier.close() }
        features.close()
    }

    private fun predict(frame: FloatArray): Float {
        val embeddings = features.push(frame)
        val input = arrayOf(embeddings) // [1, 16, 96]
        OnnxTensor.createTensor(env, input).use { tensor ->
            classifier.run(mapOf(classifier.inputNames.first() to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                return (out[0].value as Array<FloatArray>)[0][0]
            }
        }
    }

    /** Turns a stream of 1280-sample frames into the last 16 embeddings (the classifier's input). */
    private class FeatureExtractor(
        private val env: OrtEnvironment,
        private val mel: OrtSession,
        private val embedding: OrtSession,
    ) {
        private val raw = FloatArray(RAW_CAP)
        private var rawLen = 0
        private var melBuffer: Array<FloatArray> = Array(WINDOW) { FloatArray(MEL_BINS) { 1f } }
        private var featureBuffer: Array<FloatArray>

        init {
            // Start with embeddings of random noise (as the library does) so the classifier's
            // first 16 inputs are in-distribution rather than zeros.
            val noise = FloatArray(SAMPLE_RATE * 4) { Random.nextFloat() * 2000f - 1000f }
            val spec = melSpectrogram(noise)
            val windows = ArrayList<Array<FloatArray>>()
            var i = 0
            while (i + WINDOW <= spec.size) { windows.add(spec.copyOfRange(i, i + WINDOW)); i += STEP }
            featureBuffer = embed(windows)
        }

        /** Add one frame; returns the newest 16 embeddings as [16][96]. */
        fun push(frame: FloatArray): Array<FloatArray> {
            if (rawLen + frame.size > RAW_CAP) {
                val keep = RAW_CAP - frame.size
                System.arraycopy(raw, rawLen - keep, raw, 0, keep)
                rawLen = keep
            }
            System.arraycopy(frame, 0, raw, rawLen, frame.size)
            rawLen += frame.size

            // Mel frames for the newest audio, with 480 samples of lead-in for the window edge.
            val take = minOf(rawLen, frame.size + 480)
            val newMel = melSpectrogram(raw.copyOfRange(rawLen - take, rawLen))
            melBuffer = (melBuffer + newMel).let { if (it.size > MEL_MAX) it.copyOfRange(it.size - MEL_MAX, it.size) else it }

            val window = melBuffer.copyOfRange(melBuffer.size - WINDOW, melBuffer.size)
            featureBuffer = (featureBuffer + embed(listOf(window)))
                .let { if (it.size > FEATURE_MAX) it.copyOfRange(it.size - FEATURE_MAX, it.size) else it }
            return featureBuffer.copyOfRange(featureBuffer.size - CLASSIFIER_FRAMES, featureBuffer.size)
        }

        private fun melSpectrogram(samples: FloatArray): Array<FloatArray> {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
                mel.run(mapOf(mel.inputNames.first() to input)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val v = out[0].value as Array<Array<Array<FloatArray>>> // [1, 1, frames, 32]
                    return Array(v[0][0].size) { r -> FloatArray(v[0][0][r].size) { c -> v[0][0][r][c] / 10f + 2f } }
                }
            }
        }

        private fun embed(windows: List<Array<FloatArray>>): Array<FloatArray> {
            // [batch, 76, 32, 1]
            val batch = Array(windows.size) { w -> Array(WINDOW) { r -> Array(MEL_BINS) { c -> floatArrayOf(windows[w][r][c]) } } }
            OnnxTensor.createTensor(env, batch).use { input ->
                embedding.run(mapOf("input_1" to input)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val v = out[0].value as Array<Array<Array<FloatArray>>> // [batch, 1, 1, 96]
                    return Array(v.size) { i -> v[i][0][0].copyOf() }
                }
            }
        }

        fun close() {
            runCatching { mel.close() }
            runCatching { embedding.close() }
        }
    }

    private companion object {
        const val TAG = "JarvisWake"
        const val SAMPLE_RATE = 16000
        const val FRAME = 1280            // 80 ms
        const val RAW_CAP = SAMPLE_RATE   // 1 s of history is plenty for a frame + lead-in
        const val WINDOW = 76             // mel frames per embedding
        const val STEP = 8
        const val MEL_BINS = 32
        const val MEL_MAX = 970
        const val FEATURE_MAX = 120
        const val CLASSIFIER_FRAMES = 16
    }
}
