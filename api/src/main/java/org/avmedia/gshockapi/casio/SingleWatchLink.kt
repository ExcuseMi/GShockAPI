package org.avmedia.gshockapi.casio

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.avmedia.gshockapi.ble.GetSetMode
import kotlinx.coroutines.Deferred
import org.avmedia.gshockapi.link.JobOutcome
import org.avmedia.gshockapi.link.JobScope
import org.avmedia.gshockapi.link.OnDisconnect
import org.avmedia.gshockapi.link.JobPriority
import org.avmedia.gshockapi.link.JobSource
import org.avmedia.gshockapi.link.JobSpec
import org.avmedia.gshockapi.link.LinkGatt
import org.avmedia.gshockapi.link.LinkState
import org.avmedia.gshockapi.link.NoGatt

/**
 * The single-watch API on top of one [org.avmedia.gshockapi.link.WatchLink]: every write and request is one job of
 * command priority on that link, so the current apps keep their order and behaviour. New code takes a link from a
 * [org.avmedia.gshockapi.link.WatchLinks] registry and submits its own jobs.
 */
@Deprecated("Single-watch adapter: take a link from WatchLinks and submit jobs to it.")
object WatchLink {
    /** Ready from the start: until the connection code reports the link's state, the manager waits for the watch itself. */
    val link: org.avmedia.gshockapi.link.WatchLink =
        org.avmedia.gshockapi.link.WatchLink("single", NoGatt, CoroutineScope(SupervisorJob() + Dispatchers.IO), LinkState.READY).also {
            singleWatchHooks(it)
        }

    /**
     * The connection setup and keep-alive of the one link, for the single-watch code that is not per link yet (the
     * protocol handlers, the transfers, the settings reads). New code takes them from its own link.
     */
    val connection: ConnectionContext get() = link.connection

    /** The LifeLog and workout transfers of the one link, for their single-watch adapters ([org.avmedia.gshockapi.io.H5600HealthIO], [org.avmedia.gshockapi.io.H5600WorkoutIO]). */
    val transfers: org.avmedia.gshockapi.io.LinkTransfers get() = link.transfers

    /** The notification texts of the one link, for their single-watch adapters ([NotificationGate], [NotificationSender]). */
    val notifications: LinkNotifications get() = link.notifications

    /** What the one link's watch is, for the single-watch [org.avmedia.gshockapi.WatchInfo]. */
    val watchInfo: org.avmedia.gshockapi.LinkWatchInfo get() = link.watchInfo

    /** The one link's cache, button info and settings reads, for their single-watch adapters ([CachedIO], [org.avmedia.gshockapi.io.ButtonPressedIO], [org.avmedia.gshockapi.io.RawSettingIO]). */
    val cache: org.avmedia.gshockapi.io.LinkCache get() = link.cache
    val button: org.avmedia.gshockapi.io.LinkButtonInfo get() = link.button
    val rawSettings: org.avmedia.gshockapi.io.LinkRawSettings get() = link.rawSettings

    /** The one link's time, for the single-watch time code ([org.avmedia.gshockapi.io.TimeIO] and the DST, world city and home time reads). */
    val time: org.avmedia.gshockapi.io.LinkTime get() = link.time

    /** The one link's settings, alarms, timer, reminders and watch status, for their single-watch adapters (SettingsIO, AlarmsIO, ...). */
    val io: org.avmedia.gshockapi.io.LinkIO get() = link.io

    /** The one link's dispatcher, for the single-watch [MessageDispatcher]. */
    val dispatcher: LinkDispatcher get() = link.dispatcher

    /** The public calls on the one link, for the deprecated single-watch [org.avmedia.gshockapi.GShockAPI] calls. */
    val api: org.avmedia.gshockapi.WatchApi get() = link.api

    /** The one link as a driver's connection, for [org.avmedia.gshockapi.driver.Drivers.bindCurrent]. */
    val driverConnection: org.avmedia.gshockapi.driver.LinkConnection get() = org.avmedia.gshockapi.driver.LinkConnection(link)

    /** True for the one link: the steps that only a single-watch adapter has run on it alone. */
    fun isTheOne(other: org.avmedia.gshockapi.link.WatchLink): Boolean = other === link

    /** What only the one link has. A link from the registry has none of this. */
    private fun singleWatchHooks(link: org.avmedia.gshockapi.link.WatchLink) {
        // The one link's watch is the connected one (a registry link is its key).
        link.transfers.workouts.watch = { link.watchInfo.address }
    }

    /**
     * The frame sequencer of the one link; tests put their own in. The jobs of the old one end with it (a job stuck on a
     * transport that never answers would otherwise hold the link for its whole deadline).
     */
    var queue: WatchQueue
        get() = link.frames
        set(value) {
            link.jobs.endAll("The frame sequencer was replaced.")
            link.frames = value
        }

    fun setTransport(transport: WatchTransport) {
        link.gatt = transport as? LinkGatt ?: object : LinkGatt {
            override suspend fun write(channel: WatchChannel, frame: ByteArray): Boolean = transport.write(channel, frame)
        }
    }

    /** True while a job holds the one link, for log lines. */
    fun busy(): Boolean = link.jobs.busy()

    /** The manager is gone (closed by the app): what waits ends, and writes fail at once again until the next manager. */
    fun release(reason: String) = link.jobs.detach(reason)

    /**
     * A long transfer (LifeLog, workouts, tide) as one background job on the one link: it holds the link between its
     * boundaries and gives way at them, and one transfer runs at a time. A drop ends it. [quiet]: it starts only after
     * the settle of the jobs before it ([JobSpec.COMMAND_SETTLE_MS] after a command, [JobSpec.TRANSFER_SETTLE_MS] after a
     * LifeLog or workout read), and its own end keeps the next quiet transfer waiting [JobSpec.TRANSFER_SETTLE_MS].
     */
    fun <T> transfer(name: String, deadlineMs: Long, quiet: Boolean = false, block: suspend JobScope.() -> T): Deferred<JobOutcome<T>> =
        link.transfer(name, deadlineMs, quiet, block)

    private fun job(handle: GetSetMode, data: ByteArray) = link.command(handle, data)

    /** Puts a request or write in the queue (fire and forget). */
    fun write(handle: GetSetMode, data: ByteArray) {
        job(handle, data.copyOf())
    }

    /** Like [write], and waits for the result: true when the write result came (or, for a read, the answer). */
    suspend fun writeChecked(handle: GetSetMode, data: ByteArray): Boolean =
        when (val out = job(handle, data.copyOf()).await()) {
            is JobOutcome.Done -> out.value.ok
            is JobOutcome.Failed -> false.also { ConnectionKeepAlive.log("request %02X: %s".format(data.firstOrNull() ?: 0, out.detail)) }
        }

    private val source = ThreadLocal<UUID?>()

    /** Runs [block] (the delivery of one notification) and says on which characteristic it came, for code that routes by it. */
    fun <T> withSource(characteristic: UUID, block: () -> T): T {
        val before = source.get()
        source.set(characteristic)
        try {
            return block()
        } finally {
            source.set(before)
        }
    }

    /** The characteristic of the notification being delivered on this thread, null outside a delivery. */
    fun currentSource(): UUID? = source.get()

    /** The notification hook of the connection: [hex] as the manager logs it ("0x28 06 1D"). */
    fun onNotification(characteristic: UUID, hex: String?) {
        link.onNotification(characteristic, parseHex(hex))
    }

    fun parseHex(hex: String?): ByteArray {
        if (hex == null) return ByteArray(0)
        return hex.trim().split(' ').filter { it.isNotBlank() }
            .mapNotNull { it.removePrefix("0x").toIntOrNull(16)?.toByte() }
            .toByteArray()
    }
}
