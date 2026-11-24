package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.utils.Utils

// ============================================================================
// Pure Functional Core: Watch Name Extraction
// ============================================================================

/**
 * Pure functional core for watch name processing.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles watch name parsing and sanitization.
 */
@RequiresApi(Build.VERSION_CODES.O)
object WatchNameIOFunctional {
    /**
     * Pure parser: Extracts and sanitizes watch name from data.
     *
     * Converts raw data (starting at index 1) to ASCII string,
     * then removes non-ASCII characters.
     * No side effects - pure string transformation.
     *
     * @param data Raw data string from watch
     * @return Sanitized watch name
     */
    fun parseWatchName(data: String): Result<String> =
        runCatching {
            if (data.isEmpty()) {
                throw IllegalArgumentException("Empty watch name data")
            }
            val asciiName = Utils.toAsciiString(data, 1)
            Utils.trimNonAsciiCharacters(asciiName)
        }
}

// ============================================================================
// Imperative Shell: Side Effects & State Management
// ============================================================================

/**
 * Watch Name IO handler with state management.
 *
 * Retrieves the user-assigned name of the watch.
 * Uses pure functional core for data parsing and sanitization.
 */
@RequiresApi(Build.VERSION_CODES.O)
object WatchNameIO {
    private val one: LinkWatchStatus get() = org.avmedia.gshockapi.casio.WatchLink.io.status

    suspend fun request(): String = one.requestName()

    fun onReceived(data: String) = one.onName(data)
}
