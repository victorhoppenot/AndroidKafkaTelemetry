package com.example.vnetgps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

class HealthService : Service() {

    private val channelId = "health_service_channel"
    private val notificationId = 2
    private val healthPollMs = 30_000L

    private val healthWindowOverlap: Duration = Duration.ofMinutes(5)
    private var lastHealthRead: Instant = Instant.now()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null

    private lateinit var publisher: TelemetryPublisher

    // remove duplicates in overlapped health data extractions
    private val seenRecordIds = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>) = size > 512
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Health Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        publisher = TelemetryPublisher(this, serviceScope)
        publisher.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Service Running")
            .setContentText("Logging health data...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        )

        val missing = AppPermissions.missingRuntime(this)
        if (missing.isNotEmpty()) {
            Log.w("HealthStatus", "permissions revoked; stopping: $missing")
            TelemetryServices.stopAll(this)
            return START_NOT_STICKY
        }
        if (!AppPermissions.healthConnectAvailable(this)) {
            Log.w("HealthStatus", "Health Connect unavailable; stopping")
            TelemetryServices.stopAll(this)
            return START_NOT_STICKY
        }

        if (healthJob?.isActive != true) {
            healthJob = startHealthPolling()
        }

        return START_STICKY
    }

    private fun startHealthPolling(): Job {
        val client = HealthConnectClient.getOrCreate(this)

        return serviceScope.launch {
            while (isActive) {
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(AppPermissions.HEALTH)) {
                    Log.w("HealthStatus", "health permissions revoked; stopping: " +
                        "${AppPermissions.HEALTH - granted}")
                    TelemetryServices.stopAll(this@HealthService)
                    return@launch
                }
                val now = Instant.now()
                val from = lastHealthRead.minus(healthWindowOverlap)
                val heartOk = readHeartRate(client, from, now)
                val skinOk = readSkinTemperature(client, from, now)
                if (heartOk && skinOk) lastHealthRead = now
                delay(healthPollMs)
            }
        }
    }

    private suspend fun readHeartRate(
        client: HealthConnectClient,
        from: Instant,
        to: Instant,
    ): Boolean {
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to)
                )
            )
            for (record in response.records) {
                if (isDuplicate(record.metadata.id)) continue
                for (sample in record.samples) {
                    Log.d("LiveHeartRate", "${sample.time}: ${sample.beatsPerMinute} bpm")
                    publisher.publish(
                        TelemetryTopic.HEART_RATE,
                        JSONObject()
                            .put("type", "heart_rate")
                            .put("time", sample.time.toString())
                            .put("bpm", sample.beatsPerMinute)
                    )
                }
            }
            true
        } catch (e: Exception) {
            Log.e("LiveHeartRate", "Failed to read heart rate", e)
            false
        }
    }

    private suspend fun readSkinTemperature(
        client: HealthConnectClient,
        from: Instant,
        to: Instant,
    ): Boolean {
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SkinTemperatureRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to)
                )
            )
            for (record in response.records) {
                if (isDuplicate(record.metadata.id)) continue
                val baseline = record.baseline?.inCelsius
                for (delta in record.deltas) {
                    val absolute = baseline?.plus(delta.delta.inCelsius)
                    Log.d(
                        "LiveSkinTemp",
                        "${delta.time}: delta ${delta.delta.inCelsius}C" +
                            (absolute?.let { ", absolute ${it}C" } ?: ", no baseline")
                    )
                    publisher.publish(
                        TelemetryTopic.SKIN_TEMPERATURE,
                        JSONObject()
                            .put("type", "skin_temperature")
                            .put("time", delta.time.toString())
                            .put("deltaC", delta.delta.inCelsius)
                            .put("absoluteC", absolute ?: JSONObject.NULL)
                    )
                }
            }
            true
        } catch (e: Exception) {
            Log.e("LiveSkinTemp", "Failed to read skin temperature", e)
            false
        }
    }

    private fun isDuplicate(recordId: String): Boolean =
        seenRecordIds.put(recordId, Unit) != null

    override fun onDestroy() {
        super.onDestroy()
        publisher.close()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
