package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.Channel
import org.avmedia.gshockapi.casio.WatchLink
import org.avmedia.gshockapi.link.JobOutcome
import org.avmedia.gshockapi.casio.ConnectionKeepAlive

/**
 * Sends a port record to a DW-H5600 as a background job on the link. Routing of the watch's answers works as for
 * the workout transfer: while it runs, the H5600 protocol hands bulk and data request notifications to [onDrsp] and [onConvoy].
 */
@RequiresApi(Build.VERSION_CODES.O)
object TideTransferIO {
    @Volatile private var inbox: Channel<WorkoutMsg>? = null

    val isActive: Boolean get() = inbox != null

    /** The channel a message belongs to: the characteristic decides, as for the workouts ([H5600WorkoutIO.channelOf]). */
    fun channelOf(hex: String): H5600Channel = H5600WorkoutIO.channelOf(hex)

    fun onDrsp(hex: String) = push(H5600Channel.DRSP, hex)

    fun onConvoy(hex: String) = push(H5600Channel.CONVOY, hex)

    private fun push(channel: H5600Channel, hex: String) {
        if (inbox != null) ConnectionKeepAlive.log("tide rx $channel " + hex.trim().split(' ').take(12).joinToString(" "))
        inbox?.trySend(WorkoutMsg(channel, H5600HealthIO.bytesOf(hex)))
    }

    /** Waits for its turn on the link, then sends [record]. Returns an error text or null when the watch took it. */
    suspend fun send(record: ByteArray): String? {
        val channel = Channel<WorkoutMsg>(Channel.UNLIMITED)
        try {
            // Its own job on the link (one transfer at a time): it gives way to more urgent jobs between pieces. The watch's
            // messages are ours only once the job holds the link.
            return when (val out = WatchLink.transfer("tide transfer", JOB_DEADLINE_MS) { inbox = channel; TideTransfer(link ?: JobWorkoutLink(this), channel).run(record).error }.await()) {
                is JobOutcome.Done -> out.value
                is JobOutcome.Failed -> "The tide transfer ended: ${out.detail}"
            }
        } finally {
            inbox = null
        }
    }

    /** A test seam: a scripted watch in place of the link. */
    @Volatile internal var link: WorkoutLink? = null

    /** Start, pieces with their gaps, end: well under this. */
    internal const val JOB_DEADLINE_MS = 3 * 60 * 1000L
}
