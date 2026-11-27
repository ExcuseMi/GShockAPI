package org.avmedia.gshockapi.io

import CachedIOFunctional
import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.CasioTimeZoneHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.avmedia.gshockapi.casio.Outcome
import org.avmedia.gshockapi.link.JobOutcome
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.log.SyncReport
import org.avmedia.gshockapi.utils.Utils
import timber.log.Timber
import java.time.Clock
import java.util.TimeZone

/**
 * The time of one link's watch: its time zone, its DST, world city and home time reads (`1D`, `1E`, `1F`, `24`) and the
 * time write (`09`). The reads and writes are command jobs on this link, the answers come from this link's watch only
 * ([routes]) and are kept in this link's cache. Made only by its [WatchLink].
 */
/** A time sync that did not get through: which read or write, and why. */
class TimeSyncFailed(message: String) : Exception(message)

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

    /**
     * A read of the time sync: when its request failed (no answer in 10 s, refused, the link gone) it ends with
     * [TimeSyncFailed]. Before, it waited for ever, the time was never written on that link and no line said so.
     */
    private suspend fun syncRead(answer: LinkAnswer<String>, key: String): String =
        link.cache.request(key) { k ->
            answer.askOrFail { fail ->
                val sent = link.command(GetSetMode.GET, IOFunctional.toCasioCmd(k))
                sent.invokeOnCompletion { failureOf(sent)?.let { fail(TimeSyncFailed("read ${k.uppercase()}: $it")) } }
            }
        }

    /** Why a command did not get through, null when it did. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun failureOf(sent: kotlinx.coroutines.Deferred<JobOutcome<Outcome>>): String? =
        when (val out = runCatching { sent.getCompleted() }.getOrNull()) {
            is JobOutcome.Done -> if (out.value.ok) null else out.value.reason ?: "failed"
            is JobOutcome.Failed -> out.detail
            null -> "cancelled"
        }

    suspend fun requestDstWatchState(state: IO.DstState): String = read(dstWatchState, "1d0${state.state}")

    suspend fun requestDstForWorldCities(cityNumber: Int): String = read(dstForWorldCities, "1e0$cityNumber")

    suspend fun requestWorldCities(cityNumber: Int): String = read(worldCities, "1f0$cityNumber")

    suspend fun requestHomeTime(slot: Int): String = read(homeTime, "240$slot")

    private val setLock = Mutex()

    /**
     * The DST, world city or home time values, then the time: what a time sync writes. One at a time on a link (the
     * setup's and the app's came together at a connection). It ends when the time write's result came, or with
     * [TimeSyncFailed] when a read or that write did not get through; each sync is one [SyncReport].
     */
    suspend fun set(timeMs: Long? = null, offset: Long? = null) {
        val started = System.currentTimeMillis()
        try {
            setLock.withLock {
                writeDST()
                writeDSTForWorldCities()
                val info = link.watchInfo.info
                if (info.hasWorldCities) {
                    writeWorldCities()
                } else if (info.hasHomeTime) {
                    writeHomeTimes()
                }
                val written = writeTime(timeMs ?: (Clock.systemDefaultZone().millis() + (offset ?: 0L)))
                written.await()
                failureOf(written)?.let { throw TimeSyncFailed("time write: $it") }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            SyncReport.report(SyncReport.TIME, false, System.currentTimeMillis() - started, e.message)
            throw e
        }
        SyncReport.report(SyncReport.TIME, true, System.currentTimeMillis() - started, null)
    }

    /** The time write (`09 ...`) of [timeMs] in this link's time zone; its result says whether it reached the watch. */
    fun writeTime(timeMs: Long): kotlinx.coroutines.Deferred<JobOutcome<Outcome>> {
        val adjusted = TimeIOFunctional.adjustTimeForDSTAndConvert(timeMs, zone.casioZone)
        return link.command(GetSetMode.SET, TimeIOFunctional.buildTimeCommand(adjusted))
    }

    private fun write(value: String) {
        link.command(GetSetMode.SET, IOFunctional.toCasioCmd(Utils.toCompactString(value)))
    }

    suspend fun writeDST() {
        val z = zone.casioZone
        val states = listOf(IO.DstState.ZERO, IO.DstState.TWO, IO.DstState.FOUR)
        for (i in 0 until minOf(link.watchInfo.info.dstCount, states.size)) {
            val value = syncRead(dstWatchState, "1d0${states[i].state}")
            write(if (i == 0) DstWatchStateIOFunctional.setDST(value, TimeIOFunctional.calculateDSTValue(z)) else value)
        }
    }

    suspend fun writeDSTForWorldCities() {
        val z = zone.casioZone
        for (i in 0 until minOf(link.watchInfo.info.worldCitiesCount, CITIES)) {
            val value = syncRead(dstForWorldCities, "1e0$i")
            write(if (i == 0) DstForWorldCitiesIOFunctional.setDST(value, z) else value)
            Timber.i("writeDSTForWorldCities: $i")
        }
    }

    suspend fun writeWorldCities() {
        for (i in 0 until minOf(link.watchInfo.info.worldCitiesCount, CITIES)) {
            val value = if (i == 0) homeCity() else syncRead(worldCities, "1f0$i")
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
            write(syncRead(homeTime, "240$i"))
            Timber.i("writeHomeTimes: $i")
        }
    }

    private companion object {
        /** The watch has six clocks: the home clock and five world clocks. */
        const val CITIES = 6
    }
}
