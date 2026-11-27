package org.avmedia.gshockapi.link

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import org.avmedia.gshockapi.LinkWatchInfo
import org.avmedia.gshockapi.WatchApi
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.ConnectionContext
import org.avmedia.gshockapi.casio.ConnectionKeepAlive
import org.avmedia.gshockapi.casio.LinkDispatcher
import org.avmedia.gshockapi.casio.LinkNotifications
import org.avmedia.gshockapi.casio.LinkWriter
import org.avmedia.gshockapi.casio.Outcome
import org.avmedia.gshockapi.casio.WatchChannel
import org.avmedia.gshockapi.casio.WatchQueue
import org.avmedia.gshockapi.casio.WatchTransport
import org.avmedia.gshockapi.io.LinkButtonInfo
import org.avmedia.gshockapi.io.LinkCache
import org.avmedia.gshockapi.io.LinkIO
import org.avmedia.gshockapi.io.LinkRawSettings
import org.avmedia.gshockapi.io.LinkTime
import org.avmedia.gshockapi.io.LinkTransfers
import org.avmedia.gshockapi.protocols.H5600Protocol

/** The GATT of one watch: writes, and the notification subscriptions the setup makes. A fake in the tests. */
interface LinkGatt : WatchTransport {
    /** The characteristics that notify or indicate, as discovered on this connection (empty while not known). */
    fun notifiable(): List<UUID> = emptyList()

    /** Turns notifications on for [characteristic]; returns when the descriptor write result came, false when it failed. */
    suspend fun subscribe(characteristic: UUID): Boolean = true

    /** Whether this connection has the service of [handle]: null while its services are not known (or a fake does not say). */
    fun serviceFound(handle: GetSetMode): Boolean? = null
}

/**
 * One watch: its own GATT, its own frame sequencer, its own job queue and its own connection setup. Nothing in it is
 * shared with another link. Created by a [WatchLinks] registry.
 */
class WatchLink internal constructor(
    val key: String,
    gatt: LinkGatt,
    scope: CoroutineScope,
    initial: LinkState = LinkState.DISCONNECTED,
) {
    @Volatile var gatt: LinkGatt = gatt

    /** The frame sequencer of this link: one frame at a time, a read done when its answer came. Reached only through [jobs]. */
    @Volatile var frames: WatchQueue = WatchQueue(
        scope,
        transport = WatchTransport { channel, frame -> this.gatt.write(channel, frame) },
        // The answer lengths of this link's own model.
        layouts = { watchInfo.info.protocol === H5600Protocol },
    )

    val jobs = WatchJobQueue(scope, { frames }, initial)

    val state: StateFlow<LinkState> get() = jobs.state

    /** This link's connection upkeep (`49`, `47`) and setup (`0x10` .. `0x39`): its writes are command jobs on this link. */
    val connection: ConnectionContext = ConnectionContext(LinkWriter(this))

    /** What this link's watch is (name, model, address, the model's features). */
    val watchInfo: LinkWatchInfo = LinkWatchInfo()

    /** The answers this link's watch gave, by key. */
    val cache: LinkCache = LinkCache()

    /** This link's button info (`0x10`): its own request, answer and kind of connection. */
    val button: LinkButtonInfo = LinkButtonInfo(this, cache)

    /** This link's settings reads and writes (the raw table): its own request in flight and its own locks. */
    val rawSettings: LinkRawSettings = LinkRawSettings(this)

    /** This link's time: its time zone, its DST, world city and home time reads and the time write. */
    val time: LinkTime = LinkTime(this)

    /** This link's settings, alarms, timer, reminders, name, battery, app information and activity record. */
    val io: LinkIO = LinkIO(this)

    /** This link's LifeLog and workout transfers: what runs, waits or failed on them is this watch's alone. */
    val transfers: LinkTransfers = LinkTransfers(this)

    /** This link's notification texts: its watch's gate (`3D`, the MTU) and one text at a time, held while a transfer runs. */
    val notifications: LinkNotifications = LinkNotifications(this) { transfers.busy }

    /** Where this link's watch's notifications go: to this link's own objects. */
    val dispatcher: LinkDispatcher = LinkDispatcher(this)

    /** The public calls on this watch (settings, alarms, time, health and workout reads, texts, tide, ...): all on this link. */
    val api: WatchApi by lazy { WatchApi(this) }

    init {
        connection.setup.busy = { transfers.busy }
        connection.setup.afterSetup = { transfers.workouts.onSetupDone(it) }
        // A new setup asks for the button info again: an answer of the last connection is not this one's.
        connection.setup.onReset = { button.forgetAnswer() }
        // The end of the setup writes this watch's own time and DST values.
        connection.setup.timeWriter = {
            scope.launch {
                // One more try when a read or the write did not get through and the watch is still there.
                for (attempt in 1..TIME_WRITE_TRIES) {
                    val failed = runCatching { time.set() }.exceptionOrNull() ?: break
                    ConnectionKeepAlive.log("time write failed (try $attempt of $TIME_WRITE_TRIES): ${failed.message}")
                    if (attempt == TIME_WRITE_TRIES || state.value != LinkState.READY) break
                    kotlinx.coroutines.delay(timeRetryMs)
                }
            }
        }
    }

    /** How often this link dropped: what was started before a drop (a text in several chunks) sees that it is on another link now. */
    @Volatile var drops: Int = 0
        private set

    /** How long the setup's time write waits before its second try; a test seam. */
    @Volatile internal var timeRetryMs: Long = 3_000L

    /** One request or write as a command job (fire and forget unless the result is awaited). */
    fun command(handle: GetSetMode, data: ByteArray, onDisconnect: OnDisconnect = OnDisconnect.WAIT): Deferred<JobOutcome<Outcome>> =
        submit(JobSpec("%s 0x%02X".format(handle.name.lowercase(), (data.firstOrNull()?.toInt() ?: 0) and 0xFF), COMMANDS, JobPriority.COMMAND, onDisconnect = onDisconnect, settleMs = JobSpec.COMMAND_SETTLE_MS)) {
            exchange(handle, data)
        }

    /** Extra steps of the setup after the subscriptions (protocol reads and writes), run inside the setup job. */
    @Volatile var protocolSetup: suspend JobScope.() -> Unit = {}

    /** Characteristic subscriptions that failed in the last setup, for the log. */
    @Volatile var failedSubscriptions: List<UUID> = emptyList()
        private set

    fun <T> submit(spec: JobSpec, block: suspend JobScope.() -> T): Deferred<JobOutcome<T>> = jobs.submit(spec, block)

    /**
     * A long transfer (LifeLog, workouts, tide) as one background job on this link: it holds the link between its
     * boundaries and gives way at them, and one transfer runs at a time. A drop ends it. [quiet]: it starts only after
     * the settle of the jobs before it ([JobSpec.COMMAND_SETTLE_MS] after a command, [JobSpec.TRANSFER_SETTLE_MS] after a
     * LifeLog or workout read), and its own end keeps the next quiet transfer waiting [JobSpec.TRANSFER_SETTLE_MS].
     */
    fun <T> transfer(name: String, deadlineMs: Long, quiet: Boolean = false, block: suspend JobScope.() -> T): Deferred<JobOutcome<T>> =
        submit(
            JobSpec(
                name, TRANSFERS, JobPriority.BACKGROUND, deadlineMs, OnDisconnect.CANCEL,
                settleMs = if (quiet) JobSpec.TRANSFER_SETTLE_MS else 0L, startsQuiet = quiet,
            ),
            block,
        )

    /**
     * The GATT is up and its services are known. The setup runs as one job before every other: each subscription after
     * the previous one's descriptor write result, then [protocolSetup]. Nothing else writes until it ended.
     */
    fun onGattReady(): Deferred<JobOutcome<Unit>> = jobs.linkUp {
        val g = gatt
        val failed = mutableListOf<UUID>()
        for (uuid in g.notifiable()) if (!g.subscribe(uuid)) failed += uuid
        failedSubscriptions = failed
        protocolSetup()
    }

    /** The link dropped (the watch may come back by itself): its jobs wait or end, the next `0x10` answer starts a new setup. */
    fun onGattLost() {
        drops++
        jobs.linkDown("The link to the watch was closed.")
        connection.setup.onLinkLost()
        // A LifeLog read in progress ends with the link (it must not run its setup on the next one).
        transfers.lifeLog.onLinkLost()
    }

    /** A notification of this link's watch: a read that waits may take it as its answer. */
    fun onNotification(characteristic: UUID, bytes: ByteArray) = frames.onNotification(characteristic, bytes)

    internal fun close() = jobs.close("The link to the watch was closed.")
}

/** The setup's time write: the first try and one more. */
private const val TIME_WRITE_TRIES = 2

/** The source of the link's own commands (the setup, the keep-alive and the single-watch API): one source, first asked first run. */
private val COMMANDS = JobSource.System("single-watch API")

/** The source of the link's long transfers (LifeLog, workouts, tide). */
private val TRANSFERS = JobSource.System("transfer")

/**
 * The links of every watch the phone talks to, by key (the stable watch id; the address until that exists). No current
 * or default link: every caller names its watch.
 */
class WatchLinks(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val links = ConcurrentHashMap<String, WatchLink>()

    /** The link for [key], created with [gatt] the first time; a later call gives the same link its new GATT. */
    fun open(key: String, gatt: LinkGatt): WatchLink {
        val link = links.computeIfAbsent(key) { WatchLink(it, gatt, scope) }
        link.gatt = gatt
        return link
    }

    operator fun get(key: String): WatchLink? = links[key]

    /** The public calls on the watch of [key], null when no link of that key is open. */
    fun api(key: String): WatchApi? = links[key]?.api

    fun all(): List<WatchLink> = links.values.toList()

    /** Ends every job of [key] and forgets the link. */
    fun close(key: String) {
        links.remove(key)?.close()
    }
}

/** A GATT that is not there: every write fails. */
internal object NoGatt : LinkGatt {
    override suspend fun write(channel: WatchChannel, frame: ByteArray): Boolean = false
}
