package com.example.vnetgps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.time.Instant

class RunTimerService : Service() {

    private val channelId = "run_timer_channel"
    private val notificationId = 4

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var publisher: TelemetryPublisher

    private val pause = PauseControl(
        service = this,
        channelId = channelId,
        notificationId = notificationId,
        prefsKey = PauseControl.KEY_RUN_TIMER,
        runningText = "Run in progress",
        pausedText = "Run paused",
        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        decorate = ::decorateNotification,
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Run Timer Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        RunTimer.reload(this)
        publisher = TelemetryPublisher(this, serviceScope)
        publisher.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> RunTimer.begin(this)?.let { publish(EVENT_STARTED, it) }

            ACTION_STOP -> {
                RunTimer.end(this)?.let { publish(EVENT_STOPPED, it) }
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                pause.consumeToggle(intent)
                RunTimer.applyPause(this, pause.paused)?.let {
                    publish(if (pause.paused) EVENT_PAUSED else EVENT_RESUMED, it)
                }
            }
        }

        if (!RunTimer.state.value.active || !pause.enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        publisher.close()
        serviceScope.cancel()
    }

    private fun decorateNotification(builder: NotificationCompat.Builder) {
        val snapshot = RunTimer.state.value
        val elapsed = snapshot.elapsedMs()

        builder.setContentTitle("Run Stopwatch")
        if (snapshot.phase == RunPhase.RUNNING) {
            builder.setWhen(System.currentTimeMillis() - elapsed)
                .setUsesChronometer(true)
                .setShowWhen(true)
        } else {
            builder.setShowWhen(false)
                .setContentText("Run paused at ${RunTimer.format(elapsed)}")
        }

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            PendingIntent.getService(
                this,
                notificationId,
                Intent(this, RunTimerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun publish(event: String, run: RunEvent) {
        Log.d(TAG, "run $event at ${RunTimer.format(run.elapsedMs)}")
        publisher.publish(
            TelemetryTopic.RUN_TIMER,
            JSONObject()
                .put("type", "run_timer")
                .put("event", event)
                .put("runId", run.runId)
                .put("time", Instant.now().toString())
                .put("elapsedMs", run.elapsedMs),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RunTimer"

        const val ACTION_START = "com.example.vnetgps.action.RUN_START"
        const val ACTION_STOP = "com.example.vnetgps.action.RUN_STOP"
        val ACTION_TOGGLE_PAUSE: String = PauseControl.toggleAction(RunTimerService::class.java)

        private const val EVENT_STARTED = "started"
        private const val EVENT_PAUSED = "paused"
        private const val EVENT_RESUMED = "resumed"
        private const val EVENT_STOPPED = "stopped"
    }
}
