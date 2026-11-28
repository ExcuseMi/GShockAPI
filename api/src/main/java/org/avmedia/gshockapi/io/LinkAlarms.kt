package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.model.Alarm
import timber.log.Timber

/**
 * One link's alarms (`15`, `16`), as many as that link's model has. The reads are command jobs on this
 * link, the answers come from this link's watch only and are kept in this link's cache. Made only by its link's [LinkIO].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkAlarms internal constructor(private val link: WatchLink) {
    private val alarms = LinkAnswer<ArrayList<Alarm>>()
    private val received = ArrayList<Alarm>()

    suspend fun request(): ArrayList<Alarm> =
        link.cache.request("GET_ALARMS") {
            alarms.ask("read alarms") {
                synchronized(received) { received.clear() }
                sendToWatch()
            }
        }

    fun set(list: ArrayList<Alarm>) {
        if (list.isEmpty()) {
            Timber.e("Alarm model not initialised!")
            return
        }
        link.cache.set("GET_ALARMS") { sendToWatchSet("{action: \"SET_ALARMS\", value: ${Gson().toJson(list)} }") }
    }

    /** `15` (the first alarm) goes first, `16` (the others) after it, in whichever order they came. */
    fun onReceived(data: String) {
        val parsed = AlarmsIOFunctional.parseReceivedAlarms(data, link.watchInfo.info)
        val complete = synchronized(received) {
            received.addAll(if (parsed.size == 1) 0 else received.size, parsed)
            if (AlarmsIOFunctional.isAlarmCountComplete(received.size, link.watchInfo.info.alarmCount)) ArrayList(received) else null
        }
        complete?.let { alarms.complete(it) }
    }

    /** The read of this link's alarms ("GET_ALARMS"). */
    fun sendToWatch() = send(AlarmsIOFunctional.buildFetchCommands(link.watchInfo.info.alarmCount))

    /** The write ("SET_ALARMS", the alarms in `value`). */
    fun sendToWatchSet(message: String) {
        AlarmsIOFunctional.buildSetCommand(message, link.watchInfo.info.alarmCount)
            .onSuccess { send(it) }
            .onFailure { Timber.e("Failed to set alarms: ${it.message}") }
    }

    private fun send(actions: List<BLEAction>) = actions.forEach { action ->
        when (action) {
            is BLEAction.Write -> link.command(action.mode, action.data)
        }
    }
}
