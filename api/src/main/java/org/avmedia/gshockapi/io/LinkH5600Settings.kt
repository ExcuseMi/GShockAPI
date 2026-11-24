package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.model.SmartAlarm
import org.avmedia.gshockapi.model.SoundMode
import org.avmedia.gshockapi.model.WatchFaceItem
import org.avmedia.gshockapi.protocols.H5600Protocol
import org.avmedia.gshockapi.utils.Utils
import timber.log.Timber

/**
 * One link's DW-H5600 settings packets: sound mode (`42`), watch face (`51`), smart alarm (`50`), the time manager
 * (`39`) and the home location (`24`). Reads go through this link's [LinkRawSettings]; writes are its checked
 * read-modify-write, so they keep unknown bytes. Only a link whose own model speaks the H5600 protocol takes them.
 * Made only by its link's [LinkIO]; the packet layout is described in [H5600SettingsIO].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkH5600Settings internal constructor(private val link: WatchLink) {
    /** This link's model speaks the H5600 protocol. */
    fun isSupported(): Boolean = link.watchInfo.info.protocol is H5600Protocol

    private fun checkSupported() = check(isSupported()) { "Not an H5600 protocol watch (protocol=${link.watchInfo.info.protocol})" }

    private fun checkPacket(code: Int, packet: IntArray, size: Int = PACKET_SIZE) =
        check(packet.size == size && packet[0] == code) { "Unexpected 0x%02X packet: %s".format(code, packet.toHex()) }

    private suspend fun readChecked(code: Int, size: Int = PACKET_SIZE): IntArray {
        checkSupported()
        return link.rawSettings.request(code).also { checkPacket(code, it, size) }
    }

    /** Checked read-modify-write of the [touched] bytes; returns the packet before the change. */
    private suspend fun modifyChecked(code: Int, touched: Set<Int>, size: Int = PACKET_SIZE, transform: (IntArray) -> IntArray?): IntArray {
        checkSupported()
        var before: IntArray? = null
        val result = link.rawSettings.modifySafe(code, touched) { frame ->
            val p = IntArray(frame.size) { frame[it].toInt() and 0xFF }
            checkPacket(code, p, size)
            before = p.copyOf()
            transform(p)?.let { Utils.byteArrayOfIntArray(it) }
        }
        (result as? SettingWriteResult.Failed)?.let { error(it.message) }
        return checkNotNull(before) { "0x%02X was not read".format(code) }
    }

    /** The time manager handshake: `39` is read and written back unchanged. Nothing on another model. */
    suspend fun connectionHandshake() {
        if (!isSupported()) return
        val reply = link.rawSettings.request(TIME_MANAGER_CODE)
        check(reply.isNotEmpty() && reply[0] == TIME_MANAGER_CODE && reply.size <= 4) { "Unexpected 0x39 reply: ${reply.toHex()}" }
        link.rawSettings.write(reply)
    }

    /** Read-only: `45` and `4F` as hex, for the log. */
    suspend fun readDiagnostics(): Map<Int, String> =
        listOf(0x45, 0x4F).associateWith { code ->
            runCatching { link.rawSettings.request(code).toHex() }.getOrElse { "error: ${it.message}" }
        }

    // ---- Home location (0x24) ----

    @Volatile private var homeLocation: Pair<Double, Double>? = null

    /** Supplied by the app: the location to send right now. Asked at time-sync time. */
    @Volatile var homeLocationProvider: (() -> Pair<Double, Double>?)? = null

    private fun isValid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0

    fun rememberHomeLocation(latitude: Double, longitude: Double) {
        if (isValid(latitude, longitude)) homeLocation = latitude to longitude
    }

    /** The location write in the middle of a time sync. Never throws: a bad or missing location must not stop the time. */
    fun writeLocationInTimeSync() {
        if (!isSupported()) return
        val loc = runCatching { homeLocationProvider?.invoke() }.getOrNull()?.takeIf { isValid(it.first, it.second) }
            ?: homeLocation ?: return
        runCatching { setHomeLocation(loc.first, loc.second) }.onFailure { Timber.e(it, "0x24 location write failed") }
    }

    fun setHomeLocation(latitude: Double, longitude: Double) {
        checkSupported()
        require(isValid(latitude, longitude)) { "Bad coordinates" }
        homeLocation = latitude to longitude
        val buf = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.put(0x24.toByte()).put(0x00).put(0x01).putDouble(latitude).putDouble(longitude).put(0x04)
        link.rawSettings.write(buf.array().map { it.toInt() and 0xFF }.toIntArray())
    }

    // ---- Sound mode (0x42) ----

    suspend fun getSoundPacketHex(): String = readChecked(SOUND_MODE_CODE).toHex()

    suspend fun getSoundMode(): SoundMode? = SoundMode.fromCode(readChecked(SOUND_MODE_CODE)[1])

    /** Sets the mode (no write if already equal). Returns the mode the watch had before. */
    suspend fun setSoundMode(mode: SoundMode): SoundMode? =
        modifyChecked(SOUND_MODE_CODE, setOf(1)) { p -> p.also { it[1] = mode.code } }
            .let { SoundMode.fromCode(it[1]) }
            .also { if (it == mode) Timber.i("Sound mode already $mode, not writing") }

    // ---- Watch face (0x51) ----

    suspend fun getWatchFaceItem(): WatchFaceItem? = getWatchFaceCode()?.let { WatchFaceItem.fromCode(it) }

    suspend fun getWatchFaceCode(): Int? = itemCode(readChecked(WATCH_FACE_CODE))

    private fun itemCode(p: IntArray): Int? = p.copyOfRange(2, PACKET_SIZE).distinct().singleOrNull()

    suspend fun getWatchFaceNumber(): Int = readChecked(WATCH_FACE_CODE)[1]

    suspend fun setWatchFaceNumber(face: Int) {
        require(face == 1 || face == 2) { "face must be 1 or 2" }
        modifyChecked(WATCH_FACE_CODE, setOf(1)) { p -> p.also { it[1] = face } }
    }

    suspend fun setWatchFaceItem(item: WatchFaceItem): WatchFaceItem? =
        modifyChecked(WATCH_FACE_CODE, (2 until PACKET_SIZE).toSet()) { p -> p.also { for (i in 2 until PACKET_SIZE) it[i] = item.code } }
            .let { before -> itemCode(before)?.let { WatchFaceItem.fromCode(it) } }

    // ---- Smart alarm (0x50) ----

    suspend fun getSmartAlarm(): SmartAlarm =
        readChecked(SMART_ALARM_CODE, SMART_ALARM_SIZE).let { p ->
            SmartAlarm(p[1], p[2], p[3] and 0x01 != 0, p[3] and 0x02 != 0, p[4])
        }

    suspend fun setSmartAlarm(a: SmartAlarm) {
        require(a.hour in 0..23 && a.minute in 0..59 && a.windowMin in 0..120) { "Bad smart alarm" }
        modifyChecked(SMART_ALARM_CODE, setOf(1, 2, 3, 4), SMART_ALARM_SIZE) { p ->
            var flags = p[3] and 0x03.inv()
            if (a.enabled) flags = flags or 0x01
            if (a.snooze) flags = flags or 0x02
            intArrayOf(SMART_ALARM_CODE, a.hour, a.minute, flags, a.windowMin)
        }
    }

    internal companion object {
        const val SOUND_MODE_CODE = 0x42
        const val WATCH_FACE_CODE = 0x51
        const val SMART_ALARM_CODE = 0x50
        const val TIME_MANAGER_CODE = 0x39
        const val PACKET_SIZE = 6
        const val SMART_ALARM_SIZE = 5
    }
}
