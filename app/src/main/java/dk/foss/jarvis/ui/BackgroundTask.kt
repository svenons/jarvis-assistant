package dk.foss.jarvis.ui

import dk.foss.jarvis.data.UiMessage

/** Hermes rejects job prompts over 5000 characters. */
internal const val MAX_JOB_PROMPT = 4900

/**
 * The prompt for a background job (the "→ Telegram" button in chat and on the voice screen). A job runs in a fresh
 * session and knows nothing of the conversation, so the prompt carries the last few messages for context. Null if the
 * task itself doesn't fit Hermes's prompt limit; older context is cut first.
 */
internal fun backgroundPrompt(task: String, earlier: List<UiMessage>): String? {
    val head = "You are running as a background task for the user, who is away. Your final reply is sent to them " +
        "as a message, so make it complete and self-contained, and do not ask questions.\n\n"
    val tail = "Task:\n$task"
    val budget = MAX_JOB_PROMPT - head.length - tail.length - 60
    if (budget < 0) return null
    val context = earlier.filter { !it.isError && !UiMessage.isAnnotation(it.role) }.takeLast(6)
        .joinToString("\n") { "${if (it.role == "user") "User" else "Assistant"}: ${it.text.trim().take(600)}" }
        .takeLast(budget)
    return head + (if (context.isEmpty()) "" else "Recent conversation, for context:\n$context\n\n") + tail
}
