package dk.foss.jarvis.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import dk.foss.jarvis.hermes.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    private var activeId: String = UUID.randomUUID().toString()
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
        messages.clear()
        messages.addAll(c.messages.map { UiMessage(it.role, it.text) })
        markClean()
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

    /** History (non-error) as Hermes chat messages for building a request. */
    fun historyForRequest(): List<ChatMessage> =
        messages.filter { !it.isError }.map { ChatMessage(it.role, it.text) }

    suspend fun persist() = saveLock.withLock {
        val v = version.get()
        if (v == savedVersion) return@withLock
        if (messages.none { !it.isError }) return@withLock
        val saved = store.save(
            Conversation(
                id = activeId,
                title = title.ifEmpty { "Conversation" },
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis(),
                sessionId = sessionId,
                messages = messages.filter { !it.isError }.map { StoredMessage(it.role, it.text) },
            ),
        )
        if (saved && v > savedVersion) savedVersion = v
    }

    /** Fire-and-forget save on the app-lifetime scope (safe to call at teardown). */
    fun persistAsync() {
        ioScope.launch { persist() }
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
