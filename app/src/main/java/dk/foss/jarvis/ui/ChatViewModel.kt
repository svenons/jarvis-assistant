package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())

    /** Run on the main thread; returns Unit so it fits expression-body callbacks. */
    private fun onMain(block: () -> Unit) { main.post(block) }

    val messages get() = repo.messages
    val isStreaming = mutableStateOf(false)
    val notConfigured = mutableStateOf(false)

    private var currentSource: EventSource? = null

    // Index of the reply being streamed, or -1 until its first word (and again after a tool step, so the
    // text that follows a tool becomes a new reply after it). Replies are created as they arrive, not as an
    // empty placeholder up front, so tool steps land between your message and the answer in order.
    private var replyIndex = -1

    fun newConversation() {
        cancel()
        viewModelScope.launch {
            repo.persist()
            repo.startNew()
        }
    }

    fun cancel() {
        currentSource?.cancel()
        currentSource = null
        isStreaming.value = false
        repo.persistAsync() // keep whatever streamed before the stop
    }

    fun dismissNotConfigured() { notConfigured.value = false }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || isStreaming.value) return

        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { notConfigured.value = true; return@launch }

            replyIndex = -1
            repo.addMessage("user", text)
            repo.persistAsync() // saved now, not when the reply finishes
            val history = repo.historyForRequest()
            isStreaming.value = true

            val client = HermesClient(s.baseUrl, s.apiKey)
            currentSource = client.streamChat(history, s.model, s.provider, repo.sessionId, object : HermesClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = onMain {
                    if (replyIndex < 0) replyIndex = repo.addMessage("assistant", textDelta)
                    else repo.appendToMessage(replyIndex, textDelta)
                }

                override fun onToolProgress(id: String, tool: String, emoji: String, label: String, running: Boolean) = onMain {
                    if (running) {
                        if (repo.addToolMessage(id, toolLine(emoji, tool, label)) >= 0) replyIndex = -1
                    } else {
                        repo.finishTool(id)
                    }
                }

                override fun onSessionId(id: String) { repo.setSessionId(id) }

                override fun onComplete() = onMain {
                    isStreaming.value = false
                    currentSource = null
                    repo.persistAsync()
                }

                override fun onError(message: String) = onMain {
                    repo.addMessage("assistant", "⚠️ $message", isError = true)
                    isStreaming.value = false
                    currentSource = null
                    repo.persistAsync()
                }
            })
        }
    }

    override fun onCleared() {
        cancel()
        repo.persistAsync() // viewModelScope is already cancelled here
        super.onCleared()
    }
}
