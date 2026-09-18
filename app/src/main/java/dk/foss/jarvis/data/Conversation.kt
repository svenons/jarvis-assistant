package dk.foss.jarvis.data

import kotlinx.serialization.Serializable

/**
 * A single message shown in the UI (also the in-memory unit shared by chat + voice). Two roles are not chat
 * turns: [ROLE_TOOL] (a tool step the agent ran) and [ROLE_REASONING] (the model's reasoning for a turn).
 * They are saved and shown in history but never sent to Hermes.
 * [toolId] is set only while a tool step is live in this session, to match its completion event.
 */
data class UiMessage(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val toolId: String? = null,
    val toolDone: Boolean = false,
) {
    companion object {
        const val ROLE_TOOL = "tool"
        const val ROLE_REASONING = "reasoning"

        /** Not part of the chat with Hermes: shown and saved, never sent. */
        fun isAnnotation(role: String) = role == ROLE_TOOL || role == ROLE_REASONING
    }
}

@Serializable
data class StoredMessage(val role: String, val text: String)

/** A full saved conversation. */
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sessionId: String? = null, // Hermes X-Hermes-Session-Id, for server-side continuity
    val messages: List<StoredMessage> = emptyList(),
)

/** Lightweight entry for the history list. */
data class ConversationMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    /** Real messages only; tool steps and reasoning are not counted. */
    val messageCount: Int,
)
