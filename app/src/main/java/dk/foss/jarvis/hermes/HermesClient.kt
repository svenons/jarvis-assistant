package dk.foss.jarvis.hermes

import dk.foss.jarvis.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.net.URLEncoder

/**
 * Talks to a Hermes `api_server`. This is the ONLY coupling to Hermes:
 * OpenAI-compatible `/v1/chat/completions` (streamed via SSE) plus `/v1/models`
 * for a connection test. Bearer auth; session continuity via X-Hermes-Session-Id.
 */
class HermesClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    // explicitNulls=false: an unset `provider` must be omitted from the request, not sent as null.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    interface StreamCallbacks {
        fun onDelta(textDelta: String)
        fun onSessionId(id: String) {}
        /** A tool the agent is running started ([running]) or finished. [label] is empty on finish. */
        fun onToolProgress(id: String, tool: String, emoji: String, label: String, running: Boolean) {}
        fun onComplete() {}
        fun onError(message: String) {}
    }

    fun streamChat(
        messages: List<ChatMessage>,
        model: String,
        provider: String?,
        sessionId: String?,
        cb: StreamCallbacks,
        systemPrompt: String? = null,
        /** Thinking level (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`); blank = Hermes's own setting. */
        reasoningEffort: String? = null,
    ): EventSource {
        // Hermes layers a `system` message on top of its own prompt for this request only
        // (it isn't stored in the session), so it's re-sent every turn.
        val outgoing = if (systemPrompt.isNullOrBlank()) messages
        else listOf(ChatMessage("system", systemPrompt)) + messages
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model, messages = outgoing, stream = true, provider = provider?.ifBlank { null },
                modelOptions = reasoningEffort?.ifBlank { null }?.let { ModelOptions(it) },
            ),
        )
        val builder = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA))
        if (!sessionId.isNullOrEmpty()) builder.addHeader("X-Hermes-Session-Id", sessionId)

        // The stream signals end twice (the "[DONE]" event AND onClosed) — make sure
        // the terminal callback fires exactly once.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                response.header("X-Hermes-Session-Id")?.let { cb.onSessionId(it) }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.isBlank() || data == "[DONE]") {
                    if (data == "[DONE]" && finished.compareAndSet(false, true)) cb.onComplete()
                    return
                }
                if (type == TOOL_PROGRESS_EVENT) {
                    runCatching { json.decodeFromString(ToolProgress.serializer(), data) }.getOrNull()?.let {
                        if (it.toolCallId.isNotEmpty()) {
                            cb.onToolProgress(it.toolCallId, it.tool, it.emoji, it.label, running = it.status != "completed")
                        }
                    }
                    return
                }
                try {
                    val chunk = json.decodeFromString(StreamChunk.serializer(), data)
                    val delta = chunk.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) cb.onDelta(delta)
                } catch (_: Exception) {
                    // keep-alive comment or non-JSON line — ignore
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (finished.compareAndSet(false, true)) cb.onComplete()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!finished.compareAndSet(false, true)) return
                val msg = when {
                    response != null && !response.isSuccessful -> {
                        val detail = runCatching { response.body?.string() }.getOrNull()?.take(300)
                        "HTTP ${response.code}${if (!detail.isNullOrBlank()) ": $detail" else ""}"
                    }
                    t != null -> t.message ?: "Connection failed"
                    else -> "Connection failed"
                }
                cb.onError(msg)
            }
        }
        return EventSources.createFactory(Http.streaming).newEventSource(builder.build(), listener)
    }

    /**
     * GET /api/sessions/{id}/messages: what Hermes stored for the session (newest page). Read after a turn to
     * pick up that turn's reasoning and tool calls, which the chat stream doesn't carry. Any failure (older
     * Hermes without this route, a session it doesn't know) is a failed Result the caller just ignores.
     */
    suspend fun fetchSessionMessages(sessionId: String, limit: Int = 80): Result<List<SessionMessage>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = URLEncoder.encode(sessionId, "UTF-8")
                val req = Request.Builder()
                    .url("$baseUrl/api/sessions/$id/messages?limit=$limit&order=latest")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                Http.base.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    json.decodeFromString(SessionMessagesResponse.serializer(), text).data
                }
            }
        }

    /**
     * GET /api/model/options: the models Hermes could run, by provider (the catalog its own model picker uses).
     * Picking one of these and sending its provider with the model is what makes Hermes honour the choice.
     */
    suspend fun fetchModelOptions(): Result<ModelOptionsResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/model/options")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                json.decodeFromString(ModelOptionsResponse.serializer(), text)
            }
        }
    }

    /** GET /v1/models — returns model ids on success, or a failure with the reason. */
    suspend fun testConnection(): Result<List<String>> = fetchModels().map { models -> models.map { it.id } }

    /**
     * GET /v1/models: `hermes-agent` (meaning "Hermes's own default model") plus any model routes the server's
     * admin configured. These are the only names a request can pick without also naming a provider.
     */
    suspend fun fetchModels(): Result<List<ModelEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${text.take(200).ifBlank { resp.message }}")
                }
                json.decodeFromString(ModelsResponse.serializer(), text).data
            }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val TOOL_PROGRESS_EVENT = "hermes.tool.progress"
    }
}
