package org.avmedia.gshockapi

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.ConnectionKeepAlive
import org.avmedia.gshockapi.casio.NotificationSender
import org.avmedia.gshockapi.io.AppInfoIOFunctional
import org.avmedia.gshockapi.io.AppNotificationIOFunctional
import org.avmedia.gshockapi.io.IO
import org.avmedia.gshockapi.io.LinkH5600Settings
import org.avmedia.gshockapi.io.SafeSettingWrite
import org.avmedia.gshockapi.io.SettingWriteResult
import org.avmedia.gshockapi.io.TimeAdjustmentInfo
import org.avmedia.gshockapi.io.WatchConditionIO
import org.avmedia.gshockapi.io.WorkoutRunResult
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.model.Alarm
import org.avmedia.gshockapi.model.Event
import org.avmedia.gshockapi.model.ExerciseProfile
import org.avmedia.gshockapi.model.Settings
import org.avmedia.gshockapi.model.StepCounterData
import org.avmedia.gshockapi.model.WatchFaceItem
import org.avmedia.gshockapi.model.WorkoutSession
import org.avmedia.gshockapi.protocols.H5600Protocol
import org.avmedia.gshockapi.protocols.WatchProtocol
import timber.log.Timber
import java.time.ZoneId

/**
 * The public calls on one watch: every read, write and answer runs on that watch's own [WatchLink], never on another
 * watch's. Reached by the watch's key: `links.api(key)`, `links[key]?.api` or `GShockAPI.watch(key)`; made only by
 * its link.
 *
 * Only H5600-family watches are multi-link for now: the steps that only a single-watch adapter has (the GW-B5600 and
 * MTG-B1000 time code, the Oceanus script, the exercise profile, run actions) run on the single-watch link alone and
 * throw [UnsupportedOperationException] on any other link.
 */
@RequiresApi(Build.VERSION_CODES.O)
class WatchApi internal constructor(private val link: WatchLink) {
    /** The key of this watch in its registry. */
    val key: String get() = link.key

    /** What this watch is (name, model, address, its model's features). */
    val info: LinkWatchInfo get() = link.watchInfo

    private val protocol: WatchProtocol get() = link.watchInfo.info.protocol

    // Connection start

    /**
     * The per-watch part of a connection's start: app information, the button info, the decision on it
     * ([InitCheck.decide]) and, when the connection carries on, the model's own first steps.
     */
    suspend fun initialize(): InitCheck.Decision {
        try {
            if (withTimeoutOrNull(INITIALIZATION_REQUEST_TIMEOUT_MS) { getAppInfo() } == null) {
                Timber.w("Timed out waiting for app info; continuing watch initialization")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.e(error, "Failed to read app info; continuing watch initialization")
        }
        val button =
            try {
                Timber.i("Requesting watch button info")
                withTimeoutOrNull(InitCheck.BUTTON_TIMEOUT_MS) { getPressedButton() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Failed to read button info")
                null
            }
        val setup = link.connection.setup
        val decision = InitCheck.decide(button, setup.isAutomaticConnect(), kindKnown = setup.lastKind != null)
        decision.log?.let {
            Timber.w(it)
            ConnectionKeepAlive.log(it)
        }
        if (decision.complete && decision.error == null) {
            Timber.i("Watch button info received: $button")
            try {
                protocol.onInitialized(link)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Watch protocol initialization failed")
            }
        }
        return decision
    }

    // Button info

    suspend fun getPressedButton(): IO.WatchButton {
        val value = link.button.request()
        link.button.put(value)
        return value
    }

    fun isActionButtonPressed(): Boolean = link.button.get() == IO.WatchButton.LOWER_RIGHT

    fun isAlwaysConnectedConnectionPressed(): Boolean = link.button.get() == IO.WatchButton.ALLAYS_CONNECTED_CONNECTION

    fun isNormalButtonPressed(): Boolean = link.button.get() == IO.WatchButton.LOWER_LEFT

    fun isAutoTimeStarted(): Boolean = link.button.get() == IO.WatchButton.NO_BUTTON

    fun isFindPhoneButtonPressed(): Boolean = link.button.get() == IO.WatchButton.FIND_PHONE

    // Name, condition, error

    suspend fun getWatchName(): String = link.io.status.requestName()

    suspend fun getError(): String = link.io.status.lastError()

    suspend fun getWatchCondition(): WatchConditionIO.WatchConditionValue =
        link.io.status.requestCondition(protocol.getWatchConditionRequest())

    suspend fun getBatteryLevel(): Int = protocol.getBatteryLevel(link)

    suspend fun getWatchTemperature(): Int = protocol.getWatchTemperature(link)

    // Time

    suspend fun getDSTWatchState(state: IO.DstState): String = link.time.requestDstWatchState(state)

    suspend fun getDSTForWorldCities(cityNumber: Int): String = link.time.requestDstForWorldCities(cityNumber)

    suspend fun getWorldCities(cityNumber: Int): String = link.time.requestWorldCities(cityNumber)

    suspend fun getHomeTime(): String = protocol.getHomeTime(link)

    /** Sets this watch's time (and its home time zone to [timeZone]); an unknown zone writes nothing. */
    suspend fun setTime(timeZone: String, timeMs: Long? = null, offsetFormSystemTime: Long? = null) {
        if (!ZoneId.getAvailableZoneIds().contains(timeZone)) {
            Timber.e("setTime: Invalid timezone $timeZone passed")
            ProgressEvents.onNext("ApiError")
            return
        }
        link.time.setTimezone(timeZone)
        protocol.setTime(link, timeMs, offset = offsetFormSystemTime)
    }

    suspend fun getTimeAdjustment(): TimeAdjustmentInfo = protocol.getTimeAdjustment(link)

    // Steps and LifeLog (an H5600 reads them with the LifeLog transfer)

    suspend fun getStepCount(peek: Boolean = true): StepCounterData = protocol.getStepCount(link, peek)

    suspend fun getStepSummary(): Int = getStepCount(peek = false).currentDaySteps ?: 0

    /** What this watch's LifeLog read is doing, for a progress line (null when idle). */
    val lifeLogStatus: StateFlow<String?> get() = link.transfers.lifeLog.status

    val isLifeLogActive: Boolean get() = link.transfers.lifeLog.isActive

    // Workouts (H5600 family)

    /** Where this watch's downloaded workouts go; a read starts only with one. True from it: stored. */
    var workoutSink: (suspend (WorkoutSession) -> Boolean)?
        get() = link.transfers.workouts.sink
        set(value) { link.transfers.workouts.sink = value }

    val workouts: SharedFlow<WorkoutSession> get() = link.transfers.workouts.workouts

    val workoutStatus: StateFlow<String?> get() = link.transfers.workouts.status

    val isWorkoutReadActive: Boolean get() = link.transfers.workouts.isActive

    /** Reads this watch's new workouts ([userAsked]: the button, at user priority). Null when it did not start. */
    suspend fun downloadWorkouts(userAsked: Boolean = true): WorkoutRunResult? = link.transfers.workouts.download(userAsked)

    // Tide (H5600 family)

    /** Sends one tide record to this watch; null when it took it, else the reason. */
    suspend fun sendTide(record: ByteArray): String? = link.transfers.tide.send(record)

    // Timer, alarms, exercise profile, reminders

    suspend fun getTimer(): Int = protocol.getTimer(link)

    fun setTimer(timerValue: Int) = protocol.setTimer(link, timerValue)

    suspend fun getAlarms(): ArrayList<Alarm> = protocol.getAlarms(link)

    fun setAlarms(alarms: ArrayList<Alarm>) = protocol.setAlarms(link, alarms)

    suspend fun getExerciseProfile(): ExerciseProfile = protocol.getExerciseProfile(link)

    fun setExerciseProfile(profile: ExerciseProfile) = protocol.setExerciseProfile(link, profile)

    suspend fun getEventsFromWatch(): ArrayList<Event> = ArrayList((1..5).map { getEventFromWatch(it) })

    suspend fun getEventFromWatch(eventNumber: Int): Event = link.io.events.request(eventNumber)

    fun setEvents(events: ArrayList<Event>) {
        if (!link.watchInfo.info.hasReminders) {
            Timber.i("setEvents: ${link.watchInfo.model} has no reminders; not written")
            return
        }
        link.io.events.set(events)
    }

    fun clearEvents() {
        if (!link.watchInfo.info.hasReminders) return
        link.io.events.clearAll()
    }

    // Settings

    suspend fun getSettings(): Settings = protocol.getSettings(link)

    fun setSettings(settings: Settings) = protocol.setSettings(link, settings)

    suspend fun getBasicSettings(): Settings = protocol.getBasicSettings(link)

    /** The H5600-family settings (sound, watch face, smart alarm, home location) of this watch. */
    val h5600Settings: LinkH5600Settings get() = link.io.h5600Settings

    suspend fun getWatchFaceItem(): WatchFaceItem? = h5600Settings.getWatchFaceItem()

    suspend fun setWatchFaceItem(item: WatchFaceItem): WatchFaceItem? = h5600Settings.setWatchFaceItem(item)

    suspend fun getH5600WatchFaceNumber(): Int? = h5600Settings.getWatchFaceNumber()

    suspend fun setH5600WatchFaceNumber(face: Int) = h5600Settings.setWatchFaceNumber(face)

    // Settings by key (the watch's driver, see org.avmedia.gshockapi.driver)

    /** This watch bound to its driver: reads and writes are jobs on this watch's link, writes the checked read-modify-write. */
    val driver: org.avmedia.gshockapi.driver.BoundWatch
        get() = org.avmedia.gshockapi.driver.Drivers.bind(org.avmedia.gshockapi.driver.LinkConnection(link))

    /** True when this watch's driver has [key] (`Keys.AUTO_LIGHT`, ...). */
    fun has(key: org.avmedia.gshockapi.driver.Key<*>): Boolean = driver.has(key)

    /** The value of [key] on this watch; Unsupported (nothing sent) when its driver does not have it. */
    suspend fun <T : Any> get(key: org.avmedia.gshockapi.driver.Key<T>): org.avmedia.gshockapi.driver.DriverResult<T> = driver.get(key)

    /** Writes [value] to [key] on this watch: read, change only its bytes, write, read back. */
    suspend fun <T : Any> set(key: org.avmedia.gshockapi.driver.Key<T>, value: T): org.avmedia.gshockapi.driver.DriverResult<Unit> = driver.set(key, value)

    /** One raw settings frame (`0x45`, `0x4F`, ...) of this watch, null when it did not answer. */
    suspend fun readSettingFrame(code: Int): ByteArray? = link.rawSettings.readFrame(code)

    /** A checked change of the bytes [touched] of the settings frame [code] of this watch. */
    suspend fun modifySetting(code: Int, touched: Set<Int>, change: (ByteArray) -> ByteArray?): SettingWriteResult =
        link.rawSettings.modifySafe(code, touched, change)

    /** This watch's checked settings writer; [read] gives the frame a write starts from. */
    fun settingWriter(read: suspend (Int) -> ByteArray? = { link.rawSettings.readFrame(it, fresh = true) }): SafeSettingWrite = link.rawSettings.safeWriter(read)

    // App information and scratchpad

    suspend fun getAppInfo(): String = link.io.appInfo.request()

    suspend fun setScratchpadData(data: ByteArray) {
        if (!link.watchInfo.info.hasScratchpad) {
            Timber.i("setScratchpadData: ${link.watchInfo.model} has no scratchpad; not written")
            return
        }
        link.io.appInfo.setUserData(data)
    }

    suspend fun getScratchpadData(): ByteArray {
        if (!link.watchInfo.info.hasScratchpad) {
            return ByteArray(AppInfoIOFunctional.BUFFER_SIZE - AppInfoIOFunctional.USER_DATA_START_INDEX)
        }
        link.io.appInfo.request()
        return link.io.appInfo.getUserData()
    }

    fun isScratchpadReset(): Boolean = !link.watchInfo.info.hasScratchpad || link.io.appInfo.wasScratchpadReset

    // Phone notifications

    fun sendAppNotification(notification: AppNotification) {
        queueAppNotification(notification)
    }

    suspend fun sendAppNotificationAndWait(notification: AppNotification): NotificationSender.Outcome =
        queueAppNotification(notification).await()

    private fun queueAppNotification(notification: AppNotification): Deferred<NotificationSender.Outcome> {
        val encoded = AppNotificationIOFunctional.encodeNotificationPacket(NotificationIds.assign(notification))
        val encrypted = AppNotificationIOFunctional.xorEncodeBuffer(encoded, 0xFF)
        val sender = link.notifications.sender
        sender.spacingMs = if (protocol === H5600Protocol) H5600_TEXT_SPACING_MS else TEXT_SPACING_MS
        return sender.send(encrypted)
    }

    /** True when this watch shows phone notifications (its model has them and, once known, the characteristic is there). */
    fun supportsAppNotifications(): Boolean =
        (link.gatt.serviceFound(GetSetMode.NOTIFY) ?: true) && link.watchInfo.info.hasAppNotifications

    // Outbound messages

    /** An outbound action (`{action: "GET_ALARMS"}`) carried out on this watch. */
    fun sendMessage(message: String) {
        // The single-watch link also carries the actions that are not per link yet (the exercise profile).
        if (org.avmedia.gshockapi.casio.WatchLink.isTheOne(link)) org.avmedia.gshockapi.casio.MessageDispatcher.sendToWatch(message)
        else link.dispatcher.sendToWatch(message)
    }

    fun resetHand() = sendMessage("{action: \"RESET_HAND\", value: \"\"}")

    private companion object {
        const val INITIALIZATION_REQUEST_TIMEOUT_MS = 3_000L

        /** An H5600 needs 2 s between two texts, the other models 0.5 s. */
        const val H5600_TEXT_SPACING_MS = 2_000L
        const val TEXT_SPACING_MS = 500L
    }
}
