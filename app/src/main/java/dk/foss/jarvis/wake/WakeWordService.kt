package dk.foss.jarvis.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import dk.foss.jarvis.BuildConfig
import dk.foss.jarvis.MainActivity
import dk.foss.jarvis.R
import dk.foss.jarvis.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Always-on wake-phrase listener ("Hey Jarvis" by default; see [WakeModels]). Runs openWakeWord in a
 * microphone foreground service; on detection, brings up conversation mode. Requires RECORD_AUDIO.
 */
class WakeWordService : Service() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** One source of per-frame wake scores: the library engine (bundled models) or [CustomWakeEngine]. */
    private interface Detector {
        val scores: Flow<Float>
        fun start()
        fun stop()
        fun release()
    }

    private var detector: Detector? = null
    private var scoreJob: Job? = null
    private var loadGen = 0
    private var phrase = "" // set once the model is resolved; the first notification says "Starting…"
    private var assistantName = BuildConfig.DEFAULT_ASSISTANT_NAME
    // Score bars for the model in use at the chosen sensitivity (set in startEngine).
    private var strongThreshold = 0.3f
    private var sustainedThreshold = 0.2f

    @Volatile private var cooling = false
    @Volatile private var paused = false

    // Detection is driven off the engine's raw per-frame `scores` flow (not its
    // single-frame `detections`), so we can smooth the confidence and trigger on a
    // more forgiving bar. All touched only from the main thread (the collector runs there).
    private val recentScores = ArrayDeque<Float>()
    private var lastTriggerAt = 0L
    // One-line-per-utterance peak log for tuning sensitivity (not per-frame spam).
    private var burstPeak = 0f
    private var inBurst = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        ServiceCompat.startForeground(
            this,
            NOTIF_ONGOING,
            ongoingNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        startEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startEngine() {
        if (detector != null) return
        val gen = ++loadGen
        uiScope.launch {
            try {
                val settings = SettingsStore(applicationContext).settings.first()
                val model = WakeModels.resolve(applicationContext, settings.wakeModel, settings.wakeCustomName)
                // Loading the ONNX sessions takes a moment; keep it off the main thread.
                val built = withContext(Dispatchers.Default) { buildDetector(model) }
                if (gen != loadGen) { // reloaded or destroyed while loading
                    runCatching { built.release() }
                    return@launch
                }
                val mult = SENSITIVITY[settings.wakeSensitivity.coerceIn(0, 2)]
                strongThreshold = (model.strong * mult).coerceAtMost(MAX_BAR)
                sustainedThreshold = (model.sustained * mult).coerceAtMost(MAX_BAR)
                phrase = model.phrase
                assistantName = settings.assistantName
                detector = built
                scoreJob = uiScope.launch { built.scores.collect { onScore(it) } }
                if (!paused) built.start()
                updateOngoing()
                Log.i(TAG, "wake engine ready, listening for '${model.phrase}' " +
                    "(bars %.2f / %.2f)".format(strongThreshold, sustainedThreshold))
            } catch (e: Throwable) {
                // Most likely missing model assets or no mic permission — stop gracefully.
                Log.e(TAG, "wake engine failed to start", e)
                stopSelf()
            }
        }
    }

    private fun buildDetector(model: WakeModel): Detector {
        if (model.asset == null) {
            val engine = CustomWakeEngine(this, WakeModels.customFile(this).readBytes(), engineScope)
            return object : Detector {
                override val scores = engine.scores
                override fun start() = engine.start()
                override fun stop() = engine.stop()
                override fun release() = engine.release()
            }
        }
        // Threshold is set high so the library's own detection (and its logging) stays
        // quiet — we make the decision ourselves from the raw `scores` flow.
        val engine = WakeWordEngine(
            this, listOf(WakeWordModel(model.id, model.asset, MODEL_THRESHOLD)),
            DetectionMode.SINGLE_BEST, COOLDOWN_MS, engineScope,
        )
        return object : Detector {
            override val scores = engine.scores.map { it.score }
            override fun start() = engine.start()
            override fun stop() = engine.stop()
            override fun release() = engine.release()
        }
    }

    /** Drop the running detector and start again from the current settings (phrase, sensitivity). */
    private fun reload() {
        loadGen++
        scoreJob?.cancel(); scoreJob = null
        val old = detector
        detector = null
        runCatching { old?.stop(); old?.release() }
        recentScores.clear()
        inBurst = false
        burstPeak = 0f
        startEngine()
    }

    /**
     * Raw confidence for the wake phrase arrives every ~80 ms. The wake phrase keeps the
     * score elevated for several frames, so we fire on EITHER one strong frame (keeps the
     * old behaviour, no recall regression) OR a sustained moderate average over a short
     * window (catches utterances that peak just under the single-frame bar, or whose one
     * strong frame lands in a momentary dip). A refractory period prevents re-fires.
     */
    private fun onScore(score: Float) {
        // Track + log the peak of each voice burst (one line per utterance, for tuning).
        if (score >= BURST_FLOOR) {
            inBurst = true
            if (score > burstPeak) burstPeak = score
        } else if (inBurst) {
            inBurst = false
            Log.i(TAG, "voice burst peak=%.2f".format(burstPeak))
            burstPeak = 0f
        }

        recentScores.addLast(score)
        while (recentScores.size > SMOOTHING_FRAMES) recentScores.removeFirst()
        val sustained = recentScores.size == SMOOTHING_FRAMES &&
            recentScores.sum() / SMOOTHING_FRAMES >= sustainedThreshold
        if (score < strongThreshold && !sustained) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < REFRACTORY_MS) return
        lastTriggerAt = now
        recentScores.clear()
        onWake(score)
    }

    private fun onWake(score: Float) {
        Log.i(TAG, "WAKE detected (score=%.2f) cooling=%s".format(score, cooling))
        if (cooling) return
        cooling = true

        // Free the mic so conversation mode's recognizer can record, then open the app.
        pauseEngine()

        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_FROM_ASSIST, true)
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            // Locked: a plain startActivity is suppressed, so use a full-screen-intent
            // notification — the sanctioned way to launch over the keyguard.
            notifyWake(launch)
        } else {
            // Unlocked: launch directly (allowed via SYSTEM_ALERT_WINDOW), no notification noise.
            runCatching { startActivity(launch) }
        }

        uiScope.launch {
            delay(4000)
            cooling = false
        }
    }

    private fun notifyWake(launch: Intent) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 1, launch, flags)
        val notif = NotificationCompat.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(assistantName)
            .setContentText("Listening…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_WAKE, notif)
        // The full-screen intent launches the activity; clear the shade entry shortly after.
        uiScope.launch { delay(2000); runCatching { nm.cancel(NOTIF_WAKE) } }
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(assistantName)
            .setContentText(if (phrase.isEmpty()) "Starting…" else "Listening for “$phrase”")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun updateOngoing() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ONGOING, ongoingNotification()) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Wake word", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, "Wake alerts", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** Release the mic (e.g. while a conversation is using the recognizer). */
    private fun pauseEngine() {
        if (paused) return
        paused = true
        runCatching { detector?.stop() }
        recentScores.clear()
        inBurst = false
        burstPeak = 0f
        Log.i(TAG, "wake paused (mic released)")
    }

    /** Resume always-on listening after a conversation finishes. */
    private fun resumeEngine() {
        if (!paused) return
        paused = false
        runCatching { detector?.start() }
        Log.i(TAG, "wake resumed")
    }

    override fun onDestroy() {
        instance = null
        loadGen++
        scoreJob?.cancel()
        runCatching { detector?.stop(); detector?.release() }
        detector = null
        runCatching { engineScope.cancel() }
        runCatching { uiScope.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JarvisWake"
        private const val CHANNEL_ONGOING = "jarvis_wake_ongoing"
        private const val CHANNEL_WAKE = "jarvis_wake_alert"
        private const val NOTIF_ONGOING = 1001
        private const val NOTIF_WAKE = 1002

        // --- detection sensitivity (driven from the raw scores flow) ---
        // Model threshold kept high so the library's built-in detection/logging stays
        // dormant; we decide from `scores` below.
        private const val MODEL_THRESHOLD = 0.95f
        // The score bars now live per model in WakeModels (a single strong frame, or a sustained
        // moderate average over the smoothing window). They're scaled by the sensitivity setting:
        // index 0 = stricter, 1 = normal, 2 = more sensitive.
        private val SENSITIVITY = floatArrayOf(1.4f, 1.0f, 0.7f)
        private const val MAX_BAR = 0.95f
        private const val SMOOTHING_FRAMES = 5 // ~0.4 s at 80 ms/frame (was 3)
        private const val REFRACTORY_MS = 2500L // ignore re-triggers right after a fire
        private const val BURST_FLOOR = 0.1f // score above which a "voice burst" is in progress
        private const val COOLDOWN_MS = 1500L // engine `detections` cooldown (unused now, harmless)

        @Volatile private var instance: WakeWordService? = null

        /** Pause/resume the always-on listener so the mic is free during a conversation. */
        fun pauseListening() {
            instance?.pauseEngine()
        }

        fun resumeListening() {
            instance?.resumeEngine()
        }

        /** Apply a changed wake phrase / model / sensitivity to the running listener. */
        fun reload() {
            instance?.reload()
        }

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
