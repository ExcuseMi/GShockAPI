package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.model.SmartAlarm
import org.avmedia.gshockapi.model.SoundMode
import org.avmedia.gshockapi.model.WatchFaceItem

/**
 * DW-H5600 (module 3516 family) settings decoded from the official app's BLE traffic.
 * See docs/DW-H5600-protocol.md and FINDINGS.md in the h5600-probe folder for the raw captures.
 *
 * 0x42 (6 bytes): [0]=0x42, [1]=sound mode, [2..5]=per-function CUSTOM table (kept as-is).
 * 0x50 (5 bytes): smart alarm: hh mm flags(01 on, 02 snooze) window(min).
 * 0x51 (6 bytes): [0]=0x51, [1]=face (1 weekday, 2 custom), [2..5]=display item (all four set).
 *
 * All reads go through the link's settings reads (shared requests, retries); all writes are checked read-modify-write
 * (read, change only the target bytes, write, read back), so they keep unknown bytes and cannot undo each other.
 * The same writer serves the app's screens and Home Assistant. This object is the single-watch adapter onto the one
 * link's [LinkH5600Settings]; new code uses `link.io.h5600Settings`.
 */
@RequiresApi(Build.VERSION_CODES.O)
object H5600SettingsIO {
    const val SOUND_MODE_CODE = LinkH5600Settings.SOUND_MODE_CODE
    const val WATCH_FACE_CODE = LinkH5600Settings.WATCH_FACE_CODE
    const val SMART_ALARM_CODE = LinkH5600Settings.SMART_ALARM_CODE

    /** CASIO_CURRENT_TIME_MANAGER. The official app requests it after connecting and writes the reply back. */
    const val TIME_MANAGER_CODE = LinkH5600Settings.TIME_MANAGER_CODE

    /** The one link's H5600 settings. New code uses `link.io.h5600Settings`. */
    private val one: LinkH5600Settings get() = org.avmedia.gshockapi.casio.WatchLink.io.h5600Settings

    @RequiresApi(Build.VERSION_CODES.O)
    fun isSupported(): Boolean = one.isSupported()

    // ---- Connection ----

    /**
     * Connection handshake copied from the official CASIO app (capture 2026-11-16 17:57:06):
     *   app -> 39 (request), watch -> 39 04, app -> 39 04 (written back unchanged), watch -> 47 01.
     * Without it the watch drops Smart Sync's connection after ~45 s; with the official app it stays
     * connected. Echoes exactly what the watch sent; nothing else is written.
     */
    suspend fun connectionHandshake() = one.connectionHandshake()

    /** Read-only: reads packets the official app reads on every connect, for logging/comparison. */
    suspend fun readDiagnostics(): Map<Int, String> = one.readDiagnostics()

    // ---- Home location (0x24) ----

    /**
     * Home location for sunrise/sunset: `24 00 01 <lat f64 BE> <lon f64 BE> 04`. Trailing byte 0x04
     * as the current official app writes it (user's capture 2026-11-16 23:08; izivkov's older capture
     * had 0x01). The watch computes sunrise/sunset from it; without it the watch shows dashes.
     * Last location given by the app; used when [homeLocationProvider] has none.
     */
    fun rememberHomeLocation(latitude: Double, longitude: Double) = one.rememberHomeLocation(latitude, longitude)

    /**
     * Supplied by the app: the location to send right now (real or "weather" coordinates). Asked at
     * time-sync time so a sync never goes out without a location, even before the app has
     * remembered one in this process.
     */
    var homeLocationProvider: (() -> Pair<Double, Double>?)?
        get() = one.homeLocationProvider
        set(value) { one.homeLocationProvider = value }

    /**
     * Called by TimeIO in the middle of a time sync, after the DST/world-city writes (1D, 1E) and
     * before the city names (1F) and the time (09): the exact place the official app writes 0x24,
     * so the watch recalculates sunrise/sunset with the location already in place.
     * Never throws: a bad or missing location must not stop the time from being set.
     */
    fun writeLocationInTimeSync() = one.writeLocationInTimeSync()

    fun setHomeLocation(latitude: Double, longitude: Double) = one.setHomeLocation(latitude, longitude)

    // ---- Sound mode (0x42) ----

    /** Raw sound packet as hex, for mapping the per-function CUSTOM bits. */
    suspend fun getSoundPacketHex(): String = one.getSoundPacketHex()

    suspend fun getSoundMode(): SoundMode? = one.getSoundMode()

    /** Sets the mode (no write if already equal). Returns the mode the watch had before. */
    suspend fun setSoundMode(mode: SoundMode): SoundMode? = one.setSoundMode(mode)

    // ---- Watch face (0x51) ----

    /** Returns null if the four item bytes disagree or hold an unknown code. */
    suspend fun getWatchFaceItem(): WatchFaceItem? = one.getWatchFaceItem()

    /** Raw display-item code (also for values not yet in [WatchFaceItem]); null if the four bytes disagree. */
    suspend fun getWatchFaceCode(): Int? = one.getWatchFaceCode()

    /** Watch face: byte 1 of 0x51, 1 = simple face (weekday), 2 = customisable face (item). */
    suspend fun getWatchFaceNumber(): Int = one.getWatchFaceNumber()

    suspend fun setWatchFaceNumber(face: Int) = one.setWatchFaceNumber(face)

    /** Sets the display item (no write if already equal). Returns the item the watch had before. */
    suspend fun setWatchFaceItem(item: WatchFaceItem): WatchFaceItem? = one.setWatchFaceItem(item)

    // ---- Smart alarm (0x50) ----

    suspend fun getSmartAlarm(): SmartAlarm = one.getSmartAlarm()

    /** Writes the smart-alarm slot; other flag bits read from the watch are kept. */
    suspend fun setSmartAlarm(a: SmartAlarm) = one.setSmartAlarm(a)
}