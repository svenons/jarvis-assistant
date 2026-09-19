package dk.foss.jarvis.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dk.foss.jarvis.hermes.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The single source of truth for the *active* conversation, shared by both the
 * text chat and voice modes so messages and the Hermes session id stay unified.
 * Persists to [ConversationStore] so conversations can be reopened and continued.
 */
class ConversationRepository private constructor(private val store: ConversationStore) {

    // App-lifetime scope so a fire-and-forget save survives a ViewModel being cleared
    // (viewModelScope is cancelled BEFORE onCleared runs, which would drop the last save).
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val messages: SnapshotStateList<UiMessage> = mutableStateListOf()

    // sessionId is written from the SSE callback thread and read on main.
    @Volatile var sessionId: String? = null
        private set

    /** A turn Hermes is still working on for the active conversation (drives the "working in the background" banner). */
    var pendingRun: PendingRun? by mutableStateOf(null)
        private set

    private var activeId: String = UUID.randomUUID().toString()
    val activeConversationId: String get() = activeId
    private var title: String = ""
    private var createdAt: Long = System.currentTimeMillis()

    // Every change bumps [version]; a save records the version it wrote. Unlike a dirty flag, an edit made
    // while a save is in flight can't be marked clean by that save, and a failed save stays pending.
    private val version = AtomicLong()
    @Volatile private var savedVersion = 0L
    private val saveLock = Mutex() // saves run one at a time, so an older snapshot never overwrites a newer one

    private fun markChanged() { version.incrementAndGet() }
    private fun markClean() { savedVersion = version.get() }

    fun startNew() {
        activeId = UUID.randomUUID().toString()
        messages.clear()
        sessionId = null
        pendingRun = null
        title = ""
        createdAt = System.currentTimeMillis()
        markClean()
    }

    suspend fun open(id: String) {
        val c = store.load(id) ?: return
        activeId = c.id
        title = c.title
        createdAt = c.createdAt
        sessionId = c.sessionId
        pendingRun = c.pendingRun
        messages.clear()
        // Replies saved before streamReply existed start with a blank line: clean them as they load.
        messages.addAll(c.messages.map { UiMessage(it.role, if (it.role == "assistant") it.text.trimStart() else it.text) })
        markClean()
    }

    fun updatePendingRun(run: PendingRun?) {
        if (pendingRun != run) { pendingRun = run; markChanged() }
    }

    fun setSessionId(id: String) {
        if (sessionId != id) { sessionId = id; markChanged() }
    }

    /** Append a message and return its index. */
    fun addMessage(role: String, text: String, isError: Boolean = false): Int {
        if (title.isEmpty() && role == "user" && text.isNotBlank()) title = text.take(60)
        messages.add(UiMessage(role, text, isError))
        markChanged()
        return messages.lastIndex
    }

    /**
     * A tool the agent started. Kept in the conversation (and saved) so history shows what it did.
     * Returns the new message's index, or -1 if this step was already recorded.
     */
    fun addToolMessage(id: String, text: String): Int {
        if (messages.any { it.toolId == id }) return -1
        messages.add(UiMessage(UiMessage.ROLE_TOOL, text, toolId = id))
        markChanged()
        return messages.lastIndex
    }

    /** The tool finished (only affects how it's drawn while live, so nothing to save). */
    fun finishTool(id: String) {
        val i = messages.indexOfLast { it.toolId == id }
        if (i >= 0 && !messages[i].toolDone) messages[i] = messages[i].copy(toolDone = true)
    }

    /**
     * Stream a reply chunk into the conversation. The reply is created on its first visible text, with leading
     * blank lines dropped (Hermes opens replies with newlines, which showed up as an empty line above every
     * answer in history), then appended to. Returns the reply's index, or -1 while it is still only whitespace.
     */
    fun streamReply(index: Int, delta: String): Int {
        if (index >= 0) { appendToMessage(index, delta); return index }
        val text = delta.trimStart()
        return if (text.isEmpty()) -1 else addMessage("assistant", text)
    }

    fun appendToMessage(index: Int, delta: String) {
        if (index in messages.indices) {
            val cur = messages[index]
            messages[index] = cur.copy(text = cur.text + delta)
            markChanged()
        }
    }

    fun replaceMessage(index: Int, text: String, isError: Boolean = false) {
        if (index in messages.indices) {
            messages[index] = messages[index].copy(text = text, isError = isError)
            markChanged()
        }
    }

    /** History as Hermes chat messages for building a request: no errors, and no tool steps or reasoning (not chat turns). */
    fun historyForRequest(): List<ChatMessage> =
        messages.filter { !it.isError && !UiMessage.isAnnotation(it.role) }.map { ChatMessage(it.role, it.text) }

    /** Insert annotation messages (reasoning, tool steps) at [index], e.g. at the start of a finished turn. */
    fun insertMessages(index: Int, items: List<UiMessage>) {
        if (items.isEmpty() || index !in 0..messages.size) return
        messages.addAll(index, items)
        markChanged()
    }

    suspend fun persist() = saveLock.withLock {
        val v = version.get()
        if (v == savedVersion) return@withLock
        if (messages.none { !it.isError } && pendingRun == null) return@withLock
        val saved = store.save(
            Conversation(
                id = activeId,
                title = title.ifEmpty { "Conversation" },
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis(),
                sessionId = sessionId,
                messages = messages.filter { !it.isError }.map { StoredMessage(it.role, it.text) },
                pendingRun = pendingRun,
            ),
        )
        if (saved && v > savedVersion) savedVersion = v
    }

    /** Fire-and-forget save on the app-lifetime scope (safe to call at teardown). */
    fun persistAsync() {
        ioScope.launch { persist() }
    }

    /**
     * A run that was left running has ended: write its result into [conversationId] (the active conversation, or a
     * saved one) and clear the pending marker. Returns the text to notify with, or null if the run was not pending
     * there any more (someone else already collected it).
     */
    suspend fun completeRun(conversationId: String, runId: String, outcome: RunOutcome): String? {
        if (conversationId == activeId) {
            if (pendingRun?.runId != runId) return null
            withContext(Dispatchers.Main) {
                when (outcome) {
                    is RunOutcome.Completed -> settleReply(outcome.output)
                    is RunOutcome.Failed -> addMessage("assistant", "\u26A0\uFE0F ${outcome.error}", isError = true)
                    RunOutcome.Cancelled -> Unit // whatever streamed before the stop stays
                }
                updatePendingRun(null)
            }
            persist()
            return summary(outcome)
        }
        return saveLock.withLock {
            val c = store.load(conversationId) ?: return@withLock null
            if (c.pendingRun?.runId != runId) return@withLock null
            val msgs = if (outcome is RunOutcome.Completed) settle(c.messages, outcome.output) else c.messages
            store.save(c.copy(messages = msgs, pendingRun = null, updatedAt = System.currentTimeMillis()))
            summary(outcome)
        }
    }

    private fun summary(o: RunOutcome): String = when (o) {
        is RunOutcome.Completed -> o.output.trim()
        is RunOutcome.Failed -> "Failed: ${o.error}"
        RunOutcome.Cancelled -> "Cancelled"
    }

    /** The final answer replaces the partial reply that was streaming when the app left, or is added if none was. */
    private fun settleReply(output: String) {
        val text = output.trim()
        if (text.isEmpty()) return
        val last = messages.lastOrNull()
        val lastUser = messages.indexOfLast { it.role == "user" }
        if (last != null && last.role == "assistant" && !last.isError && messages.lastIndex > lastUser) {
            messages[messages.lastIndex] = last.copy(text = text)
            markChanged()
        } else {
            addMessage("assistant", text)
        }
    }

    private fun settle(stored: List<StoredMessage>, output: String): List<StoredMessage> {
        val text = output.trim()
        if (text.isEmpty()) return stored
        val last = stored.lastOrNull()
        val lastUser = stored.indexOfLast { it.role == "user" }
        return if (last != null && last.role == "assistant" && stored.lastIndex > lastUser) {
            stored.dropLast(1) + StoredMessage("assistant", text)
        } else {
            stored + StoredMessage("assistant", text)
        }
    }

    suspend fun list(): List<ConversationMeta> = store.list()

    suspend fun delete(id: String) {
        store.delete(id)
        if (id == activeId) startNew()
    }

    companion object {
        @Volatile private var instance: ConversationRepository? = null
        fun get(context: Context): ConversationRepository =
            instance ?: synchronized(this) {
                instance ?: ConversationRepository(ConversationStore(context)).also { instance = it }
            }
    }
}
