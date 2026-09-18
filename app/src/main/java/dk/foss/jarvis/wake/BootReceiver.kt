package dk.foss.jarvis.wake

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dk.foss.jarvis.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the always-on "Hey Jarvis" listener after the device boots, so the
 * assistant is ready without the user opening the app first.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = SettingsStore(app).settings.first()
                val enabled = settings.wakeEnabled && settings.wakeBackground
                val micOk = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (enabled && micOk) {
                    // Android 14+ refuses to start a microphone foreground service from a boot receiver, so this
                    // can throw; the listener then starts the next time the app is opened. Say so in the log.
                    runCatching { WakeWordService.start(app) }
                        .onFailure { Log.w("JarvisWake", "could not start the wake listener at boot", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
