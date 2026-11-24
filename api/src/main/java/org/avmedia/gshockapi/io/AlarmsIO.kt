package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.model.Alarm
import org.avmedia.gshockapi.model.Alarms
import org.avmedia.gshockapi.utils.Utils
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// ============================================================================
// Pure Functional Core: Command Pattern & Data Structures
// ============================================================================

/**
 * Sealed hierarchy for BLE operations as data structures.
 * These represent the 'intent' of operations without executing them.
 */
sealed class BLEAction {
    data class Write(
        val mode: GetSetMode,
        val data: ByteArray,
    ) : BLEAction() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Write) return false
            return mode == other.mode && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return 31 * mode.hashCode() + data.contentHashCode()
        }
    }
}

/**
 * Pure functional core for alarm processing logic.
 *
 * All methods are pure: no mutable state, no side effects.
 * Input -> Deterministic Output transformation.
 */
@RequiresApi(Build.VERSION_CODES.O)
object AlarmsIOFunctional {
    /**
     * Pure parser: Decodes hex string to alarm list.
     *
     * No side effects - returns parsed alarms for the IO shell to handle.
     */
    fun parseReceivedAlarms(data: String, info: WatchInfo.ModelInfo): List<Alarm> =
        runCatching {
            val decoded = AlarmDecoder.toJson(data, info).get("ALARMS")
            val gson = Gson()
            val alarmArr = gson.fromJson(decoded.toString(), Array<Alarm>::class.java)
            alarmArr.toList()
        }.getOrElse { error ->
            Timber.e("Failed to parse received alarms: ${error.message}")
            emptyList()
        }

    /**
     * Pure command builder: Creates the sequence of commands to fetch alarms.
     *
     * Returns data structures (commands) instead of executing them.
     */
    fun buildFetchCommands(alarmCount: Int): List<BLEAction> {
        if (alarmCount == 1) {
            return listOf(
                BLEAction.Write(
                    GetSetMode.GET,
                    Utils.byteArray(CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_ALM.code.toByte()),
                ),
            )
        }
        return listOf(
            // Get alarm 1 (must complete before ALM2)
            BLEAction.Write(
                GetSetMode.GET,
                Utils.byteArray(CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_ALM.code.toByte()),
            ),
            // Get the rest of the alarms (only after ALM1 response received)
            BLEAction.Write(
                GetSetMode.GET,
                Utils.byteArray(CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_ALM2.code.toByte()),
            ),
        )
    }

    /**
     * Pure command builder: Creates the sequence of commands to set alarms.
     *
     * Parses JSON and builds write commands without side effects.
     */
    fun buildSetCommand(
        message: String,
        alarmCount: Int,
    ): Result<List<BLEAction>> =
        runCatching {
            JSONObject(message).get("value").let { it as JSONArray }.let { jsonArray ->
                if (alarmCount == 1) {
                    val firstAlarm = AlarmsIO.fromJsonAlarmFirstAlarm(jsonArray.getJSONObject(0))
                    listOf(
                        BLEAction.Write(GetSetMode.SET, firstAlarm),
                    )
                } else {
                    val firstAlarm = AlarmsIO.fromJsonAlarmFirstAlarm(jsonArray.getJSONObject(0))
                    val secondaryAlarms = AlarmsIO.fromJsonAlarmSecondaryAlarms(jsonArray)
                    listOf(
                        BLEAction.Write(GetSetMode.SET, firstAlarm),
                        BLEAction.Write(GetSetMode.SET, secondaryAlarms),
                    )
                }
            }
        }

    /**
     * Pure validation: Checks if all alarms have been received.
     */
    fun isAlarmCountComplete(
        receivedCount: Int,
        expectedCount: Int,
    ): Boolean = receivedCount >= expectedCount
}

// ============================================================================
// Imperative Shell: Side Effects & State Management
// ============================================================================

@RequiresApi(Build.VERSION_CODES.O)
object AlarmsIO {
    private val one: LinkAlarms get() = org.avmedia.gshockapi.casio.WatchLink.io.alarms

    fun fromJsonAlarmFirstAlarm(alarmJson: JSONObject): ByteArray {
        val gson = Gson()
        val alarm: Alarm = gson.fromJson(alarmJson.toString(), Alarm::class.java)
        return createFirstAlarm(alarm)
    }

    fun fromJsonAlarmSecondaryAlarms(alarmsJson: JSONArray): ByteArray {
        if (alarmsJson.length() < 2) {
            return ByteArray(0)
        }
        val gson = Gson()
        val allAlarms: Array<Alarm> = gson.fromJson(alarmsJson.toString(), Array<Alarm>::class.java)
        val alarms = allAlarms.toList().subList(1, allAlarms.size)
        return createSecondaryAlarm(alarms)
    }

    private fun createFirstAlarm(alarm: Alarm): ByteArray {
        var flag = 0
        if (alarm.enabled) {
            flag = flag or Alarms.ENABLED_MASK
        }
        if (alarm.hasHourlyChime) flag = flag or Alarms.HOURLY_CHIME_MASK

        return Utils.byteArrayOfInts(
            CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_ALM.code,
            flag,
            Alarms.ALARM_CONSTANT_VALUE,
            alarm.hour,
            alarm.minute,
        )
    }

    private fun createSecondaryAlarm(alarms: List<Alarm>): ByteArray {
        var allAlarms =
            Utils.byteArrayOfInts(CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_ALM2.code)

        for (alarm in alarms) {
            var flag = 0
            if (alarm.enabled) {
                flag = flag or Alarms.ENABLED_MASK
                if (alarm.oneTime) {
                    flag = flag or Alarms.ONE_TIME_MASK
                }
            }
            if (alarm.hasHourlyChime) flag = flag or Alarms.HOURLY_CHIME_MASK

            allAlarms +=
                Utils.byteArrayOfInts(
                    flag,
                    Alarms.ALARM_CONSTANT_VALUE,
                    alarm.hour,
                    alarm.minute,
                )
        }

        return allAlarms
    }

    suspend fun request(): ArrayList<Alarm> = one.request()

    fun set(alarms: ArrayList<Alarm>) = one.set(alarms)

    fun onReceived(data: String) = one.onReceived(data)

    @Suppress("UNUSED_PARAMETER")
    fun sendToWatch(message: String) = one.sendToWatch()

    fun sendToWatchSet(message: String) = one.sendToWatchSet(message)
}

// ============================================================================
// Pure Decoder: Alarm Protocol Decoding
// ============================================================================

/**
 * Pure decoder for alarm protocol data.
 * No mutable state or side effects - pure transformations only.
 */
@RequiresApi(Build.VERSION_CODES.O)
object AlarmDecoder {
    private const val HOURLY_CHIME_MASK = 0b10000000

    /**
     * Converts a command string to a JSON object containing alarm data.
     *
     * Command buffer structure:
     * [0] - Command type:
     *     - 0x2A: Single alarm (CASIO_SETTING_FOR_ALM)
     *     - 0x2B: Multiple alarms (CASIO_SETTING_FOR_ALM2)
     *
     * For single alarm (0x2A):
     * [1..4] - One 4-byte alarm entry
     *
     * For multiple alarms (0x2B):
     * [1..16] - Four 4-byte alarm entries
     *
     * Each alarm entry is 4 bytes:
     * [0] - Flags byte:
     *     - bit 7 (0x80): Hourly chime enabled
     *     - bit 0 (0x01): Alarm enabled
     * [1] - Constant value (0x40)
     * [2] - Hour (0-23)
     * [3] - Minute (0-59)
     *
     * @param command The raw command string containing alarm data
     * @return JSONObject with format: {"ALARMS": [array of alarm objects]}
     */
    fun toJson(command: String, info: WatchInfo.ModelInfo): JSONObject =
        runCatching {
            Utils.toIntArray(command).let { intArray ->
                JSONArray().apply {
                    when (intArray.firstOrNull()) {
                        CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_ALM.code ->
                            ArrayList(
                                intArray.drop(1),
                            ).let { createJsonAlarm(it, info) }.let(::put)

                        CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_ALM2.code ->
                            intArray.drop(
                                1,
                            ).chunked(4).map { ArrayList(it) }.forEach { put(createJsonAlarm(it, info)) }

                        else -> Timber.d("Unhandled Command [$command]")
                    }
                }
            }.let { alarms -> JSONObject().put("ALARMS", alarms) }
        }.getOrElse { error ->
            Timber.e("Failed to parse command: ${error.message}")
            JSONObject()
        }

    /**
     * Pure parser: Creates a JSON object from a 4-byte alarm data buffer.
     *
     * Buffer structure:
     * [0] - Flags byte:
     *     - bit 7 (0x80): Hourly chime enabled
     *     - bit 0 (0x01): Alarm enabled
     * [1] - Constant value (typically 0x40)
     * [2] - Hour (0-23)
     * [3] - Minute (0-59)
     *
     * @param intArray ArrayList containing 4 bytes of alarm data
     * @return JSONObject containing the parsed alarm data
     */
    private fun createJsonAlarm(intArray: ArrayList<Int>, info: WatchInfo.ModelInfo): JSONObject =
        runCatching {
            Alarm(
                hour = intArray[2],
                minute = intArray[3],
                enabled = intArray[0] and Alarms.ENABLED_MASK != 0,
                hasHourlyChime = if (info.hasHourlyChime) (intArray[0] and HOURLY_CHIME_MASK != 0) else false,
                oneTime = intArray[0] and Alarms.ONE_TIME_MASK != 0,
            ).let { alarm ->
                JSONObject(Gson().toJson(alarm))
            }
        }.getOrElse { error ->
            Timber.e("Failed to create alarm: ${error.message}")
            JSONObject()
        }
}
