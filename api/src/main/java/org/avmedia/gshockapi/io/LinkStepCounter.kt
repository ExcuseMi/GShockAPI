package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.model.StepCounterData
import org.avmedia.gshockapi.utils.Utils
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * One link's activity record (`26`), for the models with a step counter in it: the record comes in pieces that are
 * joined until the announced length, then parsed. The transaction writes are command jobs on this link and the pieces
 * come from this link's watch only. Made only by its link's [LinkIO].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkStepCounter internal constructor(private val link: WatchLink) {
    private class Read(val result: CompletableDeferred<StepCounterData>, val peek: Boolean) {
        var accumulator = ByteArray(0)
        var expectedLength = FALLBACK_EXPECTED_LENGTH
    }

    @Volatile private var read: Read? = null
    @Volatile private var lastData: StepCounterData? = null

    suspend fun request(peek: Boolean = true): StepCounterData {
        if (!link.watchInfo.info.hasStepCounter) {
            Timber.i("Step counter not supported on watch model: ${link.watchInfo.model}")
            return StepCounterData.unavailable()
        }
        // A peek while a full read runs returns what the last read gave.
        val last = lastData
        if (peek && read != null && last != null) return last
        val r = Read(CompletableDeferred(), peek)
        synchronized(this) { read = r }
        try {
            link.command(GetSetMode.DATA_REQUEST, START_TRANSACTION_CMD)
            val data = withTimeoutOrNull(10_000L.milliseconds) { r.result.await() }
            if (data == null) Timber.w("LinkStepCounter ${link.key}: timed out waiting for activity record (accumulated ${r.accumulator.size}/${r.expectedLength}B)")
            return data ?: StepCounterData.unavailable()
        } finally {
            synchronized(this) { if (read === r) read = null }
        }
    }

    /** The data response of this link's watch: the length of the record it is about to send. */
    fun onDrspReceived(data: ByteArray) {
        if (data.size < 5 || data[1].toInt() and 0xFF != DRSP_CATEGORY_EXERCISE || data[0].toInt() != 0x00) return
        val length = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8) or ((data[4].toInt() and 0xFF) shl 16)
        synchronized(this) { read?.expectedLength = length }
    }

    fun onReceived(data: String) {
        val r = synchronized(this) { read } ?: return
        try {
            val complete = synchronized(this) {
                r.accumulator += Utils.byteArrayOfIntArray(Utils.toIntArray(data).toIntArray())
                r.accumulator.size >= r.expectedLength
            }
            if (!complete) return
            if (!r.peek) link.command(GetSetMode.DATA_REQUEST, END_TRANSACTION_CMD)
            val parsed = StepCounterIOFunctional.parse(synchronized(this) { r.accumulator })
            if (parsed != null) lastData = parsed else Timber.w("Failed to parse activity record from ${r.accumulator.size}B")
            r.result.complete(parsed ?: StepCounterData.unavailable())
        } catch (e: Exception) {
            Timber.e("Exception parsing step counter data: ${e.message}")
            r.result.complete(StepCounterData.unavailable())
        }
    }

    private companion object {
        const val FALLBACK_EXPECTED_LENGTH = 400
        const val DRSP_CATEGORY_EXERCISE = 0x11
        val START_TRANSACTION_CMD = byteArrayOf(0x00, DRSP_CATEGORY_EXERCISE.toByte(), 0x00, 0x00, 0x00)
        val END_TRANSACTION_CMD = byteArrayOf(0x04, DRSP_CATEGORY_EXERCISE.toByte(), 0x00, 0x00, 0x00)
    }
}
