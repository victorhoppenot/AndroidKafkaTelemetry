package com.example.vnetgps

import android.Manifest
import android.app.Service
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val foregroundPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.RECORD_AUDIO,
    )

    private val healthReadPermissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SkinTemperatureRecord::class),
    )
    private val healthBackgroundPermission =
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    private lateinit var foregroundLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backgroundLocationLauncher: ActivityResultLauncher<String>
    private lateinit var healthReadLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var healthBackgroundLauncher: ActivityResultLauncher<Set<String>>


    private lateinit var locationStatus: TextView
    private lateinit var healthStatus: TextView
    private lateinit var microphoneStatus: TextView

    private lateinit var runElapsed: TextView
    private lateinit var runToggle: Button
    private lateinit var runStop: Button


    private fun isGranted(permission: String) =
        ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerPermissionLaunchers()

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val container = findViewById<View>(R.id.container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        locationStatus = findViewById(R.id.tv_location_service)
        healthStatus = findViewById(R.id.tv_health_service)
        microphoneStatus = findViewById(R.id.tv_microphone_service)

        runElapsed = findViewById(R.id.tv_run_elapsed)
        runToggle = findViewById(R.id.btn_run_timer)
        runStop = findViewById(R.id.btn_stop_run)

        runToggle.setOnClickListener {
            if (RunTimer.state.value.active) RunTimer.togglePause(this) else RunTimer.start(this)
        }
        runStop.setOnClickListener { RunTimer.stop(this) }

        RunTimer.restore(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TelemetryServices.running.collect(::renderServiceStatus)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RunTimer.state.collectLatest { snapshot ->
                    renderRunTimer(snapshot)
                    while (snapshot.phase == RunPhase.RUNNING) {
                        runElapsed.text = RunTimer.format(snapshot.elapsedMs())
                        delay(TICK_MS)
                    }
                }
            }
        }

        if (foregroundPermissions.all { isGranted(it) }) {
            requestBackgroundLocation()
        } else {
            foregroundLauncher.launch(foregroundPermissions)
        }
    }

    private fun renderServiceStatus(running: Set<Class<out Service>>) {
        locationStatus.showStatus("Location Service", LocationService::class.java in running)
        healthStatus.showStatus("Health Service", HealthService::class.java in running)
        microphoneStatus.showStatus("Microphone Service", MicrophoneService::class.java in running)
    }

    private fun renderRunTimer(snapshot: RunSnapshot) {
        runElapsed.text = RunTimer.format(snapshot.elapsedMs())
        runToggle.text = when (snapshot.phase) {
            RunPhase.IDLE -> "Start Run"
            RunPhase.RUNNING -> "Pause"
            RunPhase.PAUSED -> "Resume"
        }
        runStop.isEnabled = snapshot.active
    }

    private fun TextView.showStatus(label: String, enabled: Boolean) {
        text = if (enabled) "$label Enabled" else "$label Disabled"
    }

    private fun registerPermissionLaunchers() {
        foregroundLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val denied = foregroundPermissions.filterNot { result[it] == true }
            if (denied.isEmpty()) {
                requestBackgroundLocation()
            } else {
                fail("These permissions are all required: $denied")
            }
        }

        backgroundLocationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                requestHealthReadPermissions()
            } else {
                fail("Background location is required.")
            }
        }

        healthReadLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            val missing = healthReadPermissions - granted
            if (missing.isEmpty()) {
                requestHealthBackgroundPermission()
            } else {
                fail("Health Connect permissions denied: $missing")
            }
        }

        healthBackgroundLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (healthBackgroundPermission in granted) {
                startLoggingService()
            } else {
                fail("Background health access is required.")
            }
        }
    }
    private fun requestBackgroundLocation() {
        if (isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            requestHealthReadPermissions()
        } else {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun requestHealthReadPermissions() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            fail("Health Connect is not available on this device.")
            return
        }
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(healthReadPermissions)) {
                requestHealthBackgroundPermission()
            } else {
                healthReadLauncher.launch(healthReadPermissions)
            }
        }
    }

    private fun requestHealthBackgroundPermission() {
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (healthBackgroundPermission in granted) {
                startLoggingService()
            } else {
                healthBackgroundLauncher.launch(setOf(healthBackgroundPermission))
            }
        }
    }

    private fun startLoggingService() {
        TelemetryServices.startAll(this)
    }

    private fun fail(message: String) {
        Log.w("Permissions", message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TICK_MS = 200L
    }
}
