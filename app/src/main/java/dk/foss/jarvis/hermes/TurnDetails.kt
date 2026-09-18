package dk.foss.jarvis.hermes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** A tool call Hermes stored for a turn: its name and a short summary of the arguments. */
data class StoredToolCall(val name: String, val args: String)

/** What Hermes stored for the latest turn of a session: the model's reasoning and the tools it called. */
data class TurnDetails(val reasoning: String, val toolCalls: List<StoredToolCall>)

/**
 * Pick out the latest turn (everything after the last user message) from a session's stored messages and
 * collect its reasoning and tool calls. Tolerant on purpose: unknown shapes yield nothing rather than an error.
 */
fun turnDetails(messages: List<SessionMessage>): TurnDetails? {
    if (messages.isEmpty()) return null
    val ordered = when {
        messages.all { number(it.timestamp) != null } -> messages.sortedBy { number(it.timestamp) }
        messages.all { number(it.id) != null } -> messages.sortedBy { number(it.id) }
        else -> messages
    }
    val lastUser = ordered.indexOfLast { it.role == "user" }
    if (lastUser < 0) return null
    val assistant = ordered.drop(lastUser + 1).filter { it.role == "assistant" }

    val reasoning = assistant
        .mapNotNull { text(it.reasoning_content) ?: text(it.reasoning) }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n\n")
    return TurnDetails(reasoning, assistant.flatMap { toolCalls(it.tool_calls) })
}

private fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.doubleOrNull

private fun text(e: JsonElement?): String? = when (e) {
    null, JsonNull -> null
    is JsonPrimitive -> e.contentOrNull
    else -> e.toString()
}

/** OpenAI-style `[{"function": {"name": ..., "arguments": "<json>"}}]`, or a flatter `{"name", "arguments"}`. */
private fun toolCalls(e: JsonElement?): List<StoredToolCall> {
    val calls = e as? JsonArray ?: return emptyList()
    return calls.mapNotNull { call ->
        val obj = call as? JsonObject ?: return@mapNotNull null
        val fn = (obj["function"] as? JsonObject) ?: obj
        val name = text(fn["name"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        StoredToolCall(name, summarizeArgs(fn["arguments"] ?: fn["args"]))
    }
}

/** `{"command":"git status"}` (possibly as a JSON string) becomes `command=git status`, capped in length. */
private fun summarizeArgs(e: JsonElement?): String {
    val obj = when (e) {
        is JsonObject -> e
        is JsonPrimitive -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(e.content).jsonObject }.getOrNull()
        else -> null
    }
    val raw = obj?.entries?.joinToString(", ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v}" }
        ?: (e as? JsonPrimitive)?.contentOrNull
        ?: ""
    val oneLine = raw.replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= 90) oneLine else oneLine.take(89) + "…"
}
