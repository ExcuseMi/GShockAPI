package org.avmedia.gshockapi.protocols

import org.avmedia.gshockapi.io.RawSettingIO
import org.avmedia.gshockapi.io.H5600SettingsIO
import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.io.AlarmsIO
import org.avmedia.gshockapi.io.AppInfoIO
import org.avmedia.gshockapi.io.ButtonPressedIO
import org.avmedia.gshockapi.io.DstForWorldCitiesIO
import org.avmedia.gshockapi.io.DstWatchStateIO
import org.avmedia.gshockapi.io.ErrorIO
import org.avmedia.gshockapi.io.EventsIO
import org.avmedia.gshockapi.io.ExerciseIO
import org.avmedia.gshockapi.io.GwBx5600TimeIO
import org.avmedia.gshockapi.io.HomeTimeIO
import org.avmedia.gshockapi.io.RunActionsIO
import org.avmedia.gshockapi.io.SettingsIO
import org.avmedia.gshockapi.io.StepCounterIO
import org.avmedia.gshockapi.io.TimeAdjustmentIO
import org.avmedia.gshockapi.io.TimeAdjustmentInfo
import org.avmedia.gshockapi.io.TimerIO
import org.avmedia.gshockapi.io.UnknownIO
import org.avmedia.gshockapi.io.WatchConditionIO
import org.avmedia.gshockapi.io.WatchNameIO
import org.avmedia.gshockapi.io.WorldCitiesIO
import org.avmedia.gshockapi.model.Alarm
import org.avmedia.gshockapi.model.Settings
import org.avmedia.gshockapi.model.StepCounterData
import org.avmedia.gshockapi.link.WatchLink
import java.util.UUID

/** DW-H5600 settings packets: each reply arrives under its packet's first byte. */
val RAW_SETTING_CODES: List<Int> = listOf(
    H5600SettingsIO.SOUND_MODE_CODE, H5600SettingsIO.WATCH_FACE_CODE, H5600SettingsIO.TIME_MANAGER_CODE,
    H5600SettingsIO.SMART_ALARM_CODE, 0x45, 0x4F,
)

@RequiresApi(Build.VERSION_CODES.O)
interface WatchProtocol {
    val dataReceivedHandlers: Map<Int, (String) -> Unit>
        get() =
            mapOf(
                CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_ALM.code to AlarmsIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_ALM2.code to AlarmsIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_EXERCISE.code to ExerciseIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_DST_SETTING.code to DstForWorldCitiesIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_REMINDER_TIME.code to EventsIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_REMINDER_TITLE.code to EventsIO::onReceivedTitle,
                CasioConstants.CHARACTERISTICS.CASIO_TIMER.code to TimerIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_WORLD_CITIES.code to WorldCitiesIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_DST_WATCH_STATE.code to DstWatchStateIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_WATCH_NAME.code to WatchNameIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_WATCH_CONDITION.code to WatchConditionIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_APP_INFORMATION.code to AppInfoIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_BLE_FEATURES.code to ButtonPressedIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_BASIC.code to SettingsIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_BLE.code to TimeAdjustmentIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_ACTIVITY_RECORD.code to StepCounterIO::onReceived,
                CasioConstants.CHARACTERISTICS.ERROR.code to ErrorIO::onReceived,
                CasioConstants.CHARACTERISTICS.FIND_PHONE.code to RunActionsIO::onReceived,
                CasioConstants.CHARACTERISTICS.CMD_SET_TIMEMODE.code to UnknownIO::onReceived,
                CasioConstants.CHARACTERISTICS.CASIO_HOME_TIME.code to HomeTimeIO::onReceived,
                CasioConstants.CHARACTERISTICS.GW_BX5600_SP_DATA_HEADER_03.code to GwBx5600TimeIO::onReceivedStep2,
                CasioConstants.CHARACTERISTICS.GW_BX5600_SP_DATA_HEADER_05.code to GwBx5600TimeIO::onReceivedStep1,
                CasioConstants.CHARACTERISTICS.GW_BX5600_SP_DATA_HEADER_06.code to GwBx5600TimeIO::onReceivedStep3,
            ) + RAW_SETTING_CODES.associateWith { RawSettingIO::onReceived }

    /**
     * The handlers of [link]'s frames: [dataReceivedHandlers] with that link's own objects (its time reads, button info
     * and settings reads) in place of the single-watch ones. A protocol that routes more by link adds its own.
     */
    fun linkHandlers(link: WatchLink): Map<Int, (String) -> Unit> =
        dataReceivedHandlers + linkRoutes(link)

    /** The codes [link]'s own objects answer. */
    fun linkRoutes(link: WatchLink): Map<Int, (String) -> Unit> =
        link.io.routes +
            link.time.routes +
            (CasioConstants.CHARACTERISTICS.CASIO_BLE_FEATURES.code to link.button::onReceived) +
            RAW_SETTING_CODES.associateWith { link.rawSettings::onReceived }

    /** The handler key of [data] on [link], which came on [source] (null when the connection code did not say). */
    fun linkKey(link: WatchLink, data: String, source: UUID?): Int? = extractKey(data)

    fun extractKey(data: String): Int?

    /**
     * Called once per connection of [link], after the connection kind (0x10) is known and before
     * initialization completes. Watches that need extra steps on some connections, such
     * as pairing, override it. Default: nothing.
     */
    suspend fun onInitialized(link: WatchLink) = Unit

    fun unwrapPayload(
        data: String,
        key: Int,
    ): String

    fun getWatchConditionRequest(): String

    /*
     * The calls below run on the [WatchLink] they are given: its own reads, writes and answers. Steps that only a
     * single-watch adapter has (other models' time code, the exercise profile, the Oceanus script) run only on the
     * single-watch link ([singleWatchOnly]); only H5600-family watches are multi-link for now.
     */

    suspend fun setTime(
        link: WatchLink,
        timeMs: Long?,
        offset: Long?,
    )

    suspend fun getTimer(link: WatchLink): Int

    fun setTimer(link: WatchLink, timerValue: Int)

    fun getTimerRequest(): String

    fun getTimerSize(): Int

    suspend fun getHomeTime(link: WatchLink): String

    suspend fun getBatteryLevel(link: WatchLink): Int

    suspend fun getWatchTemperature(link: WatchLink): Int

    suspend fun getAlarms(link: WatchLink): ArrayList<Alarm>

    fun setAlarms(link: WatchLink, alarms: ArrayList<Alarm>)

    suspend fun getExerciseProfile(link: WatchLink): org.avmedia.gshockapi.model.ExerciseProfile

    fun setExerciseProfile(link: WatchLink, profile: org.avmedia.gshockapi.model.ExerciseProfile)

    suspend fun getSettings(link: WatchLink): Settings

    fun setSettings(link: WatchLink, settings: Settings)

    suspend fun getBasicSettings(link: WatchLink): Settings

    suspend fun getTimeAdjustment(link: WatchLink): TimeAdjustmentInfo

    suspend fun getStepCount(link: WatchLink, peek: Boolean): StepCounterData
}

/**
 * A step that only a single-watch adapter has (another model's time code, the exercise profile, the Oceanus script):
 * it runs on the single-watch link only. On any other link it throws, so it never writes to or reads from the wrong watch.
 */
internal fun singleWatchOnly(link: WatchLink, what: String) {
    if (!org.avmedia.gshockapi.casio.WatchLink.isTheOne(link)) {
        throw UnsupportedOperationException("$what runs only on the single-watch link for now: only H5600-family watches are multi-link")
    }
}
