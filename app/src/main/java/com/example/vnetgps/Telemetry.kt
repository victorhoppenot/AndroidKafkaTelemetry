package com.example.vnetgps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
object DeviceId {
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("vnet", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }
}
object AppPermissions {
    val RUNTIME = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.RECORD_AUDIO,
    )

    val HEALTH = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SkinTemperatureRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )

    fun missingRuntime(context: Context): List<String> = RUNTIME.filterNot {
        ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun healthConnectAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
}

object TelemetryServices {
    private val all = listOf(
        LocationService::class.java,
        HealthService::class.java,
        MicrophoneService::class.java,
    )

    fun startAll(context: Context) {
        PauseControl.clearAll(context)
        for (service in all) context.startForegroundService(Intent(context, service))
    }

    fun stopAll(context: Context) {
        for (service in all) context.stopService(Intent(context, service))
    }
}
class TelemetryPublisher(context: Context, scope: CoroutineScope) {

    private val deviceId = DeviceId.get(context)

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val bridge = BridgeConnection(subject = "producer", scope = scope, client = client)

    fun connect() = bridge.connect()

    fun close() = bridge.close()

    fun publish(topic: TelemetryTopic, body: JSONObject) {
        body.put("deviceId", deviceId)
        publish(topic, body.toString().toByteArray())
    }
    fun publish(topic: TelemetryTopic, payload: ByteArray) {
        bridge.publish(
            topic = topic.topic,
            key = deviceId.toByteArray(),
            payload = payload,
        )
    }
}
