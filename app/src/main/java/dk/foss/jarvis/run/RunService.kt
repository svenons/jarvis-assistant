package dk.foss.jarvis.run

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dk.foss.jarvis.MainActivity
import dk.foss.jarvis.R

/**
 * Foreground service that exists only while Hermes has runs going. It keeps the process alive so [RunWatcher] can
 * collect the results after the app is closed, and its notification carries a Cancel button (the only thing that
 * cancels a run other than the Stop / Cancel buttons in the app). After the system kills the process it restarts
 * (sticky) and resumes from the pending runs saved with the conversations.
 */
class RunService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val watcher = RunWatcher.get(this)
        RunWatcher.createChannels(this)
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(watcher.activeCount),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        when {
            intent?.action == ACTION_CANCEL -> watcher.stopAll()
            intent == null -> watcher.resumeStored() // restarted by the system after the process was killed
        }
        if (watcher.activeCount == 0 && intent != null) stopSelf() // nothing left to watch (resumeStored stops it later)
        return START_STICKY
    }

    private fun notification(active: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, RunService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, RunWatcher.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Hermes is working")
            .setContentText(if (active > 1) "$active tasks running" else "Your request is still running")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 4711
        private const val ACTION_CANCEL = "dk.foss.jarvis.run.CANCEL"

        /** Start the service while runs exist (refreshing its notification), stop it when none are left. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, RunService::class.java)
            if (RunWatcher.get(app).activeCount > 0) {
                // Refused when the app isn't allowed to start a foreground service right now; then it just isn't started.
                runCatching { if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent) }
            } else {
                app.stopService(intent)
            }
        }
    }
}
