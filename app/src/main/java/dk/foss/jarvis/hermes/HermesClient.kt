package dk.foss.jarvis.hermes

import dk.foss.jarvis.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Talks to a Hermes `api_server`. This is the ONLY coupling to Hermes.
 *
 * A turn is sent as a run (`POST /v1/runs`, events streamed from `/v1/runs/{id}/events`): the agent then runs on the
 * server independently of this connection, so leaving the app does not kill it. Older servers without `/v1/runs`
 * fall back to OpenAI-compatible `/v1/chat/completions`, which Hermes cancels the moment the client disconnects.
 * Also `/v1/models` for a connection test. Bearer auth; session continuity via `session_id` (runs) or
 * X-Hermes-Session-Id (chat completions).
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

        // --- runs only ---
        /** The server accepted the turn as a run. Fires even if the caller already detached, so the run can be tracked. */
        fun onRunStarted(runId: String) {}
        /** The run's final answer, just before [onComplete]; the caller shows it if no text streamed. */
        fun onFinalOutput(text: String) {}
        /** The stream ended (network drop) but the run may still be going; collect its result later. */
        fun onStreamLost(message: String) { onError(message) }
        fun onCancelled() { onError("The run was cancelled") }
    }

    /**
     * A turn in flight. [detach] stops listening but a run keeps going on the server (someone else must collect its
     * result); [stop] really cancels it. On the chat-completions fallback both just end the stream.
     */
    interface TurnHandle {
        /** Set once the server accepted the turn as a run; null before that and on the fallback. */
        val runId: String?
        fun detach()
        fun stop()
    }

    /** One turn: the chat so far with the new user message last. */
    class TurnRequest(
        val history: List<ChatMessage>,
        val model: String,
        val provider: String?,
        /** The conversation's Hermes session, or null for a new one. */
        val sessionId: String?,
        val systemPrompt: String?,
        val reasoningEffort: String?,
        /** False forces the chat-completions stream (no background runs). */
        val useRuns: Boolean = true,
    )

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
     * A quick "can we even reach Hermes" check, with a short timeout ([Http.probe]) so being off the right
     * network (e.g. away from home wifi with no tunnel set up) fails in a few seconds instead of only surfacing
     * after the mic has recorded, STT has transcribed, and the real request has waited out its full timeout.
     * Any HTTP response — even an error one — counts as reachable; only a failed connection doesn't.
     */
    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            Http.probe.newCall(req).execute().use { }
            true
        }.getOrDefault(false)
    }

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

    // ---------------------------------------------------------------- runs

    /** Send one turn and stream it. See the class comment for what happens if the app goes away meanwhile. */
    fun sendTurn(req: TurnRequest, cb: StreamCallbacks): TurnHandle {
        val handle = Handle()
        if (!req.useRuns) { startFallback(handle, req, cb); return handle }

        val sessionId = req.sessionId ?: "jarvis-${UUID.randomUUID()}"
        val input = req.history.lastOrNull()?.content.orEmpty()
        // With a session Hermes loads its own transcript; a session it has never seen needs the history in the request.
        val prior = if (req.sessionId == null) req.history.dropLast(1).takeIf { it.isNotEmpty() } else null
        val body = json.encodeToString(
            RunRequest.serializer(),
            RunRequest(
                input = input,
                sessionId = sessionId,
                instructions = req.systemPrompt?.ifBlank { null },
                conversationHistory = prior,
                model = req.model,
                provider = req.provider?.ifBlank { null },
                modelOptions = req.reasoningEffort?.ifBlank { null }?.let { ModelOptions(it) },
            ),
        )
        val request = Request.Builder()
            .url("$baseUrl/v1/runs")
            .addHeader("Authorization", "Bearer $apiKey")
            // A retried POST (the connection dropped after Hermes got it) must not start the turn twice.
            .addHeader("Idempotency-Key", UUID.randomUUID().toString())
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        Http.base.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!handle.closed.get()) cb.onError(e.message ?: "Connection failed")
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val text = response.use { it.body?.string().orEmpty() }
                when {
                    // An older Hermes without /v1/runs: the turn can't outlive the app there, but it still works.
                    code == 404 || code == 405 || code == 501 -> if (!handle.closed.get()) startFallback(handle, req, cb)
                    code !in 200..299 ->
                        if (!handle.closed.get()) cb.onError("HTTP $code${text.take(300).takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
                    else -> {
                        val runId = runCatching { json.decodeFromString(RunCreated.serializer(), text).runId }.getOrDefault("")
                        if (runId.isBlank()) { if (!handle.closed.get()) cb.onError("Hermes did not return a run id"); return }
                        handle.runId = runId
                        when {
                            handle.stopRequested -> postAsync("/v1/runs/${enc(runId)}/stop", "")
                            else -> {
                                // Also when already detached: the caller must still learn the id to collect the result.
                                cb.onRunStarted(runId)
                                if (req.sessionId == null) cb.onSessionId(sessionId)
                                if (!handle.closed.get()) handle.source = streamRun(handle, runId, cb)
                            }
                        }
                    }
                }
            }
        })
        return handle
    }

    private fun startFallback(handle: Handle, req: TurnRequest, cb: StreamCallbacks) {
        handle.fallback = true
        handle.source = streamChat(
            req.history, req.model, req.provider, req.sessionId,
            object : StreamCallbacks by cb {
                override fun onDelta(textDelta: String) { if (!handle.closed.get()) cb.onDelta(textDelta) }
                override fun onComplete() { if (!handle.closed.get()) cb.onComplete() }
                override fun onError(message: String) { if (!handle.closed.get()) cb.onError(message) }
            },
            systemPrompt = req.systemPrompt, reasoningEffort = req.reasoningEffort,
        )
    }

    private inner class Handle : TurnHandle {
        @Volatile override var runId: String? = null
        @Volatile var source: EventSource? = null
        @Volatile var fallback = false
        @Volatile var stopRequested = false
        /** No callbacks after this: the caller left (detach) or cancelled (stop). */
        val closed = AtomicBoolean(false)

        // Not cancelling the POST: if Hermes already got it the run exists, and its id must still reach the caller.
        override fun detach() { closed.set(true); source?.cancel() }

        override fun stop() {
            stopRequested = true
            closed.set(true)
            source?.cancel()
            runId?.let { postAsync("/v1/runs/${enc(it)}/stop", "") } // still in flight: onResponse stops it
        }
    }

    /** Listen to a run's events and map them onto [StreamCallbacks]. */
    private fun streamRun(handle: Handle, runId: String, cb: StreamCallbacks): EventSource {
        val terminal = AtomicBoolean(false)
        val counter = AtomicInteger()
        val running = HashMap<String, ConcurrentLinkedQueue<String>>() // tool name -> ids of its unfinished steps

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (handle.closed.get() || data.isBlank()) return
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
                when (type ?: obj.str("event")) {
                    "message.delta", "assistant.delta" ->
                        (obj.str("delta") ?: obj.str("text"))?.takeIf { it.isNotEmpty() }?.let(cb::onDelta)
                    // Runs' tool events carry no id, so steps are paired first-in-first-out per tool name.
                    "tool.started" -> {
                        val tool = obj.str("tool").orEmpty()
                        val stepId = "$runId#${counter.incrementAndGet()}"
                        synchronized(running) { running.getOrPut(tool) { ConcurrentLinkedQueue() }.add(stepId) }
                        val preview = obj.str("preview").orEmpty().trim().take(100)
                        cb.onToolProgress(stepId, tool, "", if (preview.isEmpty()) tool else "$tool · $preview", running = true)
                    }
                    "tool.completed" -> {
                        val tool = obj.str("tool").orEmpty()
                        val stepId = synchronized(running) { running[tool]?.poll() }
                        if (stepId != null) cb.onToolProgress(stepId, tool, "", "", running = false)
                    }
                    // A run pauses here until someone answers. Jarvis never approves for you: deny, so it can't hang.
                    "approval.request" -> {
                        postAsync("/v1/runs/${enc(runId)}/approval", """{"choice":"deny","resolve_all":true}""")
                        val what = (obj.str("description") ?: obj.str("command")).orEmpty().trim().take(100)
                        val stepId = "$runId#${counter.incrementAndGet()}"
                        cb.onToolProgress(stepId, "approval", "\uD83D\uDEAB", "Denied, needs your approval${if (what.isEmpty()) "" else ": $what"}", running = true)
                        cb.onToolProgress(stepId, "approval", "", "", running = false)
                    }
                    "run.completed" -> if (terminal.compareAndSet(false, true)) {
                        cb.onFinalOutput(obj.str("output").orEmpty())
                        cb.onComplete()
                    }
                    "run.failed" -> if (terminal.compareAndSet(false, true)) cb.onError(obj.str("error") ?: "The run failed")
                    "run.cancelled", "run.interrupted" -> if (terminal.compareAndSet(false, true)) cb.onCancelled()
                }
            }

            // The run outlives its stream, so a stream that ends early is not a finished (or failed) run.
            override fun onClosed(eventSource: EventSource) {
                if (!handle.closed.get() && !terminal.get()) cb.onStreamLost("The connection to Hermes closed before the run finished")
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (handle.closed.get() || terminal.get()) return
                cb.onStreamLost(t?.message ?: response?.let { "HTTP ${it.code}" } ?: "Connection to Hermes lost")
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/v1/runs/${enc(runId)}/events")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .get()
            .build()
        return EventSources.createFactory(Http.streaming).newEventSource(request, listener)
    }

    /** GET /v1/runs/{id}. A run Hermes has forgotten (finished long ago, or never existed) is [RunGoneException]. */
    suspend fun fetchRun(runId: String): Result<RunStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/runs/${enc(runId)}")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 404) throw RunGoneException()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                json.decodeFromString(RunStatus.serializer(), text)
            }
        }
    }

    /** POST /v1/runs/{id}/stop. Hermes stops at the next safe point, so the run settles as cancelled shortly after. */
    suspend fun stopRun(runId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/runs/${enc(runId)}/stop")
                .addHeader("Authorization", "Bearer $apiKey")
                .post("".toRequestBody(JSON_MEDIA))
                .build()
            Http.base.newCall(req).execute().use { resp -> if (!resp.isSuccessful && resp.code != 404) throw RuntimeException("HTTP ${resp.code}") }
        }
    }

    /**
     * Hand a task to Hermes as a one-shot job (`POST /api/jobs`, then run-now) whose answer Hermes itself delivers to
     * [deliver], a platform's home channel (`telegram`, `discord`, ... or `all`). The job runs in a fresh session with no
     * chat context, so [prompt] must carry everything, and the server checks its jobs once a minute. Returns the job id.
     *
     * Hermes only validates [deliver] when the job fires: a platform with no home channel set is accepted here and
     * fails later on the server (`last_status = delivery_failed`), which this app does not see.
     */
    suspend fun createBackgroundJob(name: String, prompt: String, deliver: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(JobRequest.serializer(), JobRequest(name, prompt, schedule = "in 1m", deliver = deliver, repeat = 1))
            val create = Request.Builder()
                .url("$baseUrl/api/jobs")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            val id = Http.base.newCall(create).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 404) throw RuntimeException("this Hermes has no jobs API (HTTP 404)")
                if (!resp.isSuccessful) {
                    val detail = runCatching { json.parseToJsonElement(text).jsonObject.str("error") }.getOrNull()
                    throw RuntimeException("HTTP ${resp.code}${detail?.let { ": $it" } ?: ""}")
                }
                json.decodeFromString(JobEnvelope.serializer(), text).job.id
                    .ifBlank { throw RuntimeException("Hermes did not return a job id") }
            }
            // Start it at the next tick instead of waiting out the minute. If this fails the job still fires on schedule.
            runCatching {
                val run = Request.Builder()
                    .url("$baseUrl/api/jobs/${enc(id)}/run")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post("".toRequestBody(JSON_MEDIA))
                    .build()
                Http.base.newCall(run).execute().close()
            }
            id
        }
    }

    /** Fire-and-forget POST (stop, approval denial): safe to call from a callback thread. */
    private fun postAsync(path: String, body: String) {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        Http.base.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    class RunGoneException : RuntimeException("Hermes no longer has this task")

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val TOOL_PROGRESS_EVENT = "hermes.tool.progress"
    }
}
