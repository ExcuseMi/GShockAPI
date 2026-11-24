package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi

// ============================================================================
// Pure Functional Core: Error State Management
// ============================================================================

/**
 * Pure functional core for error state operations.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles error message validation and retrieval.
 */
object ErrorIOFunctional {
    /**
     * Pure validator: Returns the error message or default.
     *
     * No side effects - simple data accessor.
     */
    fun getErrorOrDefault(error: String): String = error.ifEmpty { "ERROR" }
}

@RequiresApi(Build.VERSION_CODES.O)
object ErrorIO {
    private val one: LinkIO get() = org.avmedia.gshockapi.casio.WatchLink.io

    suspend fun request(): String = one.status.lastError()

    /** An error frame of the one watch: the one link's routes take it (its settings reads that wait end). */
    fun onReceived(data: String) {
        one.routes.getValue(org.avmedia.gshockapi.casio.CasioConstants.CHARACTERISTICS.ERROR.code)(data)
    }
}
