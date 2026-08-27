package com.example.vnetgps

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
class PauseControl(
    private val service: Service,
    private val channelId: String,
    private val notificationId: Int,
    private val prefsKey: String,
    private val runningText: String,
    private val pausedText: String,
    private val foregroundServiceType: Int,
    private val decorate: (NotificationCompat.Builder) -> Unit = {},
) {
    var paused: Boolean
        get() = prefs.getBoolean(prefsKey, false)
        private set(value) {
            prefs.edit().putBoolean(prefsKey, value).apply()
        }
    private val prefs: android.content.SharedPreferences
        get() = service.getSharedPreferences("vnet", Context.MODE_PRIVATE)

    private val toggleAction = toggleAction(service.javaClass)

    private var foreground = false
    fun consumeToggle(intent: Intent?) {
        if (intent?.action != toggleAction) return
        paused = !paused
        Log.d(TAG, "${service.javaClass.simpleName}: ${if (paused) "paused" else "resumed"}")
    }

    fun buildNotification(): Notification {
        val isPaused = paused
        val toggle = PendingIntent.getService(
            service,
            notificationId,
            Intent(service, service.javaClass).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(service, channelId)
            .setContentTitle("Service Running")
            .setContentText(if (isPaused) pausedText else runningText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play
                else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                toggle,
            )
            .also(decorate)
            .build()
    }

    fun enterForeground(): Boolean {
        if (foreground) {
            service.getSystemService(NotificationManager::class.java)
                .notify(notificationId, buildNotification())
            return true
        }
        return try {
            service.startForeground(notificationId, buildNotification(), foregroundServiceType)
            foreground = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "${service.javaClass.simpleName}: cannot go foreground", e)
            false
        }
    }

    companion object {
        private const val TAG = "PauseControl"
        private const val ACTION_TOGGLE_PREFIX = "com.example.vnetgps.action.TOGGLE_PAUSE."

        const val KEY_MICROPHONE = "microphone_paused"
        const val KEY_LOCATION = "location_paused"
        const val KEY_RUN_TIMER = "run_timer_paused"

        fun toggleAction(serviceClass: Class<out Service>): String =
            ACTION_TOGGLE_PREFIX + serviceClass.simpleName

        fun clearAll(context: Context) = clear(context, KEY_MICROPHONE, KEY_LOCATION)

        fun clear(context: Context, vararg keys: String) {
            val edit = context.getSharedPreferences("vnet", Context.MODE_PRIVATE).edit()
            for (key in keys) edit.remove(key)
            edit.apply()
        }
    }
}
