package org.avmedia.gshockapi.driver

/**
 * One setting in one settings frame, pure: bytes in, value or bytes out. A driver's [WatchDriver.get] reads the frame of
 * [code] and decodes it; its [WatchDriver.set] is the checked write of [touched] with [encode]. The same object decodes a
 * frame the app already holds (a cache, a read-back), so the value is always the driver's.
 */
interface FrameSetting {
    val key: String

    /** The request code of the frame (its byte 0). */
    val code: Int

    /** The frame bytes [encode] may change. */
    val touched: Set<Int>

    /** The value in [frame], or null when the frame is not readable for this setting. */
    fun decode(frame: ByteArray): Any?

    /** [frame] with [value] in place, or null when the value or the frame is refused. An equal frame means: nothing to write. */
    fun encode(frame: ByteArray, value: Any): ByteArray?

    /** What the watch answers after [written] (a command byte comes back as it was before). */
    fun expectedBack(before: ByteArray, written: ByteArray): ByteArray = written

    /** How the read-back is compared with [expectedBack]. */
    fun matches(back: ByteArray, expected: ByteArray): Boolean = back.contentEquals(expected)
}

/**
 * A data-file setting with its checks: it is read and written only while the settings of [guards] that the frame holds
 * are readable too (the whole layout, as today's codecs check it), and a write that [required] forbids is refused.
 */
class DataSetting(
    val spec: SettingSpec,
    private val guards: List<SettingSpec>,
    private val required: SettingSpec?,
) : FrameSetting {
    override val key: String get() = spec.key
    override val code: Int get() = spec.code
    override val touched: Set<Int> = DataSettingCodec.touched(spec)

    private fun guardsHold(frame: ByteArray): Boolean =
        guards.all { g -> !DataSettingCodec.fits(g, frame) || DataSettingCodec.decode(g, frame) != null }

    override fun decode(frame: ByteArray): Any? = if (guardsHold(frame)) DataSettingCodec.decode(spec, frame) else null

    override fun encode(frame: ByteArray, value: Any): ByteArray? {
        if (!guardsHold(frame)) return null
        val others = required?.let { mapOf(it.key to DataSettingCodec.decode(it, frame)) }.orEmpty()
        return DataSettingCodec.encode(spec, frame, value, others)
    }

    override fun expectedBack(before: ByteArray, written: ByteArray): ByteArray {
        val (at, _) = spec.command ?: return written
        return written.copyOf().also { if (at < before.size && at < it.size) it[at] = before[at] }
    }
}
