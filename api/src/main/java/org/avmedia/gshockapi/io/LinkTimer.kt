package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.link.WatchLink
import timber.log.Timber

/**
 * One link's countdown timer (`18`), in the size that link's protocol writes. The read is a command job on this link,
 * its answer comes from this link's watch only and is kept in this link's cache. Made only by its link's [LinkIO].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkTimer internal constructor(private val link: WatchLink) {
    private val timer = LinkAnswer<Int>()

    suspend fun request(requestString: String = "18"): Int =
        link.cache.request(requestString) { key -> timer.ask { link.request(key) } }

    fun set(seconds: Int) {
        link.cache.set("18") { sendToWatchSet("{action: \"SET_TIMER\", value: $seconds}") }
    }

    fun onReceived(data: String) {
        TimerIOFunctional.decode(data).map { it.totalSeconds }.fold(
            onSuccess = { timer.complete(it) },
            onFailure = {
                Timber.e(it, "Failed to decode timer data")
                timer.fail(it)
            },
        )
    }

    /** The read of this link's timer ("GET_TIMER"). */
    fun sendToWatch() {
        link.command(GetSetMode.GET, TimerIOFunctional.buildFetchCommand())
    }

    /** The timer write ("SET_TIMER"), in the size this link's protocol writes. */
    fun sendToWatchSet(message: String) {
        TimerIOFunctional.buildSetCommand(message, link.watchInfo.info.protocol.getTimerSize()).fold(
            onSuccess = { link.command(GetSetMode.SET, it) },
            onFailure = { Timber.e("Failed to set timer: ${it.message}") },
        )
    }
}
