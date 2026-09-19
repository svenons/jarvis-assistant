package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.PendingRun
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.data.UiMessage
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.run.RunWatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())

    /** Run on the main thread; returns Unit so it fits expression-body callbacks. */
    private fun onMain(block: () -> Unit) { main.post(block) }

    val messages get() = repo.messages
    val isStreaming = mutableStateOf(false)
    val notConfigured = mutableStateOf(false)
    /** Where a task sent with the chat's "→" button is delivered (Settings), shown on that button. */
    val deliverTarget = mutableStateOf(SettingsStore.DEFAULT_DELIVER_TARGET)

    init {
        viewModelScope.launch { settingsStore.settings.collect { deliverTarget.value = it.deliverTarget } }
    }

    private var turn: HermesClient.TurnHandle? = null
    private val watcher = RunWatcher.get(app)

    // Index of the reply being streamed, or -1 until its first word (and again after a tool step, so the
    // text that follows a tool becomes a new reply after it). Replies are created as they arrive, not as an
    // empty placeholder up front, so tool steps land between your message and the answer in order.
    private var replyIndex = -1

    /** Hermes is still working on a turn nobody is listening to (the app was left, or the connection dropped). */
    val backgroundRun get() = repo.pendingRun?.takeIf { !isStreaming.value }

    fun newConversation() {
        leave() // a run carries on and lands in the conversation it was started in
        viewModelScope.launch {
            repo.persist()
            repo.startNew()
        }
    }

    /** Stop / Cancel: the only thing that really ends a turn early. */
    fun cancel() {
        val runId = repo.pendingRun?.runId
        turn?.stop()
        turn = null
        isStreaming.value = false
        if (runId != null) {
            watcher.stop(runId) // Hermes stops at the next safe point; the watcher waits for that, then goes quiet
            repo.updatePendingRun(null) // don't leave "still working" on screen meanwhile
        }
        repo.persistAsync() // keep whatever streamed before the stop
    }

    /** Stop listening. A run continues (and is collected by [RunWatcher]); the old chat stream just ends. */
    private fun leave() {
        val h = turn ?: return
        turn = null
        isStreaming.value = false
        h.detach()
        repo.pendingRun?.runId?.let { watcher.detach(it) }
        repo.persistAsync()
    }

    fun dismissNotConfigured() { notConfigured.value = false }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || isStreaming.value || repo.pendingRun != null) return

        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { notConfigured.value = true; return@launch }

            replyIndex = -1
            repo.addMessage("user", text)
            repo.persistAsync() // saved now, not when the reply finishes
            val turnStart = repo.messages.size // the turn's entries start right after your message
            val history = repo.historyForRequest()
            isStreaming.value = true
            val convId = repo.activeConversationId
            var gotText = false

            val client = HermesClient(s.baseUrl, s.apiKey)
            val request = HermesClient.TurnRequest(history, s.model, s.provider, repo.sessionId, null, s.thinking, s.useRuns)
            turn = client.sendTurn(request, object : HermesClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = onMain {
                    gotText = true
                    replyIndex = repo.streamReply(replyIndex, textDelta)
                }

                override fun onToolProgress(id: String, tool: String, emoji: String, label: String, running: Boolean) = onMain {
                    if (running) {
                        if (repo.addToolMessage(id, toolLine(emoji, tool, label)) >= 0) replyIndex = -1
                    } else {
                        repo.finishTool(id)
                    }
                }

                override fun onSessionId(id: String) { repo.setSessionId(id) }

                override fun onRunStarted(runId: String) = onMain {
                    // Save the marker now: if the app dies or is left from here on, the result is still collected.
                    if (repo.activeConversationId != convId) return@onMain
                    val run = PendingRun(runId, System.currentTimeMillis())
                    repo.updatePendingRun(run)
                    repo.persistAsync()
                    watcher.begin(convId, runId, run.startedAt)
                    if (turn == null) watcher.detach(runId) // left before Hermes answered the POST
                }

                override fun onFinalOutput(text: String) = onMain {
                    // Normally the text streamed already; show the final answer only if nothing did.
                    if (!gotText && text.isNotBlank()) replyIndex = repo.streamReply(replyIndex, text)
                }

                override fun onComplete() = onMain {
                    isStreaming.value = false
                    turn = null
                    endRun()
                    repo.persistAsync()
                    if (s.showReasoning) {
                        val turnEnd = repo.messages.size
                        viewModelScope.launch { TurnEnricher.addDetails(client, repo, turnStart, turnEnd) }
                    }
                }

                override fun onStreamLost(message: String) = onMain {
                    // The connection dropped but the run goes on: hand it to the watcher instead of failing the turn.
                    isStreaming.value = false
                    turn = null
                    repo.pendingRun?.runId?.let { watcher.detach(it) }
                    repo.persistAsync()
                }

                override fun onError(message: String) = onMain {
                    repo.addMessage("assistant", "⚠️ $message", isError = true)
                    isStreaming.value = false
                    turn = null
                    endRun()
                    repo.persistAsync()
                }
            })
        }
    }

    /**
     * The "→" button: hand [userText] to Hermes as a background job whose answer Hermes delivers to the home channel
     * (Settings). Independent of this chat's session, so the answer is not added here, only a note that it was sent.
     */
    fun sendInBackground(userText: String) {
        val task = userText.trim()
        if (task.isEmpty()) return
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { notConfigured.value = true; return@launch }
            val target = s.deliverTarget
            val earlier = repo.messages.toList()
            repo.addMessage("user", task)
            val prompt = backgroundPrompt(task, earlier)
            if (prompt == null) {
                repo.addMessage("assistant", "⚠️ That task is too long to send in the background (limit $MAX_JOB_PROMPT characters).", isError = true)
                return@launch
            }
            repo.persistAsync()
            val name = "Jarvis: " + task.replace(Regex("\\s+"), " ").take(80)
            HermesClient(s.baseUrl, s.apiKey).createBackgroundJob(name, prompt, target)
                .onSuccess { id ->
                    val step = "job-$id"
                    repo.addToolMessage(step, toolLine("\uD83D\uDCE8", "background", "Sent as a background task. The answer goes to your $target home channel."))
                    repo.finishTool(step)
                }
                .onFailure { repo.addMessage("assistant", "⚠️ Couldn\u2019t start the background task: ${it.message}", isError = true) }
            repo.persistAsync()
        }
    }

    /**
     * A background job runs in a fresh session and knows nothing of this chat, so the prompt carries the last few
     * messages for context. Null if the task itself doesn't fit Hermes's prompt limit; older context is cut first.
     */
    private fun backgroundPrompt(task: String, earlier: List<UiMessage>): String? {
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

    /** The run ended while we were listening, so the result is already in the conversation. */
    private fun endRun() {
        repo.pendingRun?.runId?.let { watcher.finish(it) }
        repo.updatePendingRun(null)
    }

    private companion object {
        const val MAX_JOB_PROMPT = 4900 // Hermes rejects job prompts over 5000 characters
    }

    override fun onCleared() {
        leave() // leaving the app must not kill a turn that is already running
        repo.persistAsync() // viewModelScope is already cancelled here
        super.onCleared()
    }
}
