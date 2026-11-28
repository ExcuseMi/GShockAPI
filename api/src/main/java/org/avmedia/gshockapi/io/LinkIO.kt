package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioConstants.CHARACTERISTICS
import org.avmedia.gshockapi.link.WatchLink
import org.json.JSONObject

/** A read of [key] ("23", "1D00") as a command job on this link; the job, so a read can end when it fails. */
internal fun org.avmedia.gshockapi.link.WatchLink.request(key: String) =
    command(GetSetMode.GET, IOFunctional.toCasioCmd(key))

/**
 * One link's reads and writes of its watch's own values (settings, alarms, timer, reminders, name, battery, app
 * information, activity record): each object answers only frames of this link's watch and writes only on this link.
 * [routes] are the codes they answer, [senders] the outbound actions ("GET_ALARMS", "SET_TIME") they carry out.
 * Made only by its [WatchLink].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkIO internal constructor(link: WatchLink) {
    val status = LinkWatchStatus(link)
    val appInfo = LinkAppInfo(link)
    val h5600Settings = LinkH5600Settings(link)
    val settings = LinkBasicSettings(link, h5600Settings)
    val timeAdjustment = LinkTimeAdjustment(link)
    val alarms = LinkAlarms(link)
    val timer = LinkTimer(link)
    val events = LinkEvents(link)
    val stepCounter = LinkStepCounter(link)

    /** The watch refused a request (`FF 81 <code>`): a read of `13` or `11` that waits ends with its defaults. */
    private fun onError(data: String) {
        status.onError(data)
        if (data.contains("81 13")) settings.onRunError()
        if (data.contains("81 11")) timeAdjustment.onRunError()
    }

    /** The answers of this link's watch, by code. */
    val routes: Map<Int, (String) -> Unit> = mapOf(
        CHARACTERISTICS.CASIO_SETTING_FOR_ALM.code to alarms::onReceived,
        CHARACTERISTICS.CASIO_SETTING_FOR_ALM2.code to alarms::onReceived,
        CHARACTERISTICS.CASIO_REMINDER_TIME.code to events::onTime,
        CHARACTERISTICS.CASIO_REMINDER_TITLE.code to events::onTitle,
        CHARACTERISTICS.CASIO_TIMER.code to timer::onReceived,
        CHARACTERISTICS.CASIO_WATCH_NAME.code to status::onName,
        CHARACTERISTICS.CASIO_WATCH_CONDITION.code to status::onCondition,
        CHARACTERISTICS.CASIO_APP_INFORMATION.code to appInfo::onReceived,
        CHARACTERISTICS.CASIO_SETTING_FOR_BASIC.code to settings::onReceived,
        CHARACTERISTICS.CASIO_SETTING_FOR_BLE.code to timeAdjustment::onReceived,
        CHARACTERISTICS.CASIO_ACTIVITY_RECORD.code to stepCounter::onReceived,
        CHARACTERISTICS.ERROR.code to ::onError,
    )

    /** The outbound actions of this link, by the `action` of the message. */
    val senders: Map<String, (String) -> Unit> = mapOf(
        "GET_ALARMS" to { _: String -> alarms.sendToWatch() },
        "SET_ALARMS" to alarms::sendToWatchSet,
        "SET_REMINDERS" to events::sendToWatchSet,
        "GET_SETTINGS" to { _: String -> settings.sendToWatch() },
        "SET_SETTINGS" to settings::sendToWatchSet,
        "GET_TIME_ADJUSTMENT" to { _: String -> timeAdjustment.sendToWatch() },
        "SET_TIME_ADJUSTMENT" to timeAdjustment::sendToWatchSet,
        "GET_TIMER" to { _: String -> timer.sendToWatch() },
        "SET_TIMER" to timer::sendToWatchSet,
        "SET_TIME" to { m: String -> link.time.writeTime(JSONObject(m).get("value") as Long) },
    )
}
