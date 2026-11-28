// The driver replaces the per-link single-purpose calls and runs them: their deprecation points here.
@file:Suppress("DEPRECATION")

package org.avmedia.gshockapi.driver

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.link.LinkState
import org.avmedia.gshockapi.model.Alarm
import org.avmedia.gshockapi.protocols.AnalogueProtocol
import org.avmedia.gshockapi.protocols.MipProtocol
import org.avmedia.gshockapi.protocols.OceanusProtocol
import org.avmedia.gshockapi.protocols.StandardProtocol
import org.avmedia.gshockapi.protocols.WatchProtocol

/**
 * A family that speaks one of today's protocols ([protocol]): a code driver that hands every key to that protocol's
 * calls on the link, with the library's model table as its capabilities. It matches only the exact model names of the
 * table whose protocol is [protocol]. No data file yet: these families' setting frames have no recorded frames in the
 * tests to check a file against, so their settings stay the protocol's own calls.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ProtocolDriver(override val id: String, private val protocol: WatchProtocol) : WatchDriver {
    override fun matches(advertisedName: String): Boolean =
        WatchInfo.knows(advertisedName) && WatchInfo.resolvedFor(advertisedName).second === protocol

    override fun capabilities(conn: WatchConnection): Set<String> = capabilitiesOf(conn.name)

    /** The keys of [advertisedName] from the model table; empty when this driver does not speak its protocol. */
    fun capabilitiesOf(advertisedName: String): Set<String> {
        if (!matches(advertisedName)) return emptySet()
        val info = WatchInfo.infoFor(advertisedName)
        return buildSet {
            add(Keys.TIME.id)
            add(Keys.BUTTON.id)
            if (info.alarmCount > 0) add(Keys.ALARMS.id)
            if (info.hasHomeTime) add(Keys.HOME_TIME.id).also { add(Keys.DST) }
            if (info.worldCities) add(Keys.WORLD_CITIES)
            if (info.hasBatteryLevel) add(Keys.BATTERY.id)
            if (info.hasStepCounter) add(Keys.STEPS_TODAY.id)
            if (info.hasAppNotifications) add(Keys.NOTIFICATION.id)
        }
    }

    private fun unsupported(key: Key<*>, conn: WatchConnection): DriverResult.Unsupported? =
        if (Keys.capabilityOf(key.id) in capabilities(conn)) null else DriverResult.Unsupported(key.id, "${DataFile.modelName(conn.name)} does not have ${key.id}")

    override suspend fun <T : Any> get(conn: WatchConnection, key: Key<T>): DriverResult<T> {
        unsupported(key, conn)?.let { return it }
        LinkKeys.get(conn, key)?.let { return it }
        if (key == Keys.ALARMS) {
            val api = (conn as? LinkConnection ?: return LinkKeys.needsLink(key)).api
            return LinkKeys.code(key) { api.getAlarms().take(alarmCount(conn)).map { AlarmSlot(it.hour, it.minute, it.enabled, it.oneTime, it.hasHourlyChime) } }
        }
        return DriverResult.Unsupported(key.id, "no code for ${key.id}")
    }

    override suspend fun <T : Any> set(conn: WatchConnection, key: Key<T>, value: T): DriverResult<Unit> {
        unsupported(key, conn)?.let { return it }
        if (!key.writable) return DriverResult.Unsupported(key.id, "${key.id} is read-only")
        LinkKeys.set(conn, key, value)?.let { return it }
        if (key == Keys.ALARMS) {
            val api = (conn as? LinkConnection ?: return LinkKeys.needsLink(key)).api
            @Suppress("UNCHECKED_CAST")
            val slots = value as List<AlarmSlot>
            // Today's alarm write: whole new frames, not confirmed by the watch.
            return LinkKeys.code(key) { api.setAlarms(ArrayList(slots.map { Alarm(it.hour, it.minute, it.enabled, it.hourlyChime, it.oneTime) })) }
        }
        return DriverResult.Unsupported(key.id, "no code for ${key.id}")
    }

    private fun alarmCount(conn: WatchConnection): Int = WatchInfo.infoFor(conn.name).alarmCount

    /** The connection changes, and on the link the button of each new connection. */
    override fun events(conn: WatchConnection): Flow<WatchEvent> {
        val state = conn.state.map { if (it == LinkState.DISCONNECTED) WatchEvent.Disconnected else WatchEvent.Connected }.distinctUntilChanged()
        val link = conn as? LinkConnection ?: return state
        return merge(state, conn.state.filter { it == LinkState.READY }.map { WatchEvent.ButtonPressed(link.button()) })
    }

    companion object {
        /** The library's protocol families other than the H5600's (which has its data file and [H5600Driver]). */
        val BUILT_IN: List<ProtocolDriver> by lazy {
            listOf(
                ProtocolDriver("oceanus", OceanusProtocol),
                ProtocolDriver("mip", MipProtocol),
                ProtocolDriver("analogue", AnalogueProtocol),
                ProtocolDriver("standard", StandardProtocol),
            )
        }
    }
}
