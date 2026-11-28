package org.avmedia.gshockapi.ble

import java.util.UUID

/**
 * The GATT table of the link as one log line, and the standard Bluetooth Heart Rate Service (0x180D, measurement 0x2A37)
 * when a watch has it. The watches seen so far do not: the table line of each connection says so. When one has it,
 * its measurement is subscribed only while a stream is wanted ([stream]; a link that comes back meanwhile subscribes it
 * in its setup) and each value goes to [sink] (bpm, received at); it never reaches the watch protocol. Pure apart from
 * [sink], [onPresence] and [control].
 */
object StandardHeartRate {
    val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    /** True when the link's GATT table has [SERVICE] (set at every service discovery). */
    @Volatile var present: Boolean = false
        private set

    /** Each standard heart rate measurement: bpm and the phone time it came. */
    @Volatile var sink: ((Int, Long) -> Unit)? = null

    /** Told [present] at every service discovery (the app keeps whether its watch has the service). */
    @Volatile var onPresence: ((Boolean) -> Unit)? = null

    /** True while the app wants the measurement (from [stream] on until [stream] off). */
    @Volatile var wanted: Boolean = false
        private set

    /** The connection's way to turn the measurement's notifications on or off; set by the manager. */
    @Volatile internal var control: (suspend (Boolean) -> Boolean)? = null

    /**
     * Starts or stops the stream. Kept as [wanted] whatever the link does now, so a link that comes back subscribes
     * it again; true when the connection took the change (false while not connected or without the service).
     */
    suspend fun stream(on: Boolean): Boolean {
        wanted = on
        if (!present) return false
        return runCatching { control?.invoke(on) ?: false }.getOrDefault(false)
    }

    /** The notifying characteristics a link's setup subscribes: the measurement only while [wanted]. */
    fun subscribable(notifiable: List<UUID>): List<UUID> = if (wanted) notifiable else notifiable.filter { it != MEASUREMENT }

    /** One service of the table: its UUID and each characteristic with its property names. */
    data class Service(val uuid: UUID, val characteristics: List<Pair<UUID, List<String>>>)

    /** A service discovery found [services]: remembers whether the heart rate service is there. */
    fun onDiscovery(services: List<Service>) {
        present = services.any { it.uuid == SERVICE }
        runCatching { onPresence?.invoke(present) }
    }

    /** "1800: 2a00 READ, 2a01 READ; 26eb000d: 26eb0023 WRITE|NOTIFY; heart rate service 180d: no". */
    fun tableLine(services: List<Service>): String =
        services.joinToString("; ") { s ->
            short(s.uuid) + ": " + s.characteristics.joinToString(", ") { (c, props) -> short(c) + " " + props.joinToString("|").ifEmpty { "-" } }.ifEmpty { "-" }
        } + "; heart rate service 180d: " + if (services.any { it.uuid == SERVICE }) "yes" else "no"

    /** A 16-bit UUID of the Bluetooth base as its 4 hex digits, any other as its first 8. */
    fun short(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8) else s.substring(0, 8)
    }

    /** The bpm of a measurement (flags bit 0: 16-bit value); null when too short or 0. */
    fun bpm(data: ByteArray): Int? {
        if (data.size < 2) return null
        val wide = data[0].toInt() and 0x01 != 0
        val v = if (wide) {
            if (data.size < 3) return null
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else data[1].toInt() and 0xFF
        return v.takeIf { it > 0 }
    }

    /** A notification of [MEASUREMENT] at [now]. */
    fun onMeasurement(data: ByteArray, now: Long) {
        val v = bpm(data) ?: return
        runCatching { sink?.invoke(v, now) }
    }
}
