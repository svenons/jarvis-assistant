package dk.foss.jarvis.ui

import android.util.Log
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.UiMessage
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.hermes.turnDetails

/**
 * After a turn finishes, ask Hermes what it stored for it and add the model's reasoning, and any tool calls that
 * weren't streamed, to the START of that turn in the conversation (just after your message), then save.
 *
 * The chat stream carries neither: the model's reasoning tokens are stored with the message but never streamed
 * by the API, so they appear once the turn is over. If this Hermes lacks the route, doesn't know the session, or
 * the model returned no reasoning, nothing is added and nothing breaks.
 */
object TurnEnricher {
    private const val TAG = "TurnEnricher"

    /** [turnStart] is the index of the turn's first entry (right after the user message); [turnEnd] the size when it finished. */
    suspend fun addDetails(client: HermesClient, repo: ConversationRepository, turnStart: Int, turnEnd: Int) {
        val sessionId = repo.sessionId ?: return
        val stored = client.fetchSessionMessages(sessionId).getOrElse {
            Log.d(TAG, "no stored messages for $sessionId: ${it.message}")
            return
        }
        val details = turnDetails(stored) ?: return

        // The fetch takes a moment. If anything else touched the conversation meanwhile, leave it alone.
        val messages = repo.messages
        if (repo.sessionId != sessionId || messages.size != turnEnd || turnStart !in 1..turnEnd) return
        if (messages[turnStart - 1].role != "user") return
        val turn = messages.subList(turnStart, turnEnd)
        if (turn.any { it.role == UiMessage.ROLE_REASONING }) return

        val add = ArrayList<UiMessage>()
        if (details.reasoning.isNotBlank()) add += UiMessage(UiMessage.ROLE_REASONING, details.reasoning)
        // Tool steps normally arrive live; use the stored calls only when none did.
        if (turn.none { it.role == UiMessage.ROLE_TOOL }) {
            details.toolCalls.forEach { add += UiMessage(UiMessage.ROLE_TOOL, toolLine("", it.name, "${it.name}(${it.args})")) }
        }
        if (add.isEmpty()) return
        repo.insertMessages(turnStart, add)
        repo.persistAsync()
    }
}
