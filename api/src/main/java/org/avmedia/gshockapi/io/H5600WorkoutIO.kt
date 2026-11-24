package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.WatchLink
import org.avmedia.gshockapi.link.JobScope
import org.avmedia.gshockapi.model.WorkoutSession

/**
 * The single-watch workout read: the [H5600WorkoutReader] of the one link. New code takes the reader of its own link
 * (`link.transfers.workouts`).
 */
@RequiresApi(Build.VERSION_CODES.O)
object H5600WorkoutIO {
    private val reader: H5600WorkoutReader get() = WatchLink.transfers.workouts

    const val RETRY_AFTER_FAIL_MS = H5600WorkoutReader.RETRY_AFTER_FAIL_MS

    var sink: (suspend (WorkoutSession) -> Boolean)?
        get() = reader.sink
        set(value) { reader.sink = value }

    val workouts: SharedFlow<WorkoutSession> get() = reader.workouts
    val status: StateFlow<String?> get() = reader.status
    val lastError: String? get() = reader.lastError
    val isActive: Boolean get() = reader.isActive
    val lastCheckMs: Long? get() = reader.lastCheckMs

    internal var watch: () -> String
        get() = reader.watch
        set(value) { reader.watch = value }

    internal var clock: () -> Long
        get() = reader.clock
        set(value) { reader.clock = value }

    internal var setupWaitMs: Long
        get() = reader.setupWaitMs
        set(value) { reader.setupWaitMs = value }

    /** A test seam: a scripted watch in place of the link. */
    internal var link: WorkoutLink?
        get() = reader.script
        set(value) { reader.script = value }

    /**
     * Which characteristic a message belongs to while a workout or tide transfer runs. The characteristic it came on decides;
     * the guess from the bytes is only the fallback when the connection code did not say. The
     * watch's `09 1D` (all sent) is longer than 2 bytes, the guess called it a status frame and the read never answered it.
     */
    fun channelOf(hex: String): H5600Channel =
        when (WatchLink.currentSource()) {
            CasioConstants.CASIO_DATA_REQUEST_CHARACTERISTIC_UUID -> H5600Channel.DRSP
            CasioConstants.CASIO_CONVOY_CHARACTERISTIC_UUID -> H5600Channel.CONVOY
            null -> channelOf(h5600Bytes(hex))
            else -> H5600Channel.STATUS
        }

    /** The guess from the bytes alone (no characteristic known). Single bytes are never bulk. */
    fun channelOf(data: ByteArray): H5600Channel {
        val first = data.firstOrNull()?.toInt()?.and(0xFF) ?: return H5600Channel.STATUS
        return when {
            data.size <= 2 && first in DRSP_FIRST -> H5600Channel.DRSP
            data.size > 2 && first <= BULK_LAST -> H5600Channel.CONVOY
            else -> H5600Channel.STATUS
        }
    }

    private val DRSP_FIRST = setOf(0x00, 0x03, 0x04, 0x06, 0x07, 0x08, 0x09)
    private const val BULK_LAST = 0x07

    fun onDrsp(hex: String) = reader.onDrsp(hex)

    fun onConvoy(hex: String) = reader.onConvoy(hex)

    fun onSetupDone(condition: List<Int>) = reader.onSetupDone(condition)

    fun afterRead(condition: ByteArray?, nowMs: Long = System.currentTimeMillis()) = reader.afterRead(condition, nowMs)

    internal fun resetBackoff() = reader.resetBackoff()

    suspend fun download(userAsked: Boolean = false): WorkoutRunResult? = reader.download(userAsked)

    suspend fun beforeLifeLog(condition: ByteArray?, run: suspend () -> Unit): Boolean = reader.beforeLifeLog(condition, run)

    suspend fun downloadHeld(userAsked: Boolean = false, job: JobScope? = null): WorkoutRunResult? = reader.downloadHeld(userAsked, job)
}
