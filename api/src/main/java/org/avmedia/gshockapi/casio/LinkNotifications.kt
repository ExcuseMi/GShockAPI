package org.avmedia.gshockapi.casio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.link.JobOutcome
import timber.log.Timber

/** The notification texts of one link: what its watch allows and one text at a time to it. Made only by its link. */
class LinkNotifications internal constructor(link: org.avmedia.gshockapi.link.WatchLink, busy: () -> Boolean) {
    val gate: LinkNotificationGate = LinkNotificationGate()

    val sender: LinkNotificationSender = LinkNotificationSender(link, gate, busy)
}

/**
 * What one watch said about notification texts: the last command of the notification manager (`0x3D`, from the watch) and the
 * MTU it reports in the watch condition (`0x28`, value index 4 and 5, low byte first).
 */
class LinkNotificationGate internal constructor() {
    companion object {
        /** Commands that stop the app from sending: pause (data transfer, setting operation, activity) and stop (switched off). */
        private val STOPPED = setOf(0x10, 0x11, 0x12, 0x20)

        /** Commands that start it: by the user, woke up, receiving again. */
        private val STARTED = setOf(0x00, 0x01, 0x02)
    }

    @Volatile private var command = -1

    @Volatile var mtu = 0
        private set

    fun reset() {
        command = -1
        mtu = 0
    }

    /** An unasked `3D command ...` from the watch. The value of our own read (`3D 30 ...`) is not a command and is ignored. */
    fun onManager(data: String) {
        val b = parseNotificationHex(data)
        val value = (b.getOrNull(1)?.toInt() ?: return) and 0xFF
        if (value in STARTED || value in STOPPED) command = value
    }

    /** The `0x28` answer: the MTU is the 16 bit value at value index 4 and 5. */
    fun onCondition(data: String) {
        val b = parseNotificationHex(data)
        if (b.size < 7 || b[0] != 0x28.toByte()) return
        val value = (b[5].toInt() and 0xFF) or ((b[6].toInt() and 0xFF) shl 8)
        if (value in 23..517) mtu = value
    }

    /** False after a pause or stop command; before the first command the watch has said nothing and sending is allowed. */
    fun allowed(): Boolean = command !in STOPPED

    /** The bytes per chunk: the MTU minus 4, and 20 until the watch condition was read. */
    fun chunkSize(): Int = if (mtu > 0) mtu - 4 else 20
}

/**
 * Sends a notification text to the watch of one link the way it accepts it: on the notification characteristic, without response, in chunks of
 * the MTU minus 4 bytes, the next chunk [spacingMs] after the write result of the one before, a failed write drops the rest, and
 * only while the watch allows it and no LifeLog or workout transfer runs (then the text is held back and sent after it). One text
 * at a time. A link that drops between two chunks: the whole text is sent once more on the next link, never only its rest
 * (the watch showed the second half of a text alone); a second drop drops the text.
 */
class LinkNotificationSender internal constructor(
    link: org.avmedia.gshockapi.link.WatchLink,
    private val gate: LinkNotificationGate,
    /** True while a transfer holds the link; the default asks this link's transfers. */
    @Volatile var busy: () -> Boolean,
) {
    /** 2000 ms for the DW-H5600 family, 500 ms for other models. */
    @Volatile var spacingMs = LongProcess.NOTIFICATION_TEXTS.chunkGapMs ?: 2_000L

    /** One write on the notification characteristic of this link; true when its write result came. */
    @Volatile var writer: suspend (ByteArray) -> Boolean = { chunk -> write(link, chunk, org.avmedia.gshockapi.link.OnDisconnect.WAIT) }

    /**
     * A chunk after the first: it ends with the link instead of waiting for the next one (it waited up to 3 minutes and
     * went out there alone). A test that puts its own [writer] in gets that one for every chunk.
     */
    private val linkWriter = writer
    private val laterWriter: suspend (ByteArray) -> Boolean = { chunk -> write(link, chunk, org.avmedia.gshockapi.link.OnDisconnect.CANCEL) }

    private suspend fun write(link: org.avmedia.gshockapi.link.WatchLink, chunk: ByteArray, rule: org.avmedia.gshockapi.link.OnDisconnect): Boolean =
        when (val out = link.command(GetSetMode.NOTIFY, chunk, rule).await()) {
            is JobOutcome.Done -> out.value.ok
            is JobOutcome.Failed -> false.also { ConnectionKeepAlive.log("notification chunk: ${out.detail}") }
        }

    /** How often the link dropped so far ([org.avmedia.gshockapi.link.WatchLink.drops]); a test seam. */
    @Volatile var linkDrops: () -> Int = { link.drops }

    @Volatile var holdLimitMs = 10 * 60_000L

    @Volatile var holdPollMs = 500L

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun chunks(buffer: ByteArray, size: Int): List<ByteArray> =
        if (size <= 0) emptyList() else buffer.toList().chunked(size).map { it.toByteArray() }

    /**
     * Queues [buffer] (already inverted). The result comes when the text is sent, dropped or held back too long; each
     * outcome is one line in the watch log (a text that never reached the watch was silent before).
     *
     * The text takes its place in the line before this returns (it runs in the caller up to its first wait), so texts
     * go out in the order they were handed over: each started on a thread of its own before, and two could swap.
     */
    fun send(buffer: ByteArray): Deferred<NotificationSender.Outcome> =
        scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val outcome = mutex.withLock {
                var sent = if (!awaitAllowed()) NotificationSender.Outcome.HELD_TOO_LONG else writePieces(buffer)
                if (sent == null) {
                    ConnectionKeepAlive.log("notification text: the link dropped between two chunks, the whole text is sent once more")
                    sent = if (!awaitAllowed()) NotificationSender.Outcome.HELD_TOO_LONG else writePieces(buffer)
                    if (sent == null) ConnectionKeepAlive.log("notification text: the link dropped again between two chunks")
                }
                sent ?: NotificationSender.Outcome.WRITE_FAILED
            }
            ConnectionKeepAlive.log(
                when (outcome) {
                    NotificationSender.Outcome.SENT -> "notification text sent (${buffer.size} bytes)"
                    NotificationSender.Outcome.HELD_TOO_LONG -> "notification text dropped: the watch did not allow it for ${holdLimitMs / 1000} s"
                    NotificationSender.Outcome.WRITE_FAILED -> "notification text dropped: a chunk write failed"
                    NotificationSender.Outcome.EMPTY -> "notification text dropped: empty"
                },
            )
            outcome
        }

    /** False when the watch did not allow a text within [holdLimitMs]: the text is dropped. */
    private suspend fun awaitAllowed(): Boolean {
        var held = 0L
        while (!gate.allowed() || busy()) {
            if (held >= holdLimitMs) {
                Timber.w("Notification text dropped: the watch did not allow it for ${holdLimitMs / 1000} s")
                return false
            }
            delay(holdPollMs)
            held += holdPollMs
        }
        return true
    }

    /**
     * The text in chunks, [spacingMs] apart; a failed chunk drops the rest. Null: the link dropped after the first chunk
     * went out, so the rest was not sent (on the next link it would arrive without its start).
     */
    private suspend fun writePieces(buffer: ByteArray): NotificationSender.Outcome? {
        val pieces = chunks(buffer, gate.chunkSize())
        if (pieces.isEmpty()) return NotificationSender.Outcome.EMPTY
        var drops = 0
        for ((i, piece) in pieces.withIndex()) {
            if (i > 0 && linkDrops() != drops) return null
            val write = if (i > 0 && writer === linkWriter) laterWriter else writer
            val ok = write(piece)
            // The first chunk may have waited for the link: the count is taken once it went out. A drop right after the
            // last chunk went out is no reason to send again: the watch has the whole text and would show it twice.
            val whole = ok && i == pieces.lastIndex
            if (i == 0) drops = linkDrops() else if (!whole && linkDrops() != drops) return null
            if (!ok) {
                Timber.w("Notification text: chunk ${i + 1} of ${pieces.size} failed, the rest is dropped")
                return NotificationSender.Outcome.WRITE_FAILED
            }
            if (i < pieces.size - 1) delay(spacingMs)
        }
        return NotificationSender.Outcome.SENT
    }
}

/** The bytes of a notification as the protocol hands it on ("3D 10"). */
internal fun parseNotificationHex(hex: String): ByteArray =
    hex.trim().split(' ').filter { it.isNotBlank() }.mapNotNull { it.removePrefix("0x").toIntOrNull(16)?.toByte() }.toByteArray()
