package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.model.Event
import org.avmedia.gshockapi.utils.Utils
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * One link's reminders: each is a title (`30 n`) and a time (`31 n`), read one after the other and joined into an
 * [Event]. The reads are command jobs on this link, the answers come from this link's watch only and are kept in this
 * link's cache. Made only by its link's [LinkIO].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkEvents internal constructor(private val link: WatchLink) {
    private val title = LinkAnswer<JSONObject>()
    private val time = LinkAnswer<JSONObject>()

    suspend fun request(eventNumber: Int): Event {
        val titleJson = link.cache.request("30$eventNumber") { key -> title.ask { link.request(key) } }
        val timeJson = link.cache.request("31$eventNumber") { key -> time.ask { link.request(key) } }
        return Event(JSONObject(titleJson.toString()).apply { timeJson.keys().forEach { put(it, timeJson.get(it)) } })
    }

    /** Writes [events]: the enabled ones first, then the others, cut or padded with empty ones to the watch's five. */
    fun set(events: ArrayList<Event>) {
        if (events.isEmpty()) {
            Timber.d("Events model not initialised! Cannot set reminders")
            return
        }
        val ordered = (events.filter { it.enabled } + events.filter { !it.enabled }).take(MAX_REMINDERS)
        val padded = ordered + List(MAX_REMINDERS - ordered.size) { Event(JSONObject(EMPTY_EVENT_JSON)) }
        sendToWatchSet("{action: \"SET_REMINDERS\", value: ${Gson().toJson(ArrayList(padded))} }")
    }

    fun onTime(data: String) {
        EventsIOFunctional.reminderTimeToJson(data).fold(
            onSuccess = { time.complete(it) },
            onFailure = {
                Timber.e("Failed to decode reminder time: ${it.message}")
                time.complete(JSONObject())
            },
        )
    }

    fun onTitle(data: String) {
        EventsIOFunctional.reminderTitleToJson(data).fold(
            onSuccess = { title.complete(it) },
            onFailure = {
                Timber.e("Failed to decode reminder title: ${it.message}")
                title.complete(JSONObject())
            },
        )
    }

    /** The write ("SET_REMINDERS", the reminders in `value`, the first is number 1). */
    fun sendToWatchSet(message: String) {
        val reminders = JSONObject(message).get("value") as JSONArray
        for (index in 0 until reminders.length()) {
            val reminder = reminders.getJSONObject(index)
            val number = index + 1
            link.cache.set("30$number") { writeTitle(number, reminder) }
            link.cache.set("31$number") { writeTime(number, reminder) }
        }
    }

    private fun writeTitle(number: Int, reminder: JSONObject) {
        EventsIOFunctional.reminderTitleFromJson(reminder).fold(
            onSuccess = { link.command(GetSetMode.SET, Utils.byteArrayOfInts(CasioConstants.CHARACTERISTICS.CASIO_REMINDER_TITLE.code, number) + it) },
            onFailure = { Timber.e("Failed to encode reminder title: ${it.message}") },
        )
    }

    private fun writeTime(number: Int, reminder: JSONObject) {
        EventsIOFunctional.reminderTimeFromJson(reminder).fold(
            onSuccess = {
                link.command(GetSetMode.SET, Utils.byteArrayOfIntArray(intArrayOf(CasioConstants.CHARACTERISTICS.CASIO_REMINDER_TIME.code, number) + it))
            },
            onFailure = { Timber.e("Failed to encode reminder time: ${it.message}") },
        )
    }

    /** Empties the five reminders of this link's watch. */
    fun clearAll() {
        for (index in 1..MAX_REMINDERS) {
            link.command(GetSetMode.SET, Utils.byteArrayOfInts(CasioConstants.CHARACTERISTICS.CASIO_REMINDER_TITLE.code, index) + ByteArray(18))
            link.cache.remove("30$index")
            link.command(GetSetMode.SET, Utils.byteArrayOfInts(CasioConstants.CHARACTERISTICS.CASIO_REMINDER_TIME.code, index) + ByteArray(9))
            link.cache.remove("31$index")
        }
    }

    private companion object {
        const val MAX_REMINDERS = 5
        const val EMPTY_EVENT_JSON = """
            {
                "title": "",
                "time": {
                    "daysOfWeek": [],
                    "enabled": false,
                    "startDate": { "day": 1, "month": "JANUARY", "year": 2000 },
                    "endDate": { "day": 1, "month": "JANUARY", "year": 2000 },
                    "incompatible": false,
                    "repeatPeriod": "NEVER"
                }
            }
        """
    }
}
