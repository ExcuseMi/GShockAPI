package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi

// ============================================================================
// Pure Functional Core: App Info Scratchpad Processing
// ============================================================================

/**
 * Pure functional core for app info scratchpad operations.
 *
 * All methods are pure: no mutable state, no side effects.
 * Handles buffer initialization, validation, and data access.
 */
@RequiresApi(Build.VERSION_CODES.O)
object AppInfoIOFunctional {
    // Constants for buffer structure and validation
    const val BUFFER_SIZE = 12
    const val CMD_BYTE_INDEX = 0
    const val MAGIC_NUMBER_INDEX = 1
    const val USER_DATA_START_INDEX = 2 // User data starts after magic number
    const val MAGIC_NUMBER = 0x95

    /**
     * Pure validator: Checks if scratchpad has been initialized.
     *
     * Validates that the magic number is present at the expected index.
     * No side effects - simple inspection.
     */
    fun isScratchpadInitialized(buffer: ByteArray): Boolean = buffer.getOrNull(MAGIC_NUMBER_INDEX) == MAGIC_NUMBER.toByte()

    /**
     * Pure validator: Checks if buffer needs reinitialization.
     *
     * Returns true if magic number is missing (watch was reset).
     * Protocol: Watch reset produces 0x22 FF FF FF FF FF FF FF FF FF FF 00
     */
    fun needsReinit(buffer: ByteArray): Boolean = !isScratchpadInitialized(buffer)

    /**
     * Pure builder: Creates an initialized scratchpad buffer.
     *
     * Initializes the buffer with command code and magic number.
     * No side effects - pure construction.
     */
    fun createInitializedBuffer(): ByteArray =
        ByteArray(BUFFER_SIZE).apply {
            this[CMD_BYTE_INDEX] = 0x22.toByte()
            this[MAGIC_NUMBER_INDEX] = MAGIC_NUMBER.toByte()
            // Rest remains 0x00
        }

    /**
     * Pure validator: Checks if user data write is valid.
     *
     * Validates bounds before write operation.
     */
    fun isValidWrite(
        startIndex: Int,
        dataSize: Int,
    ): Result<Unit> =
        runCatching {
            val userDataSize = BUFFER_SIZE - USER_DATA_START_INDEX
            if (startIndex < 0 || startIndex + dataSize > userDataSize) {
                throw IllegalArgumentException(
                    "Data exceeds user data buffer size. Max size: $userDataSize bytes, " +
                        "requested: startIndex=$startIndex, size=$dataSize",
                )
            }
        }

    /**
     * Pure validator: Checks if user data read is valid.
     *
     * Validates bounds before read operation.
     */
    fun isValidRead(
        startIndex: Int,
        len: Int,
    ): Result<Unit> =
        runCatching {
            val userDataSize = BUFFER_SIZE - USER_DATA_START_INDEX
            if (startIndex < 0 || len < 0 || startIndex + len > userDataSize) {
                throw IllegalArgumentException(
                    "Invalid read request. Index or length is out of bounds. " +
                        "Max size: $userDataSize, requested: startIndex=$startIndex, len=$len",
                )
            }
        }

    /**
     * Pure transformer: Writes data into a scratchpad buffer.
     *
     * Creates new buffer with data written at specified position.
     * No side effects - returns new buffer.
     */
    fun writeUserData(
        buffer: ByteArray,
        data: ByteArray,
        startIndex: Int,
    ): Result<ByteArray> =
        isValidWrite(startIndex, data.size).map {
            ByteArray(BUFFER_SIZE).apply {
                System.arraycopy(buffer, 0, this, 0, BUFFER_SIZE)
                System.arraycopy(data, 0, this, USER_DATA_START_INDEX + startIndex, data.size)
            }
        }

    /**
     * Pure extractor: Reads data from a scratchpad buffer.
     *
     * Extracts data at specified position from buffer.
     * No side effects - pure extraction.
     */
    fun readUserData(
        buffer: ByteArray,
        startIndex: Int,
        len: Int,
    ): Result<ByteArray> =
        isValidRead(startIndex, len).map {
            buffer.copyOfRange(
                USER_DATA_START_INDEX + startIndex,
                USER_DATA_START_INDEX + startIndex + len,
            )
        }
}

// ============================================================================
// Imperative Shell: Side Effects & State Management
// ============================================================================

/**
 * App Info IO handler with state management.
 *
 * Manages scratchpad data for app configuration.
 * This is needed to re-enable button D (Lower-right) after watch reset or BLE clear.
 * The value is a hard-coded magic number (0x93), matching the official app behavior.
 *
 * Protocol: When watch is reset, app info arrives as:
 *   0x22 FF FF FF FF FF FF FF FF FF FF 00
 * In this case, we reinitialize with the magic number so button D works again.
 *
 * Uses pure functional core for all buffer operations.
 */
@RequiresApi(Build.VERSION_CODES.O)
object AppInfoIO {
    private val one: LinkAppInfo get() = org.avmedia.gshockapi.casio.WatchLink.io.appInfo

    val lastReceivedData: ByteArray get() = one.lastReceivedData

    /** The one watch's scratchpad was found empty and written again on the last read. */
    var wasScratchpadReset: Boolean
        get() = one.wasScratchpadReset
        set(value) { one.wasScratchpadReset = value }

    suspend fun request(): String = one.request()

    fun onReceived(data: String) = one.onReceived(data)

    /**
     * Stores an array of bytes in the user data area of the scratchpad (the full area, from index 0).
     *
     * @throws IllegalStateException if the scratchpad is not initialized.
     * @throws IllegalArgumentException if data exceeds buffer size.
     */
    fun setUserData(data: ByteArray) = one.setUserData(data)

    /**
     * Retrieves all data from the user data area of the scratchpad.
     *
     * @throws IllegalStateException if the scratchpad is not initialized.
     */
    fun getUserData(): ByteArray = one.getUserData()
}
