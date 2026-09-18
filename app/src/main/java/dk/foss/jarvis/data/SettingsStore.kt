package dk.foss.jarvis.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dk.foss.jarvis.BuildConfig
import dk.foss.jarvis.voice.LocalSttModel
import dk.foss.jarvis.voice.LocalTtsModel
import dk.foss.jarvis.wake.WakeModels
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
    /** Keep listening when the app is closed or the screen is off (a persistent mic notification). Off = only while the app is open. */
    val wakeBackground: Boolean = true,
    /** Transcribe on-device (sherpa-onnx). When set, audio never falls back to a cloud STT. */
    val useLocalStt: Boolean = false,
    /** Which [dk.foss.jarvis.voice.LocalSttModel] to use for on-device STT (its `id`). */
    val localSttModel: String = LocalSttModel.DEFAULT_ID,
    /** Speak with an on-device voice (sherpa-onnx). Overrides ElevenLabs and the system voice. */
    val useLocalTts: Boolean = false,
    /** Which [LocalTtsModel] to speak with (its `id`). */
    val localTtsModel: String = LocalTtsModel.DEFAULT_ID,
    /** In voice conversations, ask Hermes for short, speakable replies. */
    val voiceBrief: Boolean = true,
    /** After each turn, fetch Hermes's stored reasoning and tool calls and add them to history. */
    val showReasoning: Boolean = true,
    /** Custom wording for that request; blank means [SettingsStore.DEFAULT_VOICE_PROMPT]. */
    val voicePrompt: String = "",
    /** Which wake phrase to listen for: a bundled [dk.foss.jarvis.wake.WakeModel] id, or `custom`. */
    val wakeModel: String = WakeModels.DEFAULT_ID,
    /** What to call the imported custom wake model (e.g. "Hey Hades"); shown in the notification. */
    val wakeCustomName: String = "",
    /** 0 = stricter (fewer false triggers), 1 = normal, 2 = more sensitive. */
    val wakeSensitivity: Int = 1,
    /** What the assistant is called in the app and its notifications (never blank). */
    val assistantName: String = BuildConfig.DEFAULT_ASSISTANT_NAME,
) {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
    val useElevenLabs: Boolean get() = elevenKey.isNotEmpty() && elevenVoiceId.isNotEmpty()

    /** System message for voice turns, or null when the user turned the brief style off. */
    val voiceInstructions: String? get() =
        if (voiceBrief) voicePrompt.ifBlank { SettingsStore.DEFAULT_VOICE_PROMPT } else null
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
        val WAKE_BACKGROUND = booleanPreferencesKey("wake_background")
        val LOCAL_STT = booleanPreferencesKey("local_stt")
        val LOCAL_STT_MODEL = stringPreferencesKey("local_stt_model")
        val LOCAL_TTS = booleanPreferencesKey("local_tts")
        val LOCAL_TTS_MODEL = stringPreferencesKey("local_tts_model")
        val VOICE_BRIEF = booleanPreferencesKey("voice_brief")
        val SHOW_REASONING = booleanPreferencesKey("show_reasoning")
        val VOICE_PROMPT = stringPreferencesKey("voice_prompt")
        val WAKE_MODEL = stringPreferencesKey("wake_model")
        val WAKE_CUSTOM_NAME = stringPreferencesKey("wake_custom_name")
        val WAKE_SENSITIVITY = intPreferencesKey("wake_sensitivity")
        val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
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
            wakeBackground = p[Keys.WAKE_BACKGROUND] ?: true, // before this existed, wake was always background
            useLocalStt = p[Keys.LOCAL_STT] ?: false,
            // byId falls back to the default if a stored id no longer exists in the catalog.
            localSttModel = LocalSttModel.byId(p[Keys.LOCAL_STT_MODEL] ?: "").id,
            useLocalTts = p[Keys.LOCAL_TTS] ?: false,
            localTtsModel = LocalTtsModel.byId(p[Keys.LOCAL_TTS_MODEL] ?: "").id,
            voiceBrief = p[Keys.VOICE_BRIEF] ?: true,
            showReasoning = p[Keys.SHOW_REASONING] ?: true,
            voicePrompt = p[Keys.VOICE_PROMPT] ?: "",
            wakeModel = p[Keys.WAKE_MODEL] ?: WakeModels.DEFAULT_ID,
            wakeCustomName = p[Keys.WAKE_CUSTOM_NAME] ?: "",
            wakeSensitivity = (p[Keys.WAKE_SENSITIVITY] ?: 1).coerceIn(0, 2),
            assistantName = (p[Keys.ASSISTANT_NAME] ?: "").ifBlank { BuildConfig.DEFAULT_ASSISTANT_NAME },
        )
    }

    suspend fun updateWake(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.WAKE_ENABLED] = enabled }
    }

    suspend fun updateWakeBackground(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.WAKE_BACKGROUND] = enabled }
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

    suspend fun updateAssistantName(name: String) {
        context.dataStore.edit { p -> p[Keys.ASSISTANT_NAME] = name.trim() }
    }

    suspend fun updateWakeModel(id: String) {
        context.dataStore.edit { p -> p[Keys.WAKE_MODEL] = id }
    }

    suspend fun updateWakeCustomName(name: String) {
        context.dataStore.edit { p -> p[Keys.WAKE_CUSTOM_NAME] = name.trim() }
    }

    suspend fun updateWakeSensitivity(level: Int) {
        context.dataStore.edit { p -> p[Keys.WAKE_SENSITIVITY] = level.coerceIn(0, 2) }
    }

    suspend fun updateShowReasoning(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.SHOW_REASONING] = enabled }
    }

    suspend fun updateVoiceBrief(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.VOICE_BRIEF] = enabled }
    }

    suspend fun updateVoicePrompt(prompt: String) {
        context.dataStore.edit { p -> p[Keys.VOICE_PROMPT] = prompt.trim() }
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
        /** Hermes's own name for "use whatever model the server is configured with". */
        const val DEFAULT_MODEL = "hermes-agent"
        const val LEGACY_MODEL = "kimi-for-coding" // prior default; migrated to DEFAULT_MODEL
        const val DEFAULT_ELEVEN_VOICE = "JBFqnCBsd6RMkjVDRZzb"

        /** Sent as a `system` message on voice turns so replies are short enough to listen to. */
        const val DEFAULT_VOICE_PROMPT =
            "Your reply will be read aloud by a text-to-speech voice, so answer the way a person " +
                "would say it. Lead with the outcome in one or two short sentences. Report what " +
                "happened, not how: for \"turn off all the lights\" say which rooms went dark, " +
                "not which tools or steps you used. Skip preambles, recaps, and offers of further " +
                "help. Only mention a failure, or something that needs my decision. Use plain " +
                "spoken sentences: no markdown, lists, tables, code, emoji, URLs, or file paths. " +
                "Give the long version only if I ask for details."
    }
}
