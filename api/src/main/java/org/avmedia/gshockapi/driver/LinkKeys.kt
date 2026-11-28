// The keys replace the per-link single-purpose calls and run them: their deprecation points here.
@file:Suppress("DEPRECATION")

package org.avmedia.gshockapi.driver

import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.coroutines.cancellation.CancellationException
import org.avmedia.gshockapi.AppNotification
import org.avmedia.gshockapi.casio.NotificationSender
import org.avmedia.gshockapi.io.IO

/**
 * The code keys every family has the same way: each hands over to today's per-link call ([LinkConnection.api]),
 * nothing of it is copied or changed here. A connection that is not a link refuses them before anything is sent.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal object LinkKeys {
    private val READS: Set<Key<*>> = setOf(
        Keys.TIME, Keys.HOME_TIME, Keys.BATTERY, Keys.STEPS_TODAY, Keys.LIFELOG, Keys.LIFELOG_PEEK, Keys.SLEEP, Keys.WORKOUTS, Keys.BUTTON,
    )
    private val WRITES: Set<Key<*>> = setOf(Keys.TIME, Keys.NOTIFICATION)

    private fun indexed(key: Key<*>) = key.id.startsWith(Keys.DST + ".") || key.id.startsWith(Keys.WORLD_CITIES + ".")

    fun needsLink(key: Key<*>) = DriverResult.Unsupported(key.id, "${key.id} needs the link engine")

    private fun index(key: Key<*>): Int? = key.id.substringAfterLast('.').toIntOrNull()

    /** Runs today's code for [key]: its answer, or Failed with its message. A cancellation stays a cancellation. */
    inline fun <T : Any> code(key: Key<*>, block: () -> Any): DriverResult<T> = try {
        @Suppress("UNCHECKED_CAST")
        DriverResult.Ok(block() as T)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DriverResult.Failed(key.id, e.message ?: "The watch did not answer.")
    }

    /** [key] read with today's call on the link; null when [key] is not one of these. */
    suspend fun <T : Any> get(conn: WatchConnection, key: Key<T>): DriverResult<T>? {
        if (key !in READS && !indexed(key)) return null
        if (key == Keys.TIME) return DriverResult.Unsupported(key.id, "the time is written, not read")
        val api = (conn as? LinkConnection ?: return needsLink(key)).api
        return when {
            key == Keys.STEPS_TODAY -> code(key) { api.getStepCount(peek = true).currentDaySteps ?: error("No step count yet.") }
            key == Keys.BATTERY -> code(key) { api.getBatteryLevel() }
            // Health data: today's LifeLog and workout transfers, as they are.
            key == Keys.LIFELOG || key == Keys.LIFELOG_PEEK -> code(key) { api.getStepCount(peek = key == Keys.LIFELOG_PEEK) }
            key == Keys.SLEEP -> code(key) { api.getStepCount(peek = true).h5600?.sleep ?: error("The LifeLog read had no sleep.") }
            key == Keys.WORKOUTS -> code(key) { api.downloadWorkouts(userAsked = true) ?: error("The workout read did not start.") }
            key == Keys.BUTTON -> code(key) { conn.button() }
            key == Keys.HOME_TIME -> code(key) { api.getHomeTime() }
            key.id.startsWith(Keys.DST + ".") -> {
                val state = IO.DstState.entries.firstOrNull { it.state == index(key) } ?: return DriverResult.Unsupported(key.id, "no DST state ${index(key)}")
                code(key) { api.getDSTWatchState(state) }
            }
            else -> {
                val i = index(key) ?: return DriverResult.Unsupported(key.id, "no city number")
                code(key) { api.getWorldCities(i) }
            }
        }
    }

    /** [key] written with today's call on the link; null when [key] is not one of these. */
    suspend fun set(conn: WatchConnection, key: Key<*>, value: Any): DriverResult<Unit>? {
        if (key !in WRITES) return null
        val api = (conn as? LinkConnection ?: return needsLink(key)).api
        return when (key) {
            // The zone, DST and cities, then the time stamped ahead by the Bluetooth lead.
            Keys.TIME -> {
                val sync = value as TimeSync
                code(key) { api.setTime(sync.timeZone, sync.timeMs, sync.offsetMs) }
            }
            // Today's notification sender (gate, MTU, chunks, spacing).
            else -> when (val outcome = code<NotificationSender.Outcome>(key) { api.sendAppNotificationAndWait(value as AppNotification) }) {
                is DriverResult.Ok -> if (outcome.value == NotificationSender.Outcome.SENT) DriverResult.Ok(Unit) else DriverResult.Failed(key.id, outcome.value.name)
                is DriverResult.Failed -> outcome
                is DriverResult.Unsupported -> outcome
                is DriverResult.Unconfirmed -> outcome
            }
        }
    }
}
