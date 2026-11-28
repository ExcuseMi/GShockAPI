package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.utils.Utils

// ============================================================================
// Pure Functional Core: Home Time Data Processing
// ============================================================================

/**
 * Pure functional core for home time processing.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles home city data transformations.
 */
@RequiresApi(Build.VERSION_CODES.O)
object HomeTimeIOFunctional {
    /**
     * Pure parser: Extracts home city name from world cities data.
     *
     * Converts raw city data to ASCII string using the provided offset.
     * No side effects - pure string transformation.
     */
    fun parseHomeCity(
        data: String,
        offset: Int,
    ): String {
        if (data.isBlank()) return "N/A"
        val name = Utils.toAsciiString(data, offset)
        return if (name.isBlank() || name.all { it == 'ÿ' }) "N/A" else name
    }
}

// ============================================================================
// Imperative Shell: Side Effects & State Management
// ============================================================================

/**
 * The single-watch home time (register 0x24): the one link's [LinkTime]. New code uses `link.time`.
 */
@RequiresApi(Build.VERSION_CODES.O)
object HomeTimeIO {
    /** The home city of the one link's watch, read the way its own model's protocol reads it. */
    @Suppress("DEPRECATION") // The single-watch adapter of the per-link call the drivers' HOME_TIME key runs.
    suspend fun request(): String = org.avmedia.gshockapi.casio.WatchLink.api.getHomeTime()

    suspend fun requestRaw(slot: Int): String = org.avmedia.gshockapi.casio.WatchLink.time.requestHomeTime(slot)

    fun onReceived(data: String) = org.avmedia.gshockapi.casio.WatchLink.time.onHomeTime(data)
}
