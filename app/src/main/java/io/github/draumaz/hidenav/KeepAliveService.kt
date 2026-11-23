package io.github.draumaz.hidenav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Lightweight special-use foreground service that keeps this process at perceptible priority
 * even when the overlay window is hidden (on the lock screen, with the screen off, or inside
 * apps that hide non-system overlays).
 *
 * HideNav intentionally doesn't request POST_NOTIFICATIONS: on Android 13+ startForeground()
 * still succeeds without it and simply omits the notification from the shade (it remains
 * visible in Quick Settings' Active apps). On Android 12, IMPORTANCE_MIN keeps it silent.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val settings = (application as HideNavApp).prefs.settings.value
        val needed = settings.hidePill && (settings.showOnHome || settings.keepCircleToSearch)
        if (!needed) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "HideNavKeepAlive"
        private const val CHANNEL_ID = "hidenav_active"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }.onFailure { Log.w(TAG, "startForegroundService failed", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveService::class.java))
            }.onFailure { Log.w(TAG, "stopService failed", it) }
        }
    }
}
