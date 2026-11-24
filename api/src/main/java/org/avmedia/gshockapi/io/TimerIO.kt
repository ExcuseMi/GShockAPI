package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.utils.Utils
import org.json.JSONObject

// ============================================================================
// Pure Functional Core: Timer Processing Logic
// ============================================================================

/**
 * Pure functional core for timer processing.
 *
 * All methods are pure: no mutable state, no side effects.
 * Input -> Deterministic Output transformation.
 */
@RequiresApi(Build.VERSION_CODES.O)
object TimerIOFunctional {
    data class TimerState(
        val hours: Int,
        val minutes: Int,
        val seconds: Int,
        val totalSeconds: Int,
    )

    /**
     * Pure function: Convert total seconds to timer state components.
     *
     * No side effects - simple arithmetic transformation.
     *
     * @param totalSeconds Total seconds (0 to 23:59:59 = 86399)
     * @return TimerState with hours, minutes, seconds, and total
     */
    fun secondsToTimerState(totalSeconds: Int): TimerState {
        val hours = totalSeconds / 3600
        val minutesAndSeconds = totalSeconds % 3600
        val minutes = minutesAndSeconds / 60
        val seconds = minutesAndSeconds % 60
        return TimerState(hours, minutes, seconds, totalSeconds)
    }

    /**
     * Pure function: Convert timer components to total seconds.
     */
    fun componentsToSeconds(
        hours: Int,
        minutes: Int,
        seconds: Int,
    ): Int = hours * 3600 + minutes * 60 + seconds

    /**
     * Pure decoder: Decodes hex string to timer state.
     *
     * No side effects - returns Result for error handling.
     * Buffer structure:
     * [0] - Command code (0x18)
     * [1] - Hours
     * [2] - Minutes
     * [3] - Seconds
     */
    fun decode(data: String): Result<TimerState> =
        runCatching {
            val timerIntArray = Utils.toIntArray(data)
            if (timerIntArray.size < 4) {
                throw IllegalArgumentException("Timer data too short: ${timerIntArray.size} bytes")
            }
            val totalSeconds =
                componentsToSeconds(
                    timerIntArray[1],
                    timerIntArray[2],
                    timerIntArray[3],
                )
            secondsToTimerState(totalSeconds)
        }

    /**
     * Pure encoder: Encodes timer state to byte array.
     *
     * No side effects - pure transformation.
     * Returns byte array of specified size:
     * [0] = 0x18 (command)
     * [1] = hours
     * [2] = minutes
     * [3] = seconds
     * [4..] = padding
     */
    fun encode(
        timerState: TimerState,
        size: Int,
    ): ByteArray {
        return ByteArray(size).apply {
            this[0] = 0x18
            this[1] = timerState.hours.toByte()
            this[2] = timerState.minutes.toByte()
            this[3] = timerState.seconds.toByte()
        }
    }

    /**
     * Pure command builder: Creates command to fetch timer from watch.
     */
    fun buildFetchCommand(): ByteArray = Utils.byteArray(CasioConstants.CHARACTERISTICS.CASIO_TIMER.code.toByte())

    /**
     * Pure command builder: Creates command to set timer from JSON message.
     *
     * Parses and validates input without side effects.
     */
    fun buildSetCommand(
        message: String,
        size: Int,
    ): Result<ByteArray> =
        runCatching {
            JSONObject(message).get("value").toString().toInt()
                .let { secondsToTimerState(it) }
                .let { encode(it, size) }
        }
}

// ============================================================================
// Imperative Shell: Side Effects & State Management
// ============================================================================

/**
 * Timer IO handler with state management.
 *
 * Manages the asynchronous request/response cycle for timer data.
 * Uses pure functional core for all transformations.
 */
@RequiresApi(Build.VERSION_CODES.O)
object TimerIO {
    private val one: LinkTimer get() = org.avmedia.gshockapi.casio.WatchLink.io.timer

    suspend fun request(requestString: String = "18"): Int = one.request(requestString)

    fun set(timerValue: Int) = one.set(timerValue)

    fun onReceived(data: String) = one.onReceived(data)

    @Suppress("UNUSED_PARAMETER")
    fun sendToWatch(message: String) = one.sendToWatch()

    fun sendToWatchSet(message: String) = one.sendToWatchSet(message)
}
