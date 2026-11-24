package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.CasioTimeZoneHelper
import org.avmedia.gshockapi.utils.Utils
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime

/*
When using the API, the app doesn't need to keep a list of world cities to set the time on the watch. It can simply provide the current timezone
and the API will set the timezone and any applicable Daylight Savings Time rules on the watch. This way, when a user travels from one city to another
and adjusts the time accordingly, the timezone and DST rules will be set accordingly.

In order to set timezone, we must provide data for register 0x1E, something like this:

0x1E 00 02 76 10 00 00

From the Gadgetbridge project, we have information on what these values mean:

                   0x1E 00  A  B   OFF DSTOFF DSTRULES
LOS ANGELES                 A1 00  E0  04     01
DENVER                      54 00  E4  04     01
CHICAGO                     42 00  E8  04     01
NEW YORK                    CA 00  EC  04     01
...
The CasioTimeZone class handles the code for setting the values of time-zone, offset, and DST offset on the watch.
It takes a time-zone string in the form of "Pacific/Pago_Pago" and a Casio-specific DST rules code, and
calculates the corresponding offset and DST offset. A table is then created of all possible CasioTimeZone's
that can be set on the watch.

The findTimeZone function will take a standard timezone obtained from the Android Locale and try to find the
Casio-equivalent from the table. If not found, we create a synthetic CasioTimeZone from the Offset and DST Offset
of the passed timezone, and a value of 0 for the Casio Rules. Most of the timezones not in the Casio table have no DST,
so this is a reasonable assumption.

Since are are using ZoneId API to obtain the TZ offset and DST offset, we don't have to worry about changes in the timezones,
since presumably the API will change accordingly.

For setting DST value ON/OFF/AUTO, we update the 0x1D register. The data looks something like this:
0x1D 00 01 00 03 0C 01 0C 01 FF FF FF FF FF FF

From Gadgetbridge, we see:
There are six clocks on the Casio GW-B5600
0 is the main clock
1-5 are the world clocks

0x1d 00 01 DST0 DST1 TZ0A TZ0B TZ1A TZ1B ff ff ff ff ff
0x1d 02 03 DST2 DST3 TZ2A TZ2B TZ3A TZ3B ff ff ff ff ff
0x1d 04 05 DST4 DST5 TZ4A TZ4B TZ5A TZ5B ff ff ff ff ff
DST: bitwise flags; bit0: DST on, bit1: DST auto
Here again, are are only concerned with the main clock, so we need to update the value of DST0.
If the timezone has DST, we set this flag to ON | AUTO, or 3
If the timezone has no DST, we set the flag to 0
 */

// ============================================================================
// Pure Functional Core: Time Operations & DST Management
// ============================================================================

/**
 * Pure functional core for time and DST operations.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles DST calculations, timezone conversions, and time encoding.
 */
@RequiresApi(Build.VERSION_CODES.O)
object TimeIOFunctional {
    enum class DtsMask(val mask: Int) {
        OFF(0b00),
        ON(0b01),
        AUTO(0b10),
    }

    /**
     * Pure calculator: Computes DST value based on timezone information.
     *
     * Returns an OR combination of DST flags:
     * - ON flag if timezone is currently in DST
     * - AUTO flag if timezone has DST rules
     *
     * No side effects - pure calculation from timezone data.
     */
    fun calculateDSTValue(casioTimezone: CasioTimeZoneHelper.CasioTimeZone): Int =
        (if (casioTimezone.isInDST()) DtsMask.ON.ordinal else DtsMask.OFF.ordinal) or
            (if (casioTimezone.hasRules()) DtsMask.AUTO.ordinal else 0)

    /**
     * Pure calculator: Computes DST duration in milliseconds for timezone.
     *
     * Returns the DST offset converted to milliseconds, or 0 if not in DST.
     * Formula: dstOffset (in 15-minute units) * 60 * 15 (milliseconds conversion)
     *
     * No side effects - pure calculation.
     */
    fun calculateDSTDurationMs(casioTimezone: CasioTimeZoneHelper.CasioTimeZone): Long =
        if (casioTimezone.isInDST()) casioTimezone.dstOffset * 60 * 15L else 0L

    /**
     * Pure transformer: Adjusts time for DST and converts to LocalDateTime.
     *
     * Takes milliseconds since epoch and adjusts for DST if applicable,
     * then converts to LocalDateTime in the given timezone.
     *
     * No side effects - pure transformation.
     */
    fun adjustTimeForDSTAndConvert(
        timeMs: Long,
        casioTimezone: CasioTimeZoneHelper.CasioTimeZone,
    ): LocalDateTime {
        val dstDurationToAdd = calculateDSTDurationMs(casioTimezone)
        val msAdjustedForDST = timeMs + dstDurationToAdd
        val instant = Instant.ofEpochMilli(msAdjustedForDST)
        return LocalDateTime.ofInstant(instant, casioTimezone.zoneId)
    }

    /**
     * Pure encoder: Converts LocalDateTime to Casio time byte format.
     *
     * Returns 10-byte array matching Casio CASIO_CURRENT_TIME characteristic:
     * [0-1] Year (little-endian), [2] Month, [3] Day, [4] Hour, [5] Minute,
     * [6] Second, [7] Day of week, [8] Nano-second fraction, [9] Reserved
     *
     * No side effects - pure encoding.
     */
    fun prepareCurrentTime(date: LocalDateTime): ByteArray {
        val arr = ByteArray(10)
        val year = date.year
        val weekdayZeroBased = (date.dayOfWeek.value + 6) % 7 // Monday=0..Sunday=6, matches Python datetime.weekday()

        arr[0] = (year ushr 0 and 0xff).toByte()
        arr[1] = (year ushr 8 and 0xff).toByte()
        arr[2] = date.month.value.toByte()
        arr[3] = date.dayOfMonth.toByte()
        arr[4] = date.hour.toByte()
        arr[5] = date.minute.toByte()
        arr[6] = date.second.toByte()
        arr[7] = weekdayZeroBased.toByte()
        arr[8] = ((date.nano.toLong() * 256) / 1_000_000_000).toByte()
        arr[9] = 1 // or 0?
        return arr
    }

    /**
     * Pure builder: Creates watch time command from adjusted datetime.
     *
     * Combines Casio time characteristic code with encoded time data.
     * No side effects - pure command building.
     */
    fun buildTimeCommand(adjustedDateTime: LocalDateTime): ByteArray {
        val timeData = prepareCurrentTime(adjustedDateTime)
        return Utils.byteArrayOfInts(CasioConstants.CHARACTERISTICS.CASIO_CURRENT_TIME.code) + timeData
    }
}

/** The single-watch time: the [LinkTime] of the one link. New code uses `link.time`. */
@RequiresApi(Build.VERSION_CODES.O)
object TimeIO {
    private val time: LinkTime get() = org.avmedia.gshockapi.casio.WatchLink.time

    fun setTimezone(timeZone: String) = time.setTimezone(timeZone)

    /**
     * Exposes the currently-configured CasioTimeZone so watch-specific
     * protocol code (e.g. GwBx5600TimeIO) that needs more than just the
     * home-clock timestamp -- coordinates, DST rules -- can read it
     * without threading a wider parameter list through setTime().
     */
    fun getCasioTimezone(): CasioTimeZoneHelper.CasioTimeZone = time.casioTimezone

    suspend fun set(
        timeMs: Long? = null,
        offset: Long? = null,
    ) = time.set(timeMs, offset)

    suspend fun writeHomeTimes() = time.writeHomeTimes()

    suspend fun writeDST() = time.writeDST()

    suspend fun writeDSTForWorldCities() = time.writeDSTForWorldCities()

    suspend fun writeWorldCities() = time.writeWorldCities()

    fun sendToWatchSet(message: String) {
        time.writeTime(JSONObject(message).get("value") as Long)
    }
}
