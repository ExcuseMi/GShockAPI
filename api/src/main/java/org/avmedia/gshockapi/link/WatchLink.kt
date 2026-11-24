package org.avmedia.gshockapi.link

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.ConnectionContext
import org.avmedia.gshockapi.casio.LinkWriter
import org.avmedia.gshockapi.casio.Outcome
import org.avmedia.gshockapi.casio.WatchChannel
import org.avmedia.gshockapi.casio.WatchQueue
import org.avmedia.gshockapi.casio.WatchTransport

/** The GATT of one watch: writes, and the notification subscriptions the setup makes. A fake in the tests. */
interface LinkGatt : WatchTransport {
    /** The characteristics that notify or indicate, as discovered on this connection (empty while not known). */
    fun notifiable(): List<UUID> = emptyList()

    /** Turns notifications on for [characteristic]; returns when the descriptor write result came, false when it failed. */
    suspend fun subscribe(characteristic: UUID): Boolean = true
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
    @Volatile var frames: WatchQueue = WatchQueue(scope, transport = WatchTransport { channel, frame -> this.gatt.write(channel, frame) })

    val jobs = WatchJobQueue(scope, { frames }, initial)

    val state: StateFlow<LinkState> get() = jobs.state

    /** This link's connection upkeep (`49`, `47`) and setup (`0x10` .. `0x39`): its writes are command jobs on this link. */
    val connection: ConnectionContext = ConnectionContext(LinkWriter(this))

    /** One request or write as a command job (fire and forget unless the result is awaited). */
    fun command(handle: GetSetMode, data: ByteArray): Deferred<JobOutcome<Outcome>> =
        submit(JobSpec("%s 0x%02X".format(handle.name.lowercase(), (data.firstOrNull()?.toInt() ?: 0) and 0xFF), COMMANDS, JobPriority.COMMAND, settleMs = JobSpec.COMMAND_SETTLE_MS)) {
            exchange(handle, data)
        }

    /** Extra steps of the setup after the subscriptions (protocol reads and writes), run inside the setup job. */
    @Volatile var protocolSetup: suspend JobScope.() -> Unit = {}

    /** Characteristic subscriptions that failed in the last setup, for the log. */
    @Volatile var failedSubscriptions: List<UUID> = emptyList()
        private set

    fun <T> submit(spec: JobSpec, block: suspend JobScope.() -> T): Deferred<JobOutcome<T>> = jobs.submit(spec, block)

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
        jobs.linkDown("The link to the watch was closed.")
        connection.setup.onLinkLost()
    }

    /** A notification of this link's watch: a read that waits may take it as its answer. */
    fun onNotification(characteristic: UUID, bytes: ByteArray) = frames.onNotification(characteristic, bytes)

    internal fun close() = jobs.close("The link to the watch was closed.")
}

/** The source of the link's own commands (the setup, the keep-alive and the single-watch API): one source, first asked first run. */
private val COMMANDS = JobSource.System("single-watch API")

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
