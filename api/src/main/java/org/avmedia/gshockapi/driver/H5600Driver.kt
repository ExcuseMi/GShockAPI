package org.avmedia.gshockapi.driver

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.avmedia.gshockapi.link.LinkState
import org.avmedia.gshockapi.model.Alarm

/**
 * The H5600 family: the data file ([file], docs/drivers/h5600.json) for the simple settings, and code for what it
 * cannot say. The code parts hand over to today's H5600 code on the link (time sync, the LifeLog transfer, battery
 * steps, alarm writes); nothing of it is copied or changed here.
 */
@RequiresApi(Build.VERSION_CODES.O)
class H5600Driver(file: DataFile) : DataDriver(file) {
    private fun faces(conn: WatchConnection): Int = file.model(conn.name)?.faces ?: 3

    private fun linkOf(conn: WatchConnection): LinkConnection? = conn as? LinkConnection

    private fun needsLink(key: Key<*>) = DriverResult.Unsupported(key.id, "${key.id} needs the link engine")

    override suspend fun <T : Any> get(conn: WatchConnection, key: Key<T>): DriverResult<T> {
        unsupported(key, conn)?.let { return it }
        val value: Any = when (key) {
            Keys.STEPS_TODAY -> {
                val link = linkOf(conn) ?: return needsLink(key)
                link.api.getStepCount(peek = true).currentDaySteps ?: return DriverResult.Failed(key.id, "No step count yet.")
            }
            Keys.BATTERY -> (linkOf(conn) ?: return needsLink(key)).api.getBatteryLevel()
            Keys.TIME -> return DriverResult.Unsupported(key.id, "the time is written, not read")
            else -> return super.get(conn, key)
        }
        return DriverResult.Ok(key.type.cast(value)!!)
    }

    override suspend fun <T : Any> set(conn: WatchConnection, key: Key<T>, value: T): DriverResult<Unit> {
        unsupported(key, conn)?.let { return it }
        return when (key) {
            Keys.TIME -> {
                val link = linkOf(conn) ?: return needsLink(key)
                val sync = value as TimeSync
                runCatching { link.api.setTime(sync.timeZone, sync.timeMs) }
                    .fold({ DriverResult.Ok(Unit) }, { DriverResult.Failed(key.id, it.message ?: "The time was not set.") })
            }
            // Today's alarm write sends whole new frames (no read-modify-write); kept as it is until both agree.
            Keys.ALARMS -> {
                val link = linkOf(conn) ?: return needsLink(key)
                @Suppress("UNCHECKED_CAST")
                val slots = value as List<AlarmSlot>
                link.api.setAlarms(ArrayList(slots.map { Alarm(it.hour, it.minute, it.enabled, it.hourlyChime, it.oneTime) }))
                DriverResult.Ok(Unit)
            }
            Keys.FACE ->
                if ((value as Int) > faces(conn)) DriverResult.Unsupported(key.id, "${DataFile.modelName(conn.name)} has faces 1 to ${faces(conn)}")
                else super.set(conn, key, value)
            else -> super.set(conn, key, value)
        }
    }

    /** The connection changes, and the button of each new connection. */
    override fun events(conn: WatchConnection): Flow<WatchEvent> {
        val link = conn as? LinkConnection ?: return super.events(conn)
        val buttons = conn.state.filter { it == LinkState.READY }.map { WatchEvent.ButtonPressed(link.button()) }
        val workouts = link.api.workouts.map { WatchEvent.Data("workout", it) }
        return merge(super.events(conn), buttons, workouts)
    }
}
