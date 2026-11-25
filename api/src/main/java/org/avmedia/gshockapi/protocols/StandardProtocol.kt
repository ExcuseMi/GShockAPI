package org.avmedia.gshockapi.protocols

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.io.ExerciseIO
import org.avmedia.gshockapi.io.HomeTimeIOFunctional
import org.avmedia.gshockapi.io.TimeAdjustmentInfo
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.model.Alarm
import org.avmedia.gshockapi.model.ExerciseProfile
import org.avmedia.gshockapi.model.Settings
import org.avmedia.gshockapi.model.StepCounterData
import org.avmedia.gshockapi.utils.Utils

@RequiresApi(Build.VERSION_CODES.O)
open class StandardProtocol : WatchProtocol {
    companion object : StandardProtocol()

    override fun extractKey(data: String): Int? {
        return runCatching { Utils.toIntArray(data)[0] }.getOrNull()
    }

    override fun unwrapPayload(
        data: String,
        key: Int,
    ): String {
        return data
    }

    override fun getWatchConditionRequest(): String {
        return "28"
    }

    override suspend fun setTime(
        link: WatchLink,
        timeMs: Long?,
        offset: Long?,
    ) {
        // The link's time owns the complete setup sequence and tailors it to the
        // connected model.  Performing setup here as well used to force a
        // world-city (0x1f) transaction for watches such as the ABL-100WE,
        // which do not implement that register.  The watch replies with
        // FF 81 1F and the actual current-time command never follows.
        link.time.set(timeMs, offset)
    }

    override suspend fun getStepCount(link: WatchLink, peek: Boolean): StepCounterData = link.io.stepCounter.request(peek)

    override suspend fun getTimer(link: WatchLink): Int {
        return link.io.timer.request(getTimerRequest())
    }

    override fun setTimer(link: WatchLink, timerValue: Int) {
        link.io.timer.set(timerValue)
    }

    override fun getTimerRequest(): String {
        return "18"
    }

    override fun getTimerSize(): Int {
        return 7
    }

    override suspend fun getHomeTime(link: WatchLink): String {
        val raw = link.time.requestWorldCities(0)
        return HomeTimeIOFunctional.parseHomeCity(raw, 2)
    }

    override suspend fun getBatteryLevel(link: WatchLink): Int {
        return link.io.status.requestCondition(getWatchConditionRequest()).batteryLevel
    }

    override suspend fun getWatchTemperature(link: WatchLink): Int {
        return link.io.status.requestCondition(getWatchConditionRequest()).temperature
    }

    override suspend fun getAlarms(link: WatchLink): ArrayList<Alarm> {
        return link.io.alarms.request()
    }

    override fun setAlarms(link: WatchLink, alarms: ArrayList<Alarm>) {
        link.io.alarms.set(alarms)
    }

    /** The exercise profile has only its single-watch adapter ([ExerciseIO]) so far. */
    override suspend fun getExerciseProfile(link: WatchLink): ExerciseProfile {
        singleWatchOnly(link, "The exercise profile")
        return ExerciseIO.request()
    }

    override fun setExerciseProfile(link: WatchLink, profile: ExerciseProfile) {
        singleWatchOnly(link, "The exercise profile")
        ExerciseIO.set(profile)
    }

    override suspend fun getSettings(link: WatchLink): Settings {
        // Basic watch settings (0x13) only exist on watches that have them.
        val settings = getBasicSettings(link)
        val timeAdjustment = link.io.timeAdjustment.request()
        settings.timeAdjustment = timeAdjustment.isTimeAdjustmentSet
        settings.adjustmentTimeMinutes = timeAdjustment.adjustmentTimeMinutes
        return settings
    }

    override fun setSettings(link: WatchLink, settings: Settings) {
        if (link.watchInfo.info.hasWatchSettings) link.io.settings.set(settings)
        link.io.timeAdjustment.set(settings)
    }

    override suspend fun getBasicSettings(link: WatchLink): Settings {
        return if (link.watchInfo.info.hasWatchSettings) link.io.settings.request() else Settings()
    }

    override suspend fun getTimeAdjustment(link: WatchLink): TimeAdjustmentInfo {
        return link.io.timeAdjustment.request()
    }
}
