package com.example.vnetgps

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.time.Instant

class LocationService : Service() {

    private val channelId = "location_service_channel"
    private val notificationId = 1
    private val locationIntervalMs = 1000L

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var publisher: TelemetryPublisher

    private val pause = PauseControl(
        service = this,
        channelId = channelId,
        notificationId = notificationId,
        prefsKey = PauseControl.KEY_LOCATION,
        runningText = "Logging location...",
        pausedText = "Location paused",
        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Location Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        publisher = TelemetryPublisher(this, serviceScope)
        publisher.connect()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pause.consumeToggle(intent)

        

        
        if (!pause.enterForeground()) {
            TelemetryServices.stopAll(this)
            return START_NOT_STICKY
        }

        
        val missing = AppPermissions.missingRuntime(this)
        if (missing.isNotEmpty()) {
            Log.w("LiveLocation", "permissions revoked; stopping: $missing")
            TelemetryServices.stopAll(this)
            return START_NOT_STICKY
        }

        if (pause.paused) {
            

            fusedLocationClient.removeLocationUpdates(locationCallback)
        } else {
            val locationRequest = LocationRequest.Builder(locationIntervalMs)
                .setMinUpdateIntervalMillis(locationIntervalMs)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build()
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        publisher.close()
        serviceScope.cancel()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                Log.d("LiveLocation", "Lat: ${location.latitude}, Lon: ${location.longitude}")
                publisher.publish(TelemetryTopic.LOCATION, location.toJson())
            }
        }
    }

    private fun Location.toJson(): JSONObject = JSONObject()
        .put("type", "location")
        .put("time", Instant.ofEpochMilli(time).toString())
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("accuracy", if (hasAccuracy()) accuracy else JSONObject.NULL)
        .put("altitude", if (hasAltitude()) altitude else JSONObject.NULL)
        .put("speed", if (hasSpeed()) speed else JSONObject.NULL)
        .put("bearing", if (hasBearing()) bearing else JSONObject.NULL)

    override fun onBind(intent: Intent?): IBinder? = null
}
