package dk.foss.jarvis.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dk.foss.jarvis.BuildConfig
import dk.foss.jarvis.voice.LocalSttModel
import dk.foss.jarvis.voice.LocalTtsModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

/** User configuration: how to reach Hermes, and optional premium voice. */
data class JarvisSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** Optional Hermes provider slug; sent with [model] so Hermes actually honours it. */
    val provider: String,
    val elevenKey: String,
    val elevenVoiceId: String,
    val wakeEnabled: Boolean,
    /** Transcribe on-device (sherpa-onnx). When set, audio never falls back to a cloud STT. */
    val useLocalStt: Boolean = false,
    /** Which [dk.foss.jarvis.voice.LocalSttModel] to use for on-device STT (its `id`). */
    val localSttModel: String = LocalSttModel.DEFAULT_ID,
    /** Speak with an on-device voice (sherpa-onnx). Overrides ElevenLabs and the system voice. */
    val useLocalTts: Boolean = false,
    /** Which [LocalTtsModel] to speak with (its `id`). */
    val localTtsModel: String = LocalTtsModel.DEFAULT_ID,
) {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
    val useElevenLabs: Boolean get() = elevenKey.isNotEmpty() && elevenVoiceId.isNotEmpty()
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val PROVIDER = stringPreferencesKey("provider")
        val ELEVEN_KEY = stringPreferencesKey("eleven_key")
        val ELEVEN_VOICE = stringPreferencesKey("eleven_voice")
        val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
        val LOCAL_STT = booleanPreferencesKey("local_stt")
        val LOCAL_STT_MODEL = stringPreferencesKey("local_stt_model")
        val LOCAL_TTS = booleanPreferencesKey("local_tts")
        val LOCAL_TTS_MODEL = stringPreferencesKey("local_tts_model")
    }

    val settings: Flow<JarvisSettings> = context.dataStore.data.map { p ->
        JarvisSettings(
            baseUrl = p[Keys.BASE_URL] ?: BuildConfig.DEFAULT_BASE_URL,
            apiKey = p[Keys.API_KEY] ?: BuildConfig.DEFAULT_API_KEY,
            // Empty, or the previous default ("kimi-for-coding"), migrates to DEFAULT_MODEL
            // so existing installs flip to the new model; a deliberately-set model is kept.
            model = (p[Keys.MODEL] ?: "").let { if (it.isEmpty() || it == LEGACY_MODEL) DEFAULT_MODEL else it },
            provider = p[Keys.PROVIDER] ?: "",
            elevenKey = (p[Keys.ELEVEN_KEY] ?: "").ifEmpty { BuildConfig.DEFAULT_ELEVEN_KEY },
            elevenVoiceId = (p[Keys.ELEVEN_VOICE] ?: "")
                .ifEmpty { BuildConfig.DEFAULT_ELEVEN_VOICE.ifEmpty { DEFAULT_ELEVEN_VOICE } },
            wakeEnabled = p[Keys.WAKE_ENABLED] ?: false,
            useLocalStt = p[Keys.LOCAL_STT] ?: false,
            // byId falls back to the default if a stored id no longer exists in the catalog.
            localSttModel = LocalSttModel.byId(p[Keys.LOCAL_STT_MODEL] ?: "").id,
            useLocalTts = p[Keys.LOCAL_TTS] ?: false,
            localTtsModel = LocalTtsModel.byId(p[Keys.LOCAL_TTS_MODEL] ?: "").id,
        )
    }

    suspend fun updateWake(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.WAKE_ENABLED] = enabled }
    }

    suspend fun updateLocalStt(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.LOCAL_STT] = enabled }
    }

    suspend fun updateLocalSttModel(id: String) {
        context.dataStore.edit { p -> p[Keys.LOCAL_STT_MODEL] = id }
    }

    suspend fun updateLocalTts(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.LOCAL_TTS] = enabled }
    }

    suspend fun updateLocalTtsModel(id: String) {
        context.dataStore.edit { p -> p[Keys.LOCAL_TTS_MODEL] = id }
    }

    suspend fun updateConnection(baseUrl: String, apiKey: String, model: String, provider: String) {
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            p[Keys.API_KEY] = apiKey.trim()
            p[Keys.MODEL] = model.trim().ifEmpty { DEFAULT_MODEL }
            p[Keys.PROVIDER] = provider.trim()
        }
    }

    suspend fun updateVoice(elevenKey: String, elevenVoiceId: String) {
        context.dataStore.edit { p ->
            p[Keys.ELEVEN_KEY] = elevenKey.trim()
            p[Keys.ELEVEN_VOICE] = elevenVoiceId.trim().ifEmpty { DEFAULT_ELEVEN_VOICE }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "mimo-v2.5-pro-ultraspeed"
        const val LEGACY_MODEL = "kimi-for-coding" // prior default; migrated to DEFAULT_MODEL
        const val DEFAULT_ELEVEN_VOICE = "JBFqnCBsd6RMkjVDRZzb"
    }
}
