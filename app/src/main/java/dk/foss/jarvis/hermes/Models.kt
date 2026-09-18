package dk.foss.jarvis.hermes

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    /** Hermes provider slug. Without it, Hermes ignores `model` unless direct_model_requests is on. */
    val provider: String? = null,
)

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

// --- /v1/models (connection test) ---

@Serializable
data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
data class ModelEntry(val id: String)
