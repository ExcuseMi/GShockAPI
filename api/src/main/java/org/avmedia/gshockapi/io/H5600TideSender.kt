package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.Channel
import org.avmedia.gshockapi.casio.ConnectionKeepAlive
import org.avmedia.gshockapi.link.JobOutcome
import org.avmedia.gshockapi.link.WatchLink

/**
 * Sends a port record to one link's DW-H5600 as a background job on that link. While it runs, that link's routing hands
 * its watch's bulk and data request notifications to [onDrsp] and [onConvoy]. Made only by its link's [LinkTransfers].
 */
@RequiresApi(Build.VERSION_CODES.O)
class H5600TideSender internal constructor(private val link: WatchLink) {
    companion object {
        /** Start, pieces with their gaps, end: well under this. */
        internal const val JOB_DEADLINE_MS = 3 * 60 * 1000L
    }

    @Volatile private var inbox: Channel<WorkoutMsg>? = null

    val isActive: Boolean get() = inbox != null

    fun onDrsp(hex: String) = push(H5600Channel.DRSP, hex)

    fun onConvoy(hex: String) = push(H5600Channel.CONVOY, hex)

    private fun push(channel: H5600Channel, hex: String) {
        if (inbox != null) ConnectionKeepAlive.log("tide rx $channel " + hex.trim().split(' ').take(12).joinToString(" "))
        inbox?.trySend(WorkoutMsg(channel, h5600Bytes(hex)))
    }

    /** Waits for its turn on the link, then sends [record]. Returns an error text or null when the watch took it. */
    suspend fun send(record: ByteArray): String? {
        val channel = Channel<WorkoutMsg>(Channel.UNLIMITED)
        try {
            // Its own job on the link (one transfer at a time): it gives way to more urgent jobs between pieces. The watch's
            // messages are ours only once the job holds the link.
            return when (val out = link.transfer("tide transfer", JOB_DEADLINE_MS) { inbox = channel; TideTransfer(script ?: JobWorkoutLink(this), channel).run(record).error }.await()) {
                is JobOutcome.Done -> out.value
                is JobOutcome.Failed -> "The tide transfer ended: ${out.detail}"
            }
        } finally {
            inbox = null
        }
    }

    /** A test seam: a scripted watch in place of the link. */
    @Volatile internal var script: WorkoutLink? = null
}
