package com.example.vnetgps

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

object BridgeConfig {
    const val BASE_URL = BuildConfig.BRIDGE_BASE_URL
    const val MAX_PENDING = 512

    const val MIN_RETRY_MS = 1_000L
    const val MAX_RETRY_MS = 60_000L
}

enum class TelemetryTopic(val topic: String) {
    LOCATION("device-location"),
    HEART_RATE("device-heart-rate"),
    SKIN_TEMPERATURE("device-skin-temperature");

    companion object {
        val ALL: List<String> = entries.map { it.topic }

        fun fromTopic(topic: String): TelemetryTopic? = entries.find { it.topic == topic }
    }
}

data class BridgeRecord(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val key: ByteArray,
    val payload: ByteArray,
)


class BridgeConnection(
    private val subject: String,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val baseUrl: String = BridgeConfig.BASE_URL,
    private val subscription: List<String> = emptyList(),
    private val onRecord: (BridgeRecord) -> Unit = {},
) : WebSocketListener() {

    private class Pending(val topic: String, val key: ByteArray, val payload: ByteArray)

    private val lock = Any()
    private val pending = ArrayDeque<Pending>()
    private var webSocket: WebSocket? = null
    private var open = false
    private var shuttingDown = false
    private var retryDelayMs = BridgeConfig.MIN_RETRY_MS

    private var inboundHeader: JSONObject? = null
    private var inboundKey: ByteString? = null

    fun connect() {
        synchronized(lock) {
            if (shuttingDown || webSocket != null) return
            val request = Request.Builder().url("$baseUrl/bridge/$subject").build()
            webSocket = client.newWebSocket(request, this)
        }
    }

    fun publish(topic: String, key: ByteArray, payload: ByteArray) {
        val socket = synchronized(lock) {
            if (shuttingDown) return
            val live = webSocket
            if (live == null || !open) {
                if (pending.size >= BridgeConfig.MAX_PENDING) pending.removeFirst()
                pending.addLast(Pending(topic, key, payload))
                return
            }
            live
        }
        trySend(socket, Pending(topic, key, payload))
    }

    fun close() {
        val socket = synchronized(lock) {
            shuttingDown = true
            pending.clear()
            webSocket.also { webSocket = null; open = false }
        }
        socket?.close(NORMAL_CLOSURE, null)
    }

    private fun trySend(socket: WebSocket, record: Pending): Boolean {
        val sent = socket.send(record.topic) &&
            socket.send(record.key.toByteString()) &&
            socket.send(record.payload.toByteString())
        if (!sent) {
            Log.w(TAG, "partial record write; resetting connection")
            socket.cancel()
        }
        return sent
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        val backlog = synchronized(lock) {
            open = true
            retryDelayMs = BridgeConfig.MIN_RETRY_MS
            val drained = pending.toList()
            pending.clear()
            drained
        }

        if (subject == CONSUMER) {
            if (subscription.isEmpty()) {
                Log.e(TAG, "consumer connection opened without a subscription")
                webSocket.close(NORMAL_CLOSURE, null)
                return
            }
            webSocket.send(subscription.joinToString(","))
        }

        for (record in backlog) {
            if (!trySend(webSocket, record)) return
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = runCatching { JSONObject(text) }.getOrElse {
            Log.w(TAG, "unparseable frame: $text")
            return
        }
        when (frame.optString("status")) {
            "consumer" -> {
                inboundHeader = frame
                inboundKey = null
            }
            "error" -> Log.w(TAG, "bridge error: ${frame.optString("message")}")
            else -> Log.d(TAG, "bridge: $text")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val header = inboundHeader
        if (header == null) {
            Log.w(TAG, "binary frame with no preceding header; ignoring")
            return
        }
        val key = inboundKey
        if (key == null) {
            inboundKey = bytes
            return
        }

        inboundHeader = null
        inboundKey = null
        onRecord(
            BridgeRecord(
                topic = header.optString("topic"),
                partition = header.optInt("partition"),
                offset = header.optLong("offset"),
                key = key.toByteArray(),
                payload = bytes.toByteArray(),
            )
        )
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(NORMAL_CLOSURE, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        scheduleReconnect(webSocket)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.w(TAG, "bridge connection lost", t)
        scheduleReconnect(webSocket)
    }

    private fun scheduleReconnect(dead: WebSocket) {
        val delayMs = synchronized(lock) {
            if (shuttingDown || webSocket !== dead) return
            webSocket = null
            open = false
            retryDelayMs.also {
                retryDelayMs = (it * 2).coerceAtMost(BridgeConfig.MAX_RETRY_MS)
            }
        }
        scope.launch {
            delay(delayMs)
            connect()
        }
    }

    private companion object {
        const val TAG = "BridgeConnection"
        const val CONSUMER = "consumer"
        const val NORMAL_CLOSURE = 1000
    }
}
