package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.casio.CasioTimeZoneHelper
import org.avmedia.gshockapi.utils.Utils

// ============================================================================
// Pure Functional Core: DST for World Cities Operations
// ============================================================================

/**
 * Pure functional core for DST world cities processing.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles DST value updates for world city timezones.
 */
@RequiresApi(Build.VERSION_CODES.O)
object DstForWorldCitiesIOFunctional {
    /**
     * Pure transformer: Updates timezone data for world city.
     *
     * Takes DST string and CasioTimeZone, updates timezone offset and DST offset values.
     * No side effects - pure data transformation.
     *
     * Protocol format (7 bytes):
     * 0x1e 0-5 TZ_A TZ_B TZ_OFF TZ_DSTOFF TZ_DSTRULES
     * Updates indices [4] (offset), [5] (DST offset), [6] (DST rules).
     */
    fun setDST(
        dst: String,
        casioTimeZone: CasioTimeZoneHelper.CasioTimeZone,
    ): String =
        Utils.toIntArray(dst)
            .takeIf { it.size == 7 }
            ?.apply {
                this[4] = casioTimeZone.offset
                this[5] = casioTimeZone.dstOffset.toInt()
                this[6] = casioTimeZone.dstRules
            }
            ?.let { intArray ->
                Utils.byteArrayOfIntArray(intArray.toIntArray())
                    .let(Utils::fromByteArrayToHexStrWithSpaces)
            }
            ?: dst
}

@RequiresApi(Build.VERSION_CODES.O)
/** The single-watch read: the one link's [LinkTime]. New code uses `link.time`. */
object DstForWorldCitiesIO {
    suspend fun request(cityNumber: Int): String = org.avmedia.gshockapi.casio.WatchLink.time.requestDstForWorldCities(cityNumber)

    /*
    0x1e 0-5 TZ_A TZ_B TZ_OFF TZ_DSTOFF TZ_DSTRULES
    A/B seem to be ignored by the watch
    OFF & DSTOFF in 15 minute intervals

    Timezones selectable on the watch:
                       A  B   OFF DSTOFF DSTRULES
    BAKER ISLAND       39 01  D0  04     00
    PAGO PAGO          D7 00  D4  04     00
    HONOLULU           7B 00  D8  04     00
    ...
     */

    fun setDST(
        dst: String,
        casioTimeZone: CasioTimeZoneHelper.CasioTimeZone,
    ): String = DstForWorldCitiesIOFunctional.setDST(dst, casioTimeZone)

    fun onReceived(data: String) = org.avmedia.gshockapi.casio.WatchLink.time.onDstForWorldCities(data)
}
