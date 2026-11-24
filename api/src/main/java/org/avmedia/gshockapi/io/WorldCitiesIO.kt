package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.utils.Utils

// ============================================================================
// Pure Functional Core: World Cities Data Processing
// ============================================================================

/**
 * Pure functional core for world cities processing.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles city name parsing and encoding transformations.
 */
@RequiresApi(Build.VERSION_CODES.O)
object WorldCitiesIOFunctional {
    /**
     * Pure parser: Extracts city name from timezone string.
     *
     * Converts timezone format "Asia/Tokyo" to city format "TOKYO"
     * No side effects - simple string transformation.
     *
     * @param timeZone Timezone string in format "Region/City"
     * @return City name in uppercase, or null if not parseable
     */
    fun parseCity(timeZone: String): String? {
        val city = timeZone.split('/').lastOrNull()
        return city?.uppercase()?.replace('_', ' ')
    }

    /**
     * Pure encoder: Encodes city name to padded hex format for watch protocol.
     *
     * Protocol format:
     * [0-1] = "1F" (command)
     * [2-3] = City index in hex (00-05)
     * [4+] = City name in hex, padded to 36 characters with zeros
     *
     * No side effects - pure transformation.
     *
     * @param city City name to encode (max 18 chars, will be truncated)
     * @param cityIndex Index of world city (0-5)
     * @return Formatted hex string for watch communication
     */
    fun encodeAndPad(
        city: String,
        cityIndex: Int,
    ): String {
        return (
            "1F" + "%02x".format(cityIndex) +
                Utils.toHexStr(city.take(18))
                    .padEnd(36, '0')
        )
    }
}

// ============================================================================
// Imperative Shell: Side Effects & State Management
// ============================================================================

/**
 * The single-watch world city read: the one link's [LinkTime]. New code uses `link.time`.
 */
@RequiresApi(Build.VERSION_CODES.O)
object WorldCitiesIO {
    suspend fun request(cityNumber: Int): String = org.avmedia.gshockapi.casio.WatchLink.time.requestWorldCities(cityNumber)

    fun onReceived(data: String) = org.avmedia.gshockapi.casio.WatchLink.time.onWorldCities(data)

    /**
     * Pure function for city name parsing.
     * Delegates to functional core.
     */
    fun parseCity(timeZone: String): String? = WorldCitiesIOFunctional.parseCity(timeZone)

    /**
     * Pure function for city encoding.
     * Delegates to functional core.
     */
    fun encodeAndPad(
        city: String,
        cityIndex: Int,
    ): String = WorldCitiesIOFunctional.encodeAndPad(city, cityIndex)
}
