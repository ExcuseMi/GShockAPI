package org.avmedia.gshockapi.driver

/**
 * Decodes and changes one data-file setting in a frame. Pure: no watch, no state. A frame that is too short, of
 * another code, or holds a value the file does not know gives null, and null is never written. A change touches only
 * the bits of its own setting; every other bit and byte stays as the watch sent it.
 */
object DataSettingCodec {
    private fun u8(frame: ByteArray, at: Int): Int? = frame.getOrNull(at)?.let { it.toInt() and 0xFF }

    private fun shift(mask: Int): Int = Integer.numberOfTrailingZeros(mask)

    /** [frame] is of [spec]'s code and long enough to hold it. */
    fun fits(spec: SettingSpec, frame: ByteArray): Boolean =
        frame.isNotEmpty() && (frame[0].toInt() and 0xFF) == spec.code && frame.size >= maxOf(spec.minLength, last(spec) + 1)

    private fun last(spec: SettingSpec): Int = when (spec.type) {
        "time" -> spec.byte + 1
        "uint" -> spec.byte + spec.length - 1
        else -> spec.byte
    }

    /** The frame bytes a change of [spec] may touch (for the checked write): its own, its write flags and its command byte. */
    fun touched(spec: SettingSpec): Set<Int> = (spec.byte..last(spec)).toSet() + spec.onWriteSet.keys + listOfNotNull(spec.command?.first)

    private fun inRange(spec: SettingSpec, v: Int): Boolean =
        (spec.allowed.isEmpty() || v in spec.allowed) && (spec.min == null || v >= spec.min) && (spec.max == null || v <= spec.max)

    /** Boolean, String (choice), Int (uint) or [TimeOfDay]; null when not readable. */
    fun decode(spec: SettingSpec, frame: ByteArray): Any? {
        if (!fits(spec, frame)) return null
        val b = u8(frame, spec.byte) ?: return null
        return when (spec.type) {
            "bool" -> (b and spec.mask != 0) != spec.invert
            "choice" -> ((b and spec.mask) shr shift(spec.mask)).let { raw -> spec.values.entries.firstOrNull { it.value == raw }?.key }
            "uint" -> {
                val bytes = (0 until spec.length).map { u8(frame, spec.byte + it) ?: return null }
                val v = (if (spec.littleEndian) bytes else bytes.reversed()).withIndex().sumOf { (i, x) -> x shl (8 * i) }
                val masked = if (spec.length == 1) (v and spec.mask) shr shift(spec.mask) else v
                masked.takeIf { !spec.rangeOnRead || inRange(spec, it) }
            }
            "time" -> {
                val m = u8(frame, spec.byte + 1) ?: return null
                if (b < 24 && m < 60) TimeOfDay(b, m) else null
            }
            else -> null
        }
    }

    /**
     * [frame] with [value] in place; null when the frame is not readable, the value is not allowed, or [others]
     * (the decoded values of the settings this one [SettingSpec.requires]) forbid it.
     */
    fun encode(spec: SettingSpec, frame: ByteArray, value: Any, others: Map<String, Any?> = emptyMap()): ByteArray? {
        val current = decode(spec, frame)
        if (current == null && !(spec.writeOverUnknown && fits(spec, frame))) return null
        // A command frame is written only for a change: the same value gives the frame as it is (nothing is written).
        if (spec.command != null && current == value) return frame.copyOf()
        val out = frame.copyOf()
        val b = u8(frame, spec.byte)!!
        fun put(at: Int, v: Int) { out[at] = v.toByte() }
        when (spec.type) {
            "bool" -> {
                val on = value as? Boolean ?: return null
                if (on && spec.requires != null && others[spec.requires] != true) return null
                var next = if (on != spec.invert) b or spec.mask else b and spec.mask.inv()
                if (!on) next = next and spec.clearWhenFalse.inv()
                put(spec.byte, next)
            }
            "choice" -> {
                val name = value as? String ?: return null
                if (spec.settable.isNotEmpty() && name !in spec.settable) return null
                val raw = spec.values[name] ?: return null
                put(spec.byte, (b and spec.mask.inv()) or ((raw shl shift(spec.mask)) and spec.mask))
            }
            "uint" -> {
                val v = value as? Int ?: return null
                if (!inRange(spec, v)) return null
                if (spec.length == 1) {
                    put(spec.byte, (b and spec.mask.inv()) or ((v shl shift(spec.mask)) and spec.mask))
                } else {
                    for (i in 0 until spec.length) {
                        val at = if (spec.littleEndian) spec.byte + i else spec.byte + spec.length - 1 - i
                        put(at, (v shr (8 * i)) and 0xFF)
                    }
                }
            }
            "time" -> {
                val t = value as? TimeOfDay ?: return null
                if (t.hour !in 0..23 || t.minute !in 0..59) return null
                put(spec.byte, t.hour)
                put(spec.byte + 1, t.minute)
            }
            else -> return null
        }
        spec.onWriteSet.forEach { (at, bits) -> out[at] = ((out[at].toInt() and 0xFF) or bits).toByte() }
        spec.command?.let { (at, v) -> out[at] = v.toByte() }
        return out
    }

    // ---- Records (alarm slots): several frames, each with fixed-size records. ----

    /** How many records [frame] holds for [rf]. */
    private fun recordCount(spec: SettingSpec, rf: RecordFrame, frame: ByteArray): Int =
        minOf(rf.count, (frame.size - rf.offset) / spec.recordSize).coerceAtLeast(0)

    /** The frame bytes the records of [code] may touch. */
    fun touchedRecords(spec: SettingSpec, code: Int): Set<Int> =
        spec.frames.firstOrNull { it.code == code }?.let { rf -> (rf.offset until rf.offset + rf.count * spec.recordSize).toSet() }.orEmpty()

    /** The records of one frame of [spec] as field maps; null when [frame] is not one of its frames. */
    fun decodeRecords(spec: SettingSpec, frame: ByteArray): List<Map<String, Any>>? {
        val rf = spec.frames.firstOrNull { frame.isNotEmpty() && it.code == (frame[0].toInt() and 0xFF) } ?: return null
        return (0 until recordCount(spec, rf, frame)).map { i ->
            val start = rf.offset + i * spec.recordSize
            spec.fields.associate { f ->
                val b = u8(frame, start + f.byte)!!
                f.name to (if (f.type == "bool") b and f.mask != 0 else (b and f.mask) shr shift(f.mask))
            }
        }
    }

    /** [frame] with [records] written into its slots (fields and fixed bytes only); null when a value does not fit. */
    fun encodeRecords(spec: SettingSpec, frame: ByteArray, records: List<Map<String, Any>>): ByteArray? {
        val rf = spec.frames.firstOrNull { frame.isNotEmpty() && it.code == (frame[0].toInt() and 0xFF) } ?: return null
        // The frame keeps its size: a checked write refuses a frame that grew or shrank.
        if (records.size > rf.count || rf.offset + records.size * spec.recordSize > frame.size) return null
        val out = frame.copyOf()
        records.forEachIndexed { i, record ->
            val start = rf.offset + i * spec.recordSize
            spec.fixed.forEach { (at, v) -> out[start + at] = v.toByte() }
            for (f in spec.fields) {
                val at = start + f.byte
                val b = out[at].toInt() and 0xFF
                val next = when (f.type) {
                    "bool" -> if (record[f.name] as? Boolean ?: return null) b or f.mask else b and f.mask.inv()
                    else -> {
                        val v = record[f.name] as? Int ?: return null
                        if (v < 0 || (f.max != null && v > f.max)) return null
                        (b and f.mask.inv()) or ((v shl shift(f.mask)) and f.mask)
                    }
                }
                out[at] = next.toByte()
            }
        }
        return out
    }
}
