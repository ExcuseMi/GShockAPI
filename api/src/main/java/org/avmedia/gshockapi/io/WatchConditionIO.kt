package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.utils.Utils
import timber.log.Timber
import kotlin.math.roundToInt

// ============================================================================
// Pure Functional Core: Watch Condition Decoding
// ============================================================================

/**
 * Pure functional core for watch condition processing.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles battery level and temperature extraction with calibration.
 */
@RequiresApi(Build.VERSION_CODES.O)
object WatchConditionIOFunctional {
    data class WatchConditionValue(val batteryLevel: Int, val temperature: Int)

    /**
     * Pure decoder: Extracts battery level and temperature from raw data.
     *
     * Protocol format:
     * [1] - Battery raw level (calibrated using watch-specific limits)
     * [2] - Temperature in Celsius
     *
     * Battery calibration: Maps raw value to 0-100% using watch-specific
     * lower and upper limits of its model (ModelInfo.batteryLevelLowerLimit/UpperLimit)
     *
     * No side effects - pure extraction.
     */
    fun decode(
        data: String,
        batteryLevelLowerLimit: Int,
        batteryLevelUpperLimit: Int,
    ): Result<WatchConditionValue> =
        runCatching {
            val intArr = Utils.toIntArray(data)
            val bytes = Utils.byteArrayOfIntArray(intArr.drop(1).toIntArray())

            if (bytes.size < 2) {
                throw IllegalArgumentException("Watch condition data too short: ${bytes.size} bytes (need 2)")
            }

            val batteryRawValue = bytes[0].toInt()
            Timber.i("battery level raw value: $batteryRawValue")

            // Calibrate battery level using the watch's own limits
            val range = batteryLevelUpperLimit - batteryLevelLowerLimit

            if (range <= 0) {
                throw IllegalArgumentException("Invalid battery limit range: $range")
            }

            // Exact scaling: the same as a rounded multiplier for the 10 and 100 step ranges, right for the H5600's 6 steps.
            val batteryLevel = (batteryRawValue - batteryLevelLowerLimit)
            val batteryLevelPercent: Int = (batteryLevel * 100.0 / range).roundToInt().coerceIn(0, 100)
            val temperature: Int = bytes[1].toInt()

            WatchConditionValue(batteryLevelPercent, temperature)
        }
}

// ============================================================================
// Imperative Shell: Side Effects & State Management
// ============================================================================

/**
 * Watch Condition IO handler with state management.
 *
 * Provides access to watch battery level and temperature information.
 * Uses pure functional core for data decoding and calibration.
 */
@RequiresApi(Build.VERSION_CODES.O)
object WatchConditionIO {
    class WatchConditionValue(val batteryLevel: Int, val temperature: Int)

    private val one: LinkWatchStatus get() = org.avmedia.gshockapi.casio.WatchLink.io.status

    suspend fun request(requestString: String = "28"): WatchConditionValue = one.requestCondition(requestString)

    fun onReceived(data: String) = one.onCondition(data)
}
