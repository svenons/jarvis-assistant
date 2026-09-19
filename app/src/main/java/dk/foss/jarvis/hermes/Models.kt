package dk.foss.jarvis.hermes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    /** Hermes provider slug. Without it, Hermes ignores `model` unless direct_model_requests is on. */
    val provider: String? = null,
    /** Per-request overrides Hermes reads (`model_options`); omitted when nothing is set. */
    @SerialName("model_options") val modelOptions: ModelOptions? = null,
)

/** How hard the model thinks: `none` (off), `minimal` … `max`. Hermes ignores an unrecognised value. */
@Serializable
data class ModelOptions(@SerialName("reasoning_effort") val reasoningEffort: String? = null)

// --- streaming response (OpenAI chat.completion.chunk) ---

@Serializable
data class StreamChunk(val choices: List<StreamChoice> = emptyList())

@Serializable
data class StreamChoice(
    val delta: Delta = Delta(),
    val finish_reason: String? = null,
)

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

/**
 * Payload of Hermes' custom `hermes.tool.progress` SSE event: one tool starting
 * (`status = "running"`, with a display [label]) or finishing (`"completed"`, id only).
 */
@Serializable
data class ToolProgress(
    val tool: String = "",
    val emoji: String = "",
    val label: String = "",
    val toolCallId: String = "",
    val status: String = "",
)

// --- GET /api/sessions/{id}/messages (what Hermes stored for a session) ---
// Every field is a JsonElement: this API is not in the public docs, so read it loosely and never fail a turn over it.

@Serializable
data class SessionMessagesResponse(val data: List<SessionMessage> = emptyList())

@Serializable
data class SessionMessage(
    val id: JsonElement? = null,
    val role: String = "",
    val content: JsonElement? = null,
    val reasoning: JsonElement? = null,
    val reasoning_content: JsonElement? = null,
    val tool_calls: JsonElement? = null,
    val timestamp: JsonElement? = null,
)

// --- GET /api/model/options (the real model inventory, by provider) ---
// /v1/models only lists Hermes's own default and its route aliases; this is the catalog Hermes's own model picker
// uses. Read loosely: it is not in the public docs beyond a mention.

@Serializable
data class ModelOptionsResponse(
    val providers: List<ProviderOptions> = emptyList(),
    /** Hermes's own current default model and provider. */
    val model: String? = null,
    val provider: String? = null,
)

@Serializable
data class ProviderOptions(
    val slug: String = "",
    val name: String? = null,
    /** False for providers Hermes knows but has no credentials for: they have no models and would fail. */
    val authenticated: Boolean = true,
    val is_current: Boolean = false,
    val models: List<JsonElement> = emptyList(),
    val featured_models: List<JsonElement> = emptyList(),
    val total_models: Int? = null,
)

// --- /v1/models (connection test) ---

@Serializable
data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
data class ModelEntry(
    val id: String,
    /** For a model route (alias), the model it resolves to; the same as [id] for Hermes's own default. */
    val root: String? = null,
)
