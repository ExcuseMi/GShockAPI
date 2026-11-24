package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.casio.WatchLink

/** The single-watch tide transfer: the [H5600TideSender] of the one link. New code uses `link.transfers.tide`. */
@RequiresApi(Build.VERSION_CODES.O)
object TideTransferIO {
    private val sender: H5600TideSender get() = WatchLink.transfers.tide

    val isActive: Boolean get() = sender.isActive

    /** The channel a message belongs to: the characteristic decides, as for the workouts ([H5600WorkoutIO.channelOf]). */
    fun channelOf(hex: String): H5600Channel = H5600WorkoutIO.channelOf(hex)

    fun onDrsp(hex: String) = sender.onDrsp(hex)

    fun onConvoy(hex: String) = sender.onConvoy(hex)

    /** Waits for its turn on the link, then sends [record]. Returns an error text or null when the watch took it. */
    suspend fun send(record: ByteArray): String? = sender.send(record)

    /** A test seam: a scripted watch in place of the link. */
    internal var link: WorkoutLink?
        get() = sender.script
        set(value) { sender.script = value }

    internal const val JOB_DEADLINE_MS = H5600TideSender.JOB_DEADLINE_MS
}
