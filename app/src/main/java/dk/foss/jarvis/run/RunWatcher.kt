package dk.foss.jarvis.run

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dk.foss.jarvis.MainActivity
import dk.foss.jarvis.R
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.ConversationStore
import dk.foss.jarvis.data.RunOutcome
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.hermes.latestReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps track of turns Hermes is still working on and collects their results.
 *
 * Each turn is a run on the server, so it goes on whether or not this app does. While the UI listens to the run's
 * stream it owns the result. Once the UI leaves (screen closed, app swiped away, connection lost) it calls [detach]
 * and this class polls `GET /v1/runs/{id}` until the run ends, then writes the answer into the conversation
 * ([ConversationRepository.completeRun]), saves it and notifies. The pending run is also saved with the conversation,
 * so [resumeStored] can pick it up after the process died. Only [stop] cancels a run.
 */
class RunWatcher private constructor(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo = ConversationRepository.get(app)
    private val store = ConversationStore(app)
    private val settings = SettingsStore(app)

    private class Entry(val conversationId: String, val runId: String, val startedAt: Long) {
        @Volatile var polling: Job? = null
        @Volatile var cancelling = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** True while the app is on screen; the chat already shows the result then, so there is no notification. */
    @Volatile var appVisible = false

    val activeCount: Int get() = entries.size

    /** A run started and the UI is listening to it. */
    fun begin(conversationId: String, runId: String, startedAt: Long) {
        entries.putIfAbsent(runId, Entry(conversationId, runId, startedAt))
        RunService.sync(app)
    }

    /** The UI stopped listening; the run continues, and this class collects its result. */
    fun detach(runId: String) {
        entries[runId]?.let(::startPolling)
    }

    /** The UI saw the run end itself and already applied the result. */
    fun finish(runId: String) {
        entries.remove(runId)?.polling?.cancel()
        RunService.sync(app)
    }

    /** Cancel a run for good. It settles as cancelled shortly after (Hermes stops at the next safe point). */
    fun stop(runId: String) {
        val entry = entries[runId]
        entry?.cancelling = true
        scope.launch { client().stopRun(runId) }
        entry?.let(::startPolling)
    }

    fun stopAll() = entries.keys.toList().forEach(::stop)

    /** Pick up runs saved as pending that nobody is tracking (the app was closed or killed while they ran). */
    fun resumeStored() {
        scope.launch {
            for ((conversationId, run) in store.pendingRuns()) {
                if (entries.containsKey(run.runId)) continue
                val entry = Entry(conversationId, run.runId, run.startedAt)
                if (entries.putIfAbsent(run.runId, entry) == null) startPolling(entry)
            }
            RunService.sync(app)
        }
    }

    private suspend fun client(): HermesClient {
        val s = settings.settings.first()
        return HermesClient(s.baseUrl, s.apiKey) // fresh per request, like everywhere else
    }

    private fun startPolling(entry: Entry) {
        synchronized(entry) {
            if (entry.polling?.isActive == true) return
            entry.polling = scope.launch { poll(entry) }
        }
    }

    private suspend fun poll(entry: Entry) {
        while (true) {
            val age = System.currentTimeMillis() - entry.startedAt
            val result = client().fetchRun(entry.runId)
            val outcome: RunOutcome? = result.fold(
                onSuccess = { st ->
                    when {
                        st.isCompleted -> RunOutcome.Completed(st.outputText)
                        st.isFailed -> RunOutcome.Failed(st.errorText.ifBlank { "The run failed" })
                        st.isCancelled -> RunOutcome.Cancelled
                        st.isInterrupted -> if (entry.cancelling) RunOutcome.Cancelled else RunOutcome.Failed("Hermes interrupted the run")
                        else -> null // queued / started / running / waiting_for_approval / stopping: keep polling
                    }
                },
                onFailure = { t ->
                    // Hermes only remembers a finished run briefly. Its session transcript still has the answer.
                    if (t is HermesClient.RunGoneException) recoverFromSession(entry) else null
                },
            )
            val final = outcome ?: if (age > GIVE_UP_MS) RunOutcome.Failed("Gave up waiting for Hermes") else null
            if (final != null) { conclude(entry, final); return }
            delay(if (age < 60_000) 2_000 else if (age < 600_000) 5_000 else 15_000)
        }
    }

    private suspend fun recoverFromSession(entry: Entry): RunOutcome {
        if (entry.cancelling) return RunOutcome.Cancelled
        val sessionId = if (entry.conversationId == repo.activeConversationId) repo.sessionId
        else store.load(entry.conversationId)?.sessionId
        val reply = sessionId?.let { client().fetchSessionMessages(it).getOrNull() }?.let(::latestReply)
        return if (reply != null) RunOutcome.Completed(reply)
        else RunOutcome.Failed("Hermes no longer has this task and its result could not be recovered")
    }

    private suspend fun conclude(entry: Entry, outcome: RunOutcome) {
        entries.remove(entry.runId)
        val text = repo.completeRun(entry.conversationId, entry.runId, outcome)
        Log.d(TAG, "run ${entry.runId} ended: ${outcome::class.simpleName}")
        RunService.sync(app)
        if (text != null && outcome !is RunOutcome.Cancelled) notifyDone(entry, outcome, text)
    }

    private suspend fun notifyDone(entry: Entry, outcome: RunOutcome, text: String) {
        if (appVisible) return
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return
        createChannels(app)
        val open = PendingIntent.getActivity(
            app, entry.runId.hashCode(),
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, entry.conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = settings.settings.first().assistantName +
            if (outcome is RunOutcome.Failed) " couldn’t finish" else " is done"
        val body = text.ifBlank { "Open the conversation to see the result." }
        val notif = NotificationCompat.Builder(app, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() }?.take(140) ?: body.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1500)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(entry.runId.hashCode(), notif) } // POST_NOTIFICATIONS may still be denied
    }

    companion object {
        private const val TAG = "RunWatcher"
        private const val GIVE_UP_MS = 24L * 60 * 60 * 1000
        const val CHANNEL_ONGOING = "jarvis_run_ongoing"
        const val CHANNEL_DONE = "jarvis_run_done"

        @Volatile private var instance: RunWatcher? = null
        fun get(context: Context): RunWatcher =
            instance ?: synchronized(this) {
                instance ?: RunWatcher(context.applicationContext).also { instance = it }
            }

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Working in the background", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished tasks", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
