package org.avmedia.gshockapi.io

/** How a checked write ended. [Failed.message] is plain text for the person using the app. */
sealed class SettingWriteResult {
    data object Unchanged : SettingWriteResult()

    data object Done : SettingWriteResult()

    /** The watch confirmed the write, but did not answer the read-back in time. Not a failure: the write itself was confirmed. */
    data object Written : SettingWriteResult()

    /**
     * The write went out but no write result came (a lost result, or a link busy with other jobs until the deadline),
     * and the read-back did not answer either: the change may or may not be on the watch. Not a refusal: read the
     * setting again later and report only a value that differs.
     */
    data object Unconfirmed : SettingWriteResult()

    data class Failed(val message: String) : SettingWriteResult()
}

/** Thrown by a write function when the watch did not confirm the write (no write result, or a failed one). */
class WriteNotConfirmed(message: String) : Exception(message)

/**
 * Read, change, write, read back. Used for every write to a watch setting, because the watch is the only
 * place the value lives:
 * 1. read the current frame of request [code];
 * 2. the caller changes only the decoded fields, and says which frame bytes it may touch ([touched]);
 * 3. any other byte that differs stops the write;
 * 4. write, wait [settle], read again, and compare the whole frame with what was written.
 * A read-back is only a safety check after the write result: no answer to it is not a failure ([SettingWriteResult.Written]);
 * a different value is reported once and never retried. A write without a write result is no refusal either: the
 * read-back decides (the same value: done, another value: not kept, no answer: [SettingWriteResult.Unconfirmed]).
 * No code here knows a layout.
 */
class SafeSettingWrite(
    private val read: suspend (code: Int) -> ByteArray?,
    private val write: suspend (frame: ByteArray) -> Unit,
    private val settle: suspend () -> Unit = {},
) {
    suspend fun update(
        code: Int,
        touched: Set<Int>,
        expectedBack: (before: ByteArray, written: ByteArray) -> ByteArray = { _, written -> written },
        matches: (back: ByteArray, expected: ByteArray) -> Boolean = { back, expected -> back.contentEquals(expected) },
        change: (ByteArray) -> ByteArray?,
    ): SettingWriteResult = updateWith(code, { read(code) }, touched, expectedBack, matches, change)

    /**
     * Same as [update] for a value that is read with its own request ([readValue]), such as the per-activity
     * items. [expectedBack] says what the watch should return after the write; it differs from the written frame
     * only where the watch rewrites a command byte (0x3D).
     */
    suspend fun updateWith(
        code: Int,
        readValue: suspend () -> ByteArray?,
        touched: Set<Int>,
        expectedBack: (before: ByteArray, written: ByteArray) -> ByteArray = { _, written -> written },
        matches: (back: ByteArray, expected: ByteArray) -> Boolean = { back, expected -> back.contentEquals(expected) },
        change: (ByteArray) -> ByteArray?,
    ): SettingWriteResult {
        val before = readValue()
        if (before == null || before.isEmpty() || before[0] != code.toByte()) {
            return SettingWriteResult.Failed("The watch did not answer. Nothing was changed.")
        }
        val after = change(before) ?: return SettingWriteResult.Failed("This setting is not in the layout the app knows. Nothing was changed.")
        if (after.size != before.size || before.indices.any { it !in touched && before[it] != after[it] }) {
            return SettingWriteResult.Failed("The change would alter other bytes. Nothing was changed.")
        }
        if (after.contentEquals(before)) return SettingWriteResult.Unchanged
        val confirmed =
            try {
                write(after)
                true
            } catch (e: WriteNotConfirmed) {
                false
            }
        settle()
        val back = readValue() ?: return if (confirmed) SettingWriteResult.Written else SettingWriteResult.Unconfirmed
        return if (matches(back, expectedBack(before, after))) {
            SettingWriteResult.Done
        } else {
            SettingWriteResult.Failed("The watch did not keep the change. Check the setting on the watch.")
        }
    }
}
