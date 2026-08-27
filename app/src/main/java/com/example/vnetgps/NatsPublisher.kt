package com.example.vnetgps

import android.content.Context
import android.util.Log
import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.Nats
import io.nats.client.Options
import java.time.Duration

object NatsConfig {
    const val SERVER_URL = BuildConfig.NATS_SERVER_URL
    const val SUBJECT_PREFIX = "device.microphone"

    val RECONNECT_WAIT: Duration = Duration.ofSeconds(2)
}
class NatsPublisher(context: Context) {

    val subject = "${NatsConfig.SUBJECT_PREFIX}.${DeviceId.get(context)}"
    @Volatile
    private var connection: Connection? = null

    fun connect() {
        val options = Options.Builder()
            .server(NatsConfig.SERVER_URL)
            .connectionName("vnetgps")
            .maxReconnects(-1)
            .reconnectWait(NatsConfig.RECONNECT_WAIT)
            .connectionListener { conn, event ->
                when (event) {
                    ConnectionListener.Events.CONNECTED,
                    ConnectionListener.Events.RECONNECTED -> connection = conn

                    ConnectionListener.Events.CLOSED -> connection = null

                    else -> Unit
                }
                Log.d(TAG, "nats $event")
            }
            .build()

        Nats.connectAsynchronously(options, true)
    }

    fun publish(payload: ByteArray) {
        val conn = connection
        if (conn == null) {
            Log.d(TAG, "nats not connected; dropping batch")
            return
        }
        runCatching { conn.publish(subject, payload) }
            .onFailure { Log.w(TAG, "nats publish failed", it) }
    }

    fun close() {
        val conn = connection
        connection = null
        runCatching { conn?.close() }.onFailure { Log.w(TAG, "nats close failed", it) }
    }

    private companion object {
        const val TAG = "NatsPublisher"
    }
}
