package com.example.vnetgps

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class RunPhase { IDLE, RUNNING, PAUSED }

data class RunEvent(val runId: String, val elapsedMs: Long)

data class RunSnapshot(
    val runId: String? = null,
    val paused: Boolean = false,
    val bankedMs: Long = 0L,
    val segmentStartMs: Long = 0L,
) {
    val active: Boolean get() = runId != null

    val phase: RunPhase
        get() = when {
            !active -> RunPhase.IDLE
            paused -> RunPhase.PAUSED
            else -> RunPhase.RUNNING
        }

    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
        if (phase == RunPhase.RUNNING) bankedMs + (now - segmentStartMs).coerceAtLeast(0) else bankedMs
}

object RunTimer {

    private const val KEY_RUN_ID = "run_id"
    private const val KEY_BANKED_MS = "run_banked_ms"
    private const val KEY_SEGMENT_START_MS = "run_segment_start_ms"

    private val _state = MutableStateFlow(RunSnapshot())

    val state: StateFlow<RunSnapshot> = _state.asStateFlow()

    fun format(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    fun start(context: Context) = send(context, RunTimerService.ACTION_START)

    fun togglePause(context: Context) = send(context, RunTimerService.ACTION_TOGGLE_PAUSE)

    fun stop(context: Context) = send(context, RunTimerService.ACTION_STOP)

    fun restore(context: Context) {
        reload(context)
        if (_state.value.active) send(context, null)
    }

    private fun send(context: Context, action: String?) {
        context.startForegroundService(
            Intent(context, RunTimerService::class.java).setAction(action)
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("vnet", Context.MODE_PRIVATE)

    internal fun reload(context: Context) {
        val prefs = prefs(context)
        val runId = prefs.getString(KEY_RUN_ID, null)
        _state.value = RunSnapshot(
            runId = runId,
            paused = prefs.getBoolean(PauseControl.KEY_RUN_TIMER, false),
            bankedMs = prefs.getLong(KEY_BANKED_MS, 0L),
            segmentStartMs = prefs.getLong(KEY_SEGMENT_START_MS, 0L),
        )
    }

    internal fun begin(context: Context): RunEvent? {
        if (_state.value.active) return null
        PauseControl.clear(context, PauseControl.KEY_RUN_TIMER)
        return commit(
            context,
            RunSnapshot(
                runId = UUID.randomUUID().toString(),
                segmentStartMs = SystemClock.elapsedRealtime(),
            )
        )
    }

    internal fun applyPause(context: Context, paused: Boolean): RunEvent? {
        val current = _state.value
        if (!current.active || current.paused == paused) return null
        val now = SystemClock.elapsedRealtime()
        return commit(
            context,
            if (paused) current.copy(paused = true, bankedMs = current.elapsedMs(now))
            else current.copy(paused = false, segmentStartMs = now),
        )
    }

    internal fun end(context: Context): RunEvent? {
        val current = _state.value
        val runId = current.runId ?: return null
        val finished = RunEvent(runId, current.elapsedMs())

        prefs(context).edit()
            .remove(KEY_RUN_ID)
            .remove(KEY_BANKED_MS)
            .remove(KEY_SEGMENT_START_MS)
            .apply()
        PauseControl.clear(context, PauseControl.KEY_RUN_TIMER)
        _state.value = RunSnapshot()

        return finished
    }

    private fun commit(context: Context, snapshot: RunSnapshot): RunEvent {
        prefs(context).edit()
            .putString(KEY_RUN_ID, snapshot.runId)
            .putLong(KEY_BANKED_MS, snapshot.bankedMs)
            .putLong(KEY_SEGMENT_START_MS, snapshot.segmentStartMs)
            .apply()
        _state.value = snapshot
        return RunEvent(snapshot.runId!!, snapshot.elapsedMs())
    }
}
