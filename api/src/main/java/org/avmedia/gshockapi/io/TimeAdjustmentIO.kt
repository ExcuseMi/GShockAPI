package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.model.Settings
import org.avmedia.gshockapi.utils.Utils
import org.json.JSONObject

@androidx.annotation.Keep
class TimeAdjustmentInfo(
    var isTimeAdjustmentSet: Boolean = false,
    var adjustmentTimeMinutes: Int = 0,
)

// ============================================================================
// Pure Functional Core: Time Adjustment Data Processing
// ============================================================================

/**
 * Pure functional core for time adjustment processing.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles parsing and encoding of time adjustment settings.
 */
@RequiresApi(Build.VERSION_CODES.O)
object TimeAdjustmentIOFunctional {
    /**
     * Pure parser: Checks if time adjustment is enabled.
     *
     * Protocol format:
     * [12] - bit 7: time adjustment flag:
     *     0 = syncing on  (time adjustment enabled), e.g. 0x00
     *     1 = syncing off (time adjustment disabled), e.g. 0x80
     */
    fun parseIsTimeAdjustmentSet(data: String): Boolean = Utils.toIntArray(data)[12] and SYNC_OFF == 0

    /**
     * Pure parser: Extracts the time adjustment minutes.
     *
     * Protocol format:
     * [13] - Adjustment time in minutes (0-59)
     * Returns 30 if value is out of valid range.
     */
    fun parseAdjustmentTimeMinutes(data: String): Int =
        Utils.toIntArray(data)[13].let { minutesRead ->
            if (minutesRead in 0..59) minutesRead else 30
        }

    /**
     * Pure parser: Parses complete time adjustment info.
     *
     * No side effects - pure extraction.
     */
    fun decode(data: String): Result<TimeAdjustmentInfo> =
        runCatching {
            TimeAdjustmentInfo(
                isTimeAdjustmentSet = parseIsTimeAdjustmentSet(data),
                adjustmentTimeMinutes = parseAdjustmentTimeMinutes(data),
            )
        }

    /**
     * Pure encoder: Encodes time adjustment settings to byte array.
     *
     * Takes original data and applies new settings without side effects.
     * Protocol format:
     * [12] - bit 7: time adjustment flag (0 = on, 1 = off). Some watches keep other
     *        data in bits 0-6 (e.g. the hour of the first automatic sync), so only
     *        bit 7 is changed.
     * [13] - Adjustment time minutes
     */
    fun encode(
        originalData: String,
        settings: JSONObject,
    ): Result<ByteArray> =
        runCatching {
            encode(
                originalData,
                timeAdjustment = settings.get("timeAdjustment") == true,
                adjustmentTimeMinutes = settings.getInt("adjustmentTimeMinutes"),
            ).getOrThrow()
        }

    fun encode(
        originalData: String,
        timeAdjustment: Boolean,
        adjustmentTimeMinutes: Int,
    ): Result<ByteArray> =
        runCatching {
            if (originalData.isEmpty()) {
                throw IllegalArgumentException("Original data is empty")
            }

            val intArr =
                Utils.toIntArray(originalData).apply {
                    // syncing off: 110f0f0f0600500004000100->80<-37d2
                    // syncing on:  110f0f0f0600500004000100->00<-37d2
                    this[12] = (this[12] and SYNC_OFF.inv()) or (if (timeAdjustment) 0 else SYNC_OFF)
                    this[13] = adjustmentTimeMinutes
                }
            val size = intArr.size
            intArr.foldIndexed(ByteArray(size)) { i, array, value ->
                array.apply { set(i, value.toByte()) }
            }
        }

    private const val SYNC_OFF = 0x80

    /**
     * Pure command builder: Creates fetch command for time adjustment.
     */
    fun buildFetchCommand(): ByteArray = Utils.byteArray(CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_BLE.code.toByte())
}

// ============================================================================
// Imperative Shell: Side Effects & State Management
// ============================================================================

/**
 * Time Adjustment IO handler with state management.
 *
 * Manages time adjustment/synchronization settings.
 * Stores original data to preserve unchanged fields when encoding.
 * Uses pure functional core for all parsing and encoding operations.
 */
@RequiresApi(Build.VERSION_CODES.O)
object TimeAdjustmentIO {
    private val one: LinkTimeAdjustment get() = org.avmedia.gshockapi.casio.WatchLink.io.timeAdjustment

    /** The `11` frame the one link's watch last sent; a write starts from it. */
    object CasioIsAutoTimeOriginalValue {
        var value: String
            get() = org.avmedia.gshockapi.casio.WatchLink.io.timeAdjustment.original
            set(v) { org.avmedia.gshockapi.casio.WatchLink.io.timeAdjustment.original = v }
    }

    suspend fun request(): TimeAdjustmentInfo = one.request()

    fun set(settings: Settings) = one.set(settings)

    fun onReceived(data: String) = one.onReceived(data)

    fun onRunError() = one.onRunError()

    @Suppress("UNUSED_PARAMETER")
    fun sendToWatch(message: String) = one.sendToWatch()

    fun sendToWatchSet(message: String) = one.sendToWatchSet(message)
}
