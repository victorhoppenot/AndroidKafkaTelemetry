package com.example.vnetgps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.lifecycle.lifecycleScope
import com.example.vnetgps.ui.theme.VNETGPSTheme
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

    private var statusText by mutableStateOf("Requesting permissions...")

    private lateinit var foregroundLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backgroundLocationLauncher: ActivityResultLauncher<String>
    private lateinit var healthReadLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var healthBackgroundLauncher: ActivityResultLauncher<Set<String>>

    private fun isGranted(permission: String) =
        ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerPermissionLaunchers()

        enableEdgeToEdge()
        setContent {
            VNETGPSTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Text(text = statusText, modifier = Modifier.padding(innerPadding))
                }
            }
        }

        if (foregroundPermissions.all { isGranted(it) }) {
            requestBackgroundLocation()
        } else {
            foregroundLauncher.launch(foregroundPermissions)
        }
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
        statusText = "Logging data"
        TelemetryServices.startAll(this)
    }

    private fun fail(message: String) {
        statusText = message
        Log.w("Permissions", message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
