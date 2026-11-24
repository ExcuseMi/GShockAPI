package org.avmedia.gshockapi.io

import CachedIOFunctional
import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.CasioTimeZoneHelper
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.utils.Utils
import timber.log.Timber
import java.time.Clock
import java.util.TimeZone

/**
 * The time of one link's watch: its time zone, its DST, world city and home time reads (`1D`, `1E`, `1F`, `24`) and the
 * time write (`09`). The reads and writes are command jobs on this link, the answers come from this link's watch only
 * ([routes]) and are kept in this link's cache. Made only by its [WatchLink].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkTime internal constructor(private val link: WatchLink) {
    private data class Zone(
        val id: String = TimeZone.getDefault().id,
        val casioZone: CasioTimeZoneHelper.CasioTimeZone = CasioTimeZoneHelper.findTimeZone(TimeZone.getDefault().id),
    )

    @Volatile private var zone = Zone()

    private val dstWatchState = LinkAnswer<String>()
    private val dstForWorldCities = LinkAnswer<String>()
    private val worldCities = LinkAnswer<String>()
    private val homeTime = LinkAnswer<String>()

    /** The answers of this link's watch to its time reads, by code. */
    val routes: Map<Int, (String) -> Unit> = mapOf(
        CasioConstants.CHARACTERISTICS.CASIO_DST_WATCH_STATE.code to ::onDstWatchState,
        CasioConstants.CHARACTERISTICS.CASIO_DST_SETTING.code to ::onDstForWorldCities,
        CasioConstants.CHARACTERISTICS.CASIO_WORLD_CITIES.code to ::onWorldCities,
        CasioConstants.CHARACTERISTICS.CASIO_HOME_TIME.code to ::onHomeTime,
    )

    fun onDstWatchState(data: String) = dstWatchState.complete(data)

    fun onDstForWorldCities(data: String) = dstForWorldCities.complete(data)

    fun onWorldCities(data: String) = worldCities.complete(data)

    fun onHomeTime(data: String) = homeTime.complete(data)

    fun setTimezone(timeZone: String) {
        zone = Zone(timeZone, CasioTimeZoneHelper.findTimeZone(timeZone))
    }

    val timeZone: String get() = zone.id

    /** The time zone this link writes, for the model-specific time code that needs more than the time (coordinates, DST rules). */
    val casioTimezone: CasioTimeZoneHelper.CasioTimeZone get() = zone.casioZone

    private suspend fun read(answer: LinkAnswer<String>, key: String): String =
        link.cache.request(key) { k -> answer.ask { link.command(GetSetMode.GET, IOFunctional.toCasioCmd(k)) } }

    suspend fun requestDstWatchState(state: IO.DstState): String = read(dstWatchState, "1d0${state.state}")

    suspend fun requestDstForWorldCities(cityNumber: Int): String = read(dstForWorldCities, "1e0$cityNumber")

    suspend fun requestWorldCities(cityNumber: Int): String = read(worldCities, "1f0$cityNumber")

    suspend fun requestHomeTime(slot: Int): String = read(homeTime, "240$slot")

    /** The DST, world city or home time values, then the time: what a time sync writes. */
    suspend fun set(timeMs: Long? = null, offset: Long? = null) {
        writeDST()
        writeDSTForWorldCities()
        val info = link.watchInfo.info
        if (info.hasWorldCities) {
            writeWorldCities()
        } else if (info.hasHomeTime) {
            writeHomeTimes()
        }
        writeTime(timeMs ?: (Clock.systemDefaultZone().millis() + (offset ?: 0L)))
    }

    /** The time write (`09 ...`) of [timeMs] in this link's time zone. */
    fun writeTime(timeMs: Long) {
        val adjusted = TimeIOFunctional.adjustTimeForDSTAndConvert(timeMs, zone.casioZone)
        link.command(GetSetMode.SET, TimeIOFunctional.buildTimeCommand(adjusted))
    }

    private fun write(value: String) {
        link.command(GetSetMode.SET, IOFunctional.toCasioCmd(Utils.toCompactString(value)))
    }

    suspend fun writeDST() {
        val z = zone.casioZone
        val states = listOf(IO.DstState.ZERO, IO.DstState.TWO, IO.DstState.FOUR)
        for (i in 0 until minOf(link.watchInfo.info.dstCount, states.size)) {
            val value = requestDstWatchState(states[i])
            write(if (i == 0) DstWatchStateIOFunctional.setDST(value, TimeIOFunctional.calculateDSTValue(z)) else value)
        }
    }

    suspend fun writeDSTForWorldCities() {
        val z = zone.casioZone
        for (i in 0 until minOf(link.watchInfo.info.worldCitiesCount, CITIES)) {
            val value = requestDstForWorldCities(i)
            write(if (i == 0) DstForWorldCitiesIOFunctional.setDST(value, z) else value)
            Timber.i("writeDSTForWorldCities: $i")
        }
    }

    suspend fun writeWorldCities() {
        for (i in 0 until minOf(link.watchInfo.info.worldCitiesCount, CITIES)) {
            val value = if (i == 0) homeCity() else requestWorldCities(i)
            write(value)
            Timber.i("writeWorldCities: $i")
        }
    }

    /** City 0 is the home city: the name of this link's time zone, not what the watch had. */
    private fun homeCity(): String {
        val encoded = WorldCitiesIOFunctional.encodeAndPad(WorldCitiesIOFunctional.parseCity(zone.id)!!, 0)
        link.cache.remove(CachedIOFunctional.createKey(encoded))
        return encoded
    }

    suspend fun writeHomeTimes() {
        for (i in 0 until link.watchInfo.info.worldCitiesCount) {
            write(requestHomeTime(i))
            Timber.i("writeHomeTimes: $i")
        }
    }

    private companion object {
        /** The watch has six clocks: the home clock and five world clocks. */
        const val CITIES = 6
    }
}
