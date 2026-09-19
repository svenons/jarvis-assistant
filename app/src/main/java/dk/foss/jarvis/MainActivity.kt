package dk.foss.jarvis

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.result.contract.ActivityResultContracts
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.run.RunWatcher
import dk.foss.jarvis.ui.Branding
import dk.foss.jarvis.ui.ChatScreen
import dk.foss.jarvis.ui.ChatViewModel
import dk.foss.jarvis.ui.ConversationScreen
import dk.foss.jarvis.ui.ConversationViewModel
import dk.foss.jarvis.ui.HistoryScreen
import dk.foss.jarvis.ui.HistoryViewModel
import dk.foss.jarvis.ui.JarvisTheme
import dk.foss.jarvis.ui.LocalBranding
import dk.foss.jarvis.ui.SettingsScreen
import dk.foss.jarvis.wake.WakeModels
import dk.foss.jarvis.wake.WakeWordService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Screen { Chat, Settings, Conversation, History }

class MainActivity : ComponentActivity() {

    // Incremented each time the assistant is triggered; observed by Compose to
    // jump into conversation mode (works for both cold start and onNewIntent).
    private var assistEpoch by mutableStateOf(0)

    // True while the lock screen is up. Opened over it (wake word / assist gesture), the app shows only the voice
    // screen: no chat history, no conversation list, no Settings (which holds the API key), and closing it leaves
    // the app instead of dropping into the chat. Unlocking lifts all of that.
    private var locked by mutableStateOf(false)

    private fun refreshLocked() {
        locked = (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
    }

    private val lockStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshLocked()
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* denied = no "done" notifications */ }

    /** Ask once for permission to post the "finished" notification of a task left running in the background. */
    private fun askForNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("jarvis_prompts", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Tapping a "finished" notification opens that conversation, unless a turn is in progress in the current one. */
    private fun openRequestedConversation(i: Intent?) {
        val id = i?.getStringExtra(EXTRA_OPEN_CONVERSATION) ?: return
        i.removeExtra(EXTRA_OPEN_CONVERSATION)
        if (locked) return
        val repo = ConversationRepository.get(this)
        if (repo.pendingRun != null || repo.activeConversationId == id) return
        lifecycleScope.launch { repo.open(id) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen awake while the assistant is in the foreground.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Edge-to-edge dark: transparent bars, dark background, light icons.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        window.setBackgroundDrawableResource(android.R.color.black)
        if (isAssistIntent(intent)) {
            // A rotation (or any recreation) hands us the original wake/assist intent again. Only a real launch
            // (no saved state) is a trigger; replaying it on rotation would jump to the voice screen and start
            // listening. The lock-screen flags are per-instance, so they are re-applied either way.
            if (savedInstanceState == null) assistEpoch++
            showOverLockScreen()
        }
        refreshLocked()
        if (savedInstanceState == null) openRequestedConversation(intent)
        askForNotificationsOnce()
        lifecycleScope.launch {
            SettingsStore(this@MainActivity).settings.collect { wakeInBackground = it.wakeBackground }
        }
        ContextCompat.registerReceiver(
            this, lockStateReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT).apply { addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            // The assistant's name and wake phrase, shown across the screens; follows Settings live.
            val settings by remember { SettingsStore(this@MainActivity).settings }.collectAsState(initial = null)
            val branding = settings?.let { s ->
                Branding(
                    name = s.assistantName,
                    wakePhrase = if (s.wakeEnabled) WakeModels.resolve(this@MainActivity, s.wakeModel, s.wakeCustomName).phrase else null,
                )
            } ?: Branding(BuildConfig.DEFAULT_ASSISTANT_NAME, null)
            CompositionLocalProvider(LocalBranding provides branding) {
            JarvisTheme {
                var screen by remember {
                    mutableStateOf(if (assistEpoch > 0) Screen.Conversation else Screen.Chat)
                }
                LaunchedEffect(assistEpoch) {
                    if (assistEpoch > 0) screen = Screen.Conversation
                }
                // Locked: only the voice screen, whatever `screen` says.
                when (if (locked) Screen.Conversation else screen) {
                    Screen.Chat -> {
                        val vm: ChatViewModel = viewModel()
                        ChatScreen(
                            vm = vm,
                            onOpenSettings = { screen = Screen.Settings },
                            onOpenVoice = { screen = Screen.Conversation },
                            onOpenHistory = { screen = Screen.History },
                        )
                    }
                    Screen.Settings -> {
                        BackHandler { screen = Screen.Chat }
                        SettingsScreen(onBack = { screen = Screen.Chat })
                    }
                    Screen.Conversation -> {
                        val leave = { if (locked) finish() else screen = Screen.Chat }
                        BackHandler { leave() }
                        val cvm: ConversationViewModel = viewModel()
                        ConversationScreen(
                            vm = cvm,
                            assistTrigger = assistEpoch,
                            onRequestUnlock = { done -> requestUnlock(done) },
                            onExit = { leave() },
                        )
                    }
                    Screen.History -> {
                        BackHandler { screen = Screen.Chat }
                        val hvm: HistoryViewModel = viewModel()
                        HistoryScreen(
                            vm = hvm,
                            onOpen = { screen = Screen.Chat },
                            onBack = { screen = Screen.Chat },
                        )
                    }
                }
            }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocked()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(lockStateReceiver) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refreshLocked()
        setIntent(intent)
        openRequestedConversation(intent)
        if (isAssistIntent(intent)) {
            assistEpoch++
            showOverLockScreen()
        }
    }

    /**
     * Ask the system to unlock the phone (fingerprint, PIN or pattern, whatever the lock screen uses), then report
     * whether it worked. Used when a locked-phone request needs the phone unlocked; the conversation carries on after.
     */
    private fun requestUnlock(done: (Boolean) -> Unit) {
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (km == null || !km.isKeyguardLocked) { refreshLocked(); done(true); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { refreshLocked(); done(true) }
            override fun onDismissCancelled() = done(false)
            override fun onDismissError() = done(false)
        })
    }

    /** Appear over the lock screen and turn the display on (wake-word / assist launch). */
    private fun showOverLockScreen() {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // Actually dismiss the keyguard so the user can interact immediately.
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        km?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = refreshLocked()
            override fun onDismissCancelled() {}
            override fun onDismissError() {}
        })
    }

    // Whether the wake listener keeps running once the app is out of sight (Settings → Wake word). Kept here so
    // onStop can act on it immediately; the service can't be stopped from a coroutine that onDestroy may cancel.
    @Volatile private var wakeInBackground = true

    override fun onStart() {
        super.onStart()
        RunWatcher.get(this).apply {
            appVisible = true
            resumeStored() // results of turns left running while the app was closed or killed
        }
        rearmWakeWord()
    }

    override fun onStop() {
        super.onStop()
        RunWatcher.get(this).appVisible = false
        // "Only while the app is open": stop listening when it leaves the screen (not on a rotation).
        if (!wakeInBackground && !isChangingConfigurations) WakeWordService.stop(this)
    }

    /** If the wake word is enabled, make sure the listener is running while the app is open. */
    private fun rearmWakeWord() {
        lifecycleScope.launch {
            val s = SettingsStore(this@MainActivity).settings.first()
            val micOk = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (s.wakeEnabled && micOk) runCatching { WakeWordService.start(this@MainActivity) }
        }
    }

    private fun isAssistIntent(i: Intent?): Boolean =
        i?.getBooleanExtra(EXTRA_FROM_ASSIST, false) == true ||
            i?.action == Intent.ACTION_ASSIST

    companion object {
        const val EXTRA_FROM_ASSIST = "from_assist"
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
    }
}
