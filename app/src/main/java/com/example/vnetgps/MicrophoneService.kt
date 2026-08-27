package com.example.vnetgps

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

// Microphone service captures audio using opus codec and streams to nats
class MicrophoneService : Service() {

    private val channelId = "microphone_service_channel"
    private val notificationId = 3

    private val sampleRate = 48_000
    private val channelCount = 1
    private val bitRate = 32_000
    private val batchMs = 1_000L

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    private lateinit var publisher: NatsPublisher

    private val pause = PauseControl(
        service = this,
        channelId = channelId,
        notificationId = notificationId,
        prefsKey = PauseControl.KEY_MICROPHONE,
        runningText = "Logging microphone audio...",
        pausedText = "Microphone paused",
        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Microphone Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        publisher = NatsPublisher(this)
        publisher.connect()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pause.consumeToggle(intent)

        if (!pause.enterForeground()) {
            TelemetryServices.stopAll(this)
            return START_NOT_STICKY
        }
        val missing = AppPermissions.missingRuntime(this)
        if (missing.isNotEmpty()) {
            Log.w(TAG, "permissions revoked; stopping: $missing")
            TelemetryServices.stopAll(this)
            return START_NOT_STICKY
        }
        if (!opusEncoderAvailable()) {
            Log.w(TAG, "no Opus encoder on this device; stopping")
            TelemetryServices.stopAll(this)
            return START_NOT_STICKY
        }

        if (pause.paused) {
            captureJob?.cancel()
            captureJob = null
        } else if (captureJob?.isActive != true) {
            captureJob = startCapture()
        }

        return START_STICKY
    }

    private fun opusEncoderAvailable(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
            }
        }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startCapture(): Job = serviceScope.launch {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "${sampleRate}Hz mono capture unsupported; stopping")
            TelemetryServices.stopAll(this@MicrophoneService)
            return@launch
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 4,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord did not initialise; stopping")
            record.release()
            TelemetryServices.stopAll(this@MicrophoneService)
            return@launch
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channelCount
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        try {
            codec.start()
            record.startRecording()
            pump(record, codec)
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun CoroutineScope.pump(record: AudioRecord, codec: MediaCodec) {
        val pcm = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        val packets = ArrayList<ByteArray>()
        var batchStart = System.currentTimeMillis()
        var presentationUs = 0L

        while (isActive) {
            val read = record.read(pcm, 0, pcm.size)
            if (read > 0) {
                val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    codec.getInputBuffer(index)?.apply { clear(); put(pcm, 0, read) }
                    codec.queueInputBuffer(index, 0, read, presentationUs, 0)
                    // 16-bit mono, so two bytes per frame.
                    presentationUs += read.toLong() * 1_000_000L / (sampleRate * 2L)
                }
            }

            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!isConfig && info.size > 0) {
                    codec.getOutputBuffer(out)?.let { buffer ->
                        buffer.position(info.offset)
                        packets.add(ByteArray(info.size).also { buffer.get(it) })
                    }
                }
                codec.releaseOutputBuffer(out, false)
            }

            val now = System.currentTimeMillis()
            if (packets.isNotEmpty() && now - batchStart >= batchMs) {
                publisher.publish(encodeBatch(packets, batchStart))
                Log.d(TAG, "published ${packets.size} Opus packets")
                packets.clear()
                batchStart = now
            }
        }
    }

    private fun encodeBatch(packets: List<ByteArray>, startMillis: Long): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + packets.sumOf { 2 + it.size })
        buffer.put(MAGIC)
        buffer.putInt(sampleRate)
        buffer.put(channelCount.toByte())
        buffer.putLong(startMillis)
        buffer.putShort(packets.size.toShort())
        for (packet in packets) {
            buffer.putShort(packet.size.toShort())
            buffer.put(packet)
        }
        return buffer.array()
    }

    override fun onDestroy() {
        super.onDestroy()
        publisher.close()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "LiveMicrophone"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val HEADER_BYTES = 19
        val MAGIC = "VOP1".toByteArray(Charsets.US_ASCII)
    }
}
